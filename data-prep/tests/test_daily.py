"""Spec 002 / ADR 0002 ACs: D 01:00..D+1 00:00 window, row counts, missing steps, NaN, tmin>tmax."""

from datetime import date

import numpy as np
import pandas as pd
import pytest
from synth import hours, series_c

from dataprep.daily import DailySeries, daily_extremes
from dataprep.errors import DataError


def window(start: str, end: str) -> pd.DatetimeIndex:
    """All steps needed for start..end: start 01:00 .. end+1 00:00."""
    last = pd.Timestamp(end) + pd.Timedelta(days=1)
    return hours(f"{start}T01:00", last.isoformat())


def flat(times, mx=5.0, mn=1.0):
    return series_c([mx] * len(times), times), series_c([mn] * len(times), times)


def test_spec_hour_alignment_example():
    # Spec worked example (degrees C at the station):
    #   2023-12-31 01:00..23:00   mx 8.0  mn 6.0   (filler for the previous day)
    #   2024-01-01 00:00          mx 9.0  mn 4.0   -> belongs to 2023-12-31
    #   2024-01-01 01:00..23:00   mx 5.0  mn 1.0
    #   2024-01-02 00:00          mx 12.0 mn -3.0  -> belongs to 2024-01-01
    # => 2023-12-31: tmax 9.0, tmin 4.0; 2024-01-01: tmax 12.0, tmin -3.0
    #    (a 00:00-23:00 window would give 2024-01-01: 9.0 / 1.0)
    times = hours("2023-12-31T01:00", "2024-01-02T00:00")
    mx = [8.0] * 23 + [9.0] + [5.0] * 23 + [12.0]
    mn = [6.0] * 23 + [4.0] + [1.0] * 23 + [-3.0]
    out = daily_extremes(
        series_c(mx, times), series_c(mn, times), date(2023, 12, 31), date(2024, 1, 1)
    )
    assert isinstance(out, DailySeries)
    np.testing.assert_array_equal(
        out.dates, np.array(["2023-12-31", "2024-01-01"], dtype="datetime64[D]")
    )
    assert out.tmax_c.tolist() == [9.0, 12.0]
    assert out.tmin_c.tolist() == [4.0, -3.0]
    assert out.tmax_c.dtype == np.float64 and out.tmin_c.dtype == np.float64


def test_single_day_uses_only_its_own_window():
    # Extra steps outside the window (start 00:00 and end+1 01:00) carry decoy extremes and
    # must be ignored. 2024-01-01: tmax 5.0, tmin 1.0.
    times = hours("2024-01-01T00:00", "2024-01-02T01:00")
    mx = [50.0] + [5.0] * 24 + [60.0]
    mn = [-50.0] + [1.0] * 24 + [-60.0]
    out = daily_extremes(
        series_c(mx, times), series_c(mn, times), date(2024, 1, 1), date(2024, 1, 1)
    )
    assert out.tmax_c.tolist() == [5.0]
    assert out.tmin_c.tolist() == [1.0]


def test_nan_outside_the_window_is_not_an_error():
    # 2024-01-01 00:00 belongs to 2023-12-31, which is not requested.
    times = hours("2024-01-01T00:00", "2024-01-02T00:00")
    mx = [np.nan] + [5.0] * 24
    mn = [np.nan] + [1.0] * 24
    out = daily_extremes(
        series_c(mx, times), series_c(mn, times), date(2024, 1, 1), date(2024, 1, 1)
    )
    assert out.tmax_c.tolist() == [5.0]


@pytest.mark.parametrize(
    ("year", "n_days"),
    # 2000 is a leap year (divisible by 400); 1900 is not (century rule).
    [(2024, 366), (2023, 365), (2000, 366), (1900, 365), (1991, 365)],
)
def test_full_year_row_count_sorted_without_gaps(year, n_days):
    times = window(f"{year}-01-01", f"{year}-12-31")
    mx, mn = flat(times)
    out = daily_extremes(mx, mn, date(year, 1, 1), date(year, 12, 31))
    expected = np.arange(
        np.datetime64(f"{year}-01-01"), np.datetime64(f"{year + 1}-01-01"), dtype="datetime64[D]"
    )
    assert len(out.dates) == n_days
    assert len(out.tmax_c) == n_days and len(out.tmin_c) == n_days
    np.testing.assert_array_equal(out.dates, expected)


def test_leap_day_is_present():
    times = window("2024-02-28", "2024-03-01")
    mx, mn = flat(times)
    out = daily_extremes(mx, mn, date(2024, 2, 28), date(2024, 3, 1))
    np.testing.assert_array_equal(
        out.dates, np.array(["2024-02-28", "2024-02-29", "2024-03-01"], dtype="datetime64[D]")
    )


def test_missing_interior_step_raises_naming_timestamp():
    times = window("2024-01-01", "2024-01-02").delete(12)  # drops 2024-01-01 13:00
    mx, mn = flat(times)
    with pytest.raises(DataError) as err:
        daily_extremes(mx, mn, date(2024, 1, 1), date(2024, 1, 2))
    assert "2024-01-01T13:00" in str(err.value)


def test_missing_next_day_midnight_raises_naming_timestamp():
    times = hours("2024-01-01T01:00", "2024-01-01T23:00")  # no 2024-01-02 00:00
    mx, mn = flat(times)
    with pytest.raises(DataError) as err:
        daily_extremes(mx, mn, date(2024, 1, 1), date(2024, 1, 1))
    assert "2024-01-02T00:00" in str(err.value)


def test_missing_step_only_in_mn2t_raises_naming_timestamp():
    times = window("2024-01-01", "2024-01-01")
    mx = series_c([5.0] * len(times), times)
    short = times.delete(4)  # 2024-01-01 05:00
    mn = series_c([1.0] * len(short), short)
    with pytest.raises(DataError) as err:
        daily_extremes(mx, mn, date(2024, 1, 1), date(2024, 1, 1))
    assert "2024-01-01T05:00" in str(err.value)


@pytest.mark.parametrize("variable", ["mx2t", "mn2t"])
def test_nan_in_hourly_series_raises_naming_date(variable):
    times = window("2024-03-09", "2024-03-11")
    mx_vals = np.full(len(times), 5.0)
    mn_vals = np.full(len(times), 1.0)
    # 2024-03-10 14:00 -> index of that step in the window
    idx = times.get_loc(pd.Timestamp("2024-03-10T14:00"))
    (mx_vals if variable == "mx2t" else mn_vals)[idx] = np.nan
    with pytest.raises(DataError) as err:
        daily_extremes(
            series_c(mx_vals, times),
            series_c(mn_vals, times),
            date(2024, 3, 9),
            date(2024, 3, 11),
        )
    assert "2024-03-10" in str(err.value)


def test_daily_tmin_above_tmax_raises_naming_date():
    # 2024-01-02: mx2t 1.0 all day, mn2t 3.0 all day -> tmin 3.0 > tmax 1.0.
    times = window("2024-01-01", "2024-01-03")
    day2 = (times > pd.Timestamp("2024-01-02T00:00")) & (times <= pd.Timestamp("2024-01-03T00:00"))
    mx = np.where(day2, 1.0, 5.0)
    mn = np.where(day2, 3.0, 1.0)
    with pytest.raises(DataError) as err:
        daily_extremes(series_c(mx, times), series_c(mn, times), date(2024, 1, 1), date(2024, 1, 3))
    assert "2024-01-02" in str(err.value)


def test_tmin_equal_to_tmax_is_allowed():
    times = window("2024-01-01", "2024-01-01")
    mx, mn = flat(times, mx=2.0, mn=2.0)
    out = daily_extremes(mx, mn, date(2024, 1, 1), date(2024, 1, 1))
    assert out.tmax_c.tolist() == [2.0] and out.tmin_c.tolist() == [2.0]
