# 002 — ERA5 → daily station temperatures (data-prep)

## Why
The pricer (spec 001) reads `data/processed/<city>.parquet`, but nothing produces it.
data-prep must turn ERA5 into one daily Tmax/Tmin series per settlement station that
matches what HDD contracts settle on, as decided in ADR 0002 and ADR 0003.

## Scope
In:
- **Station registry** `data-prep/stations.toml` (versioned): one entry per city.
- **Download** ERA5 `mx2t`, `mn2t` (hourly) and `lsm` (static) per city box and year via
  `cdsapi`, following the `era5-data` skill (skip if present, temp file + atomic rename).
- **Process**: load → normalise (`valid_time`→`time`, collapse `expver` preferring ERA5,
  latitude ascending, K→°C) → interpolate hourly fields to the station (ADR 0003) →
  daily Tmax/Tmin over UTC days with the D 01:00..D+1 00:00 window (ADR 0002).
- **Write** `data/processed/<city>.parquet` in the spec 001 schema, Snappy-compressed.
- **CLI**: `python -m dataprep build --city london --start 1991-01-01 --end 2024-12-31`
  (`download` and `process` also runnable separately).

Out (later specs):
- Station observations and ERA5-vs-station bias analysis.
- Detrending, CDD, any pricing. Hourly or forecast data. Scheduling/automation.

## Inputs & outputs

**Station registry** (`data-prep/stations.toml`):

```toml
[london]
station = "London Heathrow"
wmo_id = "03772"
icao = "EGLL"
lat = 51.479      # decimal degrees, WGS84 — confirm against the contract's station
lon = -0.449
```

Rules: city key matches `[a-z0-9_-]+` (spec 001 loader); lat in [−90, 90], lon in
[−180, 180]; missing or duplicate keys are errors.

**Download** (raw, never committed; `data/raw/` is hook-protected and git-ignored):
`data/raw/era5_<city>_<year>.nc` holding `mx2t`, `mn2t` for the whole year **plus
00:00 of 1 January of the next year** (ADR 0002), and `data/raw/era5_lsm_<city>.nc`.
Area = station ± 0.5° (N, W, S, E), enough for the four surrounding 0.25° cells.

**Output** (`data/processed/<city>.parquet`, spec 001 contract):

| column | Parquet type | units |
| --- | --- | --- |
| `date` | DATE | UTC calendar day |
| `tmax_c` | DOUBLE | °C |
| `tmin_c` | DOUBLE | °C |

Snappy compression; one row per day, sorted, no gaps. File key-value metadata:
`source=ERA5 mx2t/mn2t`, `method=bilinear|nearest_land`, `station`, `lat`, `lon`,
`registry_version`, `created_utc`, and the diagnostics `tmean_nearest_c_mean` and
`tmean_3x3_c_mean` (period means; diagnostics only, ADR 0003).

**Worked example (hour alignment, ADR 0002)**, one station value per hour, °C after
interpolation:

| valid time (UTC) | mx2t | mn2t | belongs to day |
| --- | --- | --- | --- |
| 2024-01-01 00:00 | 9.0 | 4.0 | 2023-12-31 |
| 2024-01-01 01:00 … 23:00 | 5.0 | 1.0 | 2024-01-01 |
| 2024-01-02 00:00 | 12.0 | −3.0 | 2024-01-01 |

→ 2024-01-01: `tmax_c = 12.0`, `tmin_c = −3.0` (not 9.0 / 1.0, which a 00:00–23:00
window would give).

**Worked example (interpolation, ADR 0003)**: cells at lat 51.25/51.50 and lon
−0.50/−0.25, all land, values 10, 12 (lat 51.25) and 14, 16 (lat 51.50) for lon −0.50/−0.25;
station at (51.375, −0.375) → bilinear value **13.0**.

## Acceptance criteria
Tests run offline: the CDS client is mocked and inputs are small synthetic xarray
datasets built in the test.
- [ ] Given the hour-alignment example, then 2024-01-01 has `tmax_c = 12.0`, `tmin_c = −3.0`, and the 2024-01-01 00:00 values go to 2023-12-31.
- [ ] Given the interpolation example, then the station value is **13.0** (tolerance 1e-9) and metadata `method = bilinear`.
- [ ] Given a field linear in lat and lon, then bilinear interpolation reproduces it exactly at any station inside the cell.
- [ ] Given one of the four surrounding cells with `lsm < 0.5`, then the nearest cell with `lsm ≥ 0.5` is used and `method = nearest_land`; given no land cell in the box, then an error names the city.
- [ ] Given hourly fields whose daily peaks differ between cells, then Tmax is the max of the **interpolated hourly** series, not the interpolation of per-cell daily maxima (known answer in the test).
- [ ] Given input in kelvin, then 273.15 K → 0.0 °C, converted once on load.
- [ ] Given overlapping `expver` 1 (ERA5) and 5 (ERA5T) values, then ERA5 is kept.
- [ ] Given latitude stored north→south, then results equal those from the same data stored south→north.
- [ ] Given 2024-01-01..2024-12-31 (leap year), then the output has exactly **366** rows, sorted, with no gaps; given 2023, **365**.
- [ ] Given a missing hourly time step inside the range (including the next day's 00:00), then an error names the missing timestamp; nothing is skipped or interpolated in time.
- [ ] Given a NaN in the station's hourly series, or a daily `tmin_c > tmax_c`, then an error names the date.
- [ ] The written file has columns `date` (DATE), `tmax_c`, `tmin_c` (DOUBLE), Snappy compression and the metadata keys above.
- [ ] **Contract check (manual, documented in the README):** a file written by data-prep is loaded by the Java `ParquetTemperatureLoader` (spec 001) without error, with the same row count and values.
- [ ] Given a target raw file that already exists, then no download is made; a failed download leaves no partial file (temp file + rename).
- [ ] Given a registry entry with an invalid key, out-of-range coordinates or a duplicate city, then an error names the entry.
- [ ] `ruff check` and `ruff format --check` clean; public functions have docstrings with units; `pytest -q` green.

## Decisions (approved 2026-11-16)
1. **Cities**: London Heathrow only for v1 (WMO 03772, EGLL, 51.479, −0.449).
2. **Date range**: 1991-01-01..2024-12-31 (34 winters).
3. **Physical range check (−90..60 °C)**: not in data-prep. It belongs in the Java loader
   only — a separate spec 001 amendment, not part of this spec.
4. **Contract check**: manual, documented in the README; no cross-language CI job.
5. **Dependencies**: `cdsapi`, `xarray`, `netCDF4`, `numpy`, `pyarrow`; dev: `pytest`,
   `ruff`. Layout: `data-prep/pyproject.toml`, package `dataprep`.

Expected disk use (estimate): raw ERA5 ~60–100 MB for London 1991–2024, processed file
< 1 MB, Python environment ~300–450 MB.

## Open questions
None.
