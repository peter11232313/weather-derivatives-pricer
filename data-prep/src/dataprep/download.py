"""ERA5 downloads via the Copernicus CDS (spec 002, ADR 0002, ``era5-data`` skill).

Every function takes the CDS client as an argument (anything with
``retrieve(dataset, request, target)``, such as ``cdsapi.Client()``) so tests can pass a
fake. Files are written to a temporary name, checked, and renamed into place; an existing
target is never downloaded again.

The new CDS splits netCDF output by GRIB step type (``mx2t`` is "max", ``mn2t`` is "min")
and zips multi-file results, so requests ask for a zip; its netCDF parts are merged into
one raw file (spec 002, review option A). A plain netCDF response is also accepted.
"""

import math
import os
import shutil
import zipfile
from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from datetime import date
from pathlib import Path
from typing import Any, Protocol

import numpy as np
import pandas as pd
import xarray as xr

from dataprep.daily import needed_span
from dataprep.errors import DataError, DownloadError
from dataprep.load import open_era5
from dataprep.registry import Station

DATASET = "reanalysis-era5-single-levels"
GRID_DEG = 0.25
MARGIN_DEG = 0.5
MX2T = "maximum_2m_temperature_since_previous_post_processing"
MN2T = "minimum_2m_temperature_since_previous_post_processing"
LSM = "land_sea_mask"
# Short names each request must deliver.
TEMPERATURE_VARS = ("mx2t", "mn2t")
LSM_VARS = ("lsm",)
# Any date works for the static land-sea mask; one step keeps the request small.
LSM_DATE = date(2024, 1, 1)
# Years of mx2t + mn2t per request (spec 002 amendment A): 3 x 17,568 items fits the
# published ~60,000-item cap.
MAX_YEARS_PER_REQUEST = 3
DEFAULT_YEARS_PER_REQUEST = 3
_ZIP_MAGIC = b"PK\x03\x04"
# Absorbs float noise when a station edge lands exactly on a grid line.
_SNAP_TOL = 1e-9


class CdsClient(Protocol):
    """The part of ``cdsapi.Client`` that data-prep uses."""

    def retrieve(self, name: str, request: dict[str, Any], target: str) -> Any:
        """Download ``name`` with ``request`` into the file ``target``."""
        ...


def area_for(station: Station) -> tuple[float, float, float, float]:
    """Return the download area (N, W, S, E) in degrees.

    Station +/- 0.5 degrees, widened outward to 0.25-degree grid lines.

    Raises:
        DownloadError: If the box would cross a pole or the +/-180 degree meridian (not
            supported; not needed for European stations).
    """
    north = _snap_up(station.lat_deg + MARGIN_DEG)
    south = _snap_down(station.lat_deg - MARGIN_DEG)
    west = _snap_down(station.lon_deg - MARGIN_DEG)
    east = _snap_up(station.lon_deg + MARGIN_DEG)
    if north > 90.0 or south < -90.0 or east >= 180.0 or west < -180.0:
        raise DownloadError(
            f"station {station.city} ({station.lat_deg}, {station.lon_deg}) is within "
            f"{MARGIN_DEG} deg of a pole or the +/-180 meridian, which is not supported"
        )
    return north, west, south, east


def year_file(raw_dir: Path, city: str, year: int) -> Path:
    """Return ``<raw_dir>/era5_<city>_<year>.nc`` (every hour of ``year``)."""
    return raw_dir / f"era5_{city}_{year}.nc"


def edge_day_file(raw_dir: Path, city: str, day: date) -> Path:
    """Return ``<raw_dir>/era5_<city>_<YYYYMMDD>.nc`` (every hour of one edge day)."""
    return raw_dir / f"era5_{city}_{day:%Y%m%d}.nc"


def download_edge_day(client: CdsClient, station: Station, day: date, raw_dir: Path) -> Path:
    """Download every hour (00:00..23:00) of ``day`` of ``mx2t`` and ``mn2t``.

    Used for the day before or after a range when that day's year file is absent (spec 002
    amendment B). The request is a year request for one day; the result is checked for
    exactly the 24 hours of ``day`` and written with temp file + rename. An existing file
    is not downloaded again.

    Returns:
        The edge-day file path.

    Raises:
        DownloadError: If the download fails, is not netCDF with both variables, or lacks
            or repeats an hour of ``day`` (names the day); no partial file is left.
    """
    target = edge_day_file(raw_dir, station.city, day)
    if target.exists():
        return target
    request = _request(
        station,
        [MX2T, MN2T],
        years=[day.year],
        months=[f"{day.month:02d}"],
        days=[f"{day.day:02d}"],
        times=[f"{h:02d}:00" for h in range(24)],
    )
    stem = raw_dir / f"era5_{station.city}_{day:%Y%m%d}.download"
    with _retrieved(client, request, stem, TEMPERATURE_VARS) as (_, ds):
        start = pd.Timestamp(day)
        day_ds, problem = _split_hours(ds, start, start + pd.Timedelta(days=1))
        if problem is not None:
            raise DownloadError(
                f"incomplete ERA5 edge day {day.isoformat()} for {station.city} "
                f"({problem}; {target}); rerun to request it again"
            )
        _write_atomic(day_ds, target)
    return target


def lsm_file(raw_dir: Path, city: str) -> Path:
    """Return ``<raw_dir>/era5_lsm_<city>.nc`` (static land-sea mask)."""
    return raw_dir / f"era5_lsm_{city}.nc"


def download_year(client: CdsClient, station: Station, year: int, raw_dir: Path) -> Path:
    """Download hourly ``mx2t`` and ``mn2t`` for every hour of ``year``.

    Returns:
        The year file path.

    Raises:
        DownloadError: If the download fails, is not netCDF with both variables, or the
            year is incomplete (names the first missing or repeated hour); no partial file
            is left.
    """
    return download_years(client, station, [year], raw_dir, years_per_request=1)[0]


def year_chunks(years: Sequence[int], years_per_request: int) -> list[list[int]]:
    """Group ``years`` into runs of consecutive years, each cut into chunks of at most N.

    Args:
        years: Years to request, in any order; duplicates are ignored.
        years_per_request: Maximum years per chunk, 1..``MAX_YEARS_PER_REQUEST``.

    Returns:
        The chunks in year order; each chunk is consecutive and starts at the first year of
        its run (or right after the previous chunk).

    Raises:
        ValueError: If ``years_per_request`` is outside 1..``MAX_YEARS_PER_REQUEST``.
    """
    if not 1 <= years_per_request <= MAX_YEARS_PER_REQUEST:
        raise ValueError(
            f"years_per_request must be 1..{MAX_YEARS_PER_REQUEST}, got {years_per_request}"
        )
    chunks: list[list[int]] = []
    for year in sorted(set(years)):
        last = chunks[-1] if chunks else None
        if last is not None and year == last[-1] + 1 and len(last) < years_per_request:
            last.append(year)
        else:
            chunks.append([year])
    return chunks


def download_years(
    client: CdsClient,
    station: Station,
    years: Sequence[int],
    raw_dir: Path,
    *,
    years_per_request: int = DEFAULT_YEARS_PER_REQUEST,
) -> list[Path]:
    """Download every hour of each missing year in ``years``, several years per request.

    Years whose file exists are skipped. Missing years are requested in chunks
    (``year_chunks``); each response is split into one complete year file per year.

    Returns:
        The year file paths for ``years``, in year order.

    Raises:
        DownloadError: If a request fails (stops at once), or after all chunks if any year
            was incomplete (names each one; complete years are kept).
        ValueError: If ``years_per_request`` is outside 1..``MAX_YEARS_PER_REQUEST``.
    """
    targets = {y: year_file(raw_dir, station.city, y) for y in sorted(set(years))}
    missing = [y for y, target in targets.items() if not target.exists()]
    incomplete: list[str] = []
    for chunk in year_chunks(missing, years_per_request):
        request = _request(
            station,
            [MX2T, MN2T],
            years=chunk,
            months=[f"{m:02d}" for m in range(1, 13)],
            days=[f"{d:02d}" for d in range(1, 32)],
            times=[f"{h:02d}:00" for h in range(24)],
        )
        stem = raw_dir / f"era5_{station.city}_{chunk[0]}-{chunk[-1]}.download"
        try:
            with _retrieved(client, request, stem, TEMPERATURE_VARS) as (_, ds):
                for year in chunk:
                    target = targets[year]
                    if target.exists():  # appeared meanwhile; never overwrite
                        continue
                    year_ds, problem = _split_year(ds, year)
                    if problem is not None:
                        incomplete.append(f"{year} ({problem}; {target})")
                        continue
                    _write_atomic(year_ds, target)
        except DownloadError as e:  # stop at once, but keep what is already known
            notes = []
            if len(chunk) > 1:
                notes.append(
                    "try --years-per-request 1 if Copernicus rejected the request as too large"
                )
            if incomplete:
                notes.append(f"also incomplete: {', '.join(incomplete)}")
            if not notes:
                raise
            raise DownloadError(f"{e} ({'; '.join(notes)})") from e
    if incomplete:
        raise DownloadError(
            f"incomplete ERA5 year(s) for {station.city}: {', '.join(incomplete)}. "
            "Complete years were saved; rerun to request the incomplete years again."
        )
    return list(targets.values())


def download_lsm(client: CdsClient, station: Station, raw_dir: Path) -> Path:
    """Download the land-sea mask (one time step) in its own request.

    Raises:
        DownloadError: If the download fails or is not netCDF with ``lsm``; no partial file
            is left.
    """
    request = _request(
        station,
        [LSM],
        years=[LSM_DATE.year],
        months=[f"{LSM_DATE.month:02d}"],
        days=[f"{LSM_DATE.day:02d}"],
        times=["00:00"],
    )
    return _fetch(client, request, lsm_file(raw_dir, station.city), LSM_VARS)


def download_range(
    client: CdsClient,
    station: Station,
    start: date,
    end: date,
    raw_dir: Path,
    *,
    years_per_request: int = DEFAULT_YEARS_PER_REQUEST,
) -> list[Path]:
    """Download everything needed to process ``start..end`` (inclusive settlement days).

    That is the land-sea mask, one file per year of the range, and the hours the station's
    windows need outside the range (at most the day before ``start`` and the day after
    ``end``): from that day's year file if present, else an edge-day file (spec 002
    amendment B).

    Args:
        client: The CDS client.
        station: The settlement station.
        start: First settlement day.
        end: Last settlement day (inclusive).
        raw_dir: Folder for raw files.
        years_per_request: Years per Copernicus request, 1..``MAX_YEARS_PER_REQUEST``.

    Returns:
        All raw file paths needed, whether newly downloaded or already present.

    Raises:
        DownloadError: If ``start`` is after ``end``, a request fails, or a year is
            incomplete (see ``download_years``).
        ValueError: If ``years_per_request`` is outside 1..``MAX_YEARS_PER_REQUEST``.
    """
    if start > end:
        raise DownloadError(f"start {start} is after end {end}")
    paths = [download_lsm(client, station, raw_dir)]
    paths += download_years(
        client,
        station,
        range(start.year, end.year + 1),
        raw_dir,
        years_per_request=years_per_request,
    )
    first, last = needed_span(
        station.tmax_window_start_utc_h, station.tmin_window_start_utc_h, start, end
    )
    # Offsets are within -23..23, so an edge day is the day before start or after end
    # (both ends can fall on the same day for a one-day range); each is fetched once.
    edge_days = sorted(
        {first.date(), last.date()} - {d for d in (first.date(), last.date()) if start <= d <= end}
    )
    for day in edge_days:
        own_year = year_file(raw_dir, station.city, day.year)
        if own_year.exists():
            if own_year not in paths:
                paths.append(own_year)
        else:
            paths.append(download_edge_day(client, station, day, raw_dir))
    return paths


def _request(
    station: Station,
    variables: list[str],
    *,
    years: Sequence[int],
    months: list[str],
    days: list[str],
    times: list[str],
) -> dict[str, Any]:
    """Build a CDS request for ``reanalysis-era5-single-levels``.

    Only ``year`` differs between a single-year and a multi-year request, so a repeated
    request matches Copernicus's cache.
    """
    return {
        "product_type": ["reanalysis"],
        "variable": variables,
        "year": [str(y) for y in years],
        "month": months,
        "day": days,
        "time": times,
        "area": list(area_for(station)),
        "data_format": "netcdf",
        "download_format": "zip",
    }


def _fetch(
    client: CdsClient, request: dict[str, Any], target: Path, variables: tuple[str, ...]
) -> Path:
    """Retrieve and check (``_retrieved``), then rename the response into place unchanged.

    Used for the land-sea mask; an existing target is not fetched.
    """
    if target.exists():
        return target
    with _retrieved(client, request, target, variables) as (ready, _):
        try:
            os.replace(ready, target)
        except OSError as e:
            raise DownloadError(f"cannot move download into place at {target}: {e}") from e
    return target


@contextmanager
def _retrieved(
    client: CdsClient, request: dict[str, Any], stem: Path, variables: tuple[str, ...]
) -> Iterator[tuple[Path, xr.Dataset]]:
    """Retrieve into temp files named after ``stem``, unpack/merge and check the result.

    Yields the checked file and its dataset (loaded into memory). The result must open as
    netCDF and contain ``variables`` (short names), so a bad download is never kept. All
    temp files are removed on exit.
    """
    stem.parent.mkdir(parents=True, exist_ok=True)
    tmp = stem.with_name(f"{stem.name}.tmp")
    merged = stem.with_name(f"{stem.name}.merged.tmp")
    parts = stem.with_name(f"{stem.name}.parts.tmp")
    try:
        try:
            client.retrieve(DATASET, request, str(tmp))
        except Exception as e:  # cdsapi raises plain Exception subclasses
            raise DownloadError(f"download of {stem} failed: {e}") from e
        if not tmp.is_file():
            raise DownloadError(f"download of {stem} produced no file")
        ready = tmp
        with tmp.open("rb") as f:
            is_zip = f.read(len(_ZIP_MAGIC)) == _ZIP_MAGIC
        if is_zip:
            _merge_zip(tmp, parts, merged, stem)
            ready = merged
        try:
            ds = open_era5(ready)
        except DataError as e:
            raise DownloadError(f"download of {stem} is not readable netCDF: {e}") from e
        missing = [name for name in variables if name not in ds.data_vars]
        if missing:
            found = ", ".join(map(str, ds.data_vars)) or "none"
            raise DownloadError(
                f"download of {stem} lacks variable(s) {', '.join(missing)}; found {found}"
            )
        yield ready, ds
    finally:
        tmp.unlink(missing_ok=True)
        merged.unlink(missing_ok=True)
        shutil.rmtree(parts, ignore_errors=True)


def _split_year(ds: xr.Dataset, year: int) -> tuple[xr.Dataset, str | None]:
    """Select the hours of ``year``, [Y-01-01 00:00, Y+1-01-01 00:00), from ``ds``, in order.

    Returns:
        See ``_split_hours``.
    """
    start = pd.Timestamp(year=year, month=1, day=1)
    return _split_hours(ds, start, pd.Timestamp(year=year + 1, month=1, day=1))


def _split_hours(
    ds: xr.Dataset, start: pd.Timestamp, stop: pd.Timestamp
) -> tuple[xr.Dataset, str | None]:
    """Select the hours in [``start``, ``stop``) from ``ds``, in order.

    Returns:
        The selected dataset sorted by time, and ``None`` if every hour is present exactly
        once, else a description of the first missing or repeated hour. Hours outside
        [``start``, ``stop``) are ignored.
    """
    time_name = "valid_time" if "valid_time" in ds.coords else "time"
    times = pd.DatetimeIndex(ds[time_name].values)
    in_year = np.flatnonzero((times >= start) & (times < stop))
    in_year = in_year[np.argsort(times[in_year], kind="stable")]
    year_ds = ds.isel({time_name: in_year})
    got = times[in_year]
    absent = pd.date_range(start, stop, freq="h", inclusive="left").difference(got)
    if len(absent):
        return year_ds, f"first missing hour {absent[0]:%Y-%m-%d %H:%M}"
    if got.has_duplicates:
        return year_ds, f"first repeated hour {got[got.duplicated()][0]:%Y-%m-%d %H:%M}"
    return year_ds, None


# Chunk layout from a multi-year response would not fit a single year's file.
_DROP_ENCODING = ("chunksizes", "preferred_chunks", "contiguous", "original_shape")


def _write_atomic(ds: xr.Dataset, target: Path) -> None:
    """Write ``ds`` to ``target`` via a temp file and rename; keeps dtype and packing."""
    out = ds.copy()
    for var in out.variables.values():
        var.encoding = {k: v for k, v in var.encoding.items() if k not in _DROP_ENCODING}
    tmp = target.with_name(f"{target.name}.tmp")
    try:
        out.to_netcdf(tmp)
        os.replace(tmp, target)
    except (OSError, ValueError, RuntimeError) as e:
        raise DownloadError(f"cannot write raw file {target}: {e}") from e
    finally:
        tmp.unlink(missing_ok=True)


def _merge_zip(archive: Path, parts: Path, merged: Path, target: Path) -> None:
    """Unpack the netCDF members of ``archive`` into ``parts`` and merge them into ``merged``."""
    parts.mkdir(exist_ok=True)
    try:
        with zipfile.ZipFile(archive) as zf:
            members = [m for m in zf.infolist() if not m.is_dir()]
            if not members or not all(m.filename.endswith(".nc") for m in members):
                names = ", ".join(m.filename for m in members) or "nothing"
                raise DownloadError(f"download of {target} is a zip of {names}, not netCDF parts")
            paths = []
            for i, member in enumerate(members):
                path = parts / f"part{i}.nc"
                path.write_bytes(zf.read(member))
                paths.append(path)
    except zipfile.BadZipFile as e:
        raise DownloadError(f"download of {target} is a damaged zip: {e}") from e
    try:
        datasets = [open_era5(p) for p in paths]
    except DataError as e:
        raise DownloadError(f"download of {target} holds an unreadable part: {e}") from e
    try:
        # Explicit, so xarray default changes cannot silently pad or override parts.
        combined = xr.merge(
            datasets, compat="no_conflicts", join="exact", combine_attrs="drop_conflicts"
        )
    except ValueError as e:  # includes xarray.MergeError
        raise DownloadError(f"download of {target} has parts that do not line up: {e}") from e
    combined.to_netcdf(merged)


def _snap_up(x: float) -> float:
    """Round up to the next 0.25-degree grid line."""
    return math.ceil(x / GRID_DEG - _SNAP_TOL) * GRID_DEG


def _snap_down(x: float) -> float:
    """Round down to the previous 0.25-degree grid line."""
    return math.floor(x / GRID_DEG + _SNAP_TOL) * GRID_DEG
