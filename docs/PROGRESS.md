# Progress

_Last updated: 2026-11-16_

## Done
- **Spec 001 — HDD index engine**: merged to `main` via [PR #1](https://github.com/peter11232313/weather-derivatives-pricer/pull/1) (`733d5f3`). CI green on Linux.
  - `HddIndex`, `DailyTemp`/`DailyTemps`, `ParquetTemperatureLoader` (Hardwood); 69 tests; 17/17 criteria ticked.
- **Spec 001 open questions resolved** (PR #2): full-precision index; [ADR 0002](decisions/0002-daily-tmax-tmin-source.md) `mx2t`/`mn2t`; [ADR 0003](decisions/0003-city-value-at-settlement-station.md) station interpolation; `era5-data` skill aligned.
- **Spec 002 — ERA5 → daily station temperatures (data-prep)**: merged to `main` via [PR #3](https://github.com/peter11232313/weather-derivatives-pricer/pull/3) (`8d4806a`); CI green (252 tests on Linux).
  - `python -m dataprep {download,process,publish,build}`; package in `data-prep/src/dataprep/`; London Heathrow in `data-prep/stations.toml`.
  - Folders: `data/raw` (downloads) → `data/processed` (staging) → `data/pricer` (validated; the Java loader reads it).
  - 252 tests (offline: synthetic ERA5, fake CDS client); ruff clean; 22/22 criteria ticked.
  - **First real run (2026-11-17)**, London 2024-12-01..31: Copernicus returned zips (option A merge worked; raw 2024 file 8,784 h). 31 rows, `method=bilinear`, mean daily T 7.2 °C, December HDD(18) 333.8. Manual Java check passed: `ParquetTemperatureLoader` reads `data/pricer/london.parquet` with values identical to Python and the same HDD.
  - Reviewed by `code-reviewer` and `quant-reviewer`; all findings fixed, both clean on re-review.
  - Spec amended during review (user-approved): downloads request a zip and merge the per-step-type netCDF parts; downloads validated before saving; land check ignores zero-weight cells (ADR 0003 note).

## Next
1. Full run 1991-01-01..2024-12-31 in the user's own terminal (~35 more requests; Claude Code may stop long background runs when memory is low): `python -m dataprep build --city london --start 1991-01-01 --end 2024-12-31` → expect 12,419 rows.
2. Spec 001 amendment: physical range check (−90..60 °C) in the Java loader (decided: Java only).
3. Next feature spec, e.g. CDD index or burn analysis; the payoff spec must carry per-contract settlement rounding.

## Open questions
**Repo housekeeping**
- Three subagents (`code-reviewer`, `planner`, `test-writer`) live in `~/.claude/agents/`, not the repo; move them to `.claude/agents/` so collaborators get them?
- Java/Gradle were installed after this Claude Code session started; restart Claude Code so they're on PATH (`JAVA_HOME` = `C:\Program Files\Java\jdk-21.0.12.1`).
