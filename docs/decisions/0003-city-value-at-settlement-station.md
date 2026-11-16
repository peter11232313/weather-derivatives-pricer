# 0003 — City temperature interpolated to the settlement station

- Status: accepted
- Date: 2026-11-16
- Resolves: spec 001 open question 3

## Context
ERA5 is on a 0.25° grid (~28 km N–S, ~17 km E–W at 52° N). Contracts settle on a
specific weather station (e.g. London Heathrow), not a city centre. Options considered:
- **Nearest grid cell**: simple, but for coastal stations (Lisbon, Amsterdam) the cell
  can be mostly sea, damping winter minima.
- **3×3 mean** (~80 km box): smooth, but mixes sea, hills and countryside and blunts
  extremes.
- **Interpolation to the station's coordinates**: targets the settlement point.

The gap between ERA5 and the station is basis risk; a poor point choice makes it
systematically biased rather than just noisy.

## Decision
Each city's series is computed at its **settlement station's coordinates**:
1. Bilinearly interpolate the hourly `mx2t` / `mn2t` fields (ADR 0002) from the four
   surrounding grid cells to the station latitude/longitude, then take daily max/min.
   (Interpolating first gives a true point series; max/min do not commute with
   interpolation.)
2. If any of the four cells is sea (ERA5 land-sea mask `lsm` < 0.5), use the **nearest
   land cell** (`lsm` ≥ 0.5) instead of interpolating.
3. Record the method used per city (`bilinear` or `nearest_land`) in file metadata.

Stations live in a versioned registry (city → station name, WMO/ICAO id, latitude,
longitude), defined in the data-prep spec.

## Consequences
- The pricer still reads one series per city in the spec 001 format, from the validated
  copy in `data/pricer/<city>.parquet` (spec 002).
- Needs the static `land_sea_mask` field once per grid.
- Nearest-cell and 3×3 values may still be computed as **diagnostics** only, to monitor
  sensitivity; they are not the city value.
- A later check should measure ERA5-vs-station bias where station records exist.
- Supersedes the `era5-data` skill's "nearest grid cell AND a 3×3 mean" rule for city
  point values.
