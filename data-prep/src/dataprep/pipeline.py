"""Raw ERA5 files to one processed Parquet file per city (spec 002)."""

import re
from datetime import UTC, date, datetime
from pathlib import Path

import numpy as np
import pandas as pd
import xarray as xr

from dataprep.daily import DailySeries, daily_extremes, needed_span
from dataprep.download import area_for, edge_day_file, lsm_file, year_file
from dataprep.errors import DataError
from dataprep.interpolate import mean_3x3, nearest_cell, to_station
from dataprep.load import normalise, open_era5
from dataprep.registry import Station
from dataprep.write import write_parquet

SOURCE = "ERA5 mx2t/mn2t"
# Grid edges are 0.25-degree multiples; this absorbs float noise from the file.
_GRID_TOL = 1e-6


def load_hourly(station: Station, start: date, end: date, raw_dir: Path) -> xr.Dataset:
    """Load and normalise the hourly ``mx2t``/``mn2t`` needed for ``start..end``.

    Returns exactly the steps of ``needed_span`` for the station's windows that exist in
    the files (a missing step is reported later by ``daily_extremes``). Each hour comes
    from one file only: year file Y, if present, supplies [Y-01-01 00:00, Y+1-01-01 00:00);
    otherwise a day outside ``start..end`` comes from its edge-day file (spec 002
    amendment B).

    Raises:
        DataError: If ``start`` is after ``end``, or a needed raw file is missing, lacks
            ``mx2t``/``mn2t`` or is on a grid other than ``area_for(station)``; the message
            names the path.
    """
    if start > end:
        raise DataError(f"start {start} is after end {end}")
    first, last = needed_span(
        station.tmax_window_start_utc_h, station.tmin_window_start_utc_h, start, end
    )
    parts = []
    for year in range(first.year, last.year + 1):
        path = year_file(raw_dir, station.city, year)
        if path.exists():
            parts.append(_own_hours(path, station, pd.Timestamp(year, 1, 1), 366))
            continue
        days = pd.date_range(
            max(first.normalize(), pd.Timestamp(year, 1, 1)),
            min(last.normalize(), pd.Timestamp(year, 12, 31)),
            freq="D",
        )
        if any(start <= day.date() <= end for day in days):
            raise DataError(f"raw ERA5 file not found: {path}")
        for day in days:
            edge = edge_day_file(raw_dir, station.city, day.date())
            if not edge.exists():
                raise DataError(f"raw ERA5 file not found: {path} (or edge-day file {edge})")
            parts.append(_own_hours(edge, station, day, 1))
    hourly = xr.concat(parts, dim="time") if len(parts) > 1 else parts[0]
    return hourly.sel(time=slice(first, last))


def _own_hours(path: Path, station: Station, first: pd.Timestamp, max_days: int) -> xr.Dataset:
    """Open ``path`` and keep only its own hours, [``first``, end of year or day), sorted.

    A year file owns its calendar year; an edge-day file owns its day (``max_days`` 1).
    """
    ds = _open_checked(path, station, ("mx2t", "mn2t")).sortby("time")
    stop = min(first + pd.Timedelta(days=max_days), pd.Timestamp(first.year + 1, 1, 1))
    return ds.sel(time=slice(first, stop - pd.Timedelta("1ns")))


def process(
    station: Station,
    start: date,
    end: date,
    raw_dir: Path,
    processed_dir: Path,
    registry_version: str,
    now: datetime,
) -> Path:
    """Build ``<processed_dir>/<city>.parquet`` for ``start..end``.

    Load, interpolate to the station (ADR 0003), compute daily extremes over the
    station's observation windows (ADR 0002, ADR 0005), compute the diagnostics with the
    same windows and write with metadata. ``now`` (UTC) fills ``created_utc``.

    Returns:
        The processed file path.

    Raises:
        DataError: For any data problem; the message starts with the city.
    """
    try:
        return _process(station, start, end, raw_dir, processed_dir, registry_version, now)
    except DataError as e:
        if re.search(rf"\b{re.escape(station.city)}\b", str(e)):
            raise  # already names the city
        raise DataError(f"{station.city}: {e}") from e


def _process(
    station: Station,
    start: date,
    end: date,
    raw_dir: Path,
    processed_dir: Path,
    registry_version: str,
    now: datetime,
) -> Path:
    hourly = load_hourly(station, start, end, raw_dir)
    lsm = _load_lsm(station, raw_dir)
    mx2t, method = to_station(hourly["mx2t"], lsm, station)
    mn2t, _ = to_station(hourly["mn2t"], lsm, station)
    windows = {
        "tmax_window_start_utc_h": station.tmax_window_start_utc_h,
        "tmin_window_start_utc_h": station.tmin_window_start_utc_h,
    }
    daily = daily_extremes(mx2t, mn2t, start, end, **windows)

    lat, lon = station.lat_deg, station.lon_deg
    nearest = _daily_mean_c(
        nearest_cell(hourly["mx2t"], lat, lon),
        nearest_cell(hourly["mn2t"], lat, lon),
        start,
        end,
        windows,
    )
    box = _daily_mean_c(
        mean_3x3(hourly["mx2t"], lat, lon), mean_3x3(hourly["mn2t"], lat, lon), start, end, windows
    )
    metadata = {
        "source": SOURCE,
        "method": method,
        "station": station.station,
        "lat": repr(lat),
        "lon": repr(lon),
        "start": start.isoformat(),
        "end": end.isoformat(),
        "registry_version": registry_version,
        "created_utc": now.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "tmean_nearest_c_mean": repr(nearest),
        "tmean_3x3_c_mean": repr(box),
        "tmin_tmax_adjusted_days": str(daily.tmin_tmax_adjusted_days),
        **{key: str(value) for key, value in windows.items()},
    }
    return write_parquet(processed_dir / f"{station.city}.parquet", daily, metadata)


def _load_lsm(station: Station, raw_dir: Path) -> xr.DataArray:
    """Return the land-sea mask as (latitude, longitude)."""
    path = lsm_file(raw_dir, station.city)
    if not path.exists():
        raise DataError(f"land-sea mask file not found: {path}")
    lsm = _open_checked(path, station, ("lsm",))["lsm"]
    if "time" in lsm.dims:
        lsm = lsm.isel(time=0, drop=True)
    return lsm.drop_vars("time", errors="ignore")


def _open_checked(path: Path, station: Station, variables: tuple[str, ...]) -> xr.Dataset:
    """Open and normalise a raw file, checking its variables and grid.

    Raises:
        DataError: If a variable is missing, or the grid does not span
            ``area_for(station)`` (e.g. downloaded before the station moved); names the path.
    """
    ds = normalise(open_era5(path))
    missing = [name for name in variables if name not in ds.data_vars]
    if missing:
        found = ", ".join(map(str, ds.data_vars)) or "none"
        raise DataError(f"{path} lacks variable(s) {', '.join(missing)}; found {found}")
    north, west, south, east = area_for(station)
    lats, lons = ds["latitude"].values, ds["longitude"].values
    actual = (lats.max(), lons.min(), lats.min(), lons.max())
    if not np.allclose(actual, (north, west, south, east), rtol=0, atol=_GRID_TOL):
        raise DataError(
            f"{path} covers N {actual[0]}, W {actual[1]}, S {actual[2]}, E {actual[3]} but "
            f"station {station.city} needs N {north}, W {west}, S {south}, E {east}; "
            "delete the file and download it again"
        )
    return ds[list(variables)]


def _daily_mean_c(
    mx2t: xr.DataArray, mn2t: xr.DataArray, start: date, end: date, windows: dict[str, int]
) -> float:
    """Period mean of daily (tmax + tmin) / 2 for a diagnostic series (degrees C).

    Uses the station's observation windows (``windows``: the two ``*_window_start_utc_h``);
    the diagnostics' own midpoint-adjusted day count is not recorded.
    """
    daily: DailySeries = daily_extremes(mx2t, mn2t, start, end, **windows)
    return float(np.mean((daily.tmax_c + daily.tmin_c) / 2))
