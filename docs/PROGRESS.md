# Progress

_Last updated: 2026-11-16_

## Done
- **Spec 001 — HDD index engine**: merged to `main` via [PR #1](https://github.com/peter11232313/weather-derivatives-pricer/pull/1) (`733d5f3`). CI green on Linux.
  - `HddIndex`, `DailyTemp`/`DailyTemps`, `ParquetTemperatureLoader` (Hardwood); 69 tests; 17/17 criteria ticked.
- **Spec 001 open questions resolved** (PR #2): full-precision index; [ADR 0002](decisions/0002-daily-tmax-tmin-source.md) `mx2t`/`mn2t`; [ADR 0003](decisions/0003-city-value-at-settlement-station.md) station interpolation; `era5-data` skill aligned.
- **Spec 002 — ERA5 → daily station temperatures (data-prep)**: implemented on branch `feat/002-era5-daily-temps` (not yet pushed).
  - `python -m dataprep {download,process,publish,build}`; package in `data-prep/src/dataprep/`; London Heathrow in `data-prep/stations.toml`.
  - Folders: `data/raw` (downloads) → `data/processed` (staging) → `data/pricer` (validated; the Java loader reads it).
  - 252 tests (offline: synthetic ERA5, fake CDS client); ruff clean; 21/22 criteria ticked.
  - Reviewed by `code-reviewer` and `quant-reviewer`; all findings fixed, both clean on re-review.
  - Spec amended during review (user-approved): downloads request a zip and merge the per-step-type netCDF parts; downloads validated before saving; land check ignores zero-weight cells (ADR 0003 note).

## Next
1. Push `feat/002-era5-daily-temps` and open a PR; CI's Python job now runs for real.
2. First real run on the user's Copernicus account (CDS account, licence, `~/.cdsapirc`): a short range first, e.g. `python -m dataprep build --city london --start 2024-12-01 --end 2024-12-31`, then 1991-01-01..2024-12-31 (~36 requests, hours of queueing).
3. Manual Java contract check (README steps) against `data/pricer/london.parquet`, then tick the last spec 002 criterion.
4. Spec 001 amendment: physical range check (−90..60 °C) in the Java loader (decided: Java only).
5. Next feature spec, e.g. CDD index or burn analysis; the payoff spec must carry per-contract settlement rounding.

## Open questions
**Repo housekeeping**
- Three subagents (`code-reviewer`, `planner`, `test-writer`) live in `~/.claude/agents/`, not the repo; move them to `.claude/agents/` so collaborators get them?
- Java/Gradle were installed after this Claude Code session started; restart Claude Code so they're on PATH (`JAVA_HOME` = `C:\Program Files\Java\jdk-21.0.12.1`).
