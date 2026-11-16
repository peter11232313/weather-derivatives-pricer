"""Open and normalise ERA5 netCDF files (``era5-data`` skill, spec 002)."""

from pathlib import Path

import numpy as np
import xarray as xr

from dataprep.errors import DataError

KELVIN_OFFSET = 273.15
TEMPERATURE_VARS = frozenset({"mx2t", "mn2t", "t2m"})


def open_era5(path: Path) -> xr.Dataset:
    """Open an ERA5 netCDF file, decoding packed values.

    Raises:
        DataError: If the file cannot be read; the message names the path.
    """
    try:
        # Load into memory and close, so the file is not held open (Windows rename/delete).
        with xr.open_dataset(path, mask_and_scale=True) as ds:
            return ds.load()
    except (OSError, ValueError, RuntimeError) as e:
        raise DataError(f"cannot read ERA5 file {path}: {e}") from e


def normalise(ds: xr.Dataset) -> xr.Dataset:
    """Normalise an ERA5 dataset.

    Renames ``valid_time`` to ``time``; collapses ``expver`` preferring ERA5 (1) over
    ERA5T (5); drops ``number``; sorts latitude ascending; maps longitude to
    [-180, 180); casts to float64; converts temperature variables from K to degrees C
    exactly once and sets their ``units`` to ``degC``. ``lsm`` is left unitless.

    Raises:
        DataError: If a temperature variable's ``units`` is not ``K``.
    """
    if "valid_time" in ds.variables:
        ds = ds.rename({"valid_time": "time"})
    ds = _drop_number(ds)
    ds = _collapse_expver(ds)
    ds = ds.sortby("latitude")
    # Remapping to [-180, 180) and sorting is only safe for boxes that do not cross the
    # antimeridian; download.area_for rejects such stations.
    ds = ds.assign_coords(longitude=((ds["longitude"] + 180.0) % 360.0) - 180.0).sortby("longitude")
    out = {}
    for name, da in ds.data_vars.items():
        attrs = dict(da.attrs)
        values = da.astype(np.float64)
        if name in TEMPERATURE_VARS:
            units = attrs.get("units")
            if units != "K":
                raise DataError(f"{name} must be in K but units are {units!r}")
            values = values - KELVIN_OFFSET
            attrs["units"] = "degC"
        values.attrs = attrs
        out[name] = values
    return xr.Dataset(out, coords=ds.coords, attrs=ds.attrs)


def _drop_number(ds: xr.Dataset) -> xr.Dataset:
    """Remove the ensemble ``number`` coordinate (scalar or length-1 dimension)."""
    if "number" in ds.dims:
        ds = ds.isel(number=0)
    return ds.drop_vars("number", errors="ignore")


def _collapse_expver(ds: xr.Dataset) -> xr.Dataset:
    """Merge ``expver`` versions, preferring ERA5 (1) and filling gaps from ERA5T (5).

    ``expver`` may be a dimension, a per-time coordinate (already merged) or absent, with
    integer (1, 5) or string ("0001", "0005") labels.
    """
    if "expver" not in ds.variables:
        return ds
    if "expver" not in ds.dims:
        return ds.drop_vars("expver")
    order = sorted(range(ds.sizes["expver"]), key=lambda i: int(ds["expver"].values[i]))
    merged = ds.isel(expver=order[0])
    for i in order[1:]:
        merged = merged.combine_first(ds.isel(expver=i))
    return merged.drop_vars("expver", errors="ignore")
