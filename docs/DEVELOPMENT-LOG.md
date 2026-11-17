# Development log — difficulties, confusions, resolutions and decisions

_Covers 2026-11-16 .. 2026-11-17: specs 001–003, the first real ERA5 runs and the tooling
around them._ Formal decisions live in [`docs/decisions/`](decisions/) (ADRs) and the
specs; this log records **how we got there**: what went wrong, what was unclear, and
what we chose. Each entry: **Problem → Resolution**, with a pointer where one exists.

**Keeping it current** (CLAUDE.md rule): every new difficulty, confusion, open question or
final decision is added here with its resolution and rationale, in the section it belongs
to (numbered on), and moved out of "Still open" once resolved.

Contents
1. [Tooling and environment](#1-tooling-and-environment)
2. [Spec 001 — HDD index engine](#2-spec-001--hdd-index-engine)
3. [Spec 002 — ERA5 → daily station temperatures](#3-spec-002--era5--daily-station-temperatures)
4. [Real data runs and Copernicus downloads](#4-real-data-runs-and-copernicus-downloads)
5. [Spec 003 — contract payoffs and burn analysis](#5-spec-003--contract-payoffs-and-burn-analysis)
6. [Workflow lessons](#6-workflow-lessons)
7. [Decision index](#7-decision-index)
8. [Still open](#8-still-open)

---

## 1. Tooling and environment

| # | Problem / confusion | Resolution |
| --- | --- | --- |
| 1.1 | A test request to create `data/raw/test.txt` was blocked by the project hook. | Working as designed: the hook protects raw ERA5 downloads from agent edits. Not bypassed. |
| 1.2 | `.gitignore` entry `data/` would also have ignored the Java package `com/weatherderivatives/pricer/data/`. | Anchored to the repo root: `/data/`. |
| 1.3 | Pinning the Gradle wrapper checksum: forcing a re-download needed `rm -rf`, which settings deny. | Took the SHA-256 from Gradle's published `.sha256` file instead; wrapper is sha256-pinned (Gradle 9.9.1). |
| 1.4 | JDK 21 was installed after the Claude Code session started, so `java`/`javac` are not on the tool shell's PATH. | Commands set `JAVA_HOME="/c/Program Files/Java/jdk-21.0.12.1"` explicitly; restarting Claude Code fixes it permanently. |
| 1.5 | The `test-writer` subagent may only write test files, so it could neither add the DuckDB test dependency (spec 001) nor create main-code stubs (spec 003). | The main session added DuckDB (user-approved) and wrote the stubs, so the Java tests compile and fail for the right reason. |
| 1.6 | `pip install -e` produced `dataprep.egg-info/`. | Added `*.egg-info/` to `.gitignore`. |
| 1.7 | `/tmp` paths from Git Bash did not resolve for Windows Python. | Temporary scripts and logs go in the session scratchpad. |
| 1.8 | numpy deprecation warning for a generic `timedelta64`. | Use `np.timedelta64(1, "D")`. |
| 1.9 | Shell heredocs containing quotes failed to parse in the Git Bash tool ("unexpected EOF"). | Write edit scripts to a file and run them; or use the file tools. |
| 1.10 | A long real download run in the background was killed by Claude Code under memory pressure. | Long downloads run in the user's own terminal; the command is restartable and skips finished years. |
| 1.11 | Repo setup: default branch and visibility. | Renamed `master` → `main`; private GitHub repo; CI (`.github/workflows/ci.yml`) runs Java (spotless + tests) and Python (ruff + pytest). |
| 1.12 | JDK compiler and Windows tool messages (`javac`, `tasklist`) print in the machine's Chinese locale. | Cosmetic only; tests match our own message text, not JDK wording. |

## 2. Spec 001 — HDD index engine

| # | Problem / confusion | Resolution |
| --- | --- | --- |
| 2.1 | Which Java Parquet reader? parquet-java needs Hadoop (and `winutils.exe` on Windows); DuckDB is ~77 MB. | **Hardwood 1.0.1.Final** at runtime, snappy-java runtime-only, DuckDB JDBC for test fixtures only — [ADR 0001](decisions/0001-parquet-reader.md). |
| 2.2 | Hardwood cannot read zstd (polars' default), LZ4 or Brotli. | Spec 001 lists supported codecs; data-prep must write **Snappy** explicitly. |
| 2.3 | User question: can float32 temperature columns be accepted? | Yes — float32 is accepted and **widened exactly** (`0.1f` → `0.10000000149011612`); integer/decimal types rejected naming the column. |
| 2.4 | Review: ±Infinity passed validation and produced NaN/Infinity HDD silently. | `DailyTemp` and `dailyHddDegC` reject non-finite values; a non-finite base is rejected. |
| 2.5 | Review: Hardwood throws unchecked exceptions for bad codecs/corrupt pages, escaping the documented contract. | Loader wraps `IOException` and `RuntimeException` as `TemperatureDataException` naming the path. |
| 2.6 | Review: `(tmax + tmin) / 2` overflows near `Double.MAX_VALUE`. | `meanTempC = tmax/2 + tmin/2` in one shared helper; bit-identical for physical values. |
| 2.7 | Open question: round the index to settlement precision? | Index engine stays at **full precision**; rounding is a per-contract term (became spec 003 / ADR 0004). |
| 2.8 | Open question: daily Tmax/Tmin from hourly `t2m` or from `mx2t`/`mn2t`? | **`mx2t`/`mn2t`**; day D = values stamped D 01:00..D+1 00:00 — [ADR 0002](decisions/0002-daily-tmax-tmin-source.md). |
| 2.9 | Open question: city value = nearest grid cell, 3×3 mean, or something else? | **Bilinear interpolation to the settlement station**, nearest land cell for coastal stations; nearest/3×3 kept as diagnostics — [ADR 0003](decisions/0003-city-value-at-settlement-station.md). The `era5-data` skill was updated to match. |
| 2.10 | Physical range check (−90..60 °C): Python, Java or both? | **Java loader only**, as a separate spec 001 amendment (not done yet). |

## 3. Spec 002 — ERA5 → daily station temperatures

| # | Problem / confusion | Resolution |
| --- | --- | --- |
| 3.1 | User requirement: downloaded, processed and pricer-read files must be separate. | Three folders: `data/raw` → `data/processed` (staging) → `data/pricer`, written only by a validating `publish` step. |
| 3.2 | Day D needs D+1 00:00, so the last day of a year needs the next year's first hour. | Year file Y covers `[Y-01-01 00:00, Y+1-01-01 00:00)`; the final 00:00 comes from the next year's file, else a one-step file `era5_<city>_<Y+1>0101T00.nc`. |
| 3.3 | Contract check between Python output and the Java loader: automated or manual? | User chose a **documented manual check** (README); done on the first real run. |
| 3.4 | The new CDS splits `mx2t`/`mn2t` into separate netCDF files by GRIB step type and returns a zip. | **Option A (user-approved):** request `download_format: zip`, merge parts with `xr.merge(compat="no_conflicts", join="exact")`, validate as netCDF with the needed variables **before** rename. Spec 002 amended. |
| 3.5 | Review: a station exactly on a grid line flipped to nearest-land through rounding, because zero-weight cells counted in the land check. | Land check uses only cells with weight > 1e-9 (ADR 0003 note); interpolation no longer depends on xarray dimension order. |
| 3.6 | Review: damaged zips, garbage downloads, misaligned parts, raw files on another grid, missing variables, stations near a pole or ±180°, start after end, missing registry or `~/.cdsapirc`, locked files on Windows all surfaced as tracebacks or silent NaN padding. | Each became a clear `DataPrepError` subclass; zip detection by magic bytes; `join="exact"` rejects mismatched time axes. Covered by `test_review_fixes.py` and fake-CDS modes (`zip_corrupt`, `zip_misaligned`, …). |
| 3.7 | pandas imported directly but not declared. | Declared in `pyproject.toml`. |
| 3.8 | CLI run outside a checkout, and city names duplicated in error messages. | `repo_root()` fails clearly (exit 1); the city is prefixed only if not already named. |
| 3.9 | AGENTS.md described the old single-folder flow. | Line updated (user-approved) to `data/processed` → (publish) → `data/pricer`. |

## 4. Real data runs and Copernicus downloads

| # | Problem / confusion | Resolution |
| --- | --- | --- |
| 4.1 | First real run (London, Dec 2024): would real Copernicus files match the synthetic tests? | Zips arrived and the option-A merge worked. 31 rows, bilinear, December HDD(18) 333.8. Java read `data/pricer/london.parquet` **bit-identically** (HDD 333.8037287138665). |
| 4.2 | How much disk does 1991–2024 need? | Measured, not guessed: ~1.7 MB per raw year → **~60 MB** total, against 11 GB free. Raw files can be deleted after publishing. |
| 4.3 | One year took 3–6 hours. Is the code slow? | File timestamps showed **~3 h in the Copernicus queue** per request and ~1 minute to process/download. Server-side load (ECMWF forum reports the same in 2026); request size barely matters. |
| 4.4 | 1992 was ready on the server at 14:10 but saved locally at 17:13. | Lost on the client side (PC asleep or the command stopped). Keep the PC awake during downloads. |
| 4.5 | User downloaded a zip manually from the CDS website into `data/raw`. | Inspected without extracting: it was 1992 again, **value-identical** to `era5_london_1992.nc`. The pipeline ignores non-matching file names; safe to delete. |
| 4.6 | Running 4 downloads in parallel → "exceeding the max allowed requests". | Copernicus caps queued requests per user (limits are dynamic and undisclosed). **Run one download at a time.** Rejected requests left no partial files. |
| 4.7 | How to cut total wait instead? | Options recorded: several years per request (~3 years fits the published ~60,000-item cap; needs a spec 002 amendment and a test request); ECMWF ARCO/Google ERA5 mirrors (no queue, new dependencies, ADR change). The CDS "ERA5 time series" dataset was ruled out: nearest grid point only (conflicts with ADR 0003) and `mx2t`/`mn2t` unconfirmed. |
| 4.8 | A range ending 31 December triggers an extra next-year 00:00 request (another queue wait). | For partial work, end ranges on **12-30**. |
| 4.9 | Use the two downloaded years while waiting? | Processed **1991-01-01..1992-12-30** (730 rows, no gaps; winter 1991/92 HDD 1898.45); published; Java = Python bit for bit. Publishing replaced the Dec 2024 test file. |
| 4.10 | ERA5 at Heathrow looks ~1 °C cooler than station records, with lower summer peaks. | Expected for gridded reanalysis vs an airport station; not a bug. Flagged for later bias analysis before real pricing. |

## 5. Spec 003 — contract payoffs and burn analysis

| # | Problem / confusion | Resolution |
| --- | --- | --- |
| 5.1 | Which next step while downloads run? | Burn analysis first (user choice): it can be built and tested on synthetic data. |
| 5.2 | Round the daily **HDD** or the daily **mean temperature**? | User chose **half-up rounding of each day's HDD**, opt-in per contract. |
| 5.3 | How to label a Nov–Mar season? | By the year of its first accrual day (1991 = winter 1991/92). |
| 5.4 | Detrend the 1991–2024 burn? | Not in spec 003; the warming bias is accepted and documented. Detrending is the next model spec. |
| 5.5 | Planner questions: a record can't default `baseC`; null terms; meaning of the contract's year; error type for missing days. | `HddContract.of(...)` uses 18 °C; nulls → `NullPointerException` naming the field; the year is a template shifted by `forSeason`; reuse `TemperatureDataException` with the season prefixed and the cause kept. |
| 5.6 | Java tests cannot fail "for the right reason" until the API exists. | Commit tests **with** stubs that throw `UnsupportedOperationException`; confirmed all 81 new cases failed only on that. |
| 5.7 | **Quant blocker:** rounding the binary double sent exact ties down, e.g. 18 − (6.9 + 1.8)/2 = 13.649999999999999 → 13.6 instead of 13.7. 9.6% of 0.1 °C ties were wrong, always downward (a settlement bias). | Daily HDD is computed in **exact decimal** (`BigDecimal.valueOf` of Tmax/Tmin/base) before half-up rounding. Re-review enumerated 630,900 cases: 0 wrong. Spec amended, **user-approved**; [ADR 0004](decisions/0004-settlement-rounding-daily-hdd.md). |
| 5.8 | Review NITs: negative index accepted; `-0.0` strike broke record equality; locals without units; missing null/rounded-path tests. | Fixed test-first: negative index rejected, strike normalised, unit-suffixed names, new tests. |
| 5.9 | Market-convention points a desk would raise. | Added `burnPriceStdErrorGbp()`; documented the leap-day mix (151/152-day seasons), no detrending, and the caller's look-ahead responsibility. Leap-day policy and a daily-mean rounding option deferred. |
| 5.10 | A hand-computed expected value in a new test was wrong (11,893.15). | Recomputed before running: **11,893.04**. Known answers are now computed independently, not by eye. |
| 5.11 | Does it work on real data? | January call, K=400, tick £20, seasons 1991–1992: burn **£764.05** (full precision) / **£762.00** (1 dp), matching an independent Python calculation. |
| 5.12 | Shipping. | Merged via PR #5 (`0e6f861`), CI green; 158 Java tests. |

## 5b. Spec 004 — detrended burn

| # | Problem / confusion | Resolution |
| --- | --- | --- |
| 5b.1 | Detrend daily temperatures or the seasonal HDD index? | **Daily temperatures** (shift Tmax and Tmin). HDD is `max(0, base − T)`: a linear trend on the index can extrapolate below zero, whereas shifted temperatures above the base correctly give 0. |
| 5b.2 | Fit the trend to each season's accrual-period mean or to the calendar-year mean? | **Accrual-period mean**: captures warming in the contract's own months (e.g. winter), at the cost of noisier points. |
| 5b.3 | Minimum seasons for a trend? | **10** (owner's choice) to avoid fitting noise; the draft's 3-season examples were redone over 2001–2010 with target 2011, including a noise pattern built to leave the slope at exactly 0.2 °C/yr. |
| 5b.4 | Target season fixed (`last + 1`) or explicit? | **Explicit `targetSeasonYear`**. |

## 6. Workflow lessons

- **Tests first, always.** A red commit precedes every implementation; tests are never weakened. Reviews only ever added tests.
- **Independent review pays off.** Every spec's review found real defects: overflow and non-finite inputs (001); zip handling, grid-line flips and silent NaN padding (002); the tie-rounding bias (003).
- **Spec changes need the user's explicit approval**, even when made to fix a review blocker; record the approval in the spec and PROGRESS.md.
- **Measure before answering.** Disk space, queue time and the duplicate zip were all settled from file sizes, timestamps and value comparisons, not estimates.
- **Long-running external work belongs in the user's terminal**, restartable and idempotent (skip existing files, temp file + atomic rename).
- **Commits and pushes need approval**; a rejected tool call is a "no" until the user says otherwise.

## 7. Decision index

| Decision | Where |
| --- | --- |
| Hardwood Parquet reader; Snappy only from data-prep | [ADR 0001](decisions/0001-parquet-reader.md), spec 001 |
| Full-precision index; rounding is a contract term | spec 001, [ADR 0004](decisions/0004-settlement-rounding-daily-hdd.md) |
| `mx2t`/`mn2t`, day = D 01:00..D+1 00:00 UTC | [ADR 0002](decisions/0002-daily-tmax-tmin-source.md) |
| Bilinear to the station; nearest land cell; zero-weight cells ignored | [ADR 0003](decisions/0003-city-value-at-settlement-station.md) |
| raw / processed / pricer folders with validating publish | spec 002 |
| Zip downloads merged per GRIB step type (option A) | spec 002 (amended) |
| Range check in Java only | spec 001 (amendment pending) |
| Manual Python→Java contract check | spec 002, README |
| Daily-HDD half-up rounding in exact decimal | spec 003 (amended), [ADR 0004](decisions/0004-settlement-rounding-daily-hdd.md) |
| Season label = first accrual year; no detrending yet | spec 003 |
| One Copernicus download at a time | PROGRESS.md |

## 8. Still open

- Finish the 1991–2024 London download; then the full burn on 1991–2023 seasons.
- Multi-year Copernicus requests (spec 002 amendment) if the queue stays at ~3 h per year.
- Leap-day policy; optional daily-mean rounding target; look-ahead guard.
- ERA5-vs-station bias analysis before real pricing.
- Spec 001 range-check amendment.
- Move the personal subagents (`code-reviewer`, `planner`, `test-writer`) into `.claude/agents/`.
