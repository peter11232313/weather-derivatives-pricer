"""Spec 002 amendment B decision 6: Tmin > Tmax with touching windows -> midpoint.

London's windows touch at D 09:00 (9 / -15). ERA5's 08-09 minimum and 09-10 maximum do
not share the 09:00 instant, so a day cooling steadily through 09:00 can have Tmin above
Tmax by a few hundredths of a degree (real case: 1991-11-08). Both are then set to their
midpoint and counted in metadata; with overlapping windows it stays an error.
"""

from datetime import date

import pytest
from synth import hours, make_station, series_c, write_year_file
from test_observation_windows import era5, read, run, values

from dataprep.daily import daily_extremes
from dataprep.errors import DataError
from dataprep.publish import validate

DAY = date(1991, 11, 8)
# Tmin window of 1991-11-08: stamps 11-07 10:00..11-08 09:00; Tmax window: 11-08 10:00..
# 11-09 09:00. Cooling all day: mild before 09:00, colder after 10:00.
FIRST, LAST = "1991-11-07T10:00", "1991-11-09T09:00"
MX_AT = {"1991-11-08T09:00": 11.384, "1991-11-08T10:00": 11.273}
MN_AT = {"1991-11-08T09:00": 11.302, "1991-11-08T10:00": 10.893}
TMIN_C, TMAX_C = 11.302, 11.273
MIDPOINT_C = (TMIN_C + TMAX_C) / 2  # 11.2875


def cooling_values(times, before, after, at):
    """``before`` up to 11-08 09:00, ``after`` from 11-08 10:00, with ``at`` overrides."""
    out = values(times, before, at)
    out[times >= "1991-11-08T10:00"] = after
    for stamp, value in at.items():
        out[times.get_loc(stamp)] = value
    return out


def cooling_series():
    times = hours(FIRST, LAST)
    mx = cooling_values(times, 12.5, 10.5, MX_AT)
    mn = cooling_values(times, 12.0, 10.0, MN_AT)
    return times, mx, mn


@pytest.fixture
def london_9_15():
    return make_station(tmax_window_start_utc_h=9, tmin_window_start_utc_h=-15)


def test_touching_windows_tmin_above_tmax_is_set_to_the_midpoint():
    times, mx, mn = cooling_series()
    daily = daily_extremes(
        series_c(mx, times),
        series_c(mn, times),
        DAY,
        DAY,
        tmax_window_start_utc_h=9,
        tmin_window_start_utc_h=-15,
    )
    assert daily.tmax_c[0] == pytest.approx(MIDPOINT_C, abs=1e-12)
    assert daily.tmin_c[0] == pytest.approx(MIDPOINT_C, abs=1e-12)
    assert daily.tmin_tmax_adjusted_days == 1


def test_midpoint_leaves_hdd_unchanged():
    times, mx, mn = cooling_series()
    daily = daily_extremes(
        series_c(mx, times),
        series_c(mn, times),
        DAY,
        DAY,
        tmax_window_start_utc_h=9,
        tmin_window_start_utc_h=-15,
    )
    hdd = max(0.0, 18.0 - (daily.tmax_c[0] + daily.tmin_c[0]) / 2)
    assert hdd == pytest.approx(max(0.0, 18.0 - (TMAX_C + TMIN_C) / 2), abs=1e-12)


def test_ordinary_days_are_not_adjusted_and_count_is_zero():
    times = hours(FIRST, LAST)
    daily = daily_extremes(
        series_c(values(times, 12.0), times),
        series_c(values(times, 4.0), times),
        DAY,
        DAY,
        tmax_window_start_utc_h=9,
        tmin_window_start_utc_h=-15,
    )
    assert (daily.tmax_c[0], daily.tmin_c[0]) == (12.0, 4.0)
    assert daily.tmin_tmax_adjusted_days == 0


@pytest.mark.parametrize(("tmax_h", "tmin_h"), [(0, 0), (6, -6), (9, -14), (-15, 8)])
def test_overlapping_windows_tmin_above_tmax_is_still_an_error(tmax_h, tmin_h):
    # Only possible with inconsistent data: mn2t above mx2t at every hour.
    times = hours("1991-11-06T00:00", "1991-11-10T00:00")
    with pytest.raises(DataError, match="1991-11-08"):
        daily_extremes(
            series_c(values(times, 10.0), times),
            series_c(values(times, 15.0), times),
            DAY,
            DAY,
            tmax_window_start_utc_h=tmax_h,
            tmin_window_start_utc_h=tmin_h,
        )


def test_processed_file_records_the_adjusted_day_count_and_publishes(
    raw_dir, processed_dir, london_9_15
):
    times, mx, mn = cooling_series()
    write_year_file(raw_dir, "london", 1991, era5(times, mx, mn))
    path = run(raw_dir, processed_dir, london_9_15, DAY, DAY)
    dates, tmax, tmin, kv = read(path)
    assert dates == [DAY]
    assert tmax[0] == pytest.approx(MIDPOINT_C, abs=1e-9)
    assert tmin[0] == pytest.approx(MIDPOINT_C, abs=1e-9)
    assert kv["tmin_tmax_adjusted_days"] == "1"
    validate(path)  # tmin == tmax passes publish's tmin <= tmax check


def test_processed_file_without_adjustments_records_zero(raw_dir, processed_dir, london_9_15):
    times = hours(FIRST, LAST)
    write_year_file(raw_dir, "london", 1991, era5(times, values(times, 12.0), values(times, 4.0)))
    _, _, _, kv = read(run(raw_dir, processed_dir, london_9_15, DAY, DAY))
    assert kv["tmin_tmax_adjusted_days"] == "0"
