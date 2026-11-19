"""Hourly station series to daily Tmax/Tmin per settlement day (ADR 0002, ADR 0005).

Each station has its own 24-hour Tmax and Tmin windows (spec 002 amendment B). A window
starting at D 00:00 + s hours uses the values stamped D+s+1h through D+s+24h, because each
``mx2t``/``mn2t`` value covers the hour before its time stamp. Offsets 0 / 0 give the UTC
day: stamps D 01:00 through D+1 00:00.
"""

from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd
import xarray as xr

from dataprep.errors import DataError

HOURS_PER_DAY = 24


@dataclass(frozen=True)
class DailySeries:
    """Daily temperatures for consecutive settlement days.

    Attributes:
        dates: Settlement days D, ascending, no gaps (``datetime64[D]``).
        tmax_c: Daily maximum temperature in degrees C (float64), over each day's Tmax window.
        tmin_c: Daily minimum temperature in degrees C (float64), over each day's Tmin window.
        tmin_tmax_adjusted_days: Days whose Tmin exceeded Tmax under touching windows and
            were set to the midpoint (spec 002 amendment B, decision 6).
    """

    dates: np.ndarray
    tmax_c: np.ndarray
    tmin_c: np.ndarray
    tmin_tmax_adjusted_days: int = 0


def needed_span(
    tmax_window_start_utc_h: int, tmin_window_start_utc_h: int, start: date, end: date
) -> tuple[pd.Timestamp, pd.Timestamp]:
    """Return the first and last hourly stamps needed for ``start..end`` (inclusive).

    Args:
        tmax_window_start_utc_h: Tmax window start, hours relative to D 00:00 UTC.
        tmin_window_start_utc_h: Tmin window start, hours relative to D 00:00 UTC.
        start: First settlement day.
        end: Last settlement day (inclusive).

    Returns:
        ``(start + min(s) + 1 h, end + 1 day + max(s))`` as naive UTC timestamps.
    """
    low = min(tmax_window_start_utc_h, tmin_window_start_utc_h)
    high = max(tmax_window_start_utc_h, tmin_window_start_utc_h)
    first = pd.Timestamp(start) + pd.Timedelta(hours=low + 1)
    last = pd.Timestamp(end) + pd.Timedelta(days=1, hours=high)
    return first, last


def daily_extremes(
    mx2t_c: xr.DataArray,
    mn2t_c: xr.DataArray,
    start: date,
    end: date,
    *,
    tmax_window_start_utc_h: int,
    tmin_window_start_utc_h: int,
) -> DailySeries:
    """Compute daily Tmax/Tmin for ``start..end`` inclusive over the station's windows.

    Args:
        mx2t_c: Hourly station series of ``mx2t`` in degrees C (dim ``time``).
        mn2t_c: Hourly station series of ``mn2t`` in degrees C (dim ``time``).
        start: First settlement day.
        end: Last settlement day (inclusive).
        tmax_window_start_utc_h: Tmax(D) window start, hours relative to D 00:00 UTC.
        tmin_window_start_utc_h: Tmin(D) window start, hours relative to D 00:00 UTC.

    Raises:
        DataError: If an hourly step inside a needed window is missing (the message names
            the timestamp), a needed value is NaN (the message names the timestamp and the
            day whose Tmax or Tmin uses it), or a day has tmin > tmax under overlapping
            windows (names the date). Under touching windows (24 h apart) such a day's
            Tmax and Tmin are both set to their midpoint and counted instead.
    """
    if start > end:
        raise DataError(f"start {start} is after end {end}")
    n_days = (end - start).days + 1
    tmax_c = _windowed(mx2t_c, "mx2t", "Tmax", tmax_window_start_utc_h, start, n_days).max(axis=1)
    tmin_c = _windowed(mn2t_c, "mn2t", "Tmin", tmin_window_start_utc_h, start, n_days).min(axis=1)
    dates = np.arange(np.datetime64(start, "D"), np.datetime64(end, "D") + np.timedelta64(1, "D"))
    bad = np.flatnonzero(tmin_c > tmax_c)
    touching = abs(tmax_window_start_utc_h - tmin_window_start_utc_h) == HOURS_PER_DAY
    if bad.size and not touching:
        k = bad[0]
        raise DataError(f"tmin_c {tmin_c[k]} > tmax_c {tmax_c[k]} on {dates[k]}")
    if bad.size:
        # Decision 6: the windows meet at one instant that ERA5's hourly extremes on either
        # side do not share; the midpoint approximates the temperature at that instant.
        midpoint = (tmax_c[bad] + tmin_c[bad]) / 2
        tmax_c[bad] = midpoint
        tmin_c[bad] = midpoint
    return DailySeries(
        dates=dates, tmax_c=tmax_c, tmin_c=tmin_c, tmin_tmax_adjusted_days=int(bad.size)
    )


def _windowed(
    series: xr.DataArray, name: str, label: str, window_start_h: int, start: date, n_days: int
) -> np.ndarray:
    """Return ``series`` as an ``(n_days, 24)`` float64 array, row k = day k's window.

    Day D's window uses the stamps D+s+1h .. D+s+24h, where s is ``window_start_h``.

    Raises:
        DataError: Naming the first missing timestamp, or the first NaN's timestamp and the
            day (``label`` of day D) that uses it.
    """
    offset = pd.Timedelta(hours=window_start_h + 1)
    expected = pd.date_range(pd.Timestamp(start) + offset, periods=n_days * HOURS_PER_DAY, freq="h")
    index = pd.DatetimeIndex(series["time"].values)
    if index.has_duplicates:
        raise DataError(f"{name}: duplicate time step {index[index.duplicated()][0].isoformat()}")
    missing = expected.difference(index)
    if len(missing):
        raise DataError(f"{name}: missing hourly step {missing[0].isoformat()}")
    values = np.asarray(series.sel(time=expected).values, dtype=np.float64)
    nan = np.flatnonzero(np.isnan(values))
    if nan.size:
        step = expected[nan[0]]
        day = (step - offset).date()
        raise DataError(f"{name}: NaN at {step.isoformat()} ({label} of day {day})")
    return values.reshape(n_days, HOURS_PER_DAY)
