# Changelog

## Unreleased

### Added
- HDD index engine (spec 001): `HddIndex.dailyHddDegC` and `cumulativeHddDegC` (inclusive
  UTC period, default base 18 °C), `DailyTemp`/`DailyTemps` with validation.
- `ParquetTemperatureLoader` reading `<processedDir>/<city>.parquet` via Hardwood
  (ADR 0001); float32 temperatures widened exactly.
- Gradle build for `pricer/` (Java 21, JUnit, Spotless).
