"""ERA5 downloads via the Copernicus CDS (spec 002, ADR 0002, ``era5-data`` skill).

Every function takes the CDS client as an argument (anything with
``retrieve(dataset, request, target)``, such as ``cdsapi.Client()``) so tests can pass a
fake. Files are written to a temporary name and renamed into place; an existing target is
never downloaded again.
"""

from datetime import date
from pathlib import Path
from typing import Any, Protocol

from dataprep.registry import Station

DATASET = "reanalysis-era5-single-levels"


class CdsClient(Protocol):
    """The part of ``cdsapi.Client`` that data-prep uses."""

    def retrieve(self, name: str, request: dict[str, Any], target: str) -> Any:
        """Download ``name`` with ``request`` into the file ``target``."""
        ...


def area_for(station: Station) -> tuple[float, float, float, float]:
    """Return the download area (N, W, S, E) in degrees.

    Station +/- 0.5 degrees, widened outward to 0.25-degree grid lines.
    """
    raise NotImplementedError


def year_file(raw_dir: Path, city: str, year: int) -> Path:
    """Return ``<raw_dir>/era5_<city>_<year>.nc`` (every hour of ``year``)."""
    raise NotImplementedError


def new_year_step_file(raw_dir: Path, city: str, year: int) -> Path:
    """Return ``<raw_dir>/era5_<city>_<year>0101T00.nc`` (the single step ``year``-01-01 00:00)."""
    raise NotImplementedError


def lsm_file(raw_dir: Path, city: str) -> Path:
    """Return ``<raw_dir>/era5_lsm_<city>.nc`` (static land-sea mask)."""
    raise NotImplementedError


def download_year(client: CdsClient, station: Station, year: int, raw_dir: Path) -> Path:
    """Download hourly ``mx2t`` and ``mn2t`` for every hour of ``year``.

    Returns:
        The year file path.

    Raises:
        DownloadError: If the download fails or returns a zip; no partial file is left.
    """
    raise NotImplementedError


def download_new_year_step(client: CdsClient, station: Station, year: int, raw_dir: Path) -> Path:
    """Download the single step ``year``-01-01 00:00 of ``mx2t`` and ``mn2t``.

    Only needed after the final year of a range whose next year file is absent (ADR 0002).

    Raises:
        DownloadError: If the download fails or returns a zip; no partial file is left.
    """
    raise NotImplementedError


def download_lsm(client: CdsClient, station: Station, raw_dir: Path) -> Path:
    """Download the land-sea mask (one time step) in its own request.

    Raises:
        DownloadError: If the download fails or returns a zip; no partial file is left.
    """
    raise NotImplementedError


def download_range(
    client: CdsClient, station: Station, start: date, end: date, raw_dir: Path
) -> list[Path]:
    """Download everything needed to process ``start..end`` (inclusive UTC days).

    That is the land-sea mask, one file per year touched, and the 00:00 step after ``end``
    (from the next year's file if present, else a one-step file).

    Returns:
        All raw file paths needed, whether newly downloaded or already present.
    """
    raise NotImplementedError
