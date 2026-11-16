"""Validate a processed file and copy it to the folder the pricer reads (spec 002)."""

import os
import re
import shutil
from datetime import date
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

from dataprep.errors import PublishError
from dataprep.write import REQUIRED_METADATA_KEYS

_COLUMNS = {"date": pa.date32(), "tmax_c": pa.float64(), "tmin_c": pa.float64()}
_METHODS = frozenset({"bilinear", "nearest_land"})
_SOURCE = "ERA5 mx2t/mn2t"
_REGISTRY_VERSION = re.compile(r"[0-9a-f]{12}")


def validate(path: Path, *, name: Path | None = None) -> None:
    """Check a processed file against the spec 001/002 contract.

    Checks: columns ``date`` (DATE), ``tmax_c``, ``tmin_c`` (DOUBLE); Snappy compression;
    all required metadata keys with valid ``source``, ``method`` and ``registry_version``;
    no nulls; dates sorted, unique and without gaps; row count equal to the days in the
    metadata ``start..end``; no NaN; ``tmin_c <= tmax_c``.

    Args:
        path: File to check.
        name: Path to name in error messages (default ``path``), e.g. the source file when
            checking a temporary copy.

    Raises:
        PublishError: Naming the first problem found and the path.
    """
    label = name or path
    try:
        with pq.ParquetFile(path) as parquet:
            table = parquet.read()
            meta = parquet.metadata
    except (OSError, pa.ArrowException) as e:
        raise PublishError(f"{label}: cannot read Parquet: {e}") from e

    actual = {field.name: field.type for field in table.schema}
    if actual != _COLUMNS:
        raise PublishError(
            f"{label}: columns must be {_describe(_COLUMNS)}, got {_describe(actual)}"
        )

    for rg in range(meta.num_row_groups):
        for col in range(meta.num_columns):
            chunk = meta.row_group(rg).column(col)
            if chunk.compression != "SNAPPY":
                raise PublishError(
                    f"{label}: column {chunk.path_in_schema} is {chunk.compression}, not SNAPPY"
                )

    kv = {k.decode(): v.decode() for k, v in (meta.metadata or {}).items()}
    missing = [key for key in REQUIRED_METADATA_KEYS if key not in kv]
    if missing:
        raise PublishError(f"{label}: missing metadata key(s) {', '.join(missing)}")
    if kv["source"] != _SOURCE:
        raise PublishError(f"{label}: metadata source is {kv['source']!r}, not {_SOURCE!r}")
    if kv["method"] not in _METHODS:
        raise PublishError(f"{label}: metadata method {kv['method']!r} not in {sorted(_METHODS)}")
    if not _REGISTRY_VERSION.fullmatch(kv["registry_version"]):
        raise PublishError(
            f"{label}: metadata registry_version {kv['registry_version']!r} is not "
            "12 lowercase hex characters"
        )
    try:
        start, end = date.fromisoformat(kv["start"]), date.fromisoformat(kv["end"])
    except ValueError as e:
        raise PublishError(f"{label}: metadata start/end are not ISO dates: {e}") from e

    for column in _COLUMNS:
        if table.column(column).null_count:
            raise PublishError(f"{label}: column {column} has null cells")
    dates = table.column("date").to_numpy().astype("datetime64[D]")
    expected = np.arange(
        np.datetime64(start, "D"), np.datetime64(end, "D") + np.timedelta64(1, "D")
    )
    if dates.size != expected.size:
        raise PublishError(
            f"{label}: {dates.size} rows but {start}..{end} has {expected.size} days"
        )
    wrong = np.flatnonzero(dates != expected)
    if wrong.size:
        k = wrong[0]
        raise PublishError(
            f"{label}: row {k} is {dates[k]} but {expected[k]} was expected "
            "(dates must be sorted, unique and without gaps)"
        )

    tmax = table.column("tmax_c").to_numpy()
    tmin = table.column("tmin_c").to_numpy()
    for column, values in (("tmax_c", tmax), ("tmin_c", tmin)):
        nan = np.flatnonzero(np.isnan(values))
        if nan.size:
            raise PublishError(f"{label}: {column} is NaN on {dates[nan[0]]}")
    bad = np.flatnonzero(tmin > tmax)
    if bad.size:
        k = bad[0]
        raise PublishError(f"{label}: tmin_c {tmin[k]} > tmax_c {tmax[k]} on {dates[k]}")


def publish(city: str, processed_dir: Path, pricer_dir: Path) -> Path:
    """Validate ``<processed_dir>/<city>.parquet`` and copy it to ``<pricer_dir>``.

    Copies to a temporary name in the pricer folder, validates that copy (so the bytes
    published are the bytes checked), then renames it into place. On any failure the
    pricer folder is unchanged.

    Returns:
        The published path.

    Raises:
        PublishError: If the processed file is missing or invalid, or the target cannot be
            replaced (e.g. it is open in another program on Windows).
    """
    source = processed_dir / f"{city}.parquet"
    if not source.is_file():
        raise PublishError(f"processed file not found: {source}")
    pricer_dir.mkdir(parents=True, exist_ok=True)
    target = pricer_dir / source.name
    tmp = pricer_dir / f".{source.name}.tmp"
    try:
        try:
            shutil.copyfile(source, tmp)
        except OSError as e:
            raise PublishError(f"cannot copy {source} to {pricer_dir}: {e}") from e
        validate(tmp, name=source)
        try:
            os.replace(tmp, target)
        except OSError as e:
            raise PublishError(
                f"cannot replace {target} (is it open in another program?): {e}"
            ) from e
    finally:
        tmp.unlink(missing_ok=True)
    return target


def _describe(columns: dict[str, pa.DataType]) -> str:
    return ", ".join(f"{column}: {dtype}" for column, dtype in columns.items())
