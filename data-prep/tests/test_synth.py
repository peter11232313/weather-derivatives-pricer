"""Self-tests for the test factories and the fake CDS client (these must pass now)."""

import zipfile

import numpy as np
import pytest
import xarray as xr
from synth import (
    LONDON_LATS,
    LONDON_LONS,
    FakeCdsClient,
    hours,
    linear_c,
    lsm_raw_dataset,
    make_era5,
    make_lsm,
)


def test_make_era5_has_requested_shape_attrs_and_float32_storage():
    times = hours("2024-01-01T00:00", "2024-01-01T03:00")
    ds = make_era5(times, [51.0, 51.25, 51.5], [-0.5, -0.25])
    assert ds["mx2t"].dims == ("time", "latitude", "longitude")
    assert ds["mx2t"].shape == (4, 3, 2)
    assert ds["mx2t"].attrs["units"] == "K"
    assert ds["mn2t"].attrs["units"] == "K"
    assert ds["mx2t"].dtype == np.float32
    assert list(ds["latitude"].values) == [51.0, 51.25, 51.5]


def test_make_era5_options_valid_time_descending_number_expver():
    times = hours("2024-01-01T00:00", "2024-01-01T01:00")
    data = np.arange(2 * 2 * 3 * 2, dtype=float).reshape(2, 2, 3, 2) + 250.0
    ds = make_era5(
        times,
        [51.0, 51.25, 51.5],
        [-0.5, -0.25],
        mx2t_k=data,
        time_name="valid_time",
        lat_descending=True,
        expver=["0001", "0005"],
        number=True,
        dtype="float64",
    )
    assert ds["mx2t"].dims == ("expver", "valid_time", "latitude", "longitude")
    assert list(ds["latitude"].values) == [51.5, 51.25, 51.0]
    assert "number" in ds.coords
    assert list(ds["expver"].values) == ["0001", "0005"]
    # Data follows its coordinate: the value at lat 51.0 is unchanged by the flip.
    assert ds["mx2t"].sel(latitude=51.0).values[0, 0, 0] == data[0, 0, 0, 0]


def test_make_lsm_marks_sea_and_land_cells():
    lsm = make_lsm(LONDON_LATS, LONDON_LONS, sea_cells=[(51.25, -0.5)])
    assert lsm.sel(latitude=51.25, longitude=-0.5).item() == 0.0
    assert lsm.sel(latitude=51.5, longitude=-0.5).item() == 1.0
    raw = lsm_raw_dataset(lsm)
    assert raw["lsm"].dims == ("valid_time", "latitude", "longitude")
    assert raw["latitude"].values[0] > raw["latitude"].values[-1]


def test_linear_field_reproduces_spec_worked_example_corners():
    assert linear_c(51.25, -0.5) == 10.0
    assert linear_c(51.25, -0.25) == 12.0
    assert linear_c(51.5, -0.5) == 14.0
    assert linear_c(51.5, -0.25) == 16.0


def test_fake_cds_writes_readable_netcdf_and_records_request(tmp_path):
    client = FakeCdsClient()
    target = tmp_path / "out.nc.tmp"
    client.retrieve("reanalysis-era5-single-levels", {"variable": ["mx2t"]}, str(target))
    with xr.open_dataset(target) as ds:
        assert {"mx2t", "mn2t"} <= set(ds.data_vars)
        assert "valid_time" in ds.dims
    assert client.calls[0][0] == "reanalysis-era5-single-levels"
    assert client.requests == [{"variable": ["mx2t"]}]


def test_fake_cds_writes_lsm_for_lsm_request(tmp_path):
    client = FakeCdsClient()
    target = tmp_path / "lsm.nc"
    client.retrieve("x", {"variable": ["land_sea_mask"]}, target)
    with xr.open_dataset(target) as ds:
        assert "lsm" in ds.data_vars


def test_fake_cds_raise_and_zip_modes(tmp_path):
    with pytest.raises(RuntimeError):
        FakeCdsClient(mode="raise").retrieve("x", {}, tmp_path / "a")
    assert not (tmp_path / "a").exists()
    with pytest.raises(RuntimeError):
        FakeCdsClient(mode="partial_then_raise").retrieve("x", {}, tmp_path / "b")
    assert (tmp_path / "b").exists()
    FakeCdsClient(mode="zip").retrieve("x", {}, tmp_path / "c")
    assert (tmp_path / "c").read_bytes().startswith(b"PK\x03\x04")
    assert zipfile.is_zipfile(tmp_path / "c")


# ------------------------------------------- multi-year fake (spec 002 amendment A)

YEAR_REQUEST = {
    "variable": ["mx2t", "mn2t"],
    "month": [f"{m:02d}" for m in range(1, 13)],
    "day": [f"{d:02d}" for d in range(1, 32)],
    "time": [f"{h:02d}:00" for h in range(24)],
    "area": [52.0, -1.0, 50.75, 0.25],
}


def test_fake_cds_serves_every_hour_of_every_requested_year_cds_like(tmp_path):
    client = FakeCdsClient()
    target = tmp_path / "out.nc"
    client.retrieve("x", {**YEAR_REQUEST, "year": ["1996", "1997", "1998"]}, target)
    (served,) = client.served
    assert served.sizes["valid_time"] == 8784 + 8760 + 8760
    assert served["valid_time"].values[0] == np.datetime64("1996-01-01T00:00")
    assert served["valid_time"].values[-1] == np.datetime64("1998-12-31T23:00")
    assert served["latitude"].values[0] > served["latitude"].values[-1]  # N -> S
    assert served["number"].ndim == 0
    assert served["expver"].dims == ("valid_time",)
    assert served["mx2t"].dtype == np.float32
    assert served["mx2t"].attrs["units"] == "K"
    assert bool((served["mn2t"] < served["mx2t"]).all())
    with xr.open_dataset(target) as ds:
        assert ds.sizes["valid_time"] == served.sizes["valid_time"]


def test_fake_cds_same_hour_is_served_identically_whatever_the_request(tmp_path):
    client = FakeCdsClient()
    client.retrieve("x", {**YEAR_REQUEST, "year": ["1996", "1997"]}, tmp_path / "a.nc")
    client.retrieve("x", {**YEAR_REQUEST, "year": ["1997"]}, tmp_path / "b.nc")
    both, alone = client.served
    xr.testing.assert_identical(both.sel(valid_time="1997"), alone)


def test_fake_cds_single_step_request_serves_one_step(tmp_path):
    client = FakeCdsClient()
    request = {**YEAR_REQUEST, "year": ["2025"], "month": ["01"], "day": ["01"], "time": ["00:00"]}
    client.retrieve("x", request, tmp_path / "s.nc")
    assert list(client.served[0]["valid_time"].values) == [np.datetime64("2025-01-01T00:00")]


def test_fake_cds_missing_and_pad_hours(tmp_path):
    client = FakeCdsClient(missing_hours=["1994-07-01T12:00"], pad_hours=2)
    client.retrieve("x", {**YEAR_REQUEST, "year": ["1994"]}, tmp_path / "a.nc")
    times = client.served[0]["valid_time"].values
    assert len(times) == 8760 - 1 + 4
    assert np.datetime64("1994-07-01T12:00") not in times
    assert times[0] == np.datetime64("1993-12-31T22:00")
    assert times[-1] == np.datetime64("1995-01-01T01:00")


def test_fake_cds_fail_on_calls_counts_every_call(tmp_path):
    client = FakeCdsClient(fail_on_calls=[2])
    client.retrieve("x", {"variable": ["land_sea_mask"]}, tmp_path / "1.nc")
    with pytest.raises(RuntimeError):
        client.retrieve("x", {**YEAR_REQUEST, "year": ["1994"]}, tmp_path / "2.nc")
    assert (tmp_path / "2.nc").exists()  # partial bytes, like a broken transfer
    assert client.served[1] is None
    client.retrieve("x", {**YEAR_REQUEST, "year": ["1994"]}, tmp_path / "3.nc")
    assert len(client.calls) == 3


def test_fake_cds_grid_from_area_serves_the_station_box(tmp_path):
    client = FakeCdsClient(grid_from_area=True)
    client.retrieve("x", {**YEAR_REQUEST, "year": ["1994"]}, tmp_path / "a.nc")
    client.retrieve("x", {**YEAR_REQUEST, "variable": ["land_sea_mask"]}, tmp_path / "b.nc")
    for served in client.served:
        assert sorted(served["latitude"].values) == LONDON_LATS
        assert sorted(served["longitude"].values) == LONDON_LONS


def test_fake_cds_dataset_override_still_wins(tmp_path):
    ds = make_era5(hours("2024-01-01T00:00", "2024-01-01T02:00"), [51.0], [0.0])
    client = FakeCdsClient(dataset=ds)
    client.retrieve("x", {**YEAR_REQUEST, "year": ["1994"]}, tmp_path / "a.nc")
    assert client.served[0] is ds
