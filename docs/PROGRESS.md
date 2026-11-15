# Progress

_Last updated: 2026-11-16_

## Done
- **Spec 001 — HDD index** (`docs/specs/001-hdd-index.md`)
  - Added the full-month known answer: January 2024, daily mean 10.0 °C, base 18 °C → 31 × 8.0 = **248.0 HDD**.
  - Added acceptance criteria for Parquet loading (round trip, missing column, duplicate date, missing file). Approved.
- **Decisions approved**
  - Parquet loading stays in spec 001 (not split out).
  - Scaffold the Gradle build in `pricer/` with JUnit 5 and Spotless.
- **Implementation plan drafted** by the `planner` agent (new-feature step 2), awaiting approval. Summary:
  - Java 21 (matches CI), Gradle Kotlin DSL + version catalog, package root `com.weatherderivatives.pricer` (`.index`, `.data`).
  - Types: `DailyTemp`, `DailyTemps`, `HddIndex`, `TemperatureDataException`, `ParquetTemperatureLoader`.
  - 10 steps, one commit each: build scaffold → API stubs → failing tests → validation → daily HDD → series checks → cumulative HDD → loader errors → loader rows → docs/ADR.

## Next
1. Get plan approval (new-feature step 2).
2. `git switch -c feat/001-hdd-index` (step 3).
3. Plan step 1: Gradle wrapper, JUnit 5, Spotless; root `.gitignore` (`build/`, `.gradle/`, `data/`, `*.parquet`); `*.jar binary` in `.gitattributes`.
4. Stubs, then `test-writer` writes failing acceptance tests (step 4).
5. Implement step by step; review with `code-reviewer` and `quant-reviewer`.

## Open questions
**Needed before implementation**
- Approve the extra dependencies? Hardwood (Parquet reader, no Hadoop), snappy-java (runtime, pyarrow's default compression), DuckDB JDBC (test only, writes Parquet fixtures).
- Reject NaN / `tminC > tmaxC` at load (whole file fails) or only when the bad day is inside the accrual period? Planner recommends at load.
- Loader takes the `data/processed` directory in its constructor and only accepts city names matching `[a-z0-9_-]+`?
- Assumed unless objected: extra columns ignored; null cells rejected naming date and column; `date` stored as timestamp rejected.

**From spec 001 (not blocking)**
- Round the cumulative index to exchange settlement precision, or keep full precision until payoff?
- Daily Tmax/Tmin from hourly `t2m` max/min or ERA5 `mx2t`/`mn2t`? (data-prep; record in an ADR.)
- Nearest grid cell vs 3×3 mean as the default city value? (data-prep.)

**Repo housekeeping**
- Three subagents (`code-reviewer`, `planner`, `test-writer`) live in `~/.claude/agents/`, not the repo; move them to `.claude/agents/` so collaborators get them?
- Current branch is `master`, but the main branch is configured as `main`.
