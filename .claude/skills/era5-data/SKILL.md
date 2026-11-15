---
name: era5-data
description: How to download, load and sanity-check ERA5 data from the Copernicus CDS. Use whenever code reads or fetches ERA5/reanalysis data.
---
# ERA5 data rules
- Download via `cdsapi`; skip if the target exists; write to a temp file then rename (atomic).
- On load: rename `valid_time`→`time`; collapse `expver` (prefer ERA5 over ERA5T);
  sort latitude ascending; convert K→°C at the boundary, never mid-pipeline.
- Accumulated fields (ssrd, tp) are per hour, in J/m² or m. Divide by 3600 for W/m².
- City point values: nearest grid cell AND a 3×3 mean; record which in metadata.
- Sanity checks after every load: no NaNs in range, plausible min/max, monotonic time.
- See `reference.md` for variable short names and the request template.
