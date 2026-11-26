"""Spec 002 ACs end to end: raw netCDF files -> processed Parquet via pipeline.process."""

import hashlib
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
import pytest
from synth import (
    KELVIN,
    LONDON_LATS,
    LONDON_LONS,
    hours,
    linear_c,
    make_era5,
    make_lsm,
    make_station,
    write_edge_day_file,
    write_lsm_file,
    write_step_file,
    write_year_file,
)

from dataprep.errors import DataError
from dataprep.pipeline import load_hourly, process
from dataprep.write import REQUIRED_METADATA_KEYS

NOW = datetime(2026, 11, 16, 12, 0, tzinfo=UTC)
REG_VERSION = "0123456789ab"
LAT2, LON2 = np.meshgrid(LONDON_LATS, LONDON_LONS, indexing="ij")


def temps(times, mx_c, mn_c, spatial=0.0, **kw):
    """ERA5-like file content on the London grid, as Copernicus delivers it.

    ``mx_c`` / ``mn_c``: degrees C, scalar or one value per time step (spatially uniform),
    plus an optional ``spatial`` (lat, lon) field in degrees C. Stored in kelvin, float64,
    ``valid_time``, latitude north->south, with a ``number`` coord.
    """
    times = pd.DatetimeIndex(times)
    mx = np.asarray(mx_c, dtype=float)
    mn = np.asarray(mn_c, dtype=float)
    if mx.ndim == 1:
        mx = mx[:, None, None]
    if mn.ndim == 1:
        mn = mn[:, None, None]
    options = {"time_name": "valid_time", "lat_descending": True, "number": True}
    options |= {"dtype": "float64"} | kw
    return make_era5(
        times,
        LONDON_LATS,
        LONDON_LONS,
        mx2t_k=mx + spatial + KELVIN,
        mn2t_k=mn + spatial + KELVIN,
        **options,
    )


def window(start: str, end: str) -> pd.DatetimeIndex:
    last = pd.Timestamp(end) + pd.Timedelta(days=1)
    return hours(f"{start}T01:00", last.isoformat())


def run(raw_dir, processed_dir, start, end, station=None, lsm=None, reg=REG_VERSION):
    station = station or make_station()
    if not (raw_dir / f"era5_lsm_{station.city}.nc").exists():
        write_lsm_file(
            raw_dir, station.city, lsm if lsm is not None else make_lsm(LONDON_LATS, LONDON_LONS)
        )
    return process(station, start, end, raw_dir, processed_dir, reg, NOW)


def read(path: Path) -> tuple[list[date], list[float], list[float], dict[str, str]]:
    table = pq.read_table(path)
    kv = {k.decode(): v.decode() for k, v in (pq.ParquetFile(path).metadata.metadata or {}).items()}
    return (
        table.column("date").to_pylist(),
        table.column("tmax_c").to_pylist(),
        table.column("tmin_c").to_pylist(),
        kv,
    )


# --------------------------------------------------------------------------- hour alignment


def test_spec_hour_alignment_example_end_to_end(raw_dir, processed_dir):
    # 2023 file: 2023-12-31 00:00 decoy (belongs to 2023-12-30), 01:00..23:00 mx 8 / mn 6.
    # 2024 file: 2024-01-01 00:00 mx 9 / mn 4 (-> 2023-12-31), 01:00..23:00 mx 5 / mn 1,
    #            2024-01-02 00:00 mx 12 / mn -3 (-> 2024-01-01).
    # => 2023-12-31: 9.0 / 4.0; 2024-01-01: 12.0 / -3.0 (to K round-trip tolerance).
    write_year_file(
        raw_dir,
        "london",
        2023,
        temps(
            hours("2023-12-31T00:00", "2023-12-31T23:00"),
            [40.0] + [8.0] * 23,
            [-40.0] + [6.0] * 23,
        ),
    )
    write_year_file(
        raw_dir,
        "london",
        2024,
        temps(
            hours("2024-01-01T00:00", "2024-01-02T00:00"),
            [9.0] + [5.0] * 23 + [12.0],
            [4.0] + [1.0] * 23 + [-3.0],
        ),
    )
    out = run(raw_dir, processed_dir, date(2023, 12, 31), date(2024, 1, 1))
    dates, tmax, tmin, _ = read(out)
    assert dates == [date(2023, 12, 31), date(2024, 1, 1)]
    np.testing.assert_allclose(tmax, [9.0, 12.0], atol=1e-9)
    np.testing.assert_allclose(tmin, [4.0, -3.0], atol=1e-9)


# --------------------------------------------------------------------------- year partition


def test_31_december_uses_1_january_midnight_from_next_year_file(raw_dir, processed_dir):
    # 2024 file's 00:00 = 12 / -3 must be used; its later steps (30 / -30), the
    # leftover one-step file (20 / -20) and a stray edge-day file for 2024-01-01
    # (21 / -21; the 2024 year file wins) are decoys. Expected 2023-12-31: 12.0 / -3.0.
    write_year_file(
        raw_dir,
        "london",
        2023,
        temps(
            hours("2023-12-31T00:00", "2023-12-31T23:00"),
            [40.0] + [5.0] * 23,
            [-40.0] + [1.0] * 23,
        ),
    )
    write_year_file(
        raw_dir,
        "london",
        2024,
        temps(
            hours("2024-01-01T00:00", "2024-01-01T05:00"),
            [12.0] + [30.0] * 5,
            [-3.0] + [-30.0] * 5,
        ),
    )
    write_step_file(
        raw_dir, "london", 2024, temps(hours("2024-01-01T00:00", "2024-01-01T00:00"), 20.0, -20.0)
    )
    write_edge_day_file(
        raw_dir,
        "london",
        date(2024, 1, 1),
        temps(hours("2024-01-01T00:00", "2024-01-01T23:00"), 21.0, -21.0),
    )
    hourly = load_hourly(make_station(), date(2023, 12, 31), date(2023, 12, 31), raw_dir)
    times = pd.DatetimeIndex(hourly["time"].values)
    assert times.is_unique
    assert list(times) == list(window("2023-12-31", "2023-12-31"))

    out = run(raw_dir, processed_dir, date(2023, 12, 31), date(2023, 12, 31))
    dates, tmax, tmin, _ = read(out)
    assert dates == [date(2023, 12, 31)]
    np.testing.assert_allclose(tmax, [12.0], atol=1e-9)
    np.testing.assert_allclose(tmin, [-3.0], atol=1e-9)


def test_final_year_uses_edge_day_file(raw_dir, processed_dir):
    # No 2025 year file: 2024-12-31 takes 2025-01-01 00:00 (12 / -3) from the edge-day file
    # era5_london_20250101.nc; its later hours (30 / -30) are outside the 0 / 0 windows and
    # the leftover old one-step file (20 / -20) must not be read.
    write_year_file(
        raw_dir,
        "london",
        2024,
        temps(
            hours("2024-12-31T00:00", "2024-12-31T23:00"),
            [40.0] + [5.0] * 23,
            [-40.0] + [1.0] * 23,
        ),
    )
    write_edge_day_file(
        raw_dir,
        "london",
        date(2025, 1, 1),
        temps(
            hours("2025-01-01T00:00", "2025-01-01T23:00"),
            [12.0] + [30.0] * 23,
            [-3.0] + [-30.0] * 23,
        ),
    )
    write_step_file(
        raw_dir, "london", 2025, temps(hours("2025-01-01T00:00", "2025-01-01T00:00"), 20.0, -20.0)
    )
    out = run(raw_dir, processed_dir, date(2024, 12, 31), date(2024, 12, 31))
    dates, tmax, tmin, _ = read(out)
    assert dates == [date(2024, 12, 31)]
    np.testing.assert_allclose(tmax, [12.0], atol=1e-9)
    np.testing.assert_allclose(tmin, [-3.0], atol=1e-9)


def test_multi_year_load_uses_each_hour_once(raw_dir):
    # 2024-01-01 00:00 exists in the 2024 file (7.0), in a leftover step file (20.0) and
    # in a stray edge-day file (21.0). It must appear once, from the year file; nothing
    # outside the window is loaded.
    write_year_file(
        raw_dir, "london", 2023, temps(hours("2023-12-31T00:00", "2023-12-31T23:00"), 5.0, 1.0)
    )
    write_year_file(
        raw_dir,
        "london",
        2024,
        temps(hours("2024-01-01T00:00", "2024-01-02T00:00"), [7.0] + [5.0] * 24, 1.0),
    )
    write_step_file(
        raw_dir, "london", 2024, temps(hours("2024-01-01T00:00", "2024-01-01T00:00"), 20.0, -20.0)
    )
    write_edge_day_file(
        raw_dir,
        "london",
        date(2024, 1, 1),
        temps(hours("2024-01-01T00:00", "2024-01-01T23:00"), 21.0, -21.0),
    )
    hourly = load_hourly(make_station(), date(2023, 12, 31), date(2024, 1, 1), raw_dir)
    times = pd.DatetimeIndex(hourly["time"].values)
    assert times.is_unique
    assert list(times) == list(window("2023-12-31", "2024-01-01"))
    assert len(times) == 48
    midnight = hourly["mx2t"].sel(time=pd.Timestamp("2024-01-01T00:00"))
    np.testing.assert_allclose(midnight.values, 7.0, atol=1e-9)


def test_missing_year_file_raises_naming_path(raw_dir):
    with pytest.raises(DataError) as err:
        load_hourly(make_station(), date(2024, 6, 1), date(2024, 6, 1), raw_dir)
    assert "era5_london_2024" in str(err.value)


def test_missing_final_edge_day_and_next_year_file_raises_naming_path(raw_dir):
    write_year_file(
        raw_dir, "london", 2024, temps(hours("2024-12-31T00:00", "2024-12-31T23:00"), 5.0, 1.0)
    )
    with pytest.raises(DataError) as err:
        load_hourly(make_station(), date(2024, 12, 31), date(2024, 12, 31), raw_dir)
    assert "era5_london_2025" in str(err.value)


# --------------------------------------------------------------------------- row counts


@pytest.mark.parametrize(("year", "n_rows"), [(2024, 366), (2023, 365)])
def test_full_year_row_count_sorted_without_gaps(raw_dir, processed_dir, year, n_rows):
    write_year_file(
        raw_dir,
        "london",
        year,
        temps(hours(f"{year}-01-01T00:00", f"{year}-12-31T23:00"), 8.0, 2.0, dtype="float32"),
    )
    write_edge_day_file(
        raw_dir,
        "london",
        date(year + 1, 1, 1),
        temps(hours(f"{year + 1}-01-01T00:00", f"{year + 1}-01-01T23:00"), 8.0, 2.0),
    )
    out = run(raw_dir, processed_dir, date(year, 1, 1), date(year, 12, 31))
    dates, tmax, tmin, kv = read(out)
    assert len(dates) == n_rows
    assert dates == [date(year, 1, 1) + timedelta(days=i) for i in range(n_rows)]
    assert len(tmax) == n_rows and len(tmin) == n_rows
    assert kv["start"] == f"{year}-01-01" and kv["end"] == f"{year}-12-31"


# --------------------------------------------------------------------------- missing / NaN


def test_missing_hourly_step_in_raw_file_raises_naming_timestamp(raw_dir, processed_dir):
    times = hours("2024-01-01T00:00", "2024-01-02T00:00").delete(13)  # 2024-01-01 13:00
    write_year_file(raw_dir, "london", 2024, temps(times, 5.0, 1.0))
    with pytest.raises(DataError) as err:
        run(raw_dir, processed_dir, date(2024, 1, 1), date(2024, 1, 1))
    assert "2024-01-01T13:00" in str(err.value)
    assert list(processed_dir.iterdir()) == []


def test_missing_next_day_midnight_in_raw_file_raises_naming_timestamp(raw_dir, processed_dir):
    write_year_file(
        raw_dir, "london", 2024, temps(hours("2024-01-01T00:00", "2024-01-01T23:00"), 5.0, 1.0)
    )
    with pytest.raises(DataError) as err:
        run(raw_dir, processed_dir, date(2024, 1, 1), date(2024, 1, 1))
    assert "2024-01-02T00:00" in str(err.value)


def test_nan_in_station_series_raises_naming_date(raw_dir, processed_dir):
    times = hours("2024-01-01T00:00", "2024-01-03T00:00")
    mx = np.full(len(times), 5.0)
    mx[times.get_loc(pd.Timestamp("2024-01-02T09:00"))] = np.nan
    write_year_file(raw_dir, "london", 2024, temps(times, mx, 1.0))
    with pytest.raises(DataError) as err:
        run(raw_dir, processed_dir, date(2024, 1, 1), date(2024, 1, 2))
    assert "2024-01-02" in str(err.value)


# --------------------------------------------------------------------------- interpolation


def test_spec_interpolation_example_end_to_end_bilinear(raw_dir, processed_dir):
    # linear_c gives 10/12/14/16 at the spec's four cells; station at their centre.
    # mx = linear_c (constant in time) -> 13.0; mn = linear_c - 2 -> 11.0.
    spatial = linear_c(LAT2, LON2)
    write_year_file(
        raw_dir,
        "london",
        2024,
        temps(hours("2024-01-01T00:00", "2024-01-02T00:00"), 0.0, -2.0, spatial=spatial),
    )
    station = make_station(51.375, -0.375)
    out = run(raw_dir, processed_dir, date(2024, 1, 1), date(2024, 1, 1), station=station)
    _, tmax, tmin, kv = read(out)
    assert tmax[0] == pytest.approx(13.0, abs=1e-9)
    assert tmin[0] == pytest.approx(11.0, abs=1e-9)
    assert kv["method"] == "bilinear"


def test_surrounding_sea_cell_uses_nearest_land_end_to_end(raw_dir, processed_dir):
    # London with (51.25, -0.5) sea -> nearest land (51.5, -0.5): linear_c = 14.0.
    spatial = linear_c(LAT2, LON2)
    write_year_file(
        raw_dir,
        "london",
        2024,
        temps(hours("2024-01-01T00:00", "2024-01-02T00:00"), 0.0, -2.0, spatial=spatial),
    )
    lsm = make_lsm(LONDON_LATS, LONDON_LONS, sea_cells=[(51.25, -0.5)])
    out = run(raw_dir, processed_dir, date(2024, 1, 1), date(2024, 1, 1), lsm=lsm)
    _, tmax, tmin, kv = read(out)
    assert kv["method"] == "nearest_land"
    assert tmax[0] == pytest.approx(14.0, abs=1e-9)
    assert tmin[0] == pytest.approx(12.0, abs=1e-9)


def test_no_land_in_box_raises_naming_city(raw_dir, processed_dir):
    write_year_file(
        raw_dir, "london", 2024, temps(hours("2024-01-01T00:00", "2024-01-02T00:00"), 5.0, 1.0)
    )
    lsm = make_lsm(LONDON_LATS, LONDON_LONS, land=0.0)
    with pytest.raises(DataError) as err:
        run(raw_dir, processed_dir, date(2024, 1, 1), date(2024, 1, 1), lsm=lsm)
    assert "london" in str(err.value)


def test_tmax_is_max_of_interpolated_hourly_end_to_end(raw_dir, processed_dir):
    # Station (51.375, -0.375): bilinear weights 0.25 on (51.25|51.5) x (-0.5|-0.25).
    # mx2t 280 K everywhere; 300 K at (51.25,-0.50) 01:00 and at (51.50,-0.25) 02:00.
    #   interpolated hourly max = 0.25*300 + 0.75*280 = 285 K -> 11.85 C
    #   (interpolated per-cell daily max = 0.5*300 + 0.5*280 = 290 K -> 16.85 C: wrong)
    # mn2t 270 K everywhere; 250 K at (51.25,-0.25) 03:00 and at (51.50,-0.50) 04:00.
    #   interpolated hourly min = 0.25*250 + 0.75*270 = 265 K -> -8.15 C
    #   (interpolated per-cell daily min = 260 K -> -13.15 C: wrong)
    times = hours("2024-01-01T00:00", "2024-01-02T00:00")
    i_lat = {lat: i for i, lat in enumerate(LONDON_LATS)}
    i_lon = {lon: i for i, lon in enumerate(LONDON_LONS)}
    mx = np.full((len(times), 6, 6), 280.0)
    mn = np.full((len(times), 6, 6), 270.0)
    mx[1, i_lat[51.25], i_lon[-0.5]] = 300.0
    mx[2, i_lat[51.5], i_lon[-0.25]] = 300.0
    mn[3, i_lat[51.25], i_lon[-0.25]] = 250.0
    mn[4, i_lat[51.5], i_lon[-0.5]] = 250.0
    ds = make_era5(
        times,
        LONDON_LATS,
        LONDON_LONS,
        mx2t_k=mx,
        mn2t_k=mn,
        dtype="float64",
        time_name="valid_time",
        lat_descending=True,
    )
    write_year_file(raw_dir, "london", 2024, ds)
    station = make_station(51.375, -0.375)
    out = run(raw_dir, processed_dir, date(2024, 1, 1), date(2024, 1, 1), station=station)
    _, tmax, tmin, _ = read(out)
    assert tmax[0] == pytest.approx(11.85, abs=1e-9)
    assert tmin[0] == pytest.approx(-8.15, abs=1e-9)


def test_latitude_north_to_south_gives_same_output_as_south_to_north(tmp_path):
    rng = np.random.default_rng(7)
    times = hours("2024-01-01T00:00", "2024-01-03T00:00")
    mx = rng.uniform(275.0, 290.0, size=(len(times), 6, 6))
    mn = mx - rng.uniform(1.0, 5.0, size=mx.shape)
    outputs = []
    for descending in (False, True):
        raw = tmp_path / f"raw_{descending}"
        processed = tmp_path / f"processed_{descending}"
        raw.mkdir()
        processed.mkdir()
        ds = make_era5(
            times,
            LONDON_LATS,
            LONDON_LONS,
            mx2t_k=mx,
            mn2t_k=mn,
            dtype="float64",
            lat_descending=descending,
        )
        write_year_file(raw, "london", 2024, ds)
        outputs.append(read(run(raw, processed, date(2024, 1, 1), date(2024, 1, 2))))
    (d_a, x_a, n_a, kv_a), (d_b, x_b, n_b, kv_b) = outputs
    assert d_a == d_b
    assert x_a == x_b
    assert n_a == n_b
    assert kv_a["method"] == kv_b["method"] == "bilinear"
    assert kv_a["tmean_3x3_c_mean"] == kv_b["tmean_3x3_c_mean"]


# --------------------------------------------------------------------------- written file


def test_processed_file_schema_compression_and_metadata(tmp_path, raw_dir, processed_dir):
    # London on linear_c: bilinear 14.072 (mx) / 12.072 (mn).
    # Diagnostics: nearest cell (51.5, -0.5) = 14.0 -> tmean = (14 + 12) / 2 = 13.0;
    # 3x3 mean of a linear field = centre value -> 13.0.
    registry = tmp_path / "stations.toml"
    registry.write_text('[london]\nstation = "London Heathrow"\n', encoding="utf-8")
    reg_version = hashlib.sha256(registry.read_bytes()).hexdigest()[:12]

    spatial = linear_c(LAT2, LON2)
    write_year_file(
        raw_dir,
        "london",
        2024,
        temps(hours("2024-01-01T00:00", "2024-01-03T00:00"), 0.0, -2.0, spatial=spatial),
    )
    out = run(raw_dir, processed_dir, date(2024, 1, 1), date(2024, 1, 2), reg=reg_version)
    assert out == processed_dir / "london.parquet"
    assert [p.name for p in processed_dir.iterdir()] == ["london.parquet"]

    schema = pq.read_schema(out)
    assert schema.names == ["date", "tmax_c", "tmin_c"]
    assert schema.field("date").type == pa.date32()
    assert schema.field("tmax_c").type == pa.float64()
    assert schema.field("tmin_c").type == pa.float64()
    meta = pq.ParquetFile(out).metadata
    for rg in range(meta.num_row_groups):
        for col in range(meta.num_columns):
            assert meta.row_group(rg).column(col).compression == "SNAPPY"

    dates, tmax, tmin, kv = read(out)
    assert dates == [date(2024, 1, 1), date(2024, 1, 2)]
    np.testing.assert_allclose(tmax, [14.072, 14.072], atol=1e-9)
    np.testing.assert_allclose(tmin, [12.072, 12.072], atol=1e-9)

    assert set(REQUIRED_METADATA_KEYS) <= set(kv)
    assert kv["source"] == "ERA5 mx2t/mn2t"
    assert kv["method"] == "bilinear"
    assert kv["station"] == "London Heathrow"
    assert float(kv["lat"]) == 51.479
    assert float(kv["lon"]) == -0.449
    assert kv["start"] == "2024-01-01"
    assert kv["end"] == "2024-01-02"
    assert kv["registry_version"] == reg_version
    assert kv["created_utc"].startswith("2026-11-16T12:00")
    assert float(kv["tmean_nearest_c_mean"]) == pytest.approx(13.0, abs=1e-9)
    assert float(kv["tmean_3x3_c_mean"]) == pytest.approx(13.0, abs=1e-9)


# --- pipeline robustness: bad periods and raw files, errors name the city


def era5_file(times, mx_c=5.0, mn_c=1.0, lats=LONDON_LATS, lons=LONDON_LONS):
    return make_era5(
        times,
        lats,
        lons,
        mx2t_k=mx_c + KELVIN,
        mn2t_k=mn_c + KELVIN,
        dtype="float64",
        time_name="valid_time",
        lat_descending=True,
    )


def test_process_start_after_end_is_a_data_error(raw_dir, processed_dir, london):
    with pytest.raises(DataError) as err:
        process(london, date(2025, 1, 1), date(2024, 1, 1), raw_dir, processed_dir, "x", NOW)
    assert "start 2025-01-01 is after end 2024-01-01" in str(err.value)


def test_raw_file_missing_a_variable_names_the_path(raw_dir, london):
    ds = era5_file(hours("2024-01-01T00:00", "2024-01-02T00:00"))[["mx2t"]]
    write_year_file(raw_dir, "london", 2024, ds)
    with pytest.raises(DataError) as err:
        load_hourly(london, date(2024, 1, 1), date(2024, 1, 1), raw_dir)
    assert "era5_london_2024" in str(err.value) and "mn2t" in str(err.value)


def test_raw_file_on_a_different_grid_names_the_path(raw_dir, london):
    # Downloaded before the station moved: box shifted north by one cell.
    shifted = [lat + 0.25 for lat in LONDON_LATS]
    write_year_file(
        raw_dir,
        "london",
        2024,
        era5_file(hours("2024-01-01T00:00", "2024-01-02T00:00"), lats=shifted),
    )
    with pytest.raises(DataError) as err:
        load_hourly(london, date(2024, 1, 1), date(2024, 1, 1), raw_dir)
    assert "era5_london_2024" in str(err.value)


def test_edge_day_file_is_accepted_and_scalar_time_step_file_ignored(
    raw_dir, processed_dir, london
):
    # Amendment B: 2025-01-01 00:00 (12 / -3) comes from the whole-day edge file, as
    # Copernicus delivers it (valid_time, latitude N->S). A leftover old one-step file with
    # its time squeezed to a scalar (99 / -99) is never read.
    write_year_file(
        raw_dir, "london", 2024, era5_file(hours("2024-12-31T00:00", "2024-12-31T23:00"))
    )
    mx_c = np.array([12.0] + [30.0] * 23)[:, None, None]
    mn_c = np.array([-3.0] + [-30.0] * 23)[:, None, None]
    edge = era5_file(hours("2025-01-01T00:00", "2025-01-01T23:00"), mx_c=mx_c, mn_c=mn_c)
    write_edge_day_file(raw_dir, "london", date(2025, 1, 1), edge)
    step = era5_file(hours("2025-01-01T00:00", "2025-01-01T00:00"), mx_c=99.0, mn_c=-99.0)
    write_step_file(raw_dir, "london", 2025, step.isel(valid_time=0))  # time squeezed to scalar
    write_lsm_file(raw_dir, "london", make_lsm(LONDON_LATS, LONDON_LONS))
    out = process(london, date(2024, 12, 31), date(2024, 12, 31), raw_dir, processed_dir, "x", NOW)
    assert pq.read_table(out).column("tmax_c").to_pylist() == pytest.approx([12.0], abs=1e-9)
    assert pq.read_table(out).column("tmin_c").to_pylist() == pytest.approx([-3.0], abs=1e-9)


def test_process_errors_name_the_city(raw_dir, processed_dir, london):
    times = hours("2024-01-01T00:00", "2024-01-02T00:00").delete(13)
    write_year_file(raw_dir, "london", 2024, era5_file(times))
    write_lsm_file(raw_dir, "london", make_lsm(LONDON_LATS, LONDON_LONS))
    with pytest.raises(DataError) as err:
        process(london, date(2024, 1, 1), date(2024, 1, 1), raw_dir, processed_dir, "x", NOW)
    assert "london" in str(err.value) and "2024-01-01T13:00" in str(err.value)


def test_process_error_names_the_city_once(raw_dir, processed_dir):
    station = make_station(city="london")
    with pytest.raises(DataError) as err:
        process(station, date(2024, 1, 1), date(2024, 1, 1), raw_dir, processed_dir, "x", NOW)
    assert str(err.value).count("london") == 1 + str(err.value).count("era5_london")
