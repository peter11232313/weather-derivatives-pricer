"""Spec 002 ACs: publish copies a valid file; any invalid file is refused, pricer dir unchanged.

Invalid files are built directly with pyarrow here, independently of ``write_parquet``.
"""

from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from dataprep.errors import PublishError
from dataprep.publish import publish, validate

FULL_METADATA = {
    "source": "ERA5 mx2t/mn2t",
    "method": "bilinear",
    "station": "London Heathrow",
    "lat": "51.479",
    "lon": "-0.449",
    "start": "2024-01-01",
    "end": "2024-12-31",
    "registry_version": "0123456789ab",
    "created_utc": "2026-11-16T12:00:00Z",
    "tmean_nearest_c_mean": "10.0",
    "tmean_3x3_c_mean": "10.0",
    "tmax_window_start_utc_h": "9",
    "tmin_window_start_utc_h": "-15",
}


def days(start: date, end: date) -> list[date]:
    return [start + timedelta(days=i) for i in range((end - start).days + 1)]


def write_file(
    path: Path,
    dates: list[date] | None = None,
    tmax: list[float] | None = None,
    tmin: list[float] | None = None,
    *,
    metadata: dict[str, str] | None = None,
    compression: str = "snappy",
    columns: dict[str, pa.Array] | None = None,
) -> Path:
    """Write a processed file; defaults give a valid leap year 2024 (366 rows)."""
    if dates is None:
        dates = days(date(2024, 1, 1), date(2024, 12, 31))
    n = len(dates)
    if tmax is None:
        tmax = list(np.linspace(5.0, 15.0, n))
    if tmin is None:
        tmin = [t - 6.0 for t in tmax]
    if columns is None:
        columns = {
            "date": pa.array(dates, type=pa.date32()),
            "tmax_c": pa.array(tmax, type=pa.float64()),
            "tmin_c": pa.array(tmin, type=pa.float64()),
        }
    table = pa.table(columns)
    meta = FULL_METADATA if metadata is None else metadata
    table = table.replace_schema_metadata({k.encode(): v.encode() for k, v in meta.items()})
    path.parent.mkdir(parents=True, exist_ok=True)
    pq.write_table(table, path, compression=compression)
    return path


def snapshot(folder: Path) -> dict[str, bytes]:
    return {p.name: p.read_bytes() for p in sorted(folder.iterdir())}


# --------------------------------------------------------------------------- valid


def test_valid_file_is_published_byte_identical(processed_dir, pricer_dir):
    src = write_file(processed_dir / "london.parquet")
    out = publish("london", processed_dir, pricer_dir)
    assert out == pricer_dir / "london.parquet"
    assert out.read_bytes() == src.read_bytes()
    # Only the published file: no leftover temporary file.
    assert [p.name for p in pricer_dir.iterdir()] == ["london.parquet"]


def test_valid_file_replaces_an_older_published_file(processed_dir, pricer_dir):
    write_file(pricer_dir / "london.parquet", tmax=[1.0] * 366)
    src = write_file(processed_dir / "london.parquet")
    publish("london", processed_dir, pricer_dir)
    assert (pricer_dir / "london.parquet").read_bytes() == src.read_bytes()


def test_validate_accepts_valid_file(tmp_path):
    assert validate(write_file(tmp_path / "london.parquet")) is None


def test_validate_accepts_365_day_year(tmp_path):
    meta = FULL_METADATA | {"start": "2023-01-01", "end": "2023-12-31"}
    path = write_file(
        tmp_path / "london.parquet", days(date(2023, 1, 1), date(2023, 12, 31)), metadata=meta
    )
    assert validate(path) is None


def test_missing_processed_file_raises_and_pricer_dir_unchanged(processed_dir, pricer_dir):
    with pytest.raises(PublishError) as err:
        publish("london", processed_dir, pricer_dir)
    assert "london" in str(err.value)
    assert list(pricer_dir.iterdir()) == []


# --------------------------------------------------------------------------- invalid

D = days(date(2024, 1, 1), date(2024, 12, 31))
N = len(D)


def _wrong_type_tmax(path):
    return write_file(
        path,
        columns={
            "date": pa.array(D, type=pa.date32()),
            "tmax_c": pa.array([10.0] * N, type=pa.float32()),
            "tmin_c": pa.array([1.0] * N, type=pa.float64()),
        },
    )


def _date_as_timestamp(path):
    return write_file(
        path,
        columns={
            "date": pa.array(np.array(D, dtype="datetime64[ms]"), type=pa.timestamp("ms")),
            "tmax_c": pa.array([10.0] * N, type=pa.float64()),
            "tmin_c": pa.array([1.0] * N, type=pa.float64()),
        },
    )


def _date_as_string(path):
    return write_file(
        path,
        columns={
            "date": pa.array([d.isoformat() for d in D]),
            "tmax_c": pa.array([10.0] * N, type=pa.float64()),
            "tmin_c": pa.array([1.0] * N, type=pa.float64()),
        },
    )


def _missing_column(path):
    return write_file(
        path,
        columns={
            "date": pa.array(D, type=pa.date32()),
            "tmax_c": pa.array([10.0] * N, type=pa.float64()),
        },
    )


def _renamed_column(path):
    return write_file(
        path,
        columns={
            "date": pa.array(D, type=pa.date32()),
            "tmax": pa.array([10.0] * N, type=pa.float64()),
            "tmin_c": pa.array([1.0] * N, type=pa.float64()),
        },
    )


def _gzip(path):
    return write_file(path, compression="gzip")


def _uncompressed(path):
    return write_file(path, compression="none")


def _missing_metadata(key):
    def build(path):
        return write_file(path, metadata={k: v for k, v in FULL_METADATA.items() if k != key})

    return build


def _gap(path):
    # 2024-03-15 missing; 365 rows for a 366-day start..end.
    return write_file(path, [d for d in D if d != date(2024, 3, 15)])


def _gap_with_matching_count(path):
    # 2024-03-15 missing but 2025-01-01 appended: row count matches 366, dates still wrong.
    return write_file(path, [d for d in D if d != date(2024, 3, 15)] + [date(2025, 1, 1)])


def _duplicate(path):
    dup = D[:100] + [D[99]] + D[100:-1]  # 366 rows, 2024-04-09 twice, 2024-12-31 dropped
    return write_file(path, dup)


def _unsorted(path):
    swapped = list(D)
    swapped[10], swapped[11] = swapped[11], swapped[10]
    return write_file(path, swapped)


def _nan_tmax(path):
    tmax = [10.0] * N
    tmax[200] = float("nan")
    return write_file(path, tmax=tmax, tmin=[1.0] * N)


def _nan_tmin(path):
    tmin = [1.0] * N
    tmin[59] = float("nan")  # 2024-02-29
    return write_file(path, tmax=[10.0] * N, tmin=tmin)


def _tmin_above_tmax(path):
    tmin = [1.0] * N
    tmin[45] = 12.0
    return write_file(path, tmax=[10.0] * N, tmin=tmin)


def _row_count_short(path):
    # Valid consecutive dates 2024-01-01..2024-12-30 but metadata end is 2024-12-31.
    return write_file(path, D[:-1])


def _row_count_long(path):
    # 2023-12-31..2024-12-31 consecutive, but metadata says 2024-01-01..2024-12-31.
    return write_file(path, [date(2023, 12, 31), *D])


def _non_leap_year_with_366_rows(path):
    # Metadata start..end is 2023 (365 days) but the file holds 366 consecutive days
    # 2023-01-01..2024-01-01 (as if a leap year were assumed).
    meta = FULL_METADATA | {"start": "2023-01-01", "end": "2023-12-31"}
    return write_file(path, days(date(2023, 1, 1), date(2024, 1, 1)), metadata=meta)


INVALID = {
    "tmax_float32": _wrong_type_tmax,
    "date_timestamp": _date_as_timestamp,
    "date_string": _date_as_string,
    "missing_column": _missing_column,
    "renamed_column": _renamed_column,
    "gzip": _gzip,
    "uncompressed": _uncompressed,
    **{f"missing_meta_{k}": _missing_metadata(k) for k in FULL_METADATA},
    "gap": _gap,
    "gap_matching_count": _gap_with_matching_count,
    "duplicate": _duplicate,
    "unsorted": _unsorted,
    "nan_tmax": _nan_tmax,
    "nan_tmin": _nan_tmin,
    "tmin_gt_tmax": _tmin_above_tmax,
    "rows_short": _row_count_short,
    "rows_long": _row_count_long,
    "non_leap_366_rows": _non_leap_year_with_366_rows,
}


@pytest.mark.parametrize("build", INVALID.values(), ids=INVALID.keys())
def test_invalid_file_is_rejected_by_validate(tmp_path, build):
    path = build(tmp_path / "london.parquet")
    with pytest.raises(PublishError) as err:
        validate(path)
    assert "london.parquet" in str(err.value)


@pytest.mark.parametrize("build", INVALID.values(), ids=INVALID.keys())
def test_invalid_file_is_not_published_and_existing_file_kept(processed_dir, pricer_dir, build):
    existing = write_file(pricer_dir / "london.parquet", tmax=[7.0] * N, tmin=[2.0] * N)
    before = snapshot(pricer_dir)
    build(processed_dir / "london.parquet")
    with pytest.raises(PublishError):
        publish("london", processed_dir, pricer_dir)
    assert snapshot(pricer_dir) == before
    assert existing.read_bytes() == before["london.parquet"]


@pytest.mark.parametrize("build", [_gzip, _nan_tmax, _gap], ids=["gzip", "nan", "gap"])
def test_invalid_file_into_empty_pricer_dir_leaves_it_empty(processed_dir, pricer_dir, build):
    build(processed_dir / "london.parquet")
    with pytest.raises(PublishError):
        publish("london", processed_dir, pricer_dir)
    assert list(pricer_dir.iterdir()) == []
