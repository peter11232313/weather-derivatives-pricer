"""Code-review follow-ups for spec 002 amendment A (multi-year downloads)."""

import numpy as np
import pandas as pd
import pytest
import xarray as xr
from synth import FakeCdsClient

from dataprep.download import _split_year, _write_atomic, download_years
from dataprep.errors import DownloadError
from dataprep.load import open_era5


def hourly(start: str, stop_exclusive: str) -> pd.DatetimeIndex:
    return pd.date_range(start, stop_exclusive, freq="h", inclusive="left").as_unit("ns")


def era5_like(times: pd.DatetimeIndex) -> xr.Dataset:
    """Minimal CDS-like dataset: valid_time x 2 x 2 grid, mx2t in K."""
    values = 280.0 + (np.arange(len(times)) % 24)[:, None, None] * np.ones((1, 2, 2))
    return xr.Dataset(
        {"mx2t": (("valid_time", "latitude", "longitude"), values.astype(np.float32))},
        coords={"valid_time": times, "latitude": [52.0, 51.75], "longitude": [-0.5, -0.25]},
    )


def test_split_year_complete_but_unordered_hours_is_complete_and_sorted():
    times = hourly("1994-01-01", "1995-01-01")
    ds = era5_like(times).isel(valid_time=slice(None, None, -1))  # reversed
    year_ds, problem = _split_year(ds, 1994)
    assert problem is None
    assert (pd.DatetimeIndex(year_ds["valid_time"].values) == times).all()


def test_split_year_repeated_hour_is_reported_as_repeated():
    times = hourly("1994-01-01", "1995-01-01")
    repeated = times.insert(100, times[99])  # every hour present, one twice
    _, problem = _split_year(era5_like(repeated), 1994)
    assert problem is not None
    assert "repeated" in problem
    assert "1994-01-05 03:00" in problem


def test_split_year_missing_hour_is_reported_as_missing():
    times = hourly("1994-01-01", "1995-01-01").delete(1000)
    _, problem = _split_year(era5_like(times), 1994)
    assert problem is not None
    assert "missing" in problem
    assert "1994-02-11 16:00" in problem


def test_packed_response_keeps_packing_when_a_year_is_rewritten(tmp_path):
    # Decision 5: int16 with scale/offset/_FillValue and integer time units survive.
    source = tmp_path / "chunk.nc"
    era5_like(hourly("1994-01-01", "1996-01-01")).to_netcdf(
        source,
        encoding={
            "mx2t": {
                "dtype": "int16",
                "scale_factor": 0.01,
                "add_offset": 280.0,
                "_FillValue": -32767,
                "zlib": True,
                "chunksizes": (17520, 2, 2),
            },
            "valid_time": {"units": "hours since 1900-01-01", "dtype": "int32"},
        },
    )
    year_ds, problem = _split_year(open_era5(source), 1995)
    assert problem is None
    target = tmp_path / "era5_test_1995.nc"
    _write_atomic(year_ds, target)

    with xr.open_dataset(target, mask_and_scale=False, decode_times=False) as raw:
        assert raw["mx2t"].dtype == np.int16
        assert raw["mx2t"].attrs["scale_factor"] == pytest.approx(0.01)
        assert raw["mx2t"].attrs["add_offset"] == pytest.approx(280.0)
        assert raw["mx2t"].attrs["_FillValue"] == -32767
        assert raw["valid_time"].attrs["units"] == "hours since 1900-01-01"
        assert raw["valid_time"].dtype == np.int32
    xr.testing.assert_equal(open_era5(target)["mx2t"], year_ds["mx2t"])


def test_failed_request_after_incomplete_year_still_names_the_incomplete_year(raw_dir, london):
    client = FakeCdsClient(missing_hours=["1994-03-01T05:00"], fail_on_calls={2})
    with pytest.raises(DownloadError) as err:
        download_years(client, london, range(1993, 1999), raw_dir, years_per_request=3)
    message = str(err.value)
    assert "1994" in message  # incomplete year from chunk 1 is not hidden
    assert "1996" in message  # the failed chunk


def test_failed_multi_year_request_suggests_one_year_per_request(raw_dir, london):
    client = FakeCdsClient(fail_on_calls={1})
    with pytest.raises(DownloadError) as err:
        download_years(client, london, [1993, 1994, 1995], raw_dir, years_per_request=3)
    assert "--years-per-request 1" in str(err.value)


def test_failed_single_year_request_has_no_chunk_size_hint(raw_dir, london):
    client = FakeCdsClient(fail_on_calls={1})
    with pytest.raises(DownloadError) as err:
        download_years(client, london, [1993], raw_dir, years_per_request=1)
    assert "--years-per-request" not in str(err.value)
