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

    - ``"netcdf"``: writes a valid netCDF to ``target`` (an ``lsm`` dataset for a
      land-sea-mask request, else ``mx2t``/``mn2t``);
    - ``"raise"``: raises ``RuntimeError`` without writing;
    - ``"partial_then_raise"``: writes a few bytes to ``target`` and then raises;
    - ``"zip"``: writes a real zip archive (starts with ``b"PK\\x03\\x04"``) to ``target``;
    - ``"garbage"``: writes an HTML error page;
    - ``"zip_split"``: one netCDF per variable, zipped together (as the new CDS does);
    - ``"zip_corrupt"`` / ``"zip_misaligned"``: a damaged zip / parts on shifted time axes;
    - ``"netcdf_missing_var"``: netCDF holding ``mx2t`` only.

    Served data (unless ``dataset`` overrides it for every request): for a request with a
    ``year`` key, ``mx2t``/``mn2t`` for every hour of the requested years x months x days
    x times (a 3-year request is served 3 complete years, a one-step request one step),
    CDS-like: ``valid_time``, latitude stored N->S, a scalar ``number`` and a per-time
    ``expver``, float32 in K (``cds_era5``). Values depend only on the hour stamp and the
    grid cell, so the same hour is served identically whatever the request. A request
    without ``year`` gets the original two-hour 2024 dataset.

    Knobs:

    - ``missing_hours``: time stamps dropped from every temperature response;
    - ``pad_hours``: that many extra hours added before the first and after the last
      served hour of a temperature response;
    - ``fail_on_calls``: 1-based indices of ``retrieve`` calls (counting every call,
      land-sea mask included) that write a few bytes to ``target`` and then raise;
    - ``grid_from_area``: serve the grid spanned by the request's ``area`` (N, W, S, E on
      0.25-degree lines, i.e. the station box) instead of the default 2x2 London corner;
      needed when the downloaded files are processed by the pipeline.

    ``calls`` records ``(name, request, target)``; ``served`` holds the dataset served by
    each call (``None`` when the call raised or served no dataset).
    """

    def __init__(
        self,
        mode: str = "netcdf",
        dataset: xr.Dataset | None = None,
        *,
        missing_hours: Iterable[Any] = (),
        pad_hours: int = 0,
        fail_on_calls: Iterable[int] = (),
        grid_from_area: bool = False,
    ) -> None:
        self.mode = mode
        self.dataset = dataset
        self.missing_hours = pd.DatetimeIndex(list(missing_hours))
        self.pad_hours = pad_hours
        self.fail_on_calls = set(fail_on_calls)
        self.grid_from_area = grid_from_area
        self.calls: list[tuple[str, dict[str, Any], str]] = []
        self.served: list[xr.Dataset | None] = []

    @property
    def requests(self) -> list[dict[str, Any]]:
        return [request for _, request, _ in self.calls]

    def retrieve(self, name: str, request: dict[str, Any], target: Any) -> None:
        self.calls.append((name, copy.deepcopy(request), str(target)))
        self.served.append(None)
        target_path = Path(target)
        if len(self.calls) in self.fail_on_calls:
            target_path.write_bytes(b"\x89HDF\r\n\x1a\npartial")
            raise RuntimeError(f"simulated CDS failure on call {len(self.calls)}")
        if self.mode == "raise":
            raise RuntimeError("simulated CDS failure")
        if self.mode == "partial_then_raise":
            target_path.write_bytes(b"\x89HDF\r\n\x1a\npartial")
            raise RuntimeError("simulated CDS failure mid-download")
        if self.mode == "zip":
            with zipfile.ZipFile(target_path, "w") as zf:
                zf.writestr("data_stream-oper.nc", b"not really netcdf")
            return
        if self.mode == "garbage":
            target_path.write_bytes(b"<html>Service temporarily unavailable</html>")
            return
        ds = self._dataset_for(request)
        self.served[-1] = ds
        if self.mode in ("zip_corrupt", "zip_misaligned"):
            time_dim = "valid_time" if "valid_time" in ds.dims else "time"
            parts = [ds[["mx2t"]], ds[["mn2t"]]]
            if self.mode == "zip_misaligned":
                # Second part shifted by one hour: the time axes no longer match.
                shifted = ds[time_dim].values + np.timedelta64(1, "h")
                parts[1] = parts[1].assign_coords({time_dim: shifted})
            with zipfile.ZipFile(target_path, "w", compression=zipfile.ZIP_STORED) as zf:
                for i, part in enumerate(parts):
                    tmp = target_path.with_name(f"part{i}.nc")
                    part.to_netcdf(tmp)
                    zf.write(tmp, f"data_stream-oper_stepType-{i}.nc")
                    tmp.unlink()
            if self.mode == "zip_corrupt":
                # Flip a byte inside the last member's data: CRC check fails on read.
                with zipfile.ZipFile(target_path) as zf:
                    info = zf.infolist()[-1]
                raw = bytearray(target_path.read_bytes())
                name_len = int.from_bytes(
                    raw[info.header_offset + 26 : info.header_offset + 28], "little"
                )
                extra_len = int.from_bytes(
                    raw[info.header_offset + 28 : info.header_offset + 30], "little"
                )
                data_start = info.header_offset + 30 + name_len + extra_len
                raw[data_start + info.compress_size // 2] ^= 0xFF
                target_path.write_bytes(bytes(raw))
            return
        if self.mode == "netcdf_missing_var":
            ds[["mx2t"]].to_netcdf(target_path)
            return
        if self.mode == "zip_split":
            # As the new CDS does: one netCDF per GRIB stepType, zipped together.
            parts = [ds[[name]] for name in ds.data_vars]
            with zipfile.ZipFile(target_path, "w") as zf:
                for i, part in enumerate(parts):
                    tmp = target_path.with_name(f"part{i}.nc")
                    part.to_netcdf(tmp)
                    zf.write(tmp, f"data_stream-oper_stepType-{i}.nc")
                    tmp.unlink()
            return
        ds.to_netcdf(target_path)

    def temperature_years(self) -> list[list[str]]:
        """``year`` lists of the full-period temperature requests (not lsm, not one-step)."""
        return [
            as_list(r["year"])
            for r in self.requests
            if not is_lsm_request(r) and not is_single_step_request(r)
        ]

    def _grid(self, request: dict[str, Any]) -> tuple[list[float], list[float]]:
        if self.grid_from_area and "area" in request:
            north, west, south, east = (float(x) for x in request["area"])
            return grid_lines(south, north), grid_lines(west, east)
        return LONDON_LATS[:2], LONDON_LONS[:2]

    def _dataset_for(self, request: dict[str, Any]) -> xr.Dataset:
        if self.dataset is not None:
            return self.dataset
        lats, lons = self._grid(request)
        if is_lsm_request(request):
            return lsm_raw_dataset(make_lsm(lats, lons))
        if "year" not in request:
            return make_era5(
                hours("2024-01-01T00:00", "2024-01-01T01:00"),
                LONDON_LATS[:2],
                LONDON_LONS[:2],
                time_name="valid_time",
                lat_descending=True,
            )
        times = requested_hours(request)
        if self.pad_hours and len(times):
            one = pd.Timedelta(hours=1)
            before = pd.date_range(end=times[0] - one, periods=self.pad_hours, freq="h")
            after = pd.date_range(start=times[-1] + one, periods=self.pad_hours, freq="h")
            times = before.append(times).append(after)
        if len(self.missing_hours):
            times = times[~times.isin(self.missing_hours)]
        return cds_era5(times, lats, lons)


def grid_lines(low: float, high: float) -> list[float]:
    """0.25-degree grid lines from ``low`` to ``high`` inclusive (degrees)."""
    n = round((high - low) / 0.25)
    return [low + 0.25 * i for i in range(n + 1)]


def requested_hours(request: dict[str, Any]) -> pd.DatetimeIndex:
    """Every valid hour of a CDS request's years x months x days x times, sorted."""
    years = sorted(int(y) for y in as_list(request["year"]))
    months = {int(m) for m in as_list(request["month"])}
    days = {int(d) for d in as_list(request["day"])}
    times = {int(t.split(":")[0]) for t in as_list(request["time"])}
    out = pd.DatetimeIndex([])
    for year in years:
        idx = pd.date_range(f"{year}-01-01T00:00", f"{year}-12-31T23:00", freq="h")
        keep = idx.month.isin(months) & idx.day.isin(days) & idx.hour.isin(times)
        out = out.append(idx[keep])
    return out


def _hour_number(times: Sequence[Any]) -> Any:
    """Whole hours since 1970-01-01 00:00 UTC, shape (time, 1, 1)."""
    delta = pd.DatetimeIndex(times) - pd.Timestamp("1970-01-01")
    return np.asarray(delta // pd.Timedelta(hours=1), dtype=np.int64)[:, None, None]


def hourly_mx2t_k(times: Sequence[Any], lats: Sequence[float], lons: Sequence[float]) -> Any:
    """Deterministic ``mx2t`` in K, shape (time, lat, lon), lats/lons in the given order.

    Depends only on the hour stamp and the cell's coordinates.
    """
    h = _hour_number(times)
    lat = np.asarray(lats, dtype=np.float64)[None, :, None]
    lon = np.asarray(lons, dtype=np.float64)[None, None, :]
    return 270.0 + (h % 997) * 0.03 + (h % 24) * 0.25 + 4.0 * (lat - 51.0) + 2.0 * lon


def hourly_mn2t_k(times: Sequence[Any], lats: Sequence[float], lons: Sequence[float]) -> Any:
    """Deterministic ``mn2t`` in K, 3..6 K below ``hourly_mx2t_k`` for the same hour/cell."""
    return hourly_mx2t_k(times, lats, lons) - 3.0 - (_hour_number(times) % 7) * 0.5


def cds_era5(times: Sequence[Any], lats: Sequence[float], lons: Sequence[float]) -> xr.Dataset:
    """A CDS-like ``mx2t``/``mn2t`` dataset for exactly ``times`` (float32, K).

    ``valid_time`` dimension, latitude stored N->S, scalar ``number``, a per-time
    ``expver`` coordinate and variable/global attributes as the new CDS writes them.
    """
    times = pd.DatetimeIndex(times)
    lats_desc = sorted((float(x) for x in lats), reverse=True)
    lons_asc = sorted(float(x) for x in lons)
    dims = ("valid_time", "latitude", "longitude")

    def build(values: Any, short: str, long: str) -> xr.DataArray:
        return xr.DataArray(
            np.asarray(values, dtype=np.float64).astype(np.float32),
            dims=dims,
            attrs={"units": "K", "long_name": long, "GRIB_shortName": short},
        )

    return xr.Dataset(
        {
            "mx2t": build(
                hourly_mx2t_k(times, lats_desc, lons_asc),
                "mx2t",
                "Maximum temperature at 2 metres since previous post-processing",
            ),
            "mn2t": build(
                hourly_mn2t_k(times, lats_desc, lons_asc),
                "mn2t",
                "Minimum temperature at 2 metres since previous post-processing",
            ),
        },
        coords={
            "valid_time": times.values.astype("datetime64[ns]"),
            "latitude": ("latitude", np.asarray(lats_desc), {"units": "degrees_north"}),
            "longitude": ("longitude", np.asarray(lons_asc), {"units": "degrees_east"}),
            "number": 0,
            "expver": ("valid_time", np.full(len(times), "0001")),
        },
        attrs={
            "GRIB_centre": "ecmf",
            "Conventions": "CF-1.7",
            "institution": "European Centre for Medium-Range Weather Forecasts",
        },
    )


# --------------------------------------------------------------------------- raw writers


def write_year_file(raw_dir: Path, city: str, year: int, ds: xr.Dataset) -> Path:
    return write_nc(ds, raw_dir / f"era5_{city}_{year}.nc")


def write_step_file(raw_dir: Path, city: str, year: int, ds: xr.Dataset) -> Path:
    return write_nc(ds, raw_dir / f"era5_{city}_{year}0101T00.nc")


def write_lsm_file(raw_dir: Path, city: str, lsm: xr.DataArray) -> Path:
    return write_nc(lsm_raw_dataset(lsm), raw_dir / f"era5_lsm_{city}.nc")


def days_between(start: date, end: date) -> int:
    return (end - start).days + 1
