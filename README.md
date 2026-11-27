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
| [005](docs/specs/005-index-distribution.md) | Index-distribution pricing (normal) | done |
| [006](docs/specs/006-rest-price-endpoint.md) | REST price endpoint | done |

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
Daily Tmax/Tmin come from ERA5 `mx2t`/`mn2t`
([ADR 0002](docs/decisions/0002-daily-tmax-tmin-source.md)) over each station's own
observation windows ([ADR 0005](docs/decisions/0005-per-station-observation-windows.md)).
Hourly fields are interpolated to the settlement station, or the nearest land cell for
coastal stations ([ADR 0003](docs/decisions/0003-city-value-at-settlement-station.md)).
Stations are listed in `data-prep/stations.toml`.

**Observation windows** (spec 002 amendment B). Each station has two required whole-hour
UTC offsets, `tmax_window_start_utc_h` and `tmin_window_start_utc_h`: a window starting at
D 00:00 + s uses the values stamped D+s+1h .. D+s+24h (each covers the hour before its
stamp). London (Met Office) is **9 / −15**: Tmax(D) over D 09:00..D+1 09:00, Tmin(D) over
D−1 09:00..D 09:00 (08:50 rounded to 09:00). 0 / 0 is the UTC day (KNMI, DWD).
- A settlement month therefore reaches past the calendar month: London January uses
  weather from 31 Dec 09:00 to 1 Feb 09:00 UTC, so the last day needs D+1 09:00.
- With touching windows (London) a day that cools steadily through 09:00 can come out
  with Tmin a few hundredths of a degree above Tmax (ERA5's 08–09 minimum and 09–10
  maximum do not share 09:00); both are set to their midpoint (HDD and CDD unchanged) and the
  count is in metadata `tmin_tmax_adjusted_days`. An excess above 0.5 °C is still an
  error (corrupt-data guard).
- Compared with the old 00–00 day, London monthly HDD is about 4 HDD lower on average
  (1991–1994: mean −3.9, sd 2.8, worst −9.2): a 00–24 Tmin window catches parts of two
  nights. Files built before amendment B lack the window metadata, fail `publish` and the
  Java loader (spec 001 amendment B), and must be reprocessed
  (`data/processed/london.parquet`) and republished (`data/pricer/`); never mix them with
  new ones: burn results on the old files are not comparable.

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
split back into one complete year file per year. London 1991–2024 is about 15 Copernicus
requests (1 land-sea mask + 12 three-year chunks + 2 edge days). Hours the windows need
outside the range's years (at most the day before the start and the day after the end)
come from that year's file if present, else a one-day **edge-day file**
`era5_<city>_<YYYYMMDD>.nc` (all 24 hours; for London 1991–2024: `19901231` and
`20250101`). Edge-day files are never refreshed; delete one to fetch it again (e.g. after
preliminary ERA5T data is finalised). The old one-step `era5_<city>_<YYYY>0101T00.nc` is
no longer used and can be deleted by hand. Each request waits
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
index sums daily HDD over an inclusive range of settlement days (`LocalDate`; each
station's observation windows, ADR 0005). Default base 18 °C.

```java
DailyTemps temps = new ParquetTemperatureLoader(Path.of("data/pricer")).load("london");
double hddDegC = HddIndex.cumulativeHddDegC(
    temps, LocalDate.of(2023, 11, 1), LocalDate.of(2024, 3, 31)); // base 18 °C
```

Input file: one row per settlement day with `date` (Parquet DATE), `tmax_c` and `tmin_c`
(float64; float32 accepted), Snappy, gzip or uncompressed. Missing days, duplicate
dates, nulls, non-finite values, values outside −90..60 °C (e.g. a file left in kelvin)
and `tmin_c > tmax_c` are rejected with an error naming the date, column or path; nothing
is skipped or interpolated. The range check is a guard against corrupt or wrong-unit
files, not a units check: a winter-only file in °F can stay inside the limits.
The file must also carry the observation-window metadata `tmax_window_start_utc_h` and
`tmin_window_start_utc_h` (ADR 0005; written by `dataprep process`, checked by `publish`):
each a whole number of hours in −23..23, at most 24 h apart. A file without them, e.g. one
built on the old 00–24 UTC day, is rejected (422 from the API) instead of priced with about
4 HDD per London month too many. Only the form is checked, not that the windows are the
right ones for the city.

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

## Index-distribution pricing (spec 005)
Fits a normal distribution to the seasons' settlement index values (the same values burn
reports, with the contract's rounding) and prices the contract as its expected payoff in
closed form, for call, put and swap with or without a cap. Undiscounted, no risk loading.
At least 5 seasons (10 for the detrended fit):

```java
IndexDistributionResult r = IndexDistributionAnalysis.run(call, temps, 1991, 2023);
r.expectedPayoffGbp(); r.meanIndexHddDegC(); r.sdIndexHddDegC(); r.burn().burnPriceGbp();

IndexDistributionResult d = IndexDistributionAnalysis.runDetrended(call, temps, 1991, 2023, 2024);

double priceGbp = IndexDistributionPricing.expectedPayoffGbp(call, 400.0, 50.0); // μ, σ in °C·day
```

The normal CDF is in-house (W. J. Cody's erfc), so there is no new dependency. The fitted
σ is itself uncertain (about ±25% with 10 seasons), which the price does not include.

## REST price endpoint (spec 006)
A local HTTP server (Spring Boot) prices a contract with every method in one call. It binds to
`127.0.0.1:8080` only.

```bash
cd pricer && ./gradlew bootRun          # reads data/pricer/<city>.parquet (from the repo root)
# other folder: ./gradlew bootRun --args='--pricer.data-dir=/path/to/folder'
#   (files there must carry the window metadata from `dataprep process`/`publish`)
# another GBP city: --pricer.settlement-currency.<city>=GBP (lower-case key, as the file name)

curl -s http://127.0.0.1:8080/health    # {"status":"ok"}

curl -s -X POST http://127.0.0.1:8080/price -H 'Content-Type: application/json' -d '{
  "city": "london", "type": "CALL", "firstMonth": "2001-01", "lastMonth": "2001-01",
  "strikeHddDegC": 400, "tickGbpPerDegC": 20, "capGbp": null, "dailyHddDecimals": 1,
  "firstSeasonYear": 1992, "lastSeasonYear": 2001, "targetSeasonYear": 2002 }'
```

- **Request:** `city`, `type` (`CALL` / `PUT` / `SWAP`), `firstMonth` / `lastMonth` (`YYYY-MM`,
  a template; seasons re-anchor the years), `strikeHddDegC`, `tickGbpPerDegC`, `firstSeasonYear` /
  `lastSeasonYear` are required. Optional: `capGbp`, `dailyHddDecimals`, `baseC` (default 18) and
  `targetSeasonYear`. Unknown fields and wrong types (e.g. `"400"`, `1.5` decimals, `"type": 1`)
  are rejected.
- **Response:** `currency`, each season's index and payoff, `burn` (expected payoff, standard
  error, payoff sd, min, max, mean index) and `normal` (expected payoff, μ, σ, `seasonCount`,
  `indexSkewness`). With `targetSeasonYear`, `detrended` adds the slope, its standard error,
  `slopeTStat` and `slopeSignificant`, the shifted seasons (with each season's mean temperature
  and shift) and the detrended burn and normal; otherwise `"detrended": null`.
- **Cities** are priced only if they have a settlement currency in
  `pricer.settlement-currency.<city>` (spec 006 amendment C; default `london=GBP`). Any other city
  is a 404, even with a data file, and its file is not read. Only `GBP` is accepted for now (the
  amount fields are named `…Gbp`); another value, or a key that is not a lower-case city name,
  stops the server at startup.
- **At least 5 seasons** (the normal model's minimum), and **10 with `targetSeasonYear`**.
- **No look-ahead** (spec 006 amendment A): a `targetSeasonYear` inside
  `firstSeasonYear..lastSeasonYear` is a 400, checked before the data are read, because the
  priced season would feed its own trend fit and burn. To detrend to the latest climate use
  `lastSeasonYear + 1`. Only a target after the window is a walk-forward back-test; a target
  before it is allowed but is a hindcast (the trend is fitted on later seasons and projected
  backwards). For long periods (e.g. Oct–Apr or a 12-month contract) the usual trade date for
  season `last + 1` comes before season `last` has settled; use `lastSeasonYear + 2` then.
- **Errors** are `{"error", "message"}`: 400 `invalid_request` (the message names the field),
  404 `city_not_found`, 422 `data_error` (names the season and date), 404 / 405 / 406 / 415 for a
  wrong route, method, `Accept` or content type, and 500 `internal_error` with no details.
- The city file is read on every request, so a republished file is used without a restart.

How to read the numbers:
- Every `expectedPayoffGbp` (burn and normal, plain and detrended) is an **undiscounted expected
  payoff with no risk loading**, not a quote. (Before spec 006 amendment B the field was `priceGbp`.)
- `burn.stdErrorGbp` covers only the spread of past payoffs. The normal expected payoff does not
  include the uncertainty of σ itself (about ±25% with 10 seasons).
- The detrended standard errors leave out the uncertainty of the fitted slope; check
  `slopeStdErrDegCPerYear`.
- Every `…Gbp` amount is in the settlement currency named by `currency`.
- `slopeTStat` is the slope divided by its standard error (`null` for an exactly linear history).
  `slopeSignificant` means |t| ≥ 2: a **rule of thumb, not p < 0.05**. With 10 seasons the 5%
  critical value is 2.31 (|t| = 2 is about p = 0.08), with 30 seasons 2.05; autocorrelated
  seasons make |t| look larger than it is. When it is `false`, the trend shift is mostly noise and
  the detrended price is about as uncertain as the plain one; their difference may mean nothing.
- `indexSkewness` is the sample skewness (adjusted Fisher–Pearson G1) of the index values the
  normal was fitted to (the shifted ones for `detrended.normal`). G1 > 0 is a long tail of cold
  seasons, so the normal model underprices out-of-the-money calls; G1 < 0 the reverse. Its own
  standard error is about √(6/n) (0.77 at 10 seasons, 0.45 at 30), so only |G1| above about 1 says
  much. `null` when all values are equal or there are fewer than 3 seasons.

## Docs
- Specs: `docs/specs/` · Decisions: `docs/decisions/` · Agent rules: `AGENTS.md`
