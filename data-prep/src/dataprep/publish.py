"""Validate a processed file and copy it to the folder the pricer reads (spec 002)."""

import os
import shutil
from datetime import date
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

from dataprep.errors import PublishError
from dataprep.write import REQUIRED_METADATA_KEYS

_COLUMNS = {"date": pa.date32(), "tmax_c": pa.float64(), "tmin_c": pa.float64()}


def validate(path: Path) -> None:
    """Check a processed file against the spec 001/002 contract.

    Checks: columns ``date`` (DATE), ``tmax_c``, ``tmin_c`` (DOUBLE); Snappy compression;
    all required metadata keys; dates sorted, unique and without gaps; row count equal to
    the days in the metadata ``start..end``; no NaN; ``tmin_c <= tmax_c``.

    Raises:
        PublishError: Naming the first problem found and the path.
    """
    try:
        parquet = pq.ParquetFile(path)
        table = parquet.read()
    except (OSError, pa.ArrowException) as e:
        raise PublishError(f"{path}: cannot read Parquet: {e}") from e

    actual = {field.name: field.type for field in table.schema}
    if actual != _COLUMNS:
        raise PublishError(
            f"{path}: columns must be {_describe(_COLUMNS)}, got {_describe(actual)}"
        )

    meta = parquet.metadata
    for rg in range(meta.num_row_groups):
        for col in range(meta.num_columns):
            chunk = meta.row_group(rg).column(col)
            if chunk.compression != "SNAPPY":
                raise PublishError(
                    f"{path}: column {chunk.path_in_schema} is {chunk.compression}, not SNAPPY"
                )

    kv = {k.decode(): v.decode() for k, v in (meta.metadata or {}).items()}
    missing = [key for key in REQUIRED_METADATA_KEYS if key not in kv]
    if missing:
        raise PublishError(f"{path}: missing metadata key(s) {', '.join(missing)}")
    try:
        start, end = date.fromisoformat(kv["start"]), date.fromisoformat(kv["end"])
    except ValueError as e:
        raise PublishError(f"{path}: metadata start/end are not ISO dates: {e}") from e

    for name in _COLUMNS:
        if table.column(name).null_count:
            raise PublishError(f"{path}: column {name} has null cells")
    dates = table.column("date").to_numpy().astype("datetime64[D]")
    expected = np.arange(
        np.datetime64(start, "D"), np.datetime64(end, "D") + np.timedelta64(1, "D")
    )
    if dates.size != expected.size:
        raise PublishError(f"{path}: {dates.size} rows but {start}..{end} has {expected.size} days")
    wrong = np.flatnonzero(dates != expected)
    if wrong.size:
        k = wrong[0]
        raise PublishError(
            f"{path}: row {k} is {dates[k]} but {expected[k]} was expected "
            "(dates must be sorted, unique and without gaps)"
        )

    tmax = table.column("tmax_c").to_numpy()
    tmin = table.column("tmin_c").to_numpy()
    for name, values in (("tmax_c", tmax), ("tmin_c", tmin)):
        nan = np.flatnonzero(np.isnan(values))
        if nan.size:
            raise PublishError(f"{path}: {name} is NaN on {dates[nan[0]]}")
    bad = np.flatnonzero(tmin > tmax)
    if bad.size:
        k = bad[0]
        raise PublishError(f"{path}: tmin_c {tmin[k]} > tmax_c {tmax[k]} on {dates[k]}")


def publish(city: str, processed_dir: Path, pricer_dir: Path) -> Path:
    """Validate ``<processed_dir>/<city>.parquet`` and copy it to ``<pricer_dir>``.

    Copies to a temporary name, then renames, so the pricer never sees a partial file. On
    failure the pricer folder is unchanged.

    Returns:
        The published path.

    Raises:
        PublishError: If the processed file is missing or invalid.
    """
    source = processed_dir / f"{city}.parquet"
    if not source.is_file():
        raise PublishError(f"processed file not found: {source}")
    validate(source)
    pricer_dir.mkdir(parents=True, exist_ok=True)
    target = pricer_dir / source.name
    tmp = pricer_dir / f".{source.name}.tmp"
    try:
        shutil.copyfile(source, tmp)
        os.replace(tmp, target)
    finally:
        tmp.unlink(missing_ok=True)
    return target


def _describe(columns: dict[str, pa.DataType]) -> str:
    return ", ".join(f"{name}: {dtype}" for name, dtype in columns.items())
