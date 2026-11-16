"""Spec 002 review follow-ups (code-reviewer + quant-reviewer findings, user-approved option A).

Each test pins one finding; the docstring of each section names it.
"""

import zipfile
from datetime import UTC, date, datetime

import numpy as np
import pyarrow.parquet as pq
import pytest
import xarray as xr
from synth import (
    KELVIN,
    LONDON_LATS,
    LONDON_LONS,
    FakeCdsClient,
    as_list,
    field_c,
    hours,
    linear_c,
    make_era5,
    make_lsm,
    make_station,
    write_lsm_file,
    write_step_file,
    write_year_file,
)

import dataprep.paths
from dataprep.cli import main
from dataprep.download import area_for, download_lsm, download_year
from dataprep.errors import (
    DataError,
    DataPrepError,
    DownloadError,
    PublishError,
    RegistryError,
)
from dataprep.interpolate import bilinear, nearest_land, to_station
from dataprep.pipeline import load_hourly, process
from dataprep.publish import publish, validate
from dataprep.registry import load_registry

NOW = datetime(2026, 11, 16, 12, 0, tzinfo=UTC)
LAT2, LON2 = np.meshgrid(LONDON_LATS, LONDON_LONS, indexing="ij")


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


# ------------------------------------------------- option A: zip of per-stepType netCDFs


def test_zip_of_split_netcdfs_is_merged_into_one_year_file(raw_dir, london):
    client = FakeCdsClient(mode="zip_split")
    out = download_year(client, london, 2024, raw_dir)
    assert out == raw_dir / "era5_london_2024.nc"
    assert not zipfile.is_zipfile(out)  # stored as merged netCDF, not the zip
    with xr.open_dataset(out) as ds:
        assert {"mx2t", "mn2t"} <= set(ds.data_vars)
    assert [p.name for p in raw_dir.iterdir()] == ["era5_london_2024.nc"]


def test_zipped_land_sea_mask_is_unpacked(raw_dir, london):
    out = download_lsm(FakeCdsClient(mode="zip_split"), london, raw_dir)
    with xr.open_dataset(out) as ds:
        assert "lsm" in ds.data_vars
    assert [p.name for p in raw_dir.iterdir()] == ["era5_lsm_london.nc"]


def test_requests_ask_for_a_zip(raw_dir, london):
    client = FakeCdsClient(mode="zip_split")
    download_year(client, london, 2024, raw_dir)
    download_lsm(client, london, raw_dir)
    assert all(as_list(r["download_format"]) == ["zip"] for r in client.requests)


def test_non_netcdf_download_is_rejected_and_not_kept(raw_dir, london):
    # An HTML error page must not be renamed into place (it would never be re-fetched).
    with pytest.raises(DownloadError) as err:
        download_year(FakeCdsClient(mode="garbage"), london, 2024, raw_dir)
    assert "era5_london_2024" in str(err.value)
    assert list(raw_dir.iterdir()) == []


def test_download_missing_a_variable_is_rejected_naming_it(raw_dir, london):
    with pytest.raises(DownloadError) as err:
        download_year(FakeCdsClient(mode="netcdf_missing_var"), london, 2024, raw_dir)
    assert "mn2t" in str(err.value)
    assert list(raw_dir.iterdir()) == []


def test_locked_target_on_rename_is_a_download_error(raw_dir, london, monkeypatch):
    def locked(src, dst):
        raise PermissionError(32, "The process cannot access the file", str(dst))

    monkeypatch.setattr("dataprep.download.os.replace", locked)
    with pytest.raises(DownloadError) as err:
        download_year(FakeCdsClient(), london, 2024, raw_dir)
    assert "era5_london_2024" in str(err.value)


@pytest.mark.parametrize(
    ("lat", "lon"), [(89.8, 10.0), (-89.9, 10.0), (50.0, 179.8), (50.0, -179.9)]
)
def test_area_near_pole_or_antimeridian_is_rejected(lat, lon):
    with pytest.raises(DataPrepError):
        area_for(make_station(lat, lon, city="edge"))


def test_cli_download_without_cds_credentials_exits_1(tmp_path, monkeypatch, capsys):
    import cdsapi

    def no_config(*args, **kwargs):
        raise Exception("Missing/incomplete configuration file: ~/.cdsapirc")

    monkeypatch.setattr(cdsapi, "Client", no_config)
    registry = tmp_path / "stations.toml"
    registry.write_text(
        '[london]\nstation = "London Heathrow"\nwmo_id = "03772"\nicao = "EGLL"\n'
        "lat = 51.479\nlon = -0.449\n",
        encoding="utf-8",
    )
    code = main(
        [
            "download",
            "--city",
            "london",
            "--start",
            "2024-01-01",
            "--end",
            "2024-01-02",
            "--registry",
            str(registry),
            "--raw-dir",
            str(tmp_path / "raw"),
        ]
    )
    assert code == 1
    assert ".cdsapirc" in capsys.readouterr().err


# ------------------------------------------------- pipeline robustness


def test_process_start_after_end_is_a_data_error(raw_dir, processed_dir, london):
    with pytest.raises(DataError):
        process(london, date(2025, 1, 1), date(2024, 1, 1), raw_dir, processed_dir, "x", NOW)


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


def test_step_file_with_scalar_time_is_accepted(raw_dir, processed_dir, london):
    write_year_file(
        raw_dir, "london", 2024, era5_file(hours("2024-12-31T00:00", "2024-12-31T23:00"))
    )
    step = era5_file(hours("2025-01-01T00:00", "2025-01-01T00:00"), mx_c=12.0, mn_c=-3.0)
    write_step_file(raw_dir, "london", 2025, step.isel(valid_time=0))  # time squeezed to scalar
    write_lsm_file(raw_dir, "london", make_lsm(LONDON_LATS, LONDON_LONS))
    out = process(london, date(2024, 12, 31), date(2024, 12, 31), raw_dir, processed_dir, "x", NOW)
    assert pq.read_table(out).column("tmax_c").to_pylist() == pytest.approx([12.0], abs=1e-9)


def test_process_errors_name_the_city(raw_dir, processed_dir, london):
    times = hours("2024-01-01T00:00", "2024-01-02T00:00").delete(13)
    write_year_file(raw_dir, "london", 2024, era5_file(times))
    write_lsm_file(raw_dir, "london", make_lsm(LONDON_LATS, LONDON_LONS))
    with pytest.raises(DataError) as err:
        process(london, date(2024, 1, 1), date(2024, 1, 1), raw_dir, processed_dir, "x", NOW)
    assert "london" in str(err.value) and "2024-01-01T13:00" in str(err.value)


# ------------------------------------------------- registry, paths, write, publish


def test_missing_registry_file_is_a_registry_error(tmp_path):
    with pytest.raises(RegistryError) as err:
        load_registry(tmp_path / "nope.toml")
    assert "nope.toml" in str(err.value)


def test_cli_missing_registry_exits_1(tmp_path, capsys):
    code = main(
        [
            "process",
            "--city",
            "london",
            "--start",
            "2024-01-01",
            "--end",
            "2024-01-01",
            "--registry",
            str(tmp_path / "nope.toml"),
            "--raw-dir",
            str(tmp_path),
            "--processed-dir",
            str(tmp_path),
        ]
    )
    assert code == 1
    assert "nope.toml" in capsys.readouterr().err


def test_repo_root_outside_a_checkout_is_an_error(tmp_path, monkeypatch):
    fake = tmp_path / "a" / "b" / "c" / "paths.py"
    monkeypatch.setattr(dataprep.paths, "__file__", str(fake))
    with pytest.raises(DataPrepError):
        dataprep.paths.repo_root()


def _valid_processed(path, **meta_overrides):
    from dataprep.daily import DailySeries
    from dataprep.write import write_parquet

    dates = np.arange(np.datetime64("2024-01-01"), np.datetime64("2024-01-04"))
    meta = {
        "source": "ERA5 mx2t/mn2t",
        "method": "bilinear",
        "station": "London Heathrow",
        "lat": "51.479",
        "lon": "-0.449",
        "start": "2024-01-01",
        "end": "2024-01-03",
        "registry_version": "0123456789ab",
        "created_utc": "2026-11-16T12:00:00Z",
        "tmean_nearest_c_mean": "5.0",
        "tmean_3x3_c_mean": "5.0",
    } | meta_overrides
    series = DailySeries(
        dates=dates, tmax_c=np.array([8.0, 9.0, 7.0]), tmin_c=np.array([1.0, 2.0, 0.0])
    )
    return write_parquet(path, series, meta)


@pytest.mark.parametrize(
    "override",
    [
        {"method": "cubic"},
        {"source": "ERA5 t2m"},
        {"registry_version": "not-a-hash"},
        {"registry_version": "0123456789AB"},
    ],
    ids=["method", "source", "registry-version", "registry-version-uppercase"],
)
def test_publish_checks_metadata_values(tmp_path, override):
    path = _valid_processed(tmp_path / "london.parquet", **override)
    with pytest.raises(PublishError) as err:
        validate(path)
    assert next(iter(override)) in str(err.value)


def test_publish_locked_target_is_a_publish_error_and_pricer_unchanged(
    processed_dir, pricer_dir, monkeypatch
):
    _valid_processed(processed_dir / "london.parquet")

    def locked(src, dst):
        raise PermissionError(32, "The process cannot access the file", str(dst))

    monkeypatch.setattr("dataprep.publish.os.replace", locked)
    with pytest.raises(PublishError) as err:
        publish("london", processed_dir, pricer_dir)
    assert "london.parquet" in str(err.value)
    assert list(pricer_dir.iterdir()) == []


def test_write_locked_target_is_a_data_prep_error(tmp_path, monkeypatch):
    def locked(src, dst):
        raise PermissionError(32, "The process cannot access the file", str(dst))

    monkeypatch.setattr("dataprep.write.os.replace", locked)
    with pytest.raises(DataPrepError) as err:
        _valid_processed(tmp_path / "london.parquet")
    assert "london.parquet" in str(err.value)
    assert list(tmp_path.iterdir()) == []


# ------------------------------------------------- quant review: land check and weights

CELL_LATS, CELL_LONS = [51.25, 51.5], [-0.5, -0.25]


def linear_field(lats=LONDON_LATS, lons=LONDON_LONS):
    lat2, lon2 = np.meshgrid(lats, lons, indexing="ij")
    return field_c(linear_c(lat2, lon2), lats, lons)


@pytest.mark.parametrize("corner", [(lat, lon) for lat in CELL_LATS for lon in CELL_LONS])
def test_any_of_the_four_surrounding_cells_sea_switches_to_nearest_land(corner):
    lsm = make_lsm(LONDON_LATS, LONDON_LONS, sea_cells=[corner])
    _, method = to_station(linear_field(), lsm, make_station())
    assert method == "nearest_land"


@pytest.mark.parametrize("outside", [(51.0, -0.5), (51.75, -0.25), (51.5, -0.75), (51.25, 0.0)])
def test_sea_cell_just_outside_the_block_keeps_bilinear(outside):
    lsm = make_lsm(LONDON_LATS, LONDON_LONS, sea_cells=[outside])
    _, method = to_station(linear_field(), lsm, make_station())
    assert method == "bilinear"


def test_station_on_a_grid_line_ignores_zero_weight_sea_cells():
    # Station (51.5, -0.4) lies on the 51.5 line; row 51.75 has zero weight.
    # linear_c(51.5, -0.4) = 10 + 16*0.25 + 8*0.1 = 14.8 (nearest land would give 14.0).
    lsm = make_lsm(LONDON_LATS, LONDON_LONS, sea_cells=[(51.75, -0.5)])
    series, method = to_station(linear_field(), lsm, make_station(51.5, -0.4, city="line"))
    assert method == "bilinear"
    assert series.values[0] == pytest.approx(14.8, abs=1e-9)


def test_station_on_a_grid_node_ignores_zero_weight_sea_cells():
    lsm = make_lsm(LONDON_LATS, LONDON_LONS, sea_cells=[(51.75, -0.25)])
    series, method = to_station(linear_field(), lsm, make_station(51.5, -0.5, city="node"))
    assert method == "bilinear"
    assert series.values[0] == pytest.approx(14.0, abs=1e-9)


def test_bilinear_cross_term_at_heathrow():
    # Only the upper-right corner (51.5, -0.25) is 1: value = wy * wx = 0.916 * 0.204.
    values = np.zeros((2, 2))
    values[1, 1] = 1.0
    out = bilinear(field_c(values, CELL_LATS, CELL_LONS), 51.479, -0.449)
    assert out.values[0] == pytest.approx(0.916 * 0.204, abs=1e-12)


def test_nearest_land_scales_by_cos_of_station_latitude_not_cell_latitude():
    # Station (60.0, 0.0). Land only at A (59.75, 0.25) and B (60.25, 0.25).
    # cos(station lat): both sqrt(0.25^2 + (0.25*cos60)^2) -> exact tie -> lower lat A.
    # cos(cell lat) would give B (cos 60.25 < cos 59.75 makes B's dlon shorter).
    lats, lons = [59.75, 60.0, 60.25], [-0.25, 0.0, 0.25]
    lat2, lon2 = np.meshgrid(lats, lons, indexing="ij")
    field = field_c(100.0 * lat2 + lon2, lats, lons)
    lsm = make_lsm(lats, lons, land=0.0, land_cells=[(59.75, 0.25), (60.25, 0.25)])
    out = nearest_land(field, lsm, 60.0, 0.0, city="north")
    assert out.values[0] == pytest.approx(100.0 * 59.75 + 0.25)


def test_interpolation_does_not_depend_on_dimension_order():
    field = linear_field().transpose("latitude", "longitude", "time")
    lsm = make_lsm(LONDON_LATS, LONDON_LONS)
    series, _ = to_station(field, lsm, make_station())
    assert series.values[0] == pytest.approx(14.072, abs=1e-9)
