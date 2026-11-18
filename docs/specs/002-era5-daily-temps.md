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
Stations within 0.5° of a pole or of ±180° longitude are rejected (not needed for Europe).
Every raw file's grid must span exactly this area; a file on another grid (e.g. downloaded
before the station's coordinates were corrected) is an error naming the path.

**Raw files** (`data/raw/`):
- `era5_<city>_<year>.nc`: `mx2t`, `mn2t`, every hour of the year, Y-01-01 00:00 through
  Y-12-31 23:00.
- *(Superseded by amendment B: edge-day files `era5_<city>_<YYYYMMDD>.nc`.)* `era5_<city>_<year+1>0101T00.nc`: the single step `<year+1>-01-01 00:00`, downloaded
  **only for the final year of a range** and only if `era5_<city>_<year+1>.nc` is absent.
  For every other year, the last day's 00:00 step comes from the next year's file
  (ADR 0002). Processing day D therefore reads the file containing D+1 00:00.
- `era5_lsm_<city>.nc`: land-sea mask, one time step, its own request.

**Download format (amended 2026-11-16, review option A)**: the new Copernicus service
splits netCDF output by GRIB step type (`mx2t` is "max", `mn2t` is "min"), so requests ask
for `download_format = zip`. A zip of netCDF parts is unpacked and merged into the single
raw file above; a plain netCDF response is also accepted. Before the rename, the result
must open as netCDF and contain the requested variables, otherwise the download is an
error naming the path and nothing is kept (so a bad file is never skipped later).
Since amendment A, a temperature response may cover up to 3 years; it is split into one
year file per year, each checked for every hour of its year and written by xarray
(values, coordinates, attributes and stored dtype preserved) with temp file + rename.
The land-sea mask and next-year step are still renamed into place unchanged.

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
- [x] Given the hour-alignment example, then 2024-01-01 has `tmax_c = 12.0`, `tmin_c = −3.0`, and the 2024-01-01 00:00 values go to 2023-12-31.
- [x] Given the interpolation example, then the station value is **13.0** (tolerance 1e-9) and metadata `method = bilinear`.
- [x] Given a field linear in lat and lon, then bilinear interpolation reproduces it exactly at any station inside the cell.
- [x] Given one of the four surrounding cells with non-zero bilinear weight with `lsm < 0.5` (any corner; cells with zero weight, e.g. for a station on a grid line or node, are ignored), then the nearest cell with `lsm ≥ 0.5` (distance and tie-break above) is used and `method = nearest_land`; given no land cell in the box, then an error names the city.
- [x] Given hourly fields whose daily peaks differ between cells, then Tmax is the max of the **interpolated hourly** series, not the interpolation of per-cell daily maxima (known answer in the test).
- [x] Given float64 input in kelvin, then 273.15 K → exactly 0.0 °C, converted once on load; input with `units` other than `K` is rejected.
- [x] Given overlapping `expver` 1 (ERA5) and 5 (ERA5T) values, then ERA5 is kept.
- [x] Given latitude stored north→south, then results equal those from the same data stored south→north.
- [x] Given two consecutive year files, then 31 December of the first year uses 00:00 of 1 January from the second file; given the final year, then the one-step file is used; neither is double-counted.
- [x] Given 2024-01-01..2024-12-31 (leap year), then the output has exactly **366** rows, sorted, with no gaps; given 2023, **365**.
- [x] Given a missing hourly time step inside the range (including the next day's 00:00), then an error names the missing timestamp; nothing is skipped or interpolated in time.
- [x] Given a NaN in the station's hourly series, or a daily `tmin_c > tmax_c`, then an error names the date.
- [x] The written file has columns `date` (DATE), `tmax_c`, `tmin_c` (DOUBLE), Snappy compression and all the metadata keys above with valid values (`method` bilinear/nearest_land, `source`, 12 lowercase hex `registry_version`); `registry_version` equals the SHA-256 prefix of the registry file.
- [x] Given a London station, then the download area is N 52.0, W −1.0, S 50.75, E 0.25.
- [x] Given a target raw file that already exists, then no download is made; a failed download leaves no partial file (temp file + rename); a zip of netCDF parts is merged into one file; a response that is not readable netCDF with the requested variables is an error and leaves no file.
- [x] Given a valid processed file, when published, then an identical file appears in the pricer folder via temp file + rename.
- [x] Given a processed file that fails validation (wrong schema, not Snappy, missing or invalid metadata, gaps, duplicate or unsorted dates, NaN, `tmin_c > tmax_c`, or row count ≠ days in `start..end`), when published, then an error names the problem and the pricer folder is unchanged (an existing published file is not overwritten).
- [x] Given start after end, a missing registry file, a raw file missing a variable or on another grid, or a locked target file, then a data-prep error names the problem and path (no traceback; CLI exit 1).
- [x] Given default options, then raw, processed and pricer folders resolve to `<repo>/data/raw`, `<repo>/data/processed` and `<repo>/data/pricer` regardless of the cwd.
- [x] Given a registry entry with an invalid key, out-of-range coordinates or a duplicate city, then an error names the entry.
- [x] **Contract check (manual, documented in the README):** a file published by data-prep is loaded by the Java `ParquetTemperatureLoader` (spec 001) from `data/pricer` without error, with the same row count and values.
- [x] `ruff check` and `ruff format --check` clean; public functions have docstrings with units; `pytest -q` green.

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
7. **Next-year 00:00 step** *(superseded by amendment B, decision 2)*: taken from the next year's file; one extra one-step request
   only for the final year (~36 Copernicus requests for 1991–2024 instead of ~69).
8. **Planner details accepted**: `registry_version` = SHA-256 prefix; diagnostics,
   nearest-land distance/tie-break, float64 K→°C and grid-aligned area as above.

Expected disk use (estimate): raw ERA5 ~60–100 MB for London 1991–2024, processed and
published files < 1 MB each, Python environment ~300–450 MB.

## Open questions
None.

---

## Amendment A — multi-year download requests (drafted 2026-11-18)

### Why
Each Copernicus request waits ~3 h in the queue whatever its size, and parallel requests
are rejected ("max allowed requests"). One request per year makes 1991–2024 take days.
Asking for several consecutive years per request cuts the number of queue waits.

### Scope
In:
- `download` groups the **missing** years of a range into runs of consecutive years and
  sends one request per chunk of up to `yearsPerRequest` years (default **3**). Chunks
  start at the first missing year (missing 1993..2023 → 1993–95, 1996–98, …, 2023).
- The request is today's year request with a list of years; every other key (variables,
  area, months, days, times, formats) is identical, so Copernicus's cache can serve a
  repeated request.
- The merged result is **split into one year file per year**, `era5_<city>_<Y>.nc`, each
  holding exactly that year's hours, written with temp file + atomic rename. Processing,
  file names and "skip what exists" are unchanged.
- CLI: `--years-per-request N` on `download` and `build`.

Out: retries, parallel requests, other datasets or mirrors.

### Acceptance criteria
- [x] Given years 1993..2001 missing and `yearsPerRequest = 3`, then exactly three requests are sent with `year` = [1993, 1994, 1995], [1996, 1997, 1998], [1999, 2000, 2001], and every other request key equals the single-year request's.
- [x] Given a 3-year response, then three year files are written, each with every hour of its year (8,760, or 8,784 in a leap year) and values equal to the corresponding hours of the response.
- [x] Given 1994 already present and 1993, 1995, 1996 missing, then the requests are [1993] and [1995, 1996] (only consecutive missing years are combined; existing files are never re-requested).
- [x] Given `yearsPerRequest = 1`, then the requests are exactly today's single-year requests.
- [x] Given a 3-year response in which one year lacks an hour, then the two complete years are written as year files, the incomplete year is **not** written, and a `DownloadError` names the incomplete year; a rerun requests only the missing year.
- [x] Given a response containing hours outside the requested years, then they are ignored (never written to another year's file).
- [x] Given a failed chunk, then no temporary files remain and earlier completed chunks are kept.
- [x] Given `--years-per-request` below 1 or above the maximum, then the CLI exits 2 with a message naming the option.
- [x] The final-year next 00:00 step and the land-sea mask requests are unchanged. *(The step request is superseded by amendment B: edge-day files.)*

### Decisions (2026-11-18)
1. **Chunk size**: default 3, maximum 3 (published cap ~60,000 items; one year of `mx2t` +
   `mn2t` = 17,568). Revisit only if the live 3-year test request is rejected.
2. **Partial chunks**: complete years are kept; only incomplete years are reported and
   re-requested on the next run.
3. **Completeness applies to every year file**, single-year requests included: a range
   ending in the current, unfinished year now fails at download with a clear error naming
   the first missing hour (was: downloaded unchecked).
4. **Incomplete year → carry on**: later chunks are still downloaded; one `DownloadError`
   naming every incomplete year is raised at the end. A failed request (rejected, network)
   still stops at once.
5. **Year files are rewritten by xarray** (not the raw response bytes); equal values,
   coordinates, attributes and stored dtype are required.

---

## Amendment B — per-station observation windows (approved 2026-11-18)

### Why
Spec 002 builds every day over the UTC day 00:00..24:00 (ADR 0002). That was inherited
from the project-wide "dates are UTC `LocalDate`" rule, not chosen per contract; ADR 0002
already said a non-midnight observation day "would need a separate decision". Contracts
settle on each station's own observation windows, which differ by city (all UTC, no
daylight-saving shift):

| City (station) | Tmax for day D | Tmin for day D | Source |
| --- | --- | --- | --- |
| London-Heathrow (WMO 03772) | D 08:50 → D+1 08:50 | D−1 08:50 → D 08:50 | Met Office |
| Amsterdam-Schiphol (WMO 06240) | D 00:00 → D+1 00:00 | same | KNMI |
| Paris-Orly (WMO 07149) | D 06:00 → D+1 06:00 | D−1 18:00 → D 18:00 | Météo-France |
| Essen (WMO 10410) | D 00:00 → D+1 00:00 | same | DWD |

London and Paris do not match 00:00..24:00 today.

### Scope
In:
- **Registry**: two required fields per station, whole hours, UTC, each the start of a
  24-hour window relative to D 00:00:

  ```toml
  tmax_window_start_utc_h = 9     # Tmax(D) = max over D 09:00 .. D+1 09:00
  tmin_window_start_utc_h = -15   # Tmin(D) = min over D-1 09:00 .. D 09:00
  ```

  Rules: integers (not bool, not float) in −23..23; the two windows must overlap or touch,
  |tmax − tmin| ≤ 24; a missing or invalid field is an error naming the entry.
  The four cities above: London 9 / −15, Paris 6 / −6, Amsterdam 0 / 0, Essen 0 / 0.
  Only London is added to `stations.toml` now (the others need confirmed coordinates).
- **Daily step**: a window starting at D 00:00 + s uses the `mx2t`/`mn2t` values stamped
  **D+s+1h .. D+s+24h** (each value covers the hour before its stamp, ADR 0002). Tmax(D)
  = max of `mx2t` over the Tmax window; Tmin(D) = min of `mn2t` over the Tmin window.
  Offsets 0 / 0 reproduce today's D 01:00..D+1 00:00 exactly. The diagnostics use the
  same windows.
- **Edge-day files** replace the one-step file. Processing `start..end` needs stamps from
  `start + min(s) + 1h` to `end + 1 day + max(s)`, i.e. at most one day before `start` and
  one day after `end`. When such a day falls in a year whose year file is absent, it comes
  from `era5_<city>_<YYYYMMDD>.nc`: every hour of that one day (00:00..23:00), its own
  request, skip-if-present, temp file + rename, checked for all 24 hours. Every hour is
  still read from exactly one file (year file preferred). The old
  `era5_<city>_<YYYY>0101T00.nc` is no longer requested or read.
- **Metadata**: processed and published files gain `tmax_window_start_utc_h` and
  `tmin_window_start_utc_h`; `publish` rejects them missing or invalid. The `date`
  column now means "settlement day D" under those windows.
- **ADR 0005** (new): per-station observation windows; supersedes ADR 0002's
  hour-alignment section (the `mx2t`/`mn2t` choice stays).

Out: adding Paris, Amsterdam or Essen to the registry; sub-hour windows; local-time or
DST windows; ERA5-vs-station bias.

### Worked example (London, offsets 9 / −15), one station value per hour, °C

| valid time (UTC) | mx2t | mn2t |
| --- | --- | --- |
| 2024-01-15 09:00 | 14.0 | 2.0 |
| 2024-01-15 20:00 | 8.0 | −1.0 |
| 2024-01-16 09:00 | 11.0 | −4.0 |
| every other hour | 8.0 | 3.0 |

→ 2024-01-15: `tmax_c = 11.0` (stamps 01-15 10:00..01-16 09:00), `tmin_c = 2.0` (stamps
01-14 10:00..01-15 09:00). The 00:00..24:00 window would give 14.0 / −1.0.
→ 2024-01-14: `tmax_c = 14.0` (the 01-15 09:00 stamp ends its Tmax window).
→ 2024-01-16: `tmin_c = −4.0` (window stamps 01-15 10:00..01-16 09:00).

### Acceptance criteria
- [ ] Given the London worked example, then 2024-01-15 has `tmax_c = 11.0`, `tmin_c = 2.0`; 2024-01-14 has `tmax_c = 14.0`; 2024-01-16 has `tmin_c = −4.0`.
- [ ] Given offsets 6 / −6 (Paris), then Tmax(D) uses exactly the stamps D 07:00..D+1 06:00 and Tmin(D) exactly D−1 19:00..D 18:00 (a value just outside each window does not count; known answer in the test).
- [ ] Given offsets 0 / 0, then every existing spec 002 criterion still holds, including the hour-alignment example (2024-01-01 → 12.0 / −3.0).
- [ ] Given a registry entry with a window field missing, a bool or float, a value outside −23..23, or |tmax − tmin| > 24, then an error names the entry and the field.
- [ ] Given London 1991-01-01..1991-12-31 with year files 1991 and 1992 present, then Tmin(1991-01-01) reads 1990-12-31 10:00..23:00 from the edge-day file `era5_london_19901231.nc` and Tmax(1991-12-31) reads 1992-01-01 00:00..09:00 from the 1992 year file; no hour is read twice.
- [ ] Given a missing hourly step anywhere in the needed span (including edge-day hours), then an error names the missing timestamp.
- [ ] Given `download` for London 1991..2024 with every year file present, then exactly two temperature requests are sent: 1990-12-31 and 2025-01-01, each one day with all 24 times and otherwise the same keys as a year request; given a 0 / 0 station, no previous-day request is sent; no day is requested when its year file exists; no `…0101T00` file is requested.
- [ ] Given an edge-day response missing an hour, then a `DownloadError` names the day and no file is left.
- [ ] Given a processed file, then its metadata has `tmax_window_start_utc_h` and `tmin_window_start_utc_h` equal to the registry; `publish` rejects a file with either missing or not an integer in −23..23; the diagnostics use the same windows (known answer in the test).
- [ ] `stations.toml` London has `tmax_window_start_utc_h = 9`, `tmin_window_start_utc_h = -15`.
- [ ] **Manual:** London reprocessed with the new windows over the years already downloaded; monthly HDD (base 18 °C) old vs new reported in the PR and the development log; the Java contract check rerun on the new file.
- [ ] `ruff check`, `ruff format --check` clean; docstrings with units; `pytest -q` green.

### Decisions (approved 2026-11-18)
1. **08:50 → 09:00.** ERA5 extremes are per whole hour, so a window can only start on the
   hour; 09:00 is nearest and is the Met Office climatological day (09–09 UTC).
2. **Edge-day files, whole day**: one request per edge day (~3 h queue each); two for the
   full London range (1990-12-31, 2025-01-01). The existing `era5_london_20250101T00.nc`
   becomes unused and can be deleted by hand.
3. **Window fields required, no default**: a silent 00:00 default is how the mismatch
   happened.
4. **Pairing**: Tmax(D) is the window starting on D, Tmin(D) the window ending on D, as in
   the table; HDD still uses `(Tmax + Tmin) / 2` per settlement day.
5. **London only** in `stations.toml`; the other three rows are reference for later.

### Open questions
None.
