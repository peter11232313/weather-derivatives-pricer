"""Raw ERA5 files to one processed Parquet file per city (spec 002)."""

from datetime import date, datetime
from pathlib import Path

import xarray as xr

from dataprep.registry import Station


def load_hourly(station: Station, start: date, end: date, raw_dir: Path) -> xr.Dataset:
    """Load and normalise the hourly ``mx2t``/``mn2t`` needed for ``start..end``.

    Year files are partitioned so each hour is used once: from a year file keep only steps
    in (Y-01-01 00:00, Y+1-01-01 00:00); the step after ``end`` comes from the next year's
    file if present, else the one-step file (ADR 0002).

    Raises:
        DataError: If a needed raw file is missing; the message names the path.
    """
    raise NotImplementedError


def process(
    station: Station,
    start: date,
    end: date,
    raw_dir: Path,
    processed_dir: Path,
    registry_version: str,
    now: datetime,
) -> Path:
    """Build ``<processed_dir>/<city>.parquet`` for ``start..end``.

    Load, interpolate to the station (ADR 0003), compute daily extremes (ADR 0002),
    compute the diagnostics and write with metadata. ``now`` (UTC) fills ``created_utc``.

    Returns:
        The processed file path.
    """
    raise NotImplementedError
