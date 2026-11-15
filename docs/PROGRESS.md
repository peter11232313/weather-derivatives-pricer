# Progress

_Last updated: 2026-11-16_

## Done
- **Spec 001 — HDD index engine**: merged to `main` via [PR #1](https://github.com/peter11232313/weather-derivatives-pricer/pull/1) (`733d5f3`). CI green on Linux.
  - `HddIndex` (daily + cumulative HDD, inclusive UTC period, default base 18 °C), `DailyTemp`/`DailyTemps` with validation, `ParquetTemperatureLoader` (Hardwood).
  - 69 tests; all 17 acceptance criteria ticked. Reviewed by `code-reviewer` and `quant-reviewer`, no open findings.
  - Review follow-ups fixed: reject ±∞ temperatures and base; wrap Parquet library errors (e.g. zstd) as `TemperatureDataException`; overflow-safe mean; extra tests.
- Gradle build for `pricer/`: Gradle 9.9.1 wrapper, Java 21, JUnit 6.1.3, Spotless 8.11.0.
- ADR [0001](decisions/0001-parquet-reader.md): Hardwood for Parquet, snappy-java at runtime, DuckDB for test fixtures.
- README and CHANGELOG created.
- Repo on GitHub (private): https://github.com/peter11232313/weather-derivatives-pricer — default branch `main` (renamed from `master`).

## Next
1. Write spec 002 from `docs/specs/_template.md` — candidates: CDD index, or data-prep ERA5 → `data/processed/<city>.parquet`.
2. Whichever comes first, the data-prep spec must require Snappy (not zstd) Parquet with a DATE `date` column, `tmax_c`/`tmin_c` float64 (see spec 001 and ADR 0001).
3. Optionally delete the merged remote branch `feat/001-hdd-index`.

## Open questions
**From spec 001 (not blocking)**
- Round the cumulative index to exchange settlement precision, or keep full precision until payoff?
- Daily Tmax/Tmin from hourly `t2m` max/min or ERA5 `mx2t`/`mn2t`? (data-prep; record in an ADR.)
- Nearest grid cell vs 3×3 mean as the default city value? (data-prep.)

**Raised in review (user's call)**
- Add a physical range check on temperatures (e.g. reject outside −90..60 °C)? Not in the spec today.

**Repo housekeeping**
- Three subagents (`code-reviewer`, `planner`, `test-writer`) live in `~/.claude/agents/`, not the repo; move them to `.claude/agents/` so collaborators get them?
- Java/Gradle were installed after this Claude Code session started; restart Claude Code so they're on PATH (`JAVA_HOME` = `C:\Program Files\Java\jdk-21.0.12.1`).
