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
| 2.10 | Physical range check (−90..60 °C): Python, Java or both? | **Java loader only**, as spec 001 amendment A (done 2026-11-20, see 2.11–2.14). |
| 2.11 | Planner: `v < MIN \|\| v > MAX` is true for ±∞, so infinities would get the range error instead of `DailyTemp`'s "non-finite" one. | Range-check only when both values are finite; a row with one non-finite and one out-of-range value gets the non-finite error (new criterion, owner OK). |
| 2.12 | Planner: the criterion "range before `tmin > tmax`" used a kelvin Tmax with a normal Tmin, which never trips `tmin > tmax`, so it could not detect the order. | Criterion reworded to use a kelvin Tmin (10.0 / 275.15); the kelvin-Tmax case is kept (owner OK). |
| 2.13 | Reviews (code, quant): no blockers. Some assertions were loose (kelvin test did not pin the column/row; "no `tmin_c` anywhere" could match the temp path). | Assertions added/tightened, limits also tested in the other column, float32 rounding of 60.000001 noted (owner OK). Javadoc states the check order. |
| 2.14 | Quant: a winter-only °F file (London Nov–Mar mostly < 60 °F) or tenths of °C on cold days passes the range check and gives near-zero HDD. | Out of scope; recorded as an open question (whole-file plausibility or a units field in metadata). Our own pipeline always writes °C. |

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
| 5b.5 | Planner: add the trend shift in binary double or in exact decimal? After a shift, a 0.1 °C rounding tie could go either way. | **Double** (owner OK'd the plan): the shift is a statistical estimate far noisier than 1 ulp, so decimal would be false precision. Noted in ADR 0004. |
| 5b.6 | Planner: expose the trend intercept or R²? Wire into `/price`? | **No** for now: not in the spec; there is no REST layer yet. |
| 5b.7 | A flat history must give exactly the raw burn, but a naive OLS (`m − mean(m)`) can leave a 1-ulp slope. | Slope numerator summed as `x·(m_high − m_low)` over symmetric year pairs, so equal season means give a slope of exactly +0.0. |
| 5b.8 | The spec's rounding example (means 10.04 + 0.1k) could not tell "round after shift" from "round raw HDD, then shift". | Test-writer added a 0.13 °C/yr case: correct 207.7 vs wrong-order 205.53. |
| 5b.9 | Plan had two implementation commits (trend fit, then shifted burn). | One commit: the code was small; noted rather than split artificially. |
| 5b.10 | Quant review: at 10 seasons the slope's standard error (~0.17 °C/yr for σ≈1.5 °C) is several times the real warming signal, and it was not reported. | Added `slopeStdErrDegCPerYear()` (known answer 0.110 for the noise example); Javadoc says the burn std dev excludes trend uncertainty and uses n − 1. |
| 5b.11 | Look-ahead is worse than in spec 003: the priced season would feed both the trend and the burn. | Javadoc and README: end the window before the target; normally target = last + 1. |
| 5b.12 | Code review: a huge season range (e.g. 0..Integer.MAX_VALUE) allocated an array up front (OOM / negative size) before reading data. | Season means collected as they are read, so it fails on the first missing day; test added. |
| 5b.13 | Shift reported as −0.0 for a zero slope with a target before the season; overflowing shifts lost the season context; the range message said "burn analysis". | `+ 0.0` normalisation; second pass wraps errors with the season; message names "detrended burn analysis (trend fit)". The unused `firstSeasonYear` parameter of the OLS helper was kept (would mean editing tests for no behaviour change). |
| 5b.14 | Desk concerns raised, deferred: the trend is fitted on the contract months only (noisy for one-month contracts); a uniform shift moves only the mean, not the cold tail; far-future targets scale the shift linearly. | Recorded in PROGRESS.md open questions for a later spec. |
| 5b.15 | Shipping. | Merged via PR #7 (`70dcedf`), CI green; 195 Java tests. |

## 5c. Spec 002 amendment A — multi-year downloads

| # | Problem / confusion | Resolution |
| --- | --- | --- |
| 5c.1 | Real pricing is blocked on data: ~3 h queue per request, one request per year, parallel requests rejected. | Ask for up to N consecutive missing years per request and split the result back into year files; processing unchanged. |
| 5c.2 | How many years per request? The ~60,000-item cap is old and unconfirmed. | **Default 3, maximum 3** (owner OK; 3 × 17,568 items fits). A live 3-year test request was written to match the code's request exactly, so Copernicus's cache may serve it again when the feature runs. |
| 5c.3 | One year of a multi-year response is incomplete: discard the chunk or keep the complete years? | **Keep the complete years** (owner's choice); report and re-request only the incomplete year. |
| 5c.4 | Planner found a conflict: year files were never checked for completeness, so a range ending in the current, unfinished year downloaded fine; the new check would make it fail. | **Check every year** (owner OK): an unfinished year fails with a clear error naming the first missing hour. Our 1991–2024 range is unaffected. |
| 5c.5 | Incomplete year in a chunk: stop at once or continue? | **Continue** with later chunks and raise one error at the end (each stop would waste ~3 h queue slots); a failed request still stops at once. |
| 5c.6 | Year files are now rewritten by xarray instead of saved as the raw response bytes. | Accepted: tests require equal values, coordinates, attributes and stored dtype (no silent float32/int16 repacking). Chunk-size encodings are dropped on write so 3-year chunking cannot break a 1-year file. |
| 5c.7 | The scratchpad test script calls `_request(year=0)`, which the new `years=` signature breaks. | Acceptable for a one-off script; run it before the change lands, or adapt it. |
| 5c.8 | After implementing, 7 new tests still failed: (a) pandas 3 builds date ranges in microseconds while xarray decodes netCDF times in nanoseconds, and `assert_index_equal` compares the dtype; (b) the test filter `is_single_step_request` also matched the land-sea mask request (both ask for 1 Jan 00:00). | Diagnosed as test bugs, not code bugs; fixed only with the owner's OK (`.as_unit("ns")`; `not is_lsm_request`), in a separate commit. 311 Python tests green. A pandas 4 `check_freq` deprecation warning in the new tests remains (harmless). |
| 5c.9 | A scratch-copy check of the proposed test fixes used `rm -rf`, which the settings deny. | Not worked around; asked the owner instead. |
| 5c.10 | Code review: `_split_year` crashed with `IndexError` (a traceback, not a clean error) on a complete but unordered response, and called a repeated hour "missing". | Sort the year's hours first; report "first missing hour" or "first repeated hour". Tests added. |
| 5c.11 | Code review: a failed request after an incomplete year hid the incomplete-year message; a rejected 3-year request gave no hint. | The failed-request error now appends the incomplete years and, for multi-year chunks, suggests `--years-per-request 1`. |
| 5c.12 | Code review: nothing tested that packed int16 data (scale/offset/_FillValue, integer time units) survives the year-file rewrite. | Test added; it passed first time, confirming the reviewer's manual check. |
| 5c.13 | README still said ~36 requests and did not mention the option or the unfinished-year behaviour. | README, spec text, CHANGELOG (Added + Changed) updated: ~14 requests for London 1991–2024. |
| 5c.14 | Shipping. | Merged via PR #8 (`e512d11`). The full London build was restarted with multi-year requests. |

## 5d. Spec 002 amendment B — per-station observation windows

| # | Problem / confusion | Resolution |
| --- | --- | --- |
| 5d.1 | The owner asked whether the code supports each station's own Tmax/Tmin windows (London 08:50–08:50, Paris 06–06 / 18–18, Amsterdam and Essen 00–00). | No: every day is the UTC day D 01:00..D+1 00:00 for both Tmax and Tmin. Correct for Amsterdam and Essen; wrong for London and Paris. |
| 5d.2 | Why was 00–00 used for London? | Not a London decision. It came from the project-wide "dates are UTC `LocalDate`" rule and spec 001's "UTC calendar day"; ADR 0002 only chose `mx2t`/`mn2t` and aligned the hours to that day. ADR 0002 flagged that a 09–09 day "would need a separate decision", which was never made. Lesson: a default that is really a contract term must be checked per contract. |
| 5d.3 | Are the windows UTC or local time (which would shift with summer time)? | **UTC** (owner confirmed). |
| 5d.4 | ERA5 `mx2t`/`mn2t` cover whole hours; 08:50 cannot be matched. | Proposed: round to 09:00 (Met Office climatological day 09–09 UTC). |
| 5d.5 | Windows reaching into the day before `start` (London Tmin) or after `end` need hours outside the downloaded years. | Proposed: whole-day edge files replace the one-step `…0101T00` file; two extra requests for London 1991–2024. |
| 5d.6 | Order of work. | Owner: fix the windows, then reprocess London first and compare monthly HDD old vs new. The running download is unaffected (re-processing only). |
| 5d.7 | The full download runs from the main checkout and lazily imports `pipeline`/`publish` at its end; editing that tree mid-run could crash it or mix old and new code. | Work in a git worktree (`../wdp-002b`) with the same venv and `PYTHONPATH=src`, so the worktree code is imported; the manual comparison writes only to scratch folders. |
| 5d.8 | Spec decisions approved: 08:50 → 09:00; whole-day edge files replace the one-step file; window fields required (no default); Tmax(D) = window starting on D, Tmin(D) = window ending on D; London only. Planner questions: offsets keyword-only with no default; test station 0/0; `publish` also checks the windows overlap or touch. | Owner OK. Existing tests changed only as approved (fixtures gain fields/keys, one-step-file tests rewritten as edge-day tests, amendment A criterion 9 rewritten). |
| 5d.9 | Reprocessing real London data failed: `tmin_c 11.3018 > tmax_c 11.2728 on 1991-11-08`. 3 of 1,459 days (≤ 0.07 °C). Tmin came from 08–09 and Tmax from 09–10 on mornings cooling steadily through 09:00; ERA5's hourly extremes do not share the 09:00 instant. The quant reviewer raised the same as a BLOCKER; the Java `DailyTemp` also rejects tmin > tmax. | Owner chose **option 3**: with touching windows set both to the midpoint (≈ T(09:00), HDD unchanged), count in metadata `tmin_tmax_adjusted_days`; overlapping windows keep the error. Rejected: allowing tmin > tmax (Java change) and a tolerance (arbitrary). Spec decision 6, ADR 0005. |
| 5d.10 | Size of the change. | London monthly HDD (Feb 1991..Nov 1994) new − old: mean −3.94, sd 2.79, −9.21..+4.25; Nov–Mar mean −2.59, lower in 14 of 18. A systematic time-of-observation bias, not noise: a 00–24 Tmin window catches two nights. Burn and detrended burn must be rerun on the new file. |
| 5d.11 | Code review: a one-day range with offsets 23/23 returned the same edge day twice; README still described the UTC day; `publish` accepted `"09"`/`"-0"`. | Edge days de-duplicated; publish requires canonical integers; README, CHANGELOG, PROGRESS updated; tests added. |
| 5d.12 | Manual Java check on the new file. | Java loader reads it (1,459 rows); tmin == tmax on the 3 midpoint days; Jan 1994 HDD 395.197 matches Python. |
| 5d.13 | Quant re-review: the midpoint accepted any size of Tmin > Tmax, so a corrupt or swapped file would be silently flattened. | Owner OK: guard at 0.5 °C (real gaps ≤ 0.07); a larger excess is an error naming the date. Wording: HDD and CDD unchanged; republish paths named. |
| 5d.14 | Merge. | Merged via PR #9 (`4cd9fd0`); worktree and branch removed. The London build must be restarted (no download was running) to fetch 1995–2023 and the two edge days. |
| 5d.15 | Personal subagents (`code-reviewer`, `planner`, `test-writer`) lived only in `~/.claude/agents/`. | Owner: **copy, not move**, into `.claude/agents/` (identical files, no private paths). Project copies take precedence inside the repo, so future edits go there. |

## 5e. Spec 005 — index-distribution pricing

| # | Difficulty / question | Resolution and rationale |
| --- | --- | --- |
| 5e.1 | Use 1995–2001 while the download runs? | Yes: `process` reads only local year files. With 09–09 windows the range is 1991-01-02..2001-12-30 (edge hours need 1990 and 2002). Published 4,016 rows; burn on 10 seasons. Detrended slopes not significant; extrapolating to 2026 multiplies noise (Jan £436 → £1,691). |
| 5e.2 | Spec decisions. | Owner OK: normal only; minimum 5 seasons for `run`, 10 for `runDetrended`; no parameter uncertainty in v1. |
| 5e.3 | Planner: the 2-season fit example contradicted the 5-season minimum. | Five seasons {360, 400, 400, 400, 440}: same μ, σ and price (owner OK). |
| 5e.4 | Normal CDF: no library in the project. Parity at 1e-9 on £10⁴ prices needs Φ(d) + Φ(−d) = 1 to ~1e-13. | In-house W. J. Cody erfc (netlib CALERF), package-private; no commons-math dependency. Coefficients checked digit for digit by the quant reviewer; max relative error 1.7e-13 over −30..10. |
| 5e.5 | Rounding can leave a capped price 1 ulp above the cap. | Capped prices clamped to [0, min(C, uncapped)] (call, put) and [−C, C] (swap); the capped formulas are pinned by the known answers. |
| 5e.6 | Identical seasons: sum / n can be 1 ulp off the common value. | When all seasons are equal, μ = that index exactly and σ = 0, so the price is the exact payoff. |
| 5e.7 | Test-writer may not write under `src/main`, so it could not add compile-only stubs. | It built them in a scratch copy; I reviewed and copied them in, then confirmed 50 of 51 new tests red. |
| 5e.8 | Quant review: a finite but huge cap made K + L overflow and the price NaN. | Treat the cap as absent when K + L is not finite (it can never bind); regression test added first. Tail known answers (Φ(−6), Φ(−20), Φ(−30)) from a 50-digit reference, because Python's `math.erfc` is off at Φ(−30) in the 13th digit. Monotonicity test moved to Cody's real breakpoint 0.46875·√2. |

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
| `mx2t`/`mn2t`, day = D 01:00..D+1 00:00 UTC | [ADR 0002](decisions/0002-daily-tmax-tmin-source.md) (hour alignment superseded) |
| Per-station observation windows; London 09–09 UTC; edge-day files; midpoint when Tmin > Tmax under touching windows | spec 002 amendment B, [ADR 0005](decisions/0005-per-station-observation-windows.md) |
| Bilinear to the station; nearest land cell; zero-weight cells ignored | [ADR 0003](decisions/0003-city-value-at-settlement-station.md) |
| raw / processed / pricer folders with validating publish | spec 002 |
| Zip downloads merged per GRIB step type (option A) | spec 002 (amended) |
| Range check (−90..60 °C) in the Java loader only | spec 001 amendment A |
| Manual Python→Java contract check | spec 002, README |
| Daily-HDD half-up rounding in exact decimal | spec 003 (amended), [ADR 0004](decisions/0004-settlement-rounding-daily-hdd.md) |
| Season label = first accrual year; no detrending yet | spec 003 |
| One Copernicus download at a time | PROGRESS.md |
| Normal index-distribution pricing; in-house Cody erfc; min 5 / 10 seasons | spec 005 |

## 8. Still open

- Rerun the London build (1995–2023 plus two edge-day requests) and republish with the
  09–09 windows; then the real burn and detrended burn on 1991–2023.
- Paris, Amsterdam, Essen: add to `stations.toml` with confirmed coordinates (windows
  6/−6, 0/0, 0/0); for a long Essen history check the older DWD observation day.

- Finish the 1991–2024 London download; then the full burn on 1991–2023 seasons.
- Live 3-year Copernicus test request (scratchpad `probe_multiyear.py`, owner's terminal)
  pending; it confirms the 3-year cap of spec 002 amendment A.
- Leap-day policy; optional daily-mean rounding target; look-ahead guard.
- ERA5-vs-station bias analysis before real pricing.
- Units guard beyond the range check (winter-only °F files pass; see 2.14).
