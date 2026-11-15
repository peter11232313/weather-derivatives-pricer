# Progress

_Last updated: 2026-11-16_

## Done
- **Spec 001 — HDD index engine** implemented on branch `feat/001-hdd-index` (not yet merged or pushed).
  - `HddIndex` (daily + cumulative HDD, inclusive UTC period, default base 18 °C), `DailyTemp`/`DailyTemps` with validation, `ParquetTemperatureLoader` (Hardwood).
  - 69 tests green; Spotless and Javadoc clean. All 17 acceptance criteria ticked.
  - Reviewed by `code-reviewer` and `quant-reviewer`: no blockers. Follow-ups fixed: reject ±∞ temperatures and base; wrap Parquet library errors (e.g. zstd) as `TemperatureDataException`; overflow-safe mean; extra tests (base-straddling day, −0.0, mixed float/double, pre-1970 dates).
  - Spec changes made along the way: 248 HDD example; Parquet-loading criteria; float32 accepted and widened exactly; "non-finite" instead of "NaN"; supported codecs listed.
- Gradle build for `pricer/`: Gradle 9.9.1 wrapper, Java 21, JUnit 6.1.3, Spotless 8.11.0 (google-java-format).
- ADR [0001](decisions/0001-parquet-reader.md): Hardwood for Parquet reading, snappy-java at runtime, DuckDB for test fixtures.
- README and CHANGELOG created.

## Next
1. Push `feat/001-hdd-index` and open a PR (description drafted in the session); check CI is green on Linux (native libs: snappy-java, DuckDB).
2. Decide the branch layout: `master` holds only the first commit; the configured main branch is `main`.
3. Next spec: write `docs/specs/002-*.md` (e.g. CDD index or data-prep ERA5 → Parquet), starting from `_template.md`.
4. `data-prep` must write Snappy (not zstd) Parquet with a DATE `date` column — record in its spec.

## Open questions
**From spec 001 (not blocking)**
- Round the cumulative index to exchange settlement precision, or keep full precision until payoff?
- Daily Tmax/Tmin from hourly `t2m` max/min or ERA5 `mx2t`/`mn2t`? (data-prep; record in an ADR.)
- Nearest grid cell vs 3×3 mean as the default city value? (data-prep.)

**Raised in review (user's call)**
- Add a physical range check on temperatures (e.g. reject outside −90..60 °C)? Not in the spec today.

**Repo housekeeping**
- Three subagents (`code-reviewer`, `planner`, `test-writer`) live in `~/.claude/agents/`, not the repo; move them to `.claude/agents/` so collaborators get them?
- Java/Gradle installed after this Claude Code session started; restart it so they're on PATH.
