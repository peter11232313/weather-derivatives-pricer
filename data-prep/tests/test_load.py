"""Spec 002 ACs on load/normalise: kelvin, units, expver, latitude order, valid_time."""

import numpy as np
import pytest
import xarray as xr
from synth import hours, lsm_raw_dataset, make_era5, make_lsm, write_nc

from dataprep.errors import DataError
from dataprep.load import KELVIN_OFFSET, normalise, open_era5

LATS = [51.0, 51.25, 51.5]
LONS = [-0.5, -0.25]
TIMES = hours("2024-01-01T00:00", "2024-01-01T04:00")


def test_float64_273_15_kelvin_is_exactly_zero_celsius():
    ds = make_era5(TIMES, LATS, LONS, mx2t_k=273.15, mn2t_k=273.15, dtype="float64")
    out = normalise(ds)
    assert out["mx2t"].dtype == np.float64
    assert out["mn2t"].dtype == np.float64
    assert (out["mx2t"].values == 0.0).all()
    assert (out["mn2t"].values == 0.0).all()
    assert out["mx2t"].attrs["units"] == "degC"
    assert out["mn2t"].attrs["units"] == "degC"


def test_float32_input_is_cast_to_float64_before_conversion():
    # float32(273.15) == 273.149993896484375; cast first, then subtract the float64 offset:
    # expected = 273.149993896484375 - 273.15 = -6.103515602805...e-06 (not float32 arithmetic).
    ds = make_era5(TIMES, LATS, LONS, mx2t_k=273.15, mn2t_k=273.15, dtype="float32")
    out = normalise(ds)
    expected = np.float64(np.float32(273.15)) - KELVIN_OFFSET
    assert out["mx2t"].dtype == np.float64
    assert (out["mx2t"].values == expected).all()


def test_kelvin_converted_on_load_from_file_once(tmp_path):
    # open_era5 + normalise is the load path; 283.15 K -> 10.0 C (to fp tolerance), not -263.
    path = write_nc(
        make_era5(TIMES, LATS, LONS, mx2t_k=283.15, mn2t_k=273.15, dtype="float64"),
        tmp_path / "era5_test_2024.nc",
    )
    out = normalise(open_era5(path))
    np.testing.assert_allclose(out["mx2t"].values, 10.0, atol=1e-9)
    assert (out["mn2t"].values == 0.0).all()


def test_normalising_twice_is_rejected_because_units_are_no_longer_kelvin():
    out = normalise(make_era5(TIMES, LATS, LONS, dtype="float64"))
    with pytest.raises(DataError):
        normalise(out)


@pytest.mark.parametrize("units", ["degC", "C", "°C", "kelvin", "F"])
def test_temperature_units_other_than_k_are_rejected(units):
    ds = make_era5(TIMES, LATS, LONS, units=units)
    with pytest.raises(DataError):
        normalise(ds)


def test_lsm_is_left_unitless_and_unconverted():
    temps = make_era5(TIMES, LATS, LONS, dtype="float64")
    lsm = make_lsm(LATS, LONS, land=0.7)
    lsm.attrs["units"] = "(0 - 1)"
    ds = temps.assign(lsm=lsm)
    out = normalise(ds)
    np.testing.assert_allclose(out["lsm"].values, 0.7)


def test_valid_time_is_renamed_to_time():
    ds = make_era5(TIMES, LATS, LONS, time_name="valid_time")
    out = normalise(ds)
    assert "time" in out.dims
    assert "valid_time" not in out.dims and "valid_time" not in out.coords
    np.testing.assert_array_equal(out["time"].values, TIMES.values)


def test_number_coordinate_is_dropped():
    out = normalise(make_era5(TIMES, LATS, LONS, number=True))
    assert "number" not in out.coords and "number" not in out.dims


def test_latitude_north_to_south_is_sorted_ascending_with_data():
    data = np.arange(len(TIMES) * 3 * 2, dtype=float).reshape(len(TIMES), 3, 2) + 260.0
    ds = make_era5(TIMES, LATS, LONS, mx2t_k=data, mn2t_k=data - 5, lat_descending=True)
    out = normalise(ds)
    assert list(out["latitude"].values) == LATS
    np.testing.assert_allclose(out["mx2t"].values, data - 273.15, atol=1e-4)


def test_north_to_south_and_south_to_north_normalise_identically():
    data = np.random.default_rng(42).uniform(260, 300, size=(len(TIMES), 3, 2))
    a = normalise(make_era5(TIMES, LATS, LONS, mx2t_k=data, mn2t_k=data - 3, dtype="float64"))
    b = normalise(
        make_era5(
            TIMES, LATS, LONS, mx2t_k=data, mn2t_k=data - 3, dtype="float64", lat_descending=True
        )
    )
    xr.testing.assert_equal(a["mx2t"], b["mx2t"])
    xr.testing.assert_equal(a["mn2t"], b["mn2t"])


def test_longitude_0_360_is_mapped_to_minus_180_180():
    lons = [359.5, 359.75, 0.0, 0.25]
    data = np.broadcast_to(np.array([260.0, 270.0, 280.0, 290.0]), (len(TIMES), 3, 4))
    ds = make_era5(TIMES, LATS, lons, mx2t_k=data, mn2t_k=data - 1, dtype="float64")
    out = normalise(ds)
    assert set(out["longitude"].values) == {-0.5, -0.25, 0.0, 0.25}
    assert ((out["longitude"].values >= -180) & (out["longitude"].values < 180)).all()
    np.testing.assert_allclose(out["mx2t"].sel(longitude=-0.5).values, 260.0 - 273.15)
    np.testing.assert_allclose(out["mx2t"].sel(longitude=0.25).values, 290.0 - 273.15)


@pytest.mark.parametrize("expver", [[1, 5], ["0001", "0005"]], ids=["int", "str"])
def test_overlapping_expver_keeps_era5_and_fills_from_era5t(expver):
    # Steps 0-2: both ERA5 (1) and ERA5T (5) present and different -> ERA5 kept.
    # Steps 3-4: ERA5 missing (NaN), only ERA5T present -> ERA5T used.
    nt = len(TIMES)
    era5 = np.full((nt, 3, 2), 280.0)
    era5[3:] = np.nan
    era5t = np.full((nt, 3, 2), 290.0)
    mx = np.stack([era5, era5t])
    ds = make_era5(TIMES, LATS, LONS, mx2t_k=mx, mn2t_k=mx - 10, expver=expver, dtype="float64")
    out = normalise(ds)
    assert "expver" not in out.dims
    assert out["mx2t"].dims == ("time", "latitude", "longitude")
    np.testing.assert_allclose(out["mx2t"].values[:3], 280.0 - 273.15)
    np.testing.assert_allclose(out["mx2t"].values[3:], 290.0 - 273.15)
    np.testing.assert_allclose(out["mn2t"].values[:3], 270.0 - 273.15)
    np.testing.assert_allclose(out["mn2t"].values[3:], 280.0 - 273.15)


def test_open_era5_reads_a_netcdf_file(tmp_path):
    path = write_nc(make_era5(TIMES, LATS, LONS, time_name="valid_time"), tmp_path / "a.nc")
    ds = open_era5(path)
    assert {"mx2t", "mn2t"} <= set(ds.data_vars)


def test_open_era5_unreadable_file_raises_data_error_naming_path(tmp_path):
    path = tmp_path / "era5_broken_2024.nc"
    path.write_bytes(b"this is not netcdf")
    with pytest.raises(DataError) as err:
        open_era5(path)
    assert "era5_broken_2024.nc" in str(err.value)


def test_open_era5_reads_lsm_file(tmp_path):
    path = write_nc(lsm_raw_dataset(make_lsm(LATS, LONS)), tmp_path / "era5_lsm_x.nc")
    ds = open_era5(path)
    assert "lsm" in ds.data_vars
