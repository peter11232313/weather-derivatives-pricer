"""Hourly ERA5 fields to a station point (ADR 0003).

Fields are ``xarray.DataArray`` with dims ``(time, latitude, longitude)``, latitude and
longitude ascending (as ``load.normalise`` returns them), in degrees C. The land-sea mask
has dims ``(latitude, longitude)``, values 0..1, on the same grid.
"""

import math
from typing import Literal

import numpy as np
import xarray as xr

from dataprep.errors import DataError
from dataprep.registry import Station

Method = Literal["bilinear", "nearest_land"]
LAND_THRESHOLD = 0.5
# Coordinates are 0.25-degree multiples; this absorbs float noise in comparisons.
_COORD_TOL = 1e-9


def to_station(
    field: xr.DataArray, lsm: xr.DataArray, station: Station
) -> tuple[xr.DataArray, Method]:
    """Return the field at the station and the method used.

    Bilinear if every surrounding cell with non-zero bilinear weight is land
    (``lsm >= 0.5``), else the nearest land cell. Cells with zero weight (a station on a
    grid line or node) are ignored, so the method cannot flip on float rounding.

    Returns:
        A time series (dim ``time``) in degrees C, and ``"bilinear"`` or ``"nearest_land"``.

    Raises:
        DataError: If the station is outside the field's grid, or no land cell exists in
            the box; the message names the city.
    """
    field = _ordered(field)
    lat, lon = station.lat_deg, station.lon_deg
    lats, lons = _axis(field, "latitude"), _axis(field, "longitude")
    try:
        i, wy = _bracket(lats, lat)
        j, wx = _bracket(lons, lon)
    except ValueError as e:
        raise DataError(f"station {station.city} ({lat}, {lon}) is outside the grid: {e}") from e
    mask = _mask_on_grid(lsm, lats, lons, station.city)
    weights = np.array([[(1 - wy) * (1 - wx), (1 - wy) * wx], [wy * (1 - wx), wy * wx]])
    used = weights > _COORD_TOL
    if (mask[i : i + 2, j : j + 2][used] >= LAND_THRESHOLD).all():
        return bilinear(field, lat, lon), "bilinear"
    return nearest_land(field, lsm, lat, lon, station.city), "nearest_land"


def bilinear(field: xr.DataArray, lat_deg: float, lon_deg: float) -> xr.DataArray:
    """Bilinearly interpolate ``field`` to (``lat_deg``, ``lon_deg``) at every time step.

    Raises:
        ValueError: If the point is outside the grid.
    """
    field = _ordered(field)
    lats, lons = _axis(field, "latitude"), _axis(field, "longitude")
    i, wy = _bracket(lats, lat_deg)
    j, wx = _bracket(lons, lon_deg)
    corners = field.isel(latitude=slice(i, i + 2), longitude=slice(j, j + 2)).values
    # corners[..., a, b]: a = lower/upper latitude, b = lower/upper longitude.
    values = (
        (1 - wy) * (1 - wx) * corners[..., 0, 0]
        + (1 - wy) * wx * corners[..., 0, 1]
        + wy * (1 - wx) * corners[..., 1, 0]
        + wy * wx * corners[..., 1, 1]
    )
    return _series(field, values)


def nearest_land(
    field: xr.DataArray, lsm: xr.DataArray, lat_deg: float, lon_deg: float, city: str
) -> xr.DataArray:
    """Return the series of the nearest land cell.

    Distance is sqrt((dlon * cos(lat_deg))^2 + dlat^2) over all cells with ``lsm >= 0.5``;
    ties go to the lower latitude, then the lower longitude.

    Raises:
        DataError: If no land cell exists; the message names ``city``.
    """
    field = _ordered(field)
    lats, lons = _axis(field, "latitude"), _axis(field, "longitude")
    land = _mask_on_grid(lsm, lats, lons, city) >= LAND_THRESHOLD
    if not land.any():
        raise DataError(f"no land cell (lsm >= {LAND_THRESHOLD}) in the grid for {city}")
    i, j = _closest(lats, lons, lat_deg, lon_deg, land)
    return _series(field, field.values[..., i, j])


def nearest_cell(field: xr.DataArray, lat_deg: float, lon_deg: float) -> xr.DataArray:
    """Return the series of the nearest grid cell, land or sea (diagnostic only)."""
    field = _ordered(field)
    lats, lons = _axis(field, "latitude"), _axis(field, "longitude")
    everywhere = np.ones((lats.size, lons.size), dtype=bool)
    i, j = _closest(lats, lons, lat_deg, lon_deg, everywhere)
    return _series(field, field.values[..., i, j])


def mean_3x3(field: xr.DataArray, lat_deg: float, lon_deg: float) -> xr.DataArray:
    """Return the mean over the 3x3 cells centred on the nearest cell (diagnostic only).

    Raises:
        DataError: If the nearest cell is on the edge of the grid.
    """
    field = _ordered(field)
    lats, lons = _axis(field, "latitude"), _axis(field, "longitude")
    everywhere = np.ones((lats.size, lons.size), dtype=bool)
    i, j = _closest(lats, lons, lat_deg, lon_deg, everywhere)
    if not (1 <= i <= lats.size - 2 and 1 <= j <= lons.size - 2):
        raise DataError(f"3x3 box around ({lat_deg}, {lon_deg}) does not fit in the grid")
    block = field.values[..., i - 1 : i + 2, j - 1 : j + 2]
    return _series(field, block.mean(axis=(-2, -1)))


def _ordered(field: xr.DataArray) -> xr.DataArray:
    """Return ``field`` with dims ``(time, latitude, longitude)``; indexing relies on it."""
    return field.transpose("time", "latitude", "longitude")


def _axis(field: xr.DataArray, name: str) -> np.ndarray:
    """Return a coordinate as float64, checking it is strictly ascending."""
    values = np.asarray(field[name].values, dtype=np.float64)
    if values.size < 2 or not (np.diff(values) > 0).all():
        raise DataError(f"{name} must be strictly ascending with at least 2 points")
    return values


def _bracket(axis: np.ndarray, x: float) -> tuple[int, float]:
    """Return (i, w) with axis[i] <= x <= axis[i+1] and weight w of axis[i+1] in [0, 1].

    Raises:
        ValueError: If ``x`` is outside the axis.
    """
    if x < axis[0] - _COORD_TOL or x > axis[-1] + _COORD_TOL:
        raise ValueError(f"{x} outside [{axis[0]}, {axis[-1]}]")
    i = int(np.searchsorted(axis, x, side="right")) - 1
    i = min(max(i, 0), axis.size - 2)
    w = (x - axis[i]) / (axis[i + 1] - axis[i])
    return i, min(max(w, 0.0), 1.0)


def _closest(
    lats: np.ndarray, lons: np.ndarray, lat: float, lon: float, allowed: np.ndarray
) -> tuple[int, int]:
    """Index of the closest allowed cell; ties go to lower latitude, then lower longitude."""
    lat2, lon2 = np.meshgrid(lats, lons, indexing="ij")
    dist = np.hypot((lon2 - lon) * math.cos(math.radians(lat)), lat2 - lat)
    dist = np.where(allowed, dist, np.inf)
    # Treat distances equal within float rounding as ties. Row-major order of
    # ascending axes visits lower latitude first, then lower longitude.
    best = np.flatnonzero(dist <= dist.min() + _COORD_TOL)[0]
    i, j = np.unravel_index(best, dist.shape)
    return int(i), int(j)


def _mask_on_grid(lsm: xr.DataArray, lats: np.ndarray, lons: np.ndarray, city: str) -> np.ndarray:
    """Return the land-sea mask as a (lat, lon) array on the field's grid."""
    mask = lsm.squeeze(drop=True)
    try:
        mask = mask.sortby("latitude").sortby("longitude")
        mask = mask.sel(latitude=lats, longitude=lons, method="nearest", tolerance=1e-6)
    except KeyError as e:
        raise DataError(f"land-sea mask grid does not match the field grid for {city}") from e
    return np.asarray(mask.transpose("latitude", "longitude").values, dtype=np.float64)


def _series(field: xr.DataArray, values: np.ndarray) -> xr.DataArray:
    """Wrap per-time values as a station series with the field's time coordinate."""
    return xr.DataArray(
        np.asarray(values, dtype=np.float64),
        coords={"time": field["time"].values},
        dims=("time",),
        attrs={"units": field.attrs.get("units", "degC")},
    )
