# Progress

_Last updated: 2026-11-20_

## Done
- **Spec 001 — HDD index engine**: merged to `main` via [PR #1](https://github.com/peter11232313/weather-derivatives-pricer/pull/1) (`733d5f3`). CI green on Linux.
  - `HddIndex`, `DailyTemp`/`DailyTemps`, `ParquetTemperatureLoader` (Hardwood); 69 tests; 17/17 criteria ticked.
- **Spec 001 amendment A — physical range check**: branch `feat/001a-range-check` (stacked on `chore/repo-subagents`), ready for PR.
  - Loader rejects finite `tmax_c`/`tmin_c` outside −90..60 °C inclusive; order: non-finite, Tmax range, Tmin range, `tmin > tmax`.
  - 213 Java tests green; spotless + javadoc clean; 9/9 criteria ticked. Reviewed by `code-reviewer` and `quant-reviewer`: no blockers; test assertions tightened (owner OK).
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

- **Spec 002 amendment A — multi-year downloads**: merged to `main` via [PR #8](https://github.com/peter11232313/weather-derivatives-pricer/pull/8) (`e512d11`).
  - `download_years`/`year_chunks`; `--years-per-request` (1–3, default 3); year files split from chunk responses and checked complete.
  - 318 Python tests green; ruff clean; 9/9 amendment criteria ticked. Reviewed by `code-reviewer` (no blockers; SHOULDs/NITs fixed except a pandas 4 `check_freq` test warning).

- **Spec 002 amendment B — per-station observation windows**: merged to `main` via [PR #9](https://github.com/peter11232313/weather-derivatives-pricer/pull/9) (`4cd9fd0`).
  - Registry offsets (London 9 / −15), windowed `daily_extremes`, `needed_span`, edge-day files, window metadata checked by `publish`; Tmin > Tmax under touching windows → midpoint ([ADR 0005](decisions/0005-per-station-observation-windows.md)).
  - 462 Python tests green; ruff clean (incl. a 0.5 °C guard on the midpoint, owner OK). Reviewed by `code-reviewer` (no blockers) and `quant-reviewer` (blocker Tmin > Tmax fixed by the owner's option 3).
  - Manual: London 1991-01-02..1994-12-30 reprocessed (3 midpoint days), published to scratch, loaded by Java (1,459 rows). Monthly HDD new − old: mean −3.94, sd 2.79, range −9.21..+4.25.

## Next
1. **Finish the 1991–2024 download** (multi-year requests, running in the user's terminal; one command at a time; keep the PC awake). `python -m dataprep build --city london --start 1991-01-01 --end 2024-12-31` again fetches the two edge days (1990-12-31, 2025-01-01) and reprocesses with the 09–09 windows → expect 12,419 rows; republish (the old file lacks window metadata).
2. Real burn on 1991–2023 London seasons once the data is published.
3. Real detrended burn on London once 1991–2023 is published (needs ≥ 10 seasons).
4. Later: CDD index, collars/strips, index-distribution and OU models, REST endpoint.

## Open questions
**Data guards**
- A winter-only file in °F (or tenths of °C on cold days) passes the −90..60 °C check and gives a near-zero HDD. Our pipeline always writes °C; a whole-file plausibility check or a units field in the metadata (checked by Java) would close it (quant review of spec 001 amendment A).

**Pricing conventions (from quant review, deferred)**
- Some term sheets round the daily mean temperature rather than daily HDD; add a `RoundingTarget` option when a contract needs it (ADR 0004).
- Leap-day policy for historical seasons (keep 29 Feb vs drop / match target day count).
- Detrending (quant review of spec 004): fit the trend on a longer/wider series than the contract months; a uniform shift moves only the mean, not the cold tail; guard far-future targets.

**Repo housekeeping**
- Subagents `code-reviewer`, `planner`, `test-writer` copied (not moved) into `.claude/agents/` (2026-11-20); the originals stay in `~/.claude/agents/`. Inside this repo the project copies take precedence, so edit those.
- Java is not on PATH in Claude Code's shell; commands use `JAVA_HOME` = `C:\Program Files\Java\jdk-21.0.12.1`. Restart Claude Code to pick it up.
- A stray duplicate `data/raw/fac03129d536eaa266a41c6b21829289.zip` (1992 again) can be deleted by the user.
