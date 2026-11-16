"""Hourly ERA5 fields to a station point (ADR 0003).

Fields are ``xarray.DataArray`` with dims ``(time, latitude, longitude)``, latitude
ascending, in degrees C. The land-sea mask has dims ``(latitude, longitude)``, values 0..1.
"""

from typing import Literal

import xarray as xr

from dataprep.registry import Station

Method = Literal["bilinear", "nearest_land"]
LAND_THRESHOLD = 0.5


def to_station(
    field: xr.DataArray, lsm: xr.DataArray, station: Station
) -> tuple[xr.DataArray, Method]:
    """Return the field at the station and the method used.

    Bilinear from the four surrounding cells if all are land (``lsm >= 0.5``), else the
    nearest land cell.

    Returns:
        A time series (dim ``time``) in degrees C, and ``"bilinear"`` or ``"nearest_land"``.

    Raises:
        DataError: If the station is outside the field's grid, or no land cell exists in
            the box; the message names the city.
    """
    raise NotImplementedError


def bilinear(field: xr.DataArray, lat_deg: float, lon_deg: float) -> xr.DataArray:
    """Bilinearly interpolate ``field`` to (``lat_deg``, ``lon_deg``) at every time step."""
    raise NotImplementedError


def nearest_land(
    field: xr.DataArray, lsm: xr.DataArray, lat_deg: float, lon_deg: float, city: str
) -> xr.DataArray:
    """Return the series of the nearest land cell.

    Distance is sqrt((dlon * cos(lat_deg))^2 + dlat^2) over all cells with ``lsm >= 0.5``;
    ties go to the lower latitude, then the lower longitude.

    Raises:
        DataError: If no land cell exists; the message names ``city``.
    """
    raise NotImplementedError


def nearest_cell(field: xr.DataArray, lat_deg: float, lon_deg: float) -> xr.DataArray:
    """Return the series of the nearest grid cell, land or sea (diagnostic only)."""
    raise NotImplementedError


def mean_3x3(field: xr.DataArray, lat_deg: float, lon_deg: float) -> xr.DataArray:
    """Return the mean over the 3x3 cells centred on the nearest cell (diagnostic only)."""
    raise NotImplementedError
