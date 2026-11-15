# AGENTS.md — weather-derivatives-pricer

Prices HDD/CDD weather derivatives on European cities using ERA5 temperatures.
Portfolio project: code quality and correctness matter more than features.

## Commands
- Java build + tests: `cd pricer && ./gradlew test` (Git Bash; PowerShell: `.\gradlew.bat test`)
- Single test: `./gradlew test --tests "*HddIndexTest"`
- Format/lint: `./gradlew spotlessApply` · Python: `ruff check --fix && ruff format`
- Python tests: `cd data-prep && pytest -q`
- Run API: `./gradlew bootRun` → POST http://localhost:8080/price

## Structure
- `data-prep/` Python: ERA5 → daily mean T per city → `data/processed/*.parquet`
- `pricer/.../index` degree-day indices · `.../model` burn, index-distribution, daily OU sim
- `pricer/.../contract` payoffs (call/put/swap/collar, tick, cap) · `.../api` REST layer only
- Specs: `docs/specs/NNN-*.md` · Decisions: `docs/decisions/`

## Rules
- Read the relevant spec before coding; if none exists, stop and ask for one.
- Write or update tests first; never weaken or delete a failing test to make it pass.
- Units in names: `tempC`, `hddDegC`, `notionalGbp`. Dates are `LocalDate` in UTC.
- Daily mean T = (Tmax + Tmin) / 2 unless the spec says otherwise; HDD = max(0, base − T).
- Monte Carlo takes an explicit seed; results must be reproducible.
- No new dependencies without saying why in the PR description.
- Never commit data files, API keys or `~/.cdsapirc`.

## Definition of done
- All tests green, lint clean, new public methods have Javadoc.
- Known-answer test added for any new pricing formula.
- Spec's acceptance criteria ticked; README updated if behaviour changed.
