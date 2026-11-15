# ERA5 reference

## Variables used in this portfolio

| Short name | CDS long name | Units (raw) | Convert to | Notes |
| --- | --- | --- | --- | --- |
| `t2m` | `2m_temperature` | K | °C (− 273.15) | Instantaneous, hourly |
| `mx2t` | `maximum_2m_temperature_since_previous_post_processing` | K | °C | Max over the preceding hour |
| `mn2t` | `minimum_2m_temperature_since_previous_post_processing` | K | °C | Min over the preceding hour |
| `u100` / `v100` | `100m_u/v_component_of_wind` | m/s | speed = √(u²+v²) | Wind farms (forecaster) |
| `u10` / `v10` | `10m_u/v_component_of_wind` | m/s | speed = √(u²+v²) | |
| `ssrd` | `surface_solar_radiation_downwards` | J/m² (hourly accum.) | W/m² (÷ 3600) | Solar (forecaster) |
| `tp` | `total_precipitation` | m (hourly accum.) | mm (× 1000) | |
| `lsm` | `land_sea_mask` | 0–1 fraction | — | Static; land if ≥ 0.5 (ADR 0003) |

Daily Tmax/Tmin (ADR 0002): max of hourly `mx2t`, min of hourly `mn2t`. Each value
stamped `HH:00` covers the preceding hour, so UTC day D uses the values stamped
**D 01:00 through D+1 00:00** — not 00:00–23:00. Record the source in metadata.

## Request template (`reanalysis-era5-single-levels`)

```python
import cdsapi

c = cdsapi.Client()  # reads ~/.cdsapirc — never commit it
c.retrieve(
    "reanalysis-era5-single-levels",
    {
        "product_type": ["reanalysis"],
        "variable": [
            "maximum_2m_temperature_since_previous_post_processing",
            "minimum_2m_temperature_since_previous_post_processing",
            "land_sea_mask",  # static; one time step is enough
        ],
        "year": ["2024"],
        "month": [f"{m:02d}" for m in range(1, 13)],
        "day": [f"{d:02d}" for d in range(1, 32)],
        "time": [f"{h:02d}:00" for h in range(24)],
        "area": [52.0, -0.6, 51.0, 0.4],  # N, W, S, E — box around the station, not just the city
        "data_format": "netcdf",
        "download_format": "unarchived",
    },
    "data/raw/era5_mx2t_mn2t_london_2024.nc.tmp",
)
# then os.replace(tmp, final) — atomic rename
# Day 2024-12-31 also needs 2025-01-01 00:00 (ADR 0002): fetch it with the next year's
# request or a one-step request, and join before computing daily max/min.
```

## Known quirks
- New CDS (2024+) uses `valid_time` instead of `time`, and may add `number`/`expver` coords.
- Last ~3 months are ERA5T (`expver=5`); final ERA5 (`expver=1`) replaces it later.
- Latitude comes north→south; sort ascending before slicing.
- Grid is 0.25°; the city value is interpolated to the settlement station (ADR 0003).
  Nearest cell and 3×3 mean are diagnostics only.
