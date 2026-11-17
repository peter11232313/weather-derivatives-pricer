# Progress

_Last updated: 2026-11-18_

## Done
- **Spec 001 — HDD index engine**: merged to `main` via [PR #1](https://github.com/peter11232313/weather-derivatives-pricer/pull/1) (`733d5f3`). CI green on Linux.
  - `HddIndex`, `DailyTemp`/`DailyTemps`, `ParquetTemperatureLoader` (Hardwood); 69 tests; 17/17 criteria ticked.
- **Spec 001 open questions resolved** (PR #2): full-precision index; [ADR 0002](decisions/0002-daily-tmax-tmin-source.md) `mx2t`/`mn2t`; [ADR 0003](decisions/0003-city-value-at-settlement-station.md) station interpolation; `era5-data` skill aligned.
- **Spec 002 — ERA5 → daily station temperatures (data-prep)**: merged to `main` via [PR #3](https://github.com/peter11232313/weather-derivatives-pricer/pull/3) (`8d4806a`); CI green (252 tests on Linux).
  - `python -m dataprep {download,process,publish,build}`; package in `data-prep/src/dataprep/`; London Heathrow in `data-prep/stations.toml`.
  - Folders: `data/raw` (downloads) → `data/processed` (staging) → `data/pricer` (validated; the Java loader reads it).
  - **First real run (2026-11-17)**, London 2024-12-01..31, and manual Java check passed ([PR #4](https://github.com/peter11232313/weather-derivatives-pricer/pull/4)).
  - **1991–1992 processed and published (2026-11-17)**: 1991-01-01..1992-12-30, 730 rows, no gaps; winter 1991/92 HDD(18) 1898.45; Java and Python agree bit for bit. (Ends 12-30 because 1993-01-01 00:00 is not downloaded yet.)
- **Spec 003 — HDD contract payoffs + burn analysis**: merged to `main` via [PR #5](https://github.com/peter11232313/weather-derivatives-pricer/pull/5) (`0e6f861`); CI green.
  - `contract`: `HddContract`, `ContractType`, `SettlementIndex`, `HddPayoff`; `model`: `BurnAnalysis`, `BurnResult`, `SeasonOutcome`.
  - 158 Java tests green; spotless + javadoc clean; 12/12 criteria ticked.
  - Reviewed by `code-reviewer` and `quant-reviewer`. Quant blocker fixed: rounded daily HDD is now computed in exact decimal (rounding the binary double turned ~10% of 0.1 °C ties down). Spec amended and **user-approved 2026-11-17**; [ADR 0004](decisions/0004-settlement-rounding-daily-hdd.md).
  - Smoke run on real London data: January call K=400, tick £20, seasons 1991–1992 → burn £764.05 (unrounded) / £762.00 (1 dp); matches an independent Python calculation.

- **Spec 004 — detrended burn**: merged to `main` via [PR #7](https://github.com/peter11232313/weather-derivatives-pricer/pull/7) (`70dcedf`); CI green.
  - `DetrendedBurnAnalysis`, `DetrendedBurnResult`, `SeasonTrend`; `BurnAnalysis` shares checks/statistics.
  - 195 Java tests green; spotless + javadoc clean; 11/11 criteria ticked.
  - Reviewed by `code-reviewer` and `quant-reviewer`: no blockers; SHOULDs/NITs fixed (slope standard error added, look-ahead note, -0.0 shift, huge ranges fail on data).

## Next
1. **Finish the 1991–2024 download** in the user's own terminal, **one command at a time** (Copernicus rejects parallel requests; ~3 h queue per year; keep the PC awake): `python -m dataprep build --city london --start 1991-01-01 --end 2024-12-31` → expect 12,419 rows. Option: spec 002 amendment for multi-year requests (~3 years per request) to cut the queue waits.
2. Real burn on 1991–2023 London seasons once the data is published.
3. Real detrended burn on London once 1991–2023 is published (needs ≥ 10 seasons).
4. Spec 001 amendment: physical range check (−90..60 °C) in the Java loader (decided: Java only).
5. Later: CDD index, collars/strips, index-distribution and OU models, REST endpoint.

## Open questions
**Pricing conventions (from quant review, deferred)**
- Some term sheets round the daily mean temperature rather than daily HDD; add a `RoundingTarget` option when a contract needs it (ADR 0004).
- Leap-day policy for historical seasons (keep 29 Feb vs drop / match target day count).
- Detrending (quant review of spec 004): fit the trend on a longer/wider series than the contract months; a uniform shift moves only the mean, not the cold tail; guard far-future targets.

**Repo housekeeping**
- Three subagents (`code-reviewer`, `planner`, `test-writer`) live in `~/.claude/agents/`, not the repo; move them to `.claude/agents/` so collaborators get them?
- Java is not on PATH in Claude Code's shell; commands use `JAVA_HOME` = `C:\Program Files\Java\jdk-21.0.12.1`. Restart Claude Code to pick it up.
- A stray duplicate `data/raw/fac03129d536eaa266a41c6b21829289.zip` (1992 again) can be deleted by the user.
