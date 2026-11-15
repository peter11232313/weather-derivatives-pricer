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
- [ ] Given the 5-day example above with base 18 °C, when cumulative HDD is computed for 2024-01-01..2024-01-05, then it equals **44.0** (tolerance 1e-9).
- [ ] Given 2024-01-01..2024-01-31 with every day `tmaxC = 14.0`, `tminC = 6.0` (mean 10.0 °C) and base 18 °C, when cumulative HDD is computed, then it equals **248.0** (31 days × 8.0; tolerance 1e-9).
- [ ] Given a day with mean T above the base (2024-01-03, mean 19.0), then its daily HDD is **0.0**, never negative.
- [ ] Given a day with mean T exactly equal to the base, then daily HDD is 0.0.
- [ ] Given base 15.5 °C and the same 5 days, then cumulative HDD is **34.0** (9.5 + 7.5 + 0 + 11.5 + 5.5).
- [ ] Given a winter season 2023-11-01..2024-03-31 (leap year), then exactly **152** days are accrued; for 2022-11-01..2023-03-31, exactly **151**.
- [ ] Given start == end, then exactly one day is accrued (period is inclusive).
- [ ] Given start after end, then an `IllegalArgumentException` is thrown.
- [ ] Given a missing day inside the period, then an exception names the missing date (no silent skip, no interpolation).
- [ ] Given a NaN `tmaxC` or `tminC`, then an exception names the date.
- [ ] Given `tminC > tmaxC`, then an exception names the date.
- [ ] Given a Parquet file with columns `date` (date), `tmax_c`, `tmin_c` (float64), when loaded for a city, then each row becomes one `DailyTemps` entry keyed by `LocalDate` and the stored °C values are unchanged (round-trip test on a 3-row fixture written by the test itself, not a committed data file).
- [ ] Given `tmax_c`/`tmin_c` stored as float32, when loaded, then each value equals `(double)` of the stored float exactly (e.g. `0.1f` → `0.10000000149011612`); other non-numeric types are rejected naming the column.
- [ ] Given a Parquet file missing a required column, then an exception names the column.
- [ ] Given duplicate dates in the file, then an exception names the date.
- [ ] Given `data/processed/<city>.parquet` does not exist, then an exception names the path.
- [ ] Unit names follow AGENTS.md (`tempC`, `hddDegC`); public methods have Javadoc.

## Open questions
- Should the cumulative index be rounded (e.g. to 0.1 °C·day) to match exchange settlement, or kept at full precision until payoff?
- Daily Tmax/Tmin: hourly `t2m` max/min over the UTC day, or ERA5 `mx2t`/`mn2t`? (Decide in `data-prep`, record in an ADR.)
- Nearest grid cell vs 3×3 mean as the default city value?
