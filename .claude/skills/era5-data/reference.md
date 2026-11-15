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

Daily Tmax/Tmin: take max/min of hourly `t2m` over the UTC day (00:00–23:00),
unless a spec says to use `mx2t`/`mn2t`. Record the choice in metadata.

## Request template (`reanalysis-era5-single-levels`)

```python
import cdsapi

c = cdsapi.Client()  # reads ~/.cdsapirc — never commit it
c.retrieve(
    "reanalysis-era5-single-levels",
    {
        "product_type": ["reanalysis"],
        "variable": ["2m_temperature"],
        "year": ["2024"],
        "month": [f"{m:02d}" for m in range(1, 13)],
        "day": [f"{d:02d}" for d in range(1, 32)],
        "time": [f"{h:02d}:00" for h in range(24)],
        "area": [52.0, -0.6, 51.0, 0.4],  # N, W, S, E — London box
        "data_format": "netcdf",
        "download_format": "unarchived",
    },
    "data/raw/era5_t2m_london_2024.nc.tmp",
)
# then os.replace(tmp, final) — atomic rename
```

## Known quirks
- New CDS (2024+) uses `valid_time` instead of `time`, and may add `number`/`expver` coords.
- Last ~3 months are ERA5T (`expver=5`); final ERA5 (`expver=1`) replaces it later.
- Latitude comes north→south; sort ascending before slicing.
- Grid is 0.25°; a city point is the nearest cell, cross-check against the 3×3 mean.
