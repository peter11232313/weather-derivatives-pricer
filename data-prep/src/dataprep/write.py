"""Write the daily series as Parquet in the spec 001 schema (spec 002)."""

from collections.abc import Mapping
from pathlib import Path

from dataprep.daily import DailySeries

REQUIRED_METADATA_KEYS = (
    "source",
    "method",
    "station",
    "lat",
    "lon",
    "start",
    "end",
    "registry_version",
    "created_utc",
    "tmean_nearest_c_mean",
    "tmean_3x3_c_mean",
)


def write_parquet(path: Path, daily: DailySeries, metadata: Mapping[str, str]) -> Path:
    """Write ``date`` (DATE), ``tmax_c`` and ``tmin_c`` (DOUBLE), Snappy-compressed.

    ``metadata`` is stored as Parquet file key-value metadata. The file is written to a
    temporary name and renamed into place.

    Raises:
        ValueError: If a key in ``REQUIRED_METADATA_KEYS`` is missing.
    """
    raise NotImplementedError
