# 002 — ERA5 → daily station temperatures (data-prep)

## Why
The pricer (spec 001) needs one daily Tmax/Tmin Parquet file per city, but nothing
produces it. data-prep must turn ERA5 into one daily series per settlement station that
matches what HDD contracts settle on, as decided in ADR 0002 and ADR 0003.

## Scope
In:
- **Station registry** `data-prep/stations.toml` (versioned): one entry per city.
- **Download** ERA5 `mx2t`, `mn2t` (hourly) and `lsm` (static) per city box and year via
  `cdsapi`, following the `era5-data` skill (skip if present, temp file + atomic rename).
- **Process**: load → normalise (`valid_time`→`time`, collapse `expver` preferring ERA5,
  latitude ascending, K→°C) → interpolate hourly fields to the station (ADR 0003) →
  daily Tmax/Tmin over UTC days with the D 01:00..D+1 00:00 window (ADR 0002) → write.
- **Publish**: validate a processed file, then copy it to the folder the pricer reads.
- **CLI** (`python -m dataprep`): `download`, `process`, `publish`, and `build` (all three),
  e.g. `build --city london --start 1991-01-01 --end 2024-12-31`.

Out (later specs):
- Station observations and ERA5-vs-station bias analysis.
- Detrending, CDD, any pricing. Hourly or forecast data. Scheduling/automation.
- Physical range check (−90..60 °C): Java loader only, as a separate spec 001 amendment.

## Folders
Three separate folders, all under the repo's git-ignored `data/` (never committed):

| folder | written by | read by | content |
| --- | --- | --- | --- |
| `data/raw/` | `download` | `process` | ERA5 netCDF (hook-protected from agent edits) |
| `data/processed/` | `process` | `publish` | Python output, staging; may be invalid or partial |
| `data/pricer/` | `publish` only | Java `ParquetTemperatureLoader` | validated files only |

Each is a CLI option (`--raw-dir`, `--processed-dir`, `--pricer-dir`) defaulting to the
paths above, resolved from the repo root (the parent of `data-prep/`), not the cwd.
The pricer is pointed at `data/pricer` (README, spec 001 usage). The Java loader already
takes its folder as a constructor argument, so no Java code changes.

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
[−180, 180]; missing or duplicate keys are errors. `registry_version` = first 12 hex
characters of the SHA-256 of the file's bytes.

**Download area**: station ± 0.5°, widened outward to 0.25° grid lines (no Copernicus
regridding; room for the 3×3 diagnostic). London: N 52.0, W −1.0, S 50.75, E 0.25.

**Raw files** (`data/raw/`):
- `era5_<city>_<year>.nc`: `mx2t`, `mn2t`, every hour of the year, Y-01-01 00:00 through
  Y-12-31 23:00.
- `era5_<city>_<year+1>0101T00.nc`: the single step `<year+1>-01-01 00:00`, downloaded
  **only for the final year of a range** and only if `era5_<city>_<year+1>.nc` is absent.
  For every other year, the last day's 00:00 step comes from the next year's file
  (ADR 0002). Processing day D therefore reads the file containing D+1 00:00.
- `era5_lsm_<city>.nc`: land-sea mask, one time step, its own request (mixing it with
  hourly fields can make Copernicus return a zip). A zip response is an error.

**Processed / published file** (`<city>.parquet`, spec 001 contract):

| column | Parquet type | units |
| --- | --- | --- |
| `date` | DATE | UTC calendar day |
| `tmax_c` | DOUBLE | °C |
| `tmin_c` | DOUBLE | °C |

Snappy compression; one row per day, sorted, no gaps. File key-value metadata:
`source=ERA5 mx2t/mn2t`, `method=bilinear|nearest_land`, `station`, `lat`, `lon`,
`start`, `end` (ISO dates), `registry_version`, `created_utc`, and the diagnostics
`tmean_nearest_c_mean` and `tmean_3x3_c_mean`.

**Diagnostics** (ADR 0003; never the city value): the same D 01:00..D+1 00:00 daily
max/min method applied to (a) the nearest grid cell's hourly series and (b) the 3×3
spatial mean (centred on the nearest cell) of the hourly fields; each recorded as the
period mean of daily `(tmax + tmin) / 2`.

**Nearest land cell** (ADR 0003 fallback): distance = √((Δlon·cos φ_station)² + Δlat²)
over all cells in the downloaded box with `lsm ≥ 0.5`; ties go to the lower latitude,
then the lower longitude.

**Units**: values are cast to float64 before K→°C (`− 273.15`), converted once; any
`units` attribute other than `K` on input is an error.

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
Tests run offline: the CDS client is a fake passed in by the test, inputs are small
synthetic xarray datasets built in the test, and all paths are under pytest's `tmp_path`.
- [ ] Given the hour-alignment example, then 2024-01-01 has `tmax_c = 12.0`, `tmin_c = −3.0`, and the 2024-01-01 00:00 values go to 2023-12-31.
- [ ] Given the interpolation example, then the station value is **13.0** (tolerance 1e-9) and metadata `method = bilinear`.
- [ ] Given a field linear in lat and lon, then bilinear interpolation reproduces it exactly at any station inside the cell.
- [ ] Given one of the four surrounding cells with `lsm < 0.5`, then the nearest cell with `lsm ≥ 0.5` (distance and tie-break above) is used and `method = nearest_land`; given no land cell in the box, then an error names the city.
- [ ] Given hourly fields whose daily peaks differ between cells, then Tmax is the max of the **interpolated hourly** series, not the interpolation of per-cell daily maxima (known answer in the test).
- [ ] Given float64 input in kelvin, then 273.15 K → exactly 0.0 °C, converted once on load; input with `units` other than `K` is rejected.
- [ ] Given overlapping `expver` 1 (ERA5) and 5 (ERA5T) values, then ERA5 is kept.
- [ ] Given latitude stored north→south, then results equal those from the same data stored south→north.
- [ ] Given two consecutive year files, then 31 December of the first year uses 00:00 of 1 January from the second file; given the final year, then the one-step file is used; neither is double-counted.
- [ ] Given 2024-01-01..2024-12-31 (leap year), then the output has exactly **366** rows, sorted, with no gaps; given 2023, **365**.
- [ ] Given a missing hourly time step inside the range (including the next day's 00:00), then an error names the missing timestamp; nothing is skipped or interpolated in time.
- [ ] Given a NaN in the station's hourly series, or a daily `tmin_c > tmax_c`, then an error names the date.
- [ ] The written file has columns `date` (DATE), `tmax_c`, `tmin_c` (DOUBLE), Snappy compression and all the metadata keys above; `registry_version` equals the SHA-256 prefix of the registry file.
- [ ] Given a London station, then the download area is N 52.0, W −1.0, S 50.75, E 0.25.
- [ ] Given a target raw file that already exists, then no download is made; a failed download leaves no partial file (temp file + rename); a zip response is an error.
- [ ] Given a valid processed file, when published, then an identical file appears in the pricer folder via temp file + rename.
- [ ] Given a processed file that fails validation (wrong schema, not Snappy, missing metadata, gaps, duplicate or unsorted dates, NaN, `tmin_c > tmax_c`, or row count ≠ days in `start..end`), when published, then an error names the problem and the pricer folder is unchanged (an existing published file is not overwritten).
- [ ] Given default options, then raw, processed and pricer folders resolve to `<repo>/data/raw`, `<repo>/data/processed` and `<repo>/data/pricer` regardless of the cwd.
- [ ] Given a registry entry with an invalid key, out-of-range coordinates or a duplicate city, then an error names the entry.
- [ ] **Contract check (manual, documented in the README):** a file published by data-prep is loaded by the Java `ParquetTemperatureLoader` (spec 001) from `data/pricer` without error, with the same row count and values.
- [ ] `ruff check` and `ruff format --check` clean; public functions have docstrings with units; `pytest -q` green.

## Decisions (approved 2026-11-16)
1. **Cities**: London Heathrow only for v1 (WMO 03772, EGLL, 51.479, −0.449).
2. **Date range**: 1991-01-01..2024-12-31 (34 winters).
3. **Physical range check (−90..60 °C)**: not in data-prep. It belongs in the Java loader
   only — a separate spec 001 amendment, not part of this spec.
4. **Contract check**: manual, documented in the README; no cross-language CI job.
5. **Dependencies**: `cdsapi`, `xarray`, `netCDF4`, `numpy`, `pyarrow`; dev: `pytest`,
   `ruff`. Layout: `data-prep/pyproject.toml`, src layout, package `dataprep`.
6. **Folders**: raw, processed and pricer folders are separate; only `publish` writes to
   the pricer folder, after validation.
7. **Next-year 00:00 step**: taken from the next year's file; one extra one-step request
   only for the final year (~36 Copernicus requests for 1991–2024 instead of ~69).
8. **Planner details accepted**: `registry_version` = SHA-256 prefix; diagnostics,
   nearest-land distance/tie-break, float64 K→°C and grid-aligned area as above.

Expected disk use (estimate): raw ERA5 ~60–100 MB for London 1991–2024, processed and
published files < 1 MB each, Python environment ~300–450 MB.

## Open questions
None.
