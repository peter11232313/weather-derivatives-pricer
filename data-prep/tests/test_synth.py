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
