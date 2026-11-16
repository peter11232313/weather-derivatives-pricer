"""ERA5 downloads via the Copernicus CDS (spec 002, ADR 0002, ``era5-data`` skill).

Every function takes the CDS client as an argument (anything with
``retrieve(dataset, request, target)``, such as ``cdsapi.Client()``) so tests can pass a
fake. Files are written to a temporary name and renamed into place; an existing target is
never downloaded again.
"""

import math
import os
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Protocol

from dataprep.errors import DownloadError
from dataprep.registry import Station

DATASET = "reanalysis-era5-single-levels"
GRID_DEG = 0.25
MARGIN_DEG = 0.5
MX2T = "maximum_2m_temperature_since_previous_post_processing"
MN2T = "minimum_2m_temperature_since_previous_post_processing"
LSM = "land_sea_mask"
# Any date works for the static land-sea mask; one step keeps the request small.
LSM_DATE = date(2024, 1, 1)
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
    """
    north = _snap_up(station.lat_deg + MARGIN_DEG)
    south = _snap_down(station.lat_deg - MARGIN_DEG)
    west = _snap_down(station.lon_deg - MARGIN_DEG)
    east = _snap_up(station.lon_deg + MARGIN_DEG)
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
        DownloadError: If the download fails or returns a zip; no partial file is left.
    """
    request = _request(
        station,
        [MX2T, MN2T],
        year=year,
        months=[f"{m:02d}" for m in range(1, 13)],
        days=[f"{d:02d}" for d in range(1, 32)],
        times=[f"{h:02d}:00" for h in range(24)],
    )
    return _fetch(client, request, year_file(raw_dir, station.city, year))


def download_new_year_step(client: CdsClient, station: Station, year: int, raw_dir: Path) -> Path:
    """Download the single step ``year``-01-01 00:00 of ``mx2t`` and ``mn2t``.

    Only needed after the final year of a range whose next year file is absent (ADR 0002).

    Raises:
        DownloadError: If the download fails or returns a zip; no partial file is left.
    """
    request = _request(
        station, [MX2T, MN2T], year=year, months=["01"], days=["01"], times=["00:00"]
    )
    return _fetch(client, request, new_year_step_file(raw_dir, station.city, year))


def download_lsm(client: CdsClient, station: Station, raw_dir: Path) -> Path:
    """Download the land-sea mask (one time step) in its own request.

    Raises:
        DownloadError: If the download fails or returns a zip; no partial file is left.
    """
    request = _request(
        station,
        [LSM],
        year=LSM_DATE.year,
        months=[f"{LSM_DATE.month:02d}"],
        days=[f"{LSM_DATE.day:02d}"],
        times=["00:00"],
    )
    return _fetch(client, request, lsm_file(raw_dir, station.city))


def download_range(
    client: CdsClient, station: Station, start: date, end: date, raw_dir: Path
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
        "download_format": "unarchived",
    }


def _fetch(client: CdsClient, request: dict[str, Any], target: Path) -> Path:
    """Retrieve into a temp file next to ``target``, check it is not a zip, rename."""
    if target.exists():
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(f"{target.name}.tmp")
    try:
        try:
            client.retrieve(DATASET, request, str(tmp))
        except Exception as e:  # cdsapi raises plain Exception subclasses
            raise DownloadError(f"download of {target} failed: {e}") from e
        if not tmp.is_file():
            raise DownloadError(f"download of {target} produced no file")
        with tmp.open("rb") as f:
            if f.read(len(_ZIP_MAGIC)) == _ZIP_MAGIC:
                raise DownloadError(
                    f"download of {target} returned a zip archive, not netCDF; "
                    "request one product type per call with download_format=unarchived"
                )
        os.replace(tmp, target)
    finally:
        tmp.unlink(missing_ok=True)
    return target


def _snap_up(x: float) -> float:
    """Round up to the next 0.25-degree grid line."""
    return math.ceil(x / GRID_DEG - _SNAP_TOL) * GRID_DEG


def _snap_down(x: float) -> float:
    """Round down to the previous 0.25-degree grid line."""
    return math.floor(x / GRID_DEG + _SNAP_TOL) * GRID_DEG
