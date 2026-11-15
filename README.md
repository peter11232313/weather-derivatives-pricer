# weather-derivatives-pricer

Prices HDD/CDD weather derivatives on European cities using ERA5 temperatures.

- `data-prep/` (Python): ERA5 → daily Tmax/Tmin per city → `data/processed/<city>.parquet`
- `pricer/` (Java 21, Gradle): degree-day indices, models and contract payoffs

## Status
| Spec | Feature | State |
| --- | --- | --- |
| [001](docs/specs/001-hdd-index.md) | HDD index engine + Parquet loader | done |

See [docs/PROGRESS.md](docs/PROGRESS.md) for what's next.

## Build and test
Requires JDK 21 (Gradle is provided by the wrapper).

```bash
cd pricer
./gradlew test            # PowerShell: .\gradlew.bat test
./gradlew spotlessApply   # format
```

## HDD index (spec 001)
Daily mean `T = (tmaxC + tminC) / 2`; daily `HDD = max(0, baseC − T)`; the cumulative
index sums daily HDD over an inclusive range of UTC days. Default base 18 °C.

```java
DailyTemps temps = new ParquetTemperatureLoader(Path.of("data/processed")).load("london");
double hddDegC = HddIndex.cumulativeHddDegC(
    temps, LocalDate.of(2023, 11, 1), LocalDate.of(2024, 3, 31)); // base 18 °C
```

Input file: one row per UTC day with `date` (Parquet DATE), `tmax_c` and `tmin_c`
(float64; float32 accepted), Snappy, gzip or uncompressed. Missing days, duplicate
dates, nulls, non-finite values and `tmin_c > tmax_c` are rejected with an error naming
the date, column or path; nothing is skipped or interpolated.

## Docs
- Specs: `docs/specs/` · Decisions: `docs/decisions/` · Agent rules: `AGENTS.md`
