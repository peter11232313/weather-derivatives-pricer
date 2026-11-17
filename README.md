# weather-derivatives-pricer

Prices HDD/CDD weather derivatives on European cities using ERA5 temperatures.

- `data-prep/` (Python): ERA5 → daily Tmax/Tmin per settlement station → `data/pricer/<city>.parquet`
- `pricer/` (Java 21, Gradle): degree-day indices, models and contract payoffs

## Status
| Spec | Feature | State |
| --- | --- | --- |
| [001](docs/specs/001-hdd-index.md) | HDD index engine + Parquet loader | done |
| [002](docs/specs/002-era5-daily-temps.md) | ERA5 → daily station temperatures (data-prep) | done |
| [003](docs/specs/003-burn-analysis.md) | HDD contract payoffs + burn analysis | done |
| [004](docs/specs/004-detrended-burn.md) | Detrended burn analysis | done |

See [docs/PROGRESS.md](docs/PROGRESS.md) for what's next.

## Build and test
Java: requires JDK 21 (Gradle is provided by the wrapper).

```bash
cd pricer
./gradlew test            # PowerShell: .\gradlew.bat test
./gradlew spotlessApply   # format
```

Python: requires Python 3.12.

```bash
cd data-prep
py -3.12 -m venv .venv
source .venv/Scripts/activate     # PowerShell: .venv\Scripts\Activate.ps1 · macOS/Linux: .venv/bin/activate
pip install -e ".[dev]"
pytest -q
ruff check . && ruff format --check .
```

## Data folders
All under `data/`, which is git-ignored and never committed.

| folder | written by | read by |
| --- | --- | --- |
| `data/raw/` | `dataprep download` | `dataprep process` |
| `data/processed/` | `dataprep process` (staging) | `dataprep publish` |
| `data/pricer/` | `dataprep publish` only, after validation | Java `ParquetTemperatureLoader` |

## ERA5 → daily temperatures (spec 002)
Daily Tmax/Tmin come from ERA5 `mx2t`/`mn2t`; UTC day D uses the hourly values stamped
D 01:00 through D+1 00:00 ([ADR 0002](docs/decisions/0002-daily-tmax-tmin-source.md)).
Hourly fields are interpolated to the settlement station, or the nearest land cell for
coastal stations ([ADR 0003](docs/decisions/0003-city-value-at-settlement-station.md)).
Stations are listed in `data-prep/stations.toml`.

**One-time setup for downloads**
1. Create a free account at the Copernicus Climate Data Store and accept the ERA5
   single-levels licence on the dataset page.
2. Put your API key in `~/.cdsapirc` (never commit it):
   ```
   url: https://cds.climate.copernicus.eu/api
   key: <your-api-key>
   ```

**Run** (from `data-prep/` with the venv active):

```bash
python -m dataprep build --city london --start 1991-01-01 --end 2024-12-31
# or step by step:
python -m dataprep download --city london --start 1991-01-01 --end 2024-12-31
python -m dataprep process  --city london --start 1991-01-01 --end 2024-12-31
python -m dataprep publish  --city london
```

`download` skips files already present, so an interrupted run can simply be restarted.
Missing years are fetched **up to 3 consecutive years per request**
(`--years-per-request N`, 1–3, default 3, on `download` and `build`); each response is
split back into one complete year file per year. London 1991–2024 is about 14 Copernicus
requests (1 land-sea mask + 12 three-year chunks + 1 next-year step). Each request waits
roughly 3 hours in the queue, so allow a day; run **one command at a time** (Copernicus
rejects parallel requests) and keep the PC awake. If Copernicus rejects a 3-year request
as too large, rerun with `--years-per-request 1`. Raw files are about 60 MB; the
published file is under 1 MB with 12,419 rows.

Every year file must hold every hour of its year. A range ending in the current,
unfinished year therefore fails at download with an error naming the first missing hour.
If one year of a chunk is incomplete, the complete years are kept, later chunks still
download, and the error lists the incomplete years; a rerun requests only those.

**Manual Java contract check** (spec 002, after the first real `build`):
1. Inspect the published file:
   ```bash
   python -c "import pyarrow.parquet as pq; f = pq.ParquetFile('../data/pricer/london.parquet'); t = f.read(); print(t.num_rows, t.slice(0, 2).to_pylist(), t.slice(t.num_rows - 1).to_pylist()); print({k.decode(): v.decode() for k, v in f.metadata.metadata.items() if not k.startswith(b'ARROW')})"
   ```
   Expect 12,419 rows from 1991-01-01 to 2024-12-31 and `method = bilinear`.
2. Load it with the Java loader (e.g. `jshell --class-path` with the pricer's runtime
   classpath, or a scratch JUnit test pointing at `data/pricer`):
   ```java
   DailyTemps temps = new ParquetTemperatureLoader(Path.of("data/pricer")).load("london");
   temps.size();                                   // 12419
   temps.get(LocalDate.of(1991, 1, 1));            // same values as step 1
   ```
3. Tick the contract-check criterion in spec 002.

## HDD index (spec 001)
Daily mean `T = (tmaxC + tminC) / 2`; daily `HDD = max(0, baseC − T)`; the cumulative
index sums daily HDD over an inclusive range of UTC days. Default base 18 °C.

```java
DailyTemps temps = new ParquetTemperatureLoader(Path.of("data/pricer")).load("london");
double hddDegC = HddIndex.cumulativeHddDegC(
    temps, LocalDate.of(2023, 11, 1), LocalDate.of(2024, 3, 31)); // base 18 °C
```

Input file: one row per UTC day with `date` (Parquet DATE), `tmax_c` and `tmin_c`
(float64; float32 accepted), Snappy, gzip or uncompressed. Missing days, duplicate
dates, nulls, non-finite values and `tmin_c > tmax_c` are rejected with an error naming
the date, column or path; nothing is skipped or interpolated.

## Burn analysis (spec 003)
An `HddContract` (call, put or swap; whole calendar months; strike, tick in £ per °C·day,
optional cap, optional half-up daily HDD rounding) is priced on past seasons:

```java
HddContract call = HddContract.of(ContractType.CALL,
    YearMonth.of(2024, 11), YearMonth.of(2025, 3),   // Nov–Mar; the year is a template
    1900.0, 20.0, OptionalDouble.empty(), OptionalInt.of(1));
BurnResult r = BurnAnalysis.run(call, temps, 1991, 2023); // season 1991 = winter 1991/92
r.burnPriceGbp(); r.payoffStdDevGbp(); r.burnPriceStdErrorGbp(); r.seasons();
```

The burn price is the undiscounted mean payoff. Not yet included: discounting, risk
loading; leap seasons accrue 29 February. Rounding:
[ADR 0004](docs/decisions/0004-settlement-rounding-daily-hdd.md).

## Detrended burn (spec 004)
Under warming the raw burn overstates HDD. `DetrendedBurnAnalysis` fits a linear trend
(OLS) to each season's mean temperature over the contract months, shifts every day's
Tmax/Tmin to the target season's climate, then prices as above (at least 10 seasons):

```java
DetrendedBurnResult d = DetrendedBurnAnalysis.run(call, temps, 1991, 2023, 2024);
d.burn().burnPriceGbp(); d.slopeDegCPerYear(); d.slopeStdErrDegCPerYear(); d.seasons();
```

For a back-test, end the window before the target season. Seasons are labelled by the
year of their first accrual day (Nov–Mar 1991 = winter 1991/92), for both the trend and
the shift. With few seasons the slope is noisy; check `slopeStdErrDegCPerYear()`.

## Docs
- Specs: `docs/specs/` · Decisions: `docs/decisions/` · Agent rules: `AGENTS.md`
