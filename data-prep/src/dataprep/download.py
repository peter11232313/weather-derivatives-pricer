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
from collections.abc import Sequence
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Protocol

import xarray as xr

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


def new_year_step_file(raw_dir: Path, city: str, year: int) -> Path:
    """Return ``<raw_dir>/era5_<city>_<year>0101T00.nc`` (the single step ``year``-01-01 00:00)."""
    return raw_dir / f"era5_{city}_{year}0101T00.nc"


def lsm_file(raw_dir: Path, city: str) -> Path:
    """Return ``<raw_dir>/era5_lsm_<city>.nc`` (static land-sea mask)."""
    return raw_dir / f"era5_lsm_{city}.nc"


def download_year(client: CdsClient, station: Station, year: int, raw_dir: Path) -> Path:
    """Download hourly ``mx2t`` and ``mn2t`` for every hour of ``year``.

    Returns:
        The year file path.

    Raises:
        DownloadError: If the download fails or is not netCDF with both variables; no
            partial file is left.
    """
    request = _request(
        station,
        [MX2T, MN2T],
        year=year,
        months=[f"{m:02d}" for m in range(1, 13)],
        days=[f"{d:02d}" for d in range(1, 32)],
        times=[f"{h:02d}:00" for h in range(24)],
    )
    return _fetch(client, request, year_file(raw_dir, station.city, year), TEMPERATURE_VARS)


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
    raise NotImplementedError


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
    """
    raise NotImplementedError


def download_new_year_step(client: CdsClient, station: Station, year: int, raw_dir: Path) -> Path:
    """Download the single step ``year``-01-01 00:00 of ``mx2t`` and ``mn2t``.

    Only needed after the final year of a range whose next year file is absent (ADR 0002).

    Raises:
        DownloadError: If the download fails or is not netCDF with both variables; no
            partial file is left.
    """
    request = _request(
        station, [MX2T, MN2T], year=year, months=["01"], days=["01"], times=["00:00"]
    )
    target = new_year_step_file(raw_dir, station.city, year)
    return _fetch(client, request, target, TEMPERATURE_VARS)


def download_lsm(client: CdsClient, station: Station, raw_dir: Path) -> Path:
    """Download the land-sea mask (one time step) in its own request.

    Raises:
        DownloadError: If the download fails or is not netCDF with ``lsm``; no partial file
            is left.
    """
    request = _request(
        station,
        [LSM],
        year=LSM_DATE.year,
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
    """Download everything needed to process ``start..end`` (inclusive UTC days).

    That is the land-sea mask, one file per year touched, and the 00:00 step after ``end``
    (from the next year's file if present, else a one-step file).

    Returns:
        All raw file paths needed, whether newly downloaded or already present.
    """
    if start > end:
        raise DownloadError(f"start {start} is after end {end}")
    paths = [download_lsm(client, station, raw_dir)]
    paths += [download_year(client, station, y, raw_dir) for y in range(start.year, end.year + 1)]
    # Day `end` ends at end+1 00:00 (ADR 0002); only new if that is in the next year.
    after = end + timedelta(days=1)
    if after.year != end.year:
        next_year = year_file(raw_dir, station.city, after.year)
        if next_year.exists():
            paths.append(next_year)
        else:
            paths.append(download_new_year_step(client, station, after.year, raw_dir))
    return paths


def _request(
    station: Station,
    variables: list[str],
    *,
    year: int,
    months: list[str],
    days: list[str],
    times: list[str],
) -> dict[str, Any]:
    """Build a CDS request for ``reanalysis-era5-single-levels``."""
    return {
        "product_type": ["reanalysis"],
        "variable": variables,
        "year": [str(year)],
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
    """Retrieve into temp files next to ``target``, unpack/merge and check, then rename.

    The result must open as netCDF and contain ``variables`` (short names) before it is
    renamed into place, so a bad download is never kept and then skipped on the next run.
    """
    if target.exists():
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(f"{target.name}.tmp")
    merged = target.with_name(f"{target.name}.merged.tmp")
    parts = target.with_name(f"{target.name}.parts.tmp")
    try:
        try:
            client.retrieve(DATASET, request, str(tmp))
        except Exception as e:  # cdsapi raises plain Exception subclasses
            raise DownloadError(f"download of {target} failed: {e}") from e
        if not tmp.is_file():
            raise DownloadError(f"download of {target} produced no file")
        ready = tmp
        with tmp.open("rb") as f:
            is_zip = f.read(len(_ZIP_MAGIC)) == _ZIP_MAGIC
        if is_zip:
            _merge_zip(tmp, parts, merged, target)
            ready = merged
        try:
            ds = open_era5(ready)
        except DataError as e:
            raise DownloadError(f"download of {target} is not readable netCDF: {e}") from e
        missing = [name for name in variables if name not in ds.data_vars]
        if missing:
            found = ", ".join(map(str, ds.data_vars)) or "none"
            raise DownloadError(
                f"download of {target} lacks variable(s) {', '.join(missing)}; found {found}"
            )
        try:
            os.replace(ready, target)
        except OSError as e:
            raise DownloadError(f"cannot move download into place at {target}: {e}") from e
    finally:
        tmp.unlink(missing_ok=True)
        merged.unlink(missing_ok=True)
        shutil.rmtree(parts, ignore_errors=True)
    return target


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
