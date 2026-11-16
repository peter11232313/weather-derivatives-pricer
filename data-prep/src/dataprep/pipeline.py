"""Raw ERA5 files to one processed Parquet file per city (spec 002)."""

from datetime import UTC, date, datetime
from pathlib import Path

import numpy as np
import pandas as pd
import xarray as xr

from dataprep.daily import DailySeries, daily_extremes
from dataprep.download import lsm_file, new_year_step_file, year_file
from dataprep.errors import DataError
from dataprep.interpolate import mean_3x3, nearest_cell, to_station
from dataprep.load import normalise, open_era5
from dataprep.registry import Station
from dataprep.write import write_parquet

SOURCE = "ERA5 mx2t/mn2t"


def load_hourly(station: Station, start: date, end: date, raw_dir: Path) -> xr.Dataset:
    """Load and normalise the hourly ``mx2t``/``mn2t`` needed for ``start..end``.

    Returns exactly the steps ``start`` 01:00 .. ``end``+1 00:00 that exist in the files
    (a missing step is reported later by ``daily_extremes``). Each hour comes from one
    file only: year file Y supplies [Y-01-01 00:00, Y+1-01-01 00:00). The 00:00 step after
    ``end`` comes from the next year's file if present, else from the one-step file
    (ADR 0002).

    Raises:
        DataError: If a needed raw file is missing; the message names the path.
    """
    first = pd.Timestamp(start) + pd.Timedelta(hours=1)
    last = pd.Timestamp(end) + pd.Timedelta(days=1)
    parts = []
    for year in range(first.year, last.year + 1):
        path = year_file(raw_dir, station.city, year)
        is_final_midnight = year == last.year and last == pd.Timestamp(year, 1, 1)
        if not path.exists() and is_final_midnight:
            path = new_year_step_file(raw_dir, station.city, year)
        if not path.exists():
            raise DataError(f"raw ERA5 file not found: {path}")
        ds = normalise(open_era5(path))[["mx2t", "mn2t"]]
        own = slice(pd.Timestamp(year, 1, 1), pd.Timestamp(year + 1, 1, 1) - pd.Timedelta("1ns"))
        parts.append(ds.sortby("time").sel(time=own))
    hourly = xr.concat(parts, dim="time") if len(parts) > 1 else parts[0]
    return hourly.sel(time=slice(first, last))


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

    Load, interpolate to the station (ADR 0003), compute daily extremes (ADR 0002),
    compute the diagnostics and write with metadata. ``now`` (UTC) fills ``created_utc``.

    Returns:
        The processed file path.
    """
    hourly = load_hourly(station, start, end, raw_dir)
    lsm = _load_lsm(station, raw_dir)
    mx2t, method = to_station(hourly["mx2t"], lsm, station)
    mn2t, _ = to_station(hourly["mn2t"], lsm, station)
    daily = daily_extremes(mx2t, mn2t, start, end)

    lat, lon = station.lat_deg, station.lon_deg
    nearest = _daily_mean_c(
        nearest_cell(hourly["mx2t"], lat, lon), nearest_cell(hourly["mn2t"], lat, lon), start, end
    )
    box = _daily_mean_c(
        mean_3x3(hourly["mx2t"], lat, lon), mean_3x3(hourly["mn2t"], lat, lon), start, end
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
    }
    return write_parquet(processed_dir / f"{station.city}.parquet", daily, metadata)


def _load_lsm(station: Station, raw_dir: Path) -> xr.DataArray:
    """Return the land-sea mask as (latitude, longitude)."""
    path = lsm_file(raw_dir, station.city)
    if not path.exists():
        raise DataError(f"land-sea mask file not found: {path}")
    lsm = normalise(open_era5(path))["lsm"]
    if "time" in lsm.dims:
        lsm = lsm.isel(time=0, drop=True)
    return lsm.drop_vars("time", errors="ignore")


def _daily_mean_c(mx2t: xr.DataArray, mn2t: xr.DataArray, start: date, end: date) -> float:
    """Period mean of daily (tmax + tmin) / 2 for a diagnostic series (degrees C)."""
    daily: DailySeries = daily_extremes(mx2t, mn2t, start, end)
    return float(np.mean((daily.tmax_c + daily.tmin_c) / 2))
