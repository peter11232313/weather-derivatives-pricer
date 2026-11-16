"""Open and normalise ERA5 netCDF files (``era5-data`` skill, spec 002)."""

from pathlib import Path

import xarray as xr

KELVIN_OFFSET = 273.15


def open_era5(path: Path) -> xr.Dataset:
    """Open an ERA5 netCDF file, decoding packed values.

    Raises:
        DataError: If the file cannot be read; the message names the path.
    """
    raise NotImplementedError


def normalise(ds: xr.Dataset) -> xr.Dataset:
    """Normalise an ERA5 dataset.

    Renames ``valid_time`` to ``time``; collapses ``expver`` preferring ERA5 (1) over
    ERA5T (5); drops ``number``; sorts latitude ascending; maps longitude to
    [-180, 180); casts to float64; converts temperature variables from K to degrees C
    exactly once and sets their ``units`` to ``degC``. ``lsm`` is left unitless.

    Raises:
        DataError: If a temperature variable's ``units`` is not ``K``.
    """
    raise NotImplementedError
