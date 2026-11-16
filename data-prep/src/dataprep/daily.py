"""Hourly station series to daily Tmax/Tmin over UTC days (ADR 0002).

Day D uses the hourly values stamped D 01:00 through D+1 00:00, because each
``mx2t``/``mn2t`` value covers the hour before its time stamp.
"""

from dataclasses import dataclass
from datetime import date

import numpy as np
import xarray as xr


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
    raise NotImplementedError
