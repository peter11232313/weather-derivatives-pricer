# Changelog

## Unreleased

### Added
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
