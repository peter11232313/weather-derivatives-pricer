---
name: era5-data
description: How to download, load and sanity-check ERA5 data from the Copernicus CDS. Use whenever code reads or fetches ERA5/reanalysis data.
---
# ERA5 data rules
- Download via `cdsapi`; skip if the target exists; write to a temp file then rename (atomic).
- On load: rename `valid_time`→`time`; collapse `expver` (prefer ERA5 over ERA5T);
  sort latitude ascending; convert K→°C at the boundary, never mid-pipeline.
- Accumulated fields (ssrd, tp) are per hour, in J/m² or m. Divide by 3600 for W/m².
- HDD/CDD settlement series (details in `docs/decisions/0002-*` and `0003-*`):
  - Daily Tmax/Tmin from `mx2t`/`mn2t`; UTC day D = values stamped D 01:00..D+1 00:00.
  - Interpolate hourly fields bilinearly to the settlement station's coordinates, then take
    daily max/min; if any of the 4 surrounding cells is sea (`lsm` < 0.5), use the nearest
    land cell. Record the method (`bilinear` / `nearest_land`) in metadata.
  - Nearest cell and 3×3 mean are diagnostics only, never the city value.
  - If the station registry doesn't exist yet, stop and ask for the data-prep spec.
- Sanity checks after every load: no NaNs in range, plausible min/max, monotonic time.
- See `reference.md` for variable short names and the request template.
