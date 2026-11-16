"""Hourly station series to daily Tmax/Tmin over UTC days (ADR 0002).

Day D uses the hourly values stamped D 01:00 through D+1 00:00, because each
``mx2t``/``mn2t`` value covers the hour before its time stamp.
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
    """Daily temperatures for consecutive UTC days.

    Attributes:
        dates: UTC calendar days, ascending, no gaps (``datetime64[D]``).
        tmax_c: Daily maximum temperature in degrees C (float64).
        tmin_c: Daily minimum temperature in degrees C (float64).
    """

    dates: np.ndarray
    tmax_c: np.ndarray
    tmin_c: np.ndarray


def daily_extremes(
    mx2t_c: xr.DataArray, mn2t_c: xr.DataArray, start: date, end: date
) -> DailySeries:
    """Compute daily Tmax/Tmin for ``start..end`` inclusive.

    Args:
        mx2t_c: Hourly station series of ``mx2t`` in degrees C (dim ``time``).
        mn2t_c: Hourly station series of ``mn2t`` in degrees C (dim ``time``).
        start: First UTC day.
        end: Last UTC day (inclusive).

    Raises:
        DataError: If an hourly step in start 01:00 .. end+1 00:00 is missing (the message
            names the timestamp), a value is NaN, or a day has tmin > tmax (the message
            names the date).
    """
    if start > end:
        raise DataError(f"start {start} is after end {end}")
    # Day k covers expected[24k : 24k + 24] = D 01:00 .. D+1 00:00.
    expected = pd.date_range(
        pd.Timestamp(start) + pd.Timedelta(hours=1),
        pd.Timestamp(end) + pd.Timedelta(days=1),
        freq="h",
    )
    n_days = (end - start).days + 1
    tmax_c = _hourly(mx2t_c, "mx2t", expected).reshape(n_days, HOURS_PER_DAY).max(axis=1)
    tmin_c = _hourly(mn2t_c, "mn2t", expected).reshape(n_days, HOURS_PER_DAY).min(axis=1)
    dates = np.arange(np.datetime64(start, "D"), np.datetime64(end, "D") + np.timedelta64(1, "D"))
    bad = np.flatnonzero(tmin_c > tmax_c)
    if bad.size:
        k = bad[0]
        raise DataError(f"tmin_c {tmin_c[k]} > tmax_c {tmax_c[k]} on {dates[k]}")
    return DailySeries(dates=dates, tmax_c=tmax_c, tmin_c=tmin_c)


def _hourly(series: xr.DataArray, name: str, expected: pd.DatetimeIndex) -> np.ndarray:
    """Return ``series`` on exactly the ``expected`` hourly steps, as float64.

    Raises:
        DataError: Naming the first missing timestamp, or the date of the first NaN.
    """
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
        day = (step - pd.Timedelta(hours=1)).date()
        raise DataError(f"{name}: NaN at {step.isoformat()} (day {day})")
    return values
