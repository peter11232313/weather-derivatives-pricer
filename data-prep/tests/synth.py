"""Synthetic ERA5 data, raw-file writers and a fake CDS client for spec 002 tests.

Everything here is offline. All files go under pytest's ``tmp_path``; nothing touches
the repo's ``data/`` folder.
"""

from __future__ import annotations

import copy
import zipfile
from collections.abc import Iterable, Sequence
from datetime import date
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import xarray as xr

from dataprep.registry import Station

KELVIN = 273.15

# The London download box from the spec: N 52.0, W -1.0, S 50.75, E 0.25 on the 0.25 grid.
LONDON_LATS = [50.75, 51.0, 51.25, 51.5, 51.75, 52.0]
LONDON_LONS = [-1.0, -0.75, -0.5, -0.25, 0.0, 0.25]

MX2T_NAMES = {"maximum_2m_temperature_since_previous_post_processing", "mx2t"}
MN2T_NAMES = {"minimum_2m_temperature_since_previous_post_processing", "mn2t"}
LSM_NAMES = {"land_sea_mask", "lsm"}


# --------------------------------------------------------------------------- helpers


def hours(first: str, last: str) -> pd.DatetimeIndex:
    """Hourly UTC time stamps from ``first`` to ``last`` inclusive."""
    return pd.date_range(first, last, freq="h")


def make_station(
    lat_deg: float = 51.479,
    lon_deg: float = -0.449,
    city: str = "london",
    station: str = "London Heathrow",
) -> Station:
    return Station(
        city=city, station=station, wmo_id="03772", icao="EGLL", lat_deg=lat_deg, lon_deg=lon_deg
    )


def linear_c(lat: Any, lon: Any) -> Any:
    """Linear field used across tests (degrees C).

    It reproduces the spec's interpolation worked example: 10 at (51.25, -0.50),
    12 at (51.25, -0.25), 14 at (51.50, -0.50), 16 at (51.50, -0.25).
    """
    return 10.0 + 16.0 * (np.asarray(lat) - 51.25) + 8.0 * (np.asarray(lon) + 0.5)


def make_era5(
    times: Sequence[Any],
    lats: Sequence[float],
    lons: Sequence[float],
    mx2t_k: Any = 280.0,
    mn2t_k: Any = 270.0,
    *,
    time_name: str = "time",
    lat_descending: bool = False,
    expver: Sequence[Any] | None = None,
    number: bool = False,
    dtype: str = "float32",
    units: str | None = "K",
) -> xr.Dataset:
    """Build a synthetic ERA5 ``mx2t``/``mn2t`` dataset.

    ``mx2t_k`` / ``mn2t_k`` are in kelvin, given in latitude-ascending order and
    broadcastable to ``(time, latitude, longitude)``, or ``(expver, time, latitude,
    longitude)`` when ``expver`` is given. ``lat_descending`` stores latitude north->south
    (as ERA5 does) by flipping both the coordinate and the data.
    """
    times = pd.DatetimeIndex(times)
    lats_arr = np.asarray(lats, dtype=np.float64)
    lons_arr = np.asarray(lons, dtype=np.float64)
    shape: tuple[int, ...] = (len(times), len(lats_arr), len(lons_arr))
    dims: tuple[str, ...] = (time_name, "latitude", "longitude")
    coords: dict[str, Any] = {time_name: times, "latitude": lats_arr, "longitude": lons_arr}
    if expver is not None:
        shape = (len(expver), *shape)
        dims = ("expver", *dims)
        coords["expver"] = np.asarray(expver)
    lat_axis = dims.index("latitude")

    def build(values: Any) -> xr.DataArray:
        arr = np.broadcast_to(np.asarray(values, dtype=np.float64), shape).copy()
        if lat_descending:
            arr = np.flip(arr, axis=lat_axis)
        da = xr.DataArray(arr.astype(dtype), dims=dims)
        if units is not None:
            da.attrs["units"] = units
        return da

    if lat_descending:
        coords["latitude"] = lats_arr[::-1].copy()
    ds = xr.Dataset({"mx2t": build(mx2t_k), "mn2t": build(mn2t_k)}, coords=coords)
    if number:
        ds = ds.assign_coords(number=0)
    return ds


def make_lsm(
    lats: Sequence[float],
    lons: Sequence[float],
    land: float = 1.0,
    sea_cells: Iterable[tuple[float, float]] = (),
    land_cells: Iterable[tuple[float, float]] = (),
) -> xr.DataArray:
    """Land-sea mask (latitude ascending), ``land`` everywhere except listed cells.

    ``sea_cells`` are set to 0.0 and ``land_cells`` to 1.0 (applied after ``sea_cells``).
    """
    lats_arr = np.asarray(lats, dtype=np.float64)
    lons_arr = np.asarray(lons, dtype=np.float64)
    da = xr.DataArray(
        np.full((len(lats_arr), len(lons_arr)), land, dtype=np.float64),
        coords={"latitude": lats_arr, "longitude": lons_arr},
        dims=("latitude", "longitude"),
        name="lsm",
    )
    for lat, lon in sea_cells:
        da.loc[{"latitude": lat, "longitude": lon}] = 0.0
    for lat, lon in land_cells:
        da.loc[{"latitude": lat, "longitude": lon}] = 1.0
    return da


def lsm_raw_dataset(lsm: xr.DataArray) -> xr.Dataset:
    """Wrap a mask as Copernicus delivers it: one ``valid_time`` step, latitude N->S."""
    da = lsm.sortby("latitude", ascending=False).expand_dims(
        valid_time=[np.datetime64("2024-01-01T00:00", "ns")]
    )
    da = da.astype("float32")
    da.attrs["units"] = "(0 - 1)"
    return da.to_dataset(name="lsm").assign_coords(number=0)


def field_c(
    values: Any, lats: Sequence[float], lons: Sequence[float], times: Sequence[Any] | None = None
) -> xr.DataArray:
    """An hourly station-grid field in degrees C, dims ``(time, latitude, longitude)``."""
    lats_arr = np.asarray(lats, dtype=np.float64)
    lons_arr = np.asarray(lons, dtype=np.float64)
    arr = np.asarray(values, dtype=np.float64)
    if arr.ndim == 2:
        arr = arr[np.newaxis, :, :]
    if times is None:
        times = pd.date_range("2024-01-01T01:00", periods=arr.shape[0], freq="h")
    da = xr.DataArray(
        arr,
        coords={"time": pd.DatetimeIndex(times), "latitude": lats_arr, "longitude": lons_arr},
        dims=("time", "latitude", "longitude"),
    )
    da.attrs["units"] = "degC"
    return da


def series_c(values: Sequence[float], times: Sequence[Any]) -> xr.DataArray:
    """An hourly station series in degrees C (dim ``time``)."""
    return xr.DataArray(
        np.asarray(values, dtype=np.float64),
        coords={"time": pd.DatetimeIndex(times)},
        dims="time",
    )


def write_nc(ds: xr.Dataset, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    ds.to_netcdf(path)
    return path


def as_list(value: Any) -> list[str]:
    """CDS request values may be a scalar or a list; normalise to a list of strings."""
    if isinstance(value, (str, int, float)):
        return [str(value)]
    return [str(v) for v in value]


def request_variables(request: dict[str, Any]) -> set[str]:
    return set(as_list(request.get("variable", [])))


def is_lsm_request(request: dict[str, Any]) -> bool:
    return bool(request_variables(request) & LSM_NAMES)


def is_single_step_request(request: dict[str, Any]) -> bool:
    return (
        as_list(request.get("month", [])) in (["01"], ["1"])
        and as_list(request.get("day", [])) in (["01"], ["1"])
        and len(as_list(request.get("time", []))) == 1
        and as_list(request["time"])[0].startswith("00:00")
    )


# --------------------------------------------------------------------------- fake CDS


class FakeCdsClient:
    """Stand-in for ``cdsapi.Client``.

    Records every ``retrieve(name, request, target)`` call and then, depending on
    ``mode``:

    - ``"netcdf"``: writes a small valid netCDF to ``target`` (an ``lsm`` dataset for a
      land-sea-mask request, else ``mx2t``/``mn2t``);
    - ``"raise"``: raises ``RuntimeError`` without writing;
    - ``"partial_then_raise"``: writes a few bytes to ``target`` and then raises;
    - ``"zip"``: writes a real zip archive (starts with ``b"PK\\x03\\x04"``) to ``target``.
    """

    def __init__(self, mode: str = "netcdf", dataset: xr.Dataset | None = None) -> None:
        self.mode = mode
        self.dataset = dataset
        self.calls: list[tuple[str, dict[str, Any], str]] = []

    @property
    def requests(self) -> list[dict[str, Any]]:
        return [request for _, request, _ in self.calls]

    def retrieve(self, name: str, request: dict[str, Any], target: Any) -> None:
        self.calls.append((name, copy.deepcopy(request), str(target)))
        target_path = Path(target)
        if self.mode == "raise":
            raise RuntimeError("simulated CDS failure")
        if self.mode == "partial_then_raise":
            target_path.write_bytes(b"\x89HDF\r\n\x1a\npartial")
            raise RuntimeError("simulated CDS failure mid-download")
        if self.mode == "zip":
            with zipfile.ZipFile(target_path, "w") as zf:
                zf.writestr("data_stream-oper.nc", b"not really netcdf")
            return
        if self.dataset is not None:
            ds = self.dataset
        elif is_lsm_request(request):
            ds = lsm_raw_dataset(make_lsm(LONDON_LATS[:2], LONDON_LONS[:2]))
        else:
            ds = make_era5(
                hours("2024-01-01T00:00", "2024-01-01T01:00"),
                LONDON_LATS[:2],
                LONDON_LONS[:2],
                time_name="valid_time",
                lat_descending=True,
            )
        ds.to_netcdf(target_path)


# --------------------------------------------------------------------------- raw writers


def write_year_file(raw_dir: Path, city: str, year: int, ds: xr.Dataset) -> Path:
    return write_nc(ds, raw_dir / f"era5_{city}_{year}.nc")


def write_step_file(raw_dir: Path, city: str, year: int, ds: xr.Dataset) -> Path:
    return write_nc(ds, raw_dir / f"era5_{city}_{year}0101T00.nc")


def write_lsm_file(raw_dir: Path, city: str, lsm: xr.DataArray) -> Path:
    return write_nc(lsm_raw_dataset(lsm), raw_dir / f"era5_lsm_{city}.nc")


def days_between(start: date, end: date) -> int:
    return (end - start).days + 1
