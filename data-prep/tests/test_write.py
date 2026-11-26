"""Spec 002 AC: written file schema, Snappy compression and key-value metadata."""

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq
import pytest
from synth import valid_processed_file

from dataprep.daily import DailySeries
from dataprep.errors import DataPrepError
from dataprep.write import REQUIRED_METADATA_KEYS, write_parquet

METADATA = {
    "source": "ERA5 mx2t/mn2t",
    "method": "bilinear",
    "station": "London Heathrow",
    "lat": "51.479",
    "lon": "-0.449",
    "start": "2024-02-28",
    "end": "2024-03-01",
    "registry_version": "0123456789ab",
    "created_utc": "2026-11-16T12:00:00Z",
    "tmean_nearest_c_mean": "5.5",
    "tmean_3x3_c_mean": "5.25",
    "tmax_window_start_utc_h": "9",
    "tmin_window_start_utc_h": "-15",
}


def leap_series() -> DailySeries:
    return DailySeries(
        dates=np.array(["2024-02-28", "2024-02-29", "2024-03-01"], dtype="datetime64[D]"),
        tmax_c=np.array([8.5, 10.0, -0.25]),
        tmin_c=np.array([1.0, 2.5, -4.75]),
    )


def test_spec_metadata_keys_constant_matches_spec():
    assert set(REQUIRED_METADATA_KEYS) == set(METADATA)


def test_written_file_has_spec_schema_snappy_and_metadata(tmp_path):
    path = tmp_path / "processed" / "london.parquet"
    path.parent.mkdir()
    returned = write_parquet(path, leap_series(), METADATA)
    assert returned == path

    schema = pq.read_schema(path)
    assert schema.names == ["date", "tmax_c", "tmin_c"]
    assert schema.field("date").type == pa.date32()
    assert schema.field("tmax_c").type == pa.float64()
    assert schema.field("tmin_c").type == pa.float64()

    meta = pq.ParquetFile(path).metadata
    assert meta.num_row_groups >= 1
    for rg in range(meta.num_row_groups):
        for col in range(meta.num_columns):
            assert meta.row_group(rg).column(col).compression == "SNAPPY"

    kv = {k.decode(): v.decode() for k, v in (meta.metadata or {}).items()}
    for key in REQUIRED_METADATA_KEYS:
        assert kv[key] == METADATA[key]


def test_written_values_round_trip(tmp_path):
    path = tmp_path / "london.parquet"
    write_parquet(path, leap_series(), METADATA)
    table = pq.read_table(path)
    assert [d.isoformat() for d in table.column("date").to_pylist()] == [
        "2024-02-28",
        "2024-02-29",
        "2024-03-01",
    ]
    assert table.column("tmax_c").to_pylist() == [8.5, 10.0, -0.25]
    assert table.column("tmin_c").to_pylist() == [1.0, 2.5, -4.75]


def test_write_leaves_no_temporary_files(tmp_path):
    out_dir = tmp_path / "processed"
    out_dir.mkdir()
    write_parquet(out_dir / "london.parquet", leap_series(), METADATA)
    assert [p.name for p in out_dir.iterdir()] == ["london.parquet"]


@pytest.mark.parametrize("missing", REQUIRED_METADATA_KEYS)
def test_missing_metadata_key_raises_and_writes_nothing(tmp_path, missing):
    out_dir = tmp_path / "processed"
    out_dir.mkdir()
    meta = {k: v for k, v in METADATA.items() if k != missing}
    with pytest.raises(ValueError) as err:
        write_parquet(out_dir / "london.parquet", leap_series(), meta)
    assert missing in str(err.value)
    assert list(out_dir.iterdir()) == []


# --- locked target


def test_write_locked_target_is_a_data_prep_error(tmp_path, monkeypatch):
    def locked(src, dst):
        raise PermissionError(32, "The process cannot access the file", str(dst))

    monkeypatch.setattr("dataprep.write.os.replace", locked)
    with pytest.raises(DataPrepError) as err:
        valid_processed_file(tmp_path / "london.parquet")
    assert "london.parquet" in str(err.value)
    assert list(tmp_path.iterdir()) == []
