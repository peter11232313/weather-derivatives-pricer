# Changelog

## Unreleased

### Added
- pricer (spec 006): REST endpoint (Spring Boot 4.1.3, localhost only). `POST /price` returns
  burn, normal and (with `targetSeasonYear`) detrended burn and normal prices in one call;
  `GET /health`. Strict JSON; errors are `{error, message}` with 400 / 404 / 422 / 500.
  `ParquetTemperatureLoader` throws `CityFileNotFoundException` (a `TemperatureDataException`)
  for a missing file. Run with `cd pricer && ./gradlew bootRun`.
- pricer (spec 005): index-distribution pricing. `IndexDistributionAnalysis.run` /
  `runDetrended` fit a normal to the seasonal settlement index values (sample σ, n − 1) and
  price by closed-form expected payoff (`IndexDistributionPricing`), for call, put and swap
  with or without a cap. In-house normal CDF (Cody erfc); no new dependency.

### Changed
- pricer (spec 001 amendment A): `ParquetTemperatureLoader` rejects finite `tmax_c` /
  `tmin_c` outside −90..60 °C (inclusive), naming the date, column, value and file. A file
  left in kelvin used to load and give a silent HDD index of 0. Non-finite values keep
  their own error; the range check runs before the `tmin_c > tmax_c` check.
- data-prep (spec 002 amendment B, ADR 0005): daily Tmax/Tmin follow each station's
  observation windows, two required whole-hour UTC offsets in `stations.toml` (London
  9 / −15, i.e. 09–09 UTC; was the UTC day 00–00 for every station). Processed files
  record the offsets and `tmin_tmax_adjusted_days`; `publish` requires the offsets.
  Whole-day edge-day files `era5_<city>_<YYYYMMDD>.nc` replace the one-step
  `…0101T00.nc` file. With touching windows, a day with Tmin > Tmax gets both set to the
  midpoint. London monthly HDD drops by about 4 on average; reprocess and republish.
- data-prep: every raw year file is now checked for completeness at download, so a
  range ending in the current, unfinished year fails with the first missing hour.

### Added
- Multi-year downloads (spec 002 amendment A): up to 3 consecutive missing years per
  Copernicus request (`--years-per-request`, default 3), split into complete year files;
  incomplete years reported, complete years kept. London 1991–2024 drops from ~36 to ~14
  requests.
- Detrended burn analysis (spec 004): `DetrendedBurnAnalysis` → `DetrendedBurnResult`
  (OLS trend of season mean temperature, daily Tmax/Tmin shifted to a target season,
  settlement and payoffs recomputed; slope, slope standard error, per-season mean and
  shift). At least 10 seasons.
- HDD contract payoffs and burn analysis (spec 003): `HddContract` (call/put/swap, whole
  calendar months, strike, tick, cap, opt-in half-up daily HDD rounding in exact decimal,
  ADR 0004), `SettlementIndex`, `HddPayoff`, `BurnAnalysis` → `BurnResult` (per-season
  outcomes, burn price, sample std dev, standard error, min/max, mean index).
- data-prep (spec 002): `python -m dataprep {download,process,publish,build}` turns ERA5
  `mx2t`/`mn2t` into daily Tmax/Tmin at the settlement station (ADR 0002, 0003), writes
  `data/processed/<city>.parquet` and publishes a validated copy to `data/pricer/`.
  Station registry `data-prep/stations.toml` (London Heathrow).
- HDD index engine (spec 001): `HddIndex.dailyHddDegC` and `cumulativeHddDegC` (inclusive
  UTC period, default base 18 °C), `DailyTemp`/`DailyTemps` with validation.
- `ParquetTemperatureLoader` reading `<processedDir>/<city>.parquet` via Hardwood
  (ADR 0001); float32 temperatures widened exactly.
- Gradle build for `pricer/` (Java 21, JUnit, Spotless).
