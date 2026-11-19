"""Write the daily series as Parquet in the spec 001 schema (spec 002)."""

import os
from collections.abc import Mapping
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

from dataprep.daily import DailySeries
from dataprep.errors import DataError

# Spec 001 contract. Non-nullable: a written file never has null cells.
SCHEMA = pa.schema(
    [
        pa.field("date", pa.date32(), nullable=False),
        pa.field("tmax_c", pa.float64(), nullable=False),
        pa.field("tmin_c", pa.float64(), nullable=False),
    ]
)

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
    "tmax_window_start_utc_h",
    "tmin_window_start_utc_h",
)


def write_parquet(path: Path, daily: DailySeries, metadata: Mapping[str, str]) -> Path:
    """Write ``date`` (DATE), ``tmax_c`` and ``tmin_c`` (DOUBLE), Snappy-compressed.

    ``metadata`` is stored as Parquet file key-value metadata. The file is written to a
    temporary name and renamed into place.

    Raises:
        ValueError: If a key in ``REQUIRED_METADATA_KEYS`` is missing.
        DataError: If the file cannot be written or moved into place (e.g. it is open in
            another program on Windows); the message names the path.
    """
    missing = [key for key in REQUIRED_METADATA_KEYS if key not in metadata]
    if missing:
        raise ValueError(f"missing metadata key(s) for {path}: {', '.join(missing)}")
    table = pa.Table.from_arrays(
        [
            pa.array(daily.dates.astype("datetime64[D]"), type=pa.date32()),
            pa.array(daily.tmax_c, type=pa.float64()),
            pa.array(daily.tmin_c, type=pa.float64()),
        ],
        schema=SCHEMA.with_metadata({str(k): str(v) for k, v in metadata.items()}),
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.tmp")
    try:
        pq.write_table(table, tmp, compression="snappy")
        os.replace(tmp, path)
    except OSError as e:
        raise DataError(f"cannot write {path} (is it open in another program?): {e}") from e
    finally:
        tmp.unlink(missing_ok=True)
    return path
