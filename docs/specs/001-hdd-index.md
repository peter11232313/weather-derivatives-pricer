# 001 — HDD index

## Why
Every HDD contract settles on a cumulative Heating Degree Day index. The pricer
needs one tested, unit-safe way to compute it from daily city temperatures
before any burn analysis or pricing model can be built on top.

## Scope
In:
- Daily HDD from daily Tmax/Tmin: `meanTempC = (tmaxC + tminC) / 2`, `hddDegC = max(0, baseC − meanTempC)`.
- Cumulative HDD over an inclusive `LocalDate` accrual period (UTC days).
- Configurable base temperature, default **18.0 °C** (European exchange convention).
- Loading daily temperatures for one city from `data/processed/<city>.parquet`.
  (Since spec 002 the pricer reads the validated copy in `data/pricer/`; the loader takes
  its folder as a constructor argument, so the code is unchanged.)

Out (later specs):
- CDD index, detrending, burn analysis, OU simulation, payoffs, REST endpoint.
- Rounding to exchange settlement precision (open question below).

## Inputs & outputs
Input row (Parquet, one per city per UTC day):

| column | type | units |
| --- | --- | --- |
| `date` | date | UTC calendar day |
| `tmax_c` | float64 (float32 accepted, widened exactly) | °C |
| `tmin_c` | float64 (float32 accepted, widened exactly) | °C |

float32 → float64 widening is lossless: the loaded double equals the stored float32 value
exactly (e.g. `0.1f` loads as `0.10000000149011612`, not `0.1`). Any precision loss
happened when the file was written; it is ≤ ~1e-6 °C per day, negligible for settlement.

Supported Parquet compression: Snappy (pyarrow/DuckDB default), gzip, uncompressed.
zstd (polars' default), LZ4 and Brotli are **not** supported; data-prep must write Snappy
explicitly. An unreadable file fails with a `TemperatureDataException` naming the path.

Java API (package `...index`):

```java
double dailyHddDegC(double tmaxC, double tminC, double baseC);
double cumulativeHddDegC(DailyTemps temps, LocalDate start, LocalDate endInclusive, double baseC);
```

Example (base 18 °C):

| date | tmaxC | tminC | meanTempC | hddDegC |
| --- | --- | --- | --- | --- |
| 2024-01-01 | 10.0 | 2.0 | 6.0 | 12.0 |
| 2024-01-02 | 12.0 | 4.0 | 8.0 | 10.0 |
| 2024-01-03 | 20.0 | 18.0 | 19.0 | 0.0 |
| 2024-01-04 | 9.0 | −1.0 | 4.0 | 14.0 |
| 2024-01-05 | 15.0 | 5.0 | 10.0 | 8.0 |
| **total** | | | | **44.0** |

Full-month example (base 18 °C): January 2024, all 31 days with `tmaxC = 14.0`,
`tminC = 6.0` → `meanTempC = 10.0` → `hddDegC = 18 − 10 = 8.0` per day →
cumulative **31 × 8.0 = 248.0**. (Note: the 10 °C is the daily mean, not the
daily HDD; 31 × 10 = 310 would be wrong.)

## Acceptance criteria
- [x] Given the 5-day example above with base 18 °C, when cumulative HDD is computed for 2024-01-01..2024-01-05, then it equals **44.0** (tolerance 1e-9).
- [x] Given 2024-01-01..2024-01-31 with every day `tmaxC = 14.0`, `tminC = 6.0` (mean 10.0 °C) and base 18 °C, when cumulative HDD is computed, then it equals **248.0** (31 days × 8.0; tolerance 1e-9).
- [x] Given a day with mean T above the base (2024-01-03, mean 19.0), then its daily HDD is **0.0**, never negative.
- [x] Given a day with mean T exactly equal to the base, then daily HDD is 0.0.
- [x] Given base 15.5 °C and the same 5 days, then cumulative HDD is **34.0** (9.5 + 7.5 + 0 + 11.5 + 5.5).
- [x] Given a winter season 2023-11-01..2024-03-31 (leap year), then exactly **152** days are accrued; for 2022-11-01..2023-03-31, exactly **151**.
- [x] Given start == end, then exactly one day is accrued (period is inclusive).
- [x] Given start after end, then an `IllegalArgumentException` is thrown.
- [x] Given a missing day inside the period, then an exception names the missing date (no silent skip, no interpolation).
- [x] Given a non-finite (NaN or ±∞) `tmaxC` or `tminC`, then an exception names the date; a non-finite base temperature is rejected with `IllegalArgumentException`.
- [x] Given `tminC > tmaxC`, then an exception names the date.
- [x] Given a Parquet file with columns `date` (date), `tmax_c`, `tmin_c` (float64), when loaded for a city, then each row becomes one `DailyTemps` entry keyed by `LocalDate` and the stored °C values are unchanged (round-trip test on a 3-row fixture written by the test itself, not a committed data file).
- [x] Given `tmax_c`/`tmin_c` stored as float32, when loaded, then each value equals `(double)` of the stored float exactly (e.g. `0.1f` → `0.10000000149011612`); any type other than float32/float64 (including integer and decimal) is rejected naming the column.
- [x] Given a Parquet file missing a required column, then an exception names the column.
- [x] Given duplicate dates in the file, then an exception names the date.
- [x] Given `data/processed/<city>.parquet` does not exist, then an exception names the path.
- [x] Unit names follow AGENTS.md (`tempC`, `hddDegC`); public methods have Javadoc.

## Open questions
None open. Resolved 2026-11-16:
- **Rounding:** the index engine stays at full precision. Settlement rounding (e.g. daily HDD to 0.1 °C·day) is a contract term, specified per contract in the payoff spec.
- **Daily Tmax/Tmin source:** ERA5 `mx2t`/`mn2t`, UTC day D = values stamped D 01:00..D+1 00:00 — [ADR 0002](../decisions/0002-daily-tmax-tmin-source.md).
- **City value:** interpolated to each settlement station's coordinates (nearest land cell for coastal stations) — [ADR 0003](../decisions/0003-city-value-at-settlement-station.md).

## Amendment A — physical range check (approved 2026-11-20)

### Why
The loader rejects nulls, non-finite values and `tminC > tmaxC`, but a corrupt or
wrong-unit file still loads: values left in kelvin (283.15 / 275.15) give daily HDD
`max(0, 18 − 279.15) = 0`, a silent season index of 0. Implements spec 002 decision 3
(range check in the Java loader only).

### Scope
In:
- `ParquetTemperatureLoader` rejects any `tmax_c` or `tmin_c` outside **−90.0..60.0 °C
  inclusive** (records: −89.2 °C, 56.7 °C). Limits are named constants in °C.
- The error is a `TemperatureDataException` naming the date, column, value and file.

Out:
- `DailyTemp` / `HddIndex` unchanged: in-memory temperatures (e.g. tests) have no range limit.
- Per-station or seasonal plausibility checks; any Python change.

### Acceptance criteria
- [x] Given a file with `tmax_c = 60.0` and `tmin_c = −90.0`, when loaded, then it loads (limits inclusive).
- [x] Given `tmax_c = 60.000001` on 2024-01-02, when loaded, then a `TemperatureDataException` names `2024-01-02`, `tmax_c`, the value and the file path.
- [x] Given `tmin_c = −90.5`, when loaded, then the error names `tmin_c`.
- [x] Given a 3-day file still in kelvin (283.15 / 275.15), when loaded, then it is rejected (instead of a silent HDD of 0.0).
- [x] Given float32 columns, then the check applies after widening: `60.0f` loads, `60.5f` fails.
- [x] Given NaN or ±∞, then the existing "non-finite" error is raised, not a range error.
- [x] Given `tmax_c = 10.0` and a kelvin `tmin_c = 275.15`, then the range error naming `tmin_c` is reported, not `tminC > tmaxC` (range check runs first); a kelvin `tmax_c = 283.15` with `tmin_c = 5.0` gives the range error naming `tmax_c`.
- [x] Given one non-finite and one out-of-range value in the same row (e.g. +∞ / 500.0), then the non-finite error wins; values are range-checked only when both are finite, Tmax before Tmin.
- [x] All existing Java tests green; `load()` Javadoc lists the new failure; README, CHANGELOG, PROGRESS and the development log updated.

### Decision
Check in the loader only, not in `DailyTemp` (spec 002 decision 3; owner OK 2026-11-20).
Planner findings (owner OK 2026-11-20): a plain `v < MIN || v > MAX` would give ±∞ the range
error, so only finite values are range-checked; the order criterion uses a kelvin `tmin_c`,
since a kelvin `tmax_c` never triggers `tminC > tmaxC`.

## Amendment B — observation-window metadata check (approved 2026-11-24)

### Why
Since ADR 0005 a file's `date` is the settlement day D under the station's Tmax/Tmin
observation windows, recorded in the file metadata. `publish` checks it; the Java loader
does not. Since spec 006 `pricer.data-dir` can point at any folder, so an old 00–24 UTC
file or an unpublished one is priced silently: London HDD about 4 too high per month
(1991–94: mean +3.94, worst +9.21), a systematic bias. Raised as a SHOULD in the
met-reviewer re-review of spec 006 (2026-11-24).

### Scope
In:
- `ParquetTemperatureLoader` requires file metadata keys `tmax_window_start_utc_h` and
  `tmin_window_start_utc_h`, with the same rules as `publish`: each a canonical integer
  string (`9`, `-15` OK; `+9`, `09`, `9.0`, ` 9`, `-0` rejected), each in −23..23, and the
  two at most 24 h apart.
- Failure is a `TemperatureDataException` naming the key, the value (or "missing") and the
  file; the API already maps it to 422.
- Order: after the column/schema checks, before any row is read.
- Fixtures: `ParquetFixtures.writeTemps` writes valid metadata by default (0 / 0, i.e.
  ADR 0002's 00–24 UTC day); an overload writes custom metadata or none.

Out:
- Checking the windows match the city's expected values (no station registry in Java):
  a valid-but-wrong-city file still loads.
- Exposing the windows on `DailyTemps` or in the API response.
- Other metadata keys (`source`, `method`, `start`/`end`); any Python change.

### Acceptance criteria
- [ ] Given metadata 9 / −15 (London), when loaded, then it loads with the same rows as before.
- [ ] Given metadata 0 / 0, then it loads.
- [ ] Given no key-value metadata at all, then rejected; the error names `tmax_window_start_utc_h` and the file path.
- [ ] Given only `tmax_window_start_utc_h`, then rejected naming `tmin_window_start_utc_h`.
- [ ] Given `+9`, `09`, `9.0`, ` 9`, `-0` or `abc`, then each is rejected naming the key and the value.
- [ ] Given 23 and −23, then it loads; given 24 or −24, then rejected.
- [ ] Given 9 / −15 (24 h apart), then it loads; given 10 / −15 (25 h apart), then rejected naming both keys.
- [ ] Given a wrong column type and no metadata, then the column error is reported (schema checked first).
- [ ] Given `POST /price` for a city whose file lacks window metadata, then 422 naming the missing key.
- [ ] The real `data/pricer/london.parquet` still loads (manual check, recorded in PROGRESS).
- [ ] All existing Java tests green with the fixture default; no existing assertion changed.
- [ ] `load()` Javadoc lists the new failure; `DailyTemps` Javadoc says the date is the settlement day under the station's windows (ADR 0005), not a UTC calendar day; README, CHANGELOG, PROGRESS and the development log updated.

### Decision
Owner OK 2026-11-24: form-only check (catches old/unpublished files; per-city check needs a
Java station registry, later); fixture default 0 / 0 so no existing assertion changes;
`DailyTemps` wording fixed here, AGENTS.md "Dates are LocalDate in UTC" kept, `era5-data`
skill day definition fixed separately.
Planner findings (owner OK 2026-11-24): `ParquetFixtures.writeQuery` also defaults to 0 / 0
metadata (7 loader tests use it; no assertion changes); `HddIndex` and `DailyTemp` Javadoc
reworded alongside `DailyTemps`; Java accepts exactly Python's set via `0|-?[1-9][0-9]?`;
order: tmax key (missing, form, range), then tmin, then gap; the value in an error message
is quoted and cut to 40 characters.
