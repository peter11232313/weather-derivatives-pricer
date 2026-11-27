# 006 — REST price endpoint

_Status: approved by the owner 2026-11-22; implemented._

## Why
The pricer is a Java library today: pricing a contract means writing code. A local HTTP
endpoint lets a user (or a spreadsheet or script) send contract terms and get the burn,
detrended burn and normal-model prices back in one call, as AGENTS.md already describes
(`./gradlew bootRun` → `POST http://localhost:8080/price`).

## Scope
In:
- `POST /price` with a JSON contract and season range. The response holds every method
  that applies:
  - always: burn (spec 003) and the normal model (spec 005);
  - only when `targetSeasonYear` is given: detrended burn (spec 004) and the detrended
    normal model.
- `GET /health` returns `{"status":"ok"}`.
- The data folder is configurable and defaults to `data/pricer`. The city file is read on
  each request (a 12,000-row file loads in milliseconds), so a republished file is picked
  up without a restart.
- Errors return JSON `{"error": "<code>", "message": "<text>"}` with the status codes in
  the table below. The message is the library's own (it names the field, date or path).
- Package `...api` is a REST layer only: it maps JSON to `HddContract`, calls the existing
  analyses and maps the results back. No pricing logic lives in it.

Out (later specs):
- Authentication, HTTPS, deployment, rate limiting. The server listens on localhost only.
- CDD, collars, strips, discounting, OU simulation.
- Caching loaded files; a web UI.

## Inputs & outputs
Request:

```json
{
  "city": "london",
  "type": "CALL",
  "firstMonth": "2001-01",
  "lastMonth": "2001-01",
  "strikeHddDegC": 400.0,
  "tickGbpPerDegC": 20.0,
  "capGbp": null,
  "dailyHddDecimals": 1,
  "baseC": 18.0,
  "firstSeasonYear": 1992,
  "lastSeasonYear": 2001,
  "targetSeasonYear": null
}
```

- **Optional fields:** `capGbp` (null means uncapped), `dailyHddDecimals` (null means full
  precision), `baseC` (default 18.0) and `targetSeasonYear` (null means no detrended
  results).
- **Unknown fields** are rejected, so a typo such as `strikeHdd` is not silently ignored.

Response (200):

```json
{
  "city": "london",
  "seasons": [{"seasonYear": 1992, "indexHddDegC": 435.8, "payoffGbp": 716.0}, "..."],
  "burn": {"expectedPayoffGbp": 436.0, "stdErrorGbp": 178.10, "payoffStdDevGbp": 563.21,
           "minPayoffGbp": 0.0, "maxPayoffGbp": 1732.0, "meanIndexHddDegC": 414.3},
  "normal": {"expectedPayoffGbp": 456.17, "meanIndexHddDegC": 414.3, "sdIndexHddDegC": 36.4},
  "detrended": null
}
```

When `targetSeasonYear` is given, `detrended` holds:
- `targetSeasonYear`;
- `slopeDegCPerYear` and `slopeStdErrDegCPerYear`;
- the shifted `seasons`, each also with `seasonMeanTempC` and `shiftDegC`;
- `burn` (same fields as above);
- `normal` (same fields as above).

Errors:

| Status | `error` | When |
| --- | --- | --- |
| 400 | `invalid_request` | Malformed JSON, a missing or unknown field, a wrong type, or an invalid term (e.g. negative tick, `lastMonth` before `firstMonth`, a season range below the minimum). |
| 404 | `city_not_found` | The city name is invalid or `<data>/<city>.parquet` does not exist. |
| 422 | `data_error` | The file is unreadable or invalid, or a season has a missing day (the message names the season and date). |
| 404 / 405 / 415 | `not_found` / `method_not_allowed` / `unsupported_media_type` | Unknown route, wrong HTTP method or wrong content type (Spring's own status, kept; not turned into 500). |
| 500 | `internal_error` | Anything else. Logged; no stack trace in the response. |

**Worked example:** a test-written file has five January seasons with indices 360, 400,
400, 400 and 440. A call with K = 400 and a £20 tick, full precision, seasons 2001..2005
gives:
- payoffs 0, 0, 0, 0 and 800, so **burn = £160.00**;
- **normal = £225.675833** (μ = 400, σ = 28.284271, as in spec 005).

## Acceptance criteria
- [x] Given the worked-example file and request, when `POST /price` is called, then the response is 200 with burn **160.0**, normal **225.675833** (tolerance 1e-6), μ = 400, σ = 28.284271, five seasons in year order, and `detrended` null.
- [x] Given `targetSeasonYear` and at least 10 seasons, then `detrended` equals `DetrendedBurnAnalysis.run` and `IndexDistributionAnalysis.runDetrended` on the same input (slope, its standard error, shifted seasons, both prices).
- [x] Given `targetSeasonYear` with fewer than 10 seasons, then 400 `invalid_request`, with a message naming the minimum.
- [x] Given fewer than 5 seasons (the normal model's minimum, spec 005; `normal` is always computed), then 400 `invalid_request`, with a message naming the minimum.
- [x] Given an unknown route, a wrong method (e.g. `GET /price`) or a non-JSON content type, then 404 / 405 / 415 with the `{error, message}` body.
- [x] Given each priced field (`strikeHddDegC`, `tickGbpPerDegC`, `capGbp`, `baseC`, `dailyHddDecimals`, months, season years), the API result equals the library result for the same terms. This holds for call, put and swap, with and without a cap.
- [x] Given malformed JSON, a missing required field, an unknown field, a wrong type or an invalid term, then 400 `invalid_request`, with a message naming the field.
- [x] Given an unknown city (`paris`, with no file) or an invalid name (`../x`), then 404 `city_not_found`. No path outside the data folder is ever read.
- [x] Given a file with a missing day inside a season, then 422 `data_error`, with a message naming the season and date. Given a file outside the range limits (spec 001 amendment A), then 422.
- [x] Given an unexpected exception, then 500 `internal_error`, with no stack trace or file system details in the body.
- [x] Given `GET /health`, then 200 `{"status":"ok"}`.
- [x] Given the data folder is set by configuration (e.g. `pricer.data-dir`), then files are read from it. The default is `data/pricer` relative to the repo root (`bootRun` runs from the repo root).
- [x] Given a republished file, then the next request uses it without a restart.
- [x] The server binds to `127.0.0.1` only.
- [x] README shows how to start the server and a `curl` example. Every public class has Javadoc. The `api` package holds no pricing logic.

## Decisions (approved 2026-11-22)
1. **Framework: Spring Boot (web starter)**, as AGENTS.md already names `bootRun`. It brings
   JSON mapping, validation, error handling and `MockMvc` tests. The new dependency is
   justified in the PR.
2. **One call returns every method that applies** (burn and normal always; detrended burn
   and detrended normal when `targetSeasonYear` is given). There is no `method` field.
3. **Season range always explicit** (`firstSeasonYear` / `lastSeasonYear` required), to
   keep back-tests free of look-ahead.

4. **Planner findings (owner OK 2026-11-22):**
   - The loader throws the same exception type for a missing file and for bad data, so a
     small library change adds `CityFileNotFoundException extends TemperatureDataException`
     (missing file only), mapped to 404. An invalid city name (the loader's
     `IllegalArgumentException`) is also mapped to 404, but only around the load call.
   - `bootRun` runs from the repo root, so the default `data/pricer` is the same folder as
     data-prep's.
   - Detrended seasons also carry `seasonMeanTempC` and `shiftDegC`.
   - Spring's own 404 / 405 / 415 keep their status, with the JSON error body.
   - `/price` needs at least 5 seasons because `normal` is always computed; documented.
   - Test helper `ParquetFixtures` becomes public so API tests can write Parquet files
     (visibility only).

5. **Review follow-ups (owner OK 2026-11-22):** enums and text fields reject numbers and
   booleans (Jackson read `"type": 1` as the enum index, i.e. PUT); Spring's 405 / 415 keep their
   `Allow` / `Accept` headers; 406 maps to `not_acceptable`; an out-of-range number names its field;
   library argument errors become 400 only around the contract and analysis calls (any other
   `IllegalArgumentException` is a 500).

## Open questions
None.

## Amendment A — look-ahead guard (approved 2026-11-25)

### Why
`POST /price` accepts a `targetSeasonYear` inside `firstSeasonYear..lastSeasonYear`; the
priced season then feeds both the trend fit and the burn. In a back-test that is
look-ahead: the realised outcome enters its own price. The README only advised against it.
Raised by quant-reviewer and met-reviewer in the spec 006 re-review (2026-11-24). Decision 3
already keeps the season range explicit "to keep back-tests free of look-ahead"; this makes
the API enforce it.

### Scope
In:
- `POST /price` rejects `firstSeasonYear ≤ targetSeasonYear ≤ lastSeasonYear` with 400
  `invalid_request`; the message names `targetSeasonYear`, the window and "look-ahead".
- Checked with the other request checks, before the data is loaded (so an unknown city or
  fewer than 10 seasons with an in-window target still gives this 400).
- A target before the window (`< firstSeasonYear`) stays allowed: no leakage of the priced
  season's own outcome, but it is a hindcast (trend fitted on later seasons), not a
  walk-forward back-test (clarified after quant review, 2026-11-25).

Out:
- The library (`DetrendedBurnAnalysis`, `IndexDistributionAnalysis.runDetrended`) is
  unchanged; the rule lives in the API only.
- A far-future target limit; flagging an insignificant trend.

### Acceptance criteria
- [x] Given seasons 2001–2010 and target 2011, when priced, then 200 with `detrended` (unchanged).
- [x] Given target 2010 (= last), 2001 (= first) or 2005 (inside), then 400 `invalid_request` naming `targetSeasonYear`, 2001, 2010 and "look-ahead".
- [x] Given target 2000 (before the window), then 200 with `detrended`.
- [x] Given an unknown city and an in-window target, then 400, not 404.
- [x] Given 5 seasons and an in-window target, then the look-ahead 400, not the 10-season 400.
- [x] Given no `targetSeasonYear`, then behaviour unchanged.
- [x] All other Java tests green; README (look-ahead paragraph becomes a rule), CHANGELOG, PROGRESS and the development log updated.

### Decision
Owner OK 2026-11-25: reject (400), not warn: a warning is easy to ignore, and target = last
("detrend to the latest season") is no longer allowed via the API (use `lastSeasonYear + 1`);
a target before the window stays allowed; the existing row in
`PriceEndpointPricingTest.ac2_targetSeasonYear_variousTerms_detrendedEqualsLibrary` with
seasons 2001–2010 and target 2001 changes its target to 2000 (input only; assertion unchanged).
Planner findings (owner OK 2026-11-25): guard in `PriceService.price()` after the season
years are required, before `load()`; order: contract terms → season years present →
look-ahead → load (404/422) → library (first > last, 5/10-season minimums). With
`first > last` the window is empty, so the guard is silent and the library's 400 follows
the load as today. The message suggests `lastSeasonYear + 1` (computed in `long`).

## Amendment B — `priceGbp` renamed `expectedPayoffGbp` (approved 2026-11-26)

### Why
The response calls both the burn and the normal result `priceGbp`, but neither is a quotable
price: both are undiscounted expected payoffs with no risk loading, and the README has to
warn about it. Raised by quant-reviewer in the spec 006 re-review (2026-11-24). The library
already uses the honest name (`IndexDistributionPricing.expectedPayoffGbp`,
`IndexDistributionResult.expectedPayoffGbp()`); the JSON follows it.

### Scope
In:
- `burn.priceGbp` → `burn.expectedPayoffGbp` and `normal.priceGbp` →
  `normal.expectedPayoffGbp`, everywhere they appear (also inside `detrended.burn` and
  `detrended.normal`), via `BurnDto` and `NormalDto`.
- Clean break, no alias for the old name: the API is localhost-only, has no outside clients
  and no version yet. CHANGELOG marks it **breaking**.
- README and this spec's example response and wording updated.

Out:
- Library names unchanged: `BurnResult.burnPriceGbp` stays ("burn price" is the market term
  for the historical mean payoff).
- Other response fields unchanged (`stdErrorGbp`, `payoffStdDevGbp`, `minPayoffGbp`,
  `maxPayoffGbp`, season `payoffGbp`).
- Normal-fit diagnostics; flagging an insignificant trend.

### Acceptance criteria
- [x] Given any valid request, the response has `burn.expectedPayoffGbp` and `normal.expectedPayoffGbp` with the values the old fields had, and no `priceGbp` key anywhere.
- [x] Given a `targetSeasonYear`, `detrended.burn.expectedPayoffGbp` and `detrended.normal.expectedPayoffGbp` are present, values unchanged.
- [x] Library API and library tests unchanged.
- [x] All other Java tests green; README, this spec (example response), CHANGELOG (breaking), PROGRESS and the development log updated.

### Decision
Owner OK 2026-11-26: JSON only, clean break (no alias); `BurnResult.burnPriceGbp` keeps its
name. API tests that read `priceGbp` (`ApiTestSupport`, `PriceEndpointPricingTest`) switch to
`expectedPayoffGbp` with unchanged expected values, plus an assertion that no `priceGbp` key
remains (renames, not weakening; owner OK).
Planner findings (`PLAN: READY`, owner OK 2026-11-26): only `BurnDto` and `NormalDto` change
(`PriceService` builds them by position); Javadoc wording "price" → "expected payoff" in
`PriceResponse` and `DetrendedDto`. Tests: `ApiTestSupport.assertBurn`/`assertNormal` and the
nine `priceGbp` keys in `PriceEndpointPricingTest` switch name, values unchanged; a new
recursive `assertNoFieldAnywhere` proves no `priceGbp` key remains. Reviewers: code + quant.

## Amendment C — settlement currency, trend significance, normal-fit diagnostics (approved 2026-11-26)

### Why
Three quant-review findings (spec 006 re-review, 2026-11-24) that make the real London results
readable once 1991–2023 is published:
- Every amount is labelled GBP, but only London settles in GBP; a € city with a file would be
  priced and reported as £.
- With 10–30 seasons the fitted trend is often noise (London Jan: −0.077 ± 0.134 °C/yr), yet the
  detrended prices look as firm as the plain ones.
- The normal price assumes a symmetric index; shoulder-month indices can be skewed, and the
  response gives no way to see it.

### Scope
In:
- **Currency.** New property `pricer.settlement-currency.<city>=<ISO code>`; the default
  configuration has `london=GBP`. A city not in the map → 404 `city_not_found`, message names the
  city and the configured cities. Checked after the look-ahead guard and before `load()`, so an
  unconfigured city never reads a file. Response gains top-level `"currency": "GBP"`.
  Only `GBP` is accepted as a value for now (amount fields are named `…Gbp`); any other value fails
  at startup naming the city. A € city needs a later amendment that renames the amount fields.
- **Trend significance (library + API).** `DetrendedBurnResult.slopeTStat()` = slope / its
  standard error, as `OptionalDouble`, empty when the standard error is 0;
  `slopeSignificant()` = |t| ≥ `SIGNIFICANT_T_STAT` (2.0); when the standard error is 0,
  significant iff the slope ≠ 0. JSON: `detrended.slopeTStat` (number or null) and
  `detrended.slopeSignificant` (boolean).
- **Normal-fit diagnostics (library + API).** `IndexDistributionResult.seasonCount()` and
  `indexSkewness()`: adjusted Fisher–Pearson sample skewness
  G1 = √(n(n−1)) / (n−2) · m3 / m2^{3/2}, with m_k = (1/n) Σ (x − x̄)^k over the settlement index
  values the normal was fitted to (shifted values for the detrended normal); `OptionalDouble`,
  empty when m2 = 0. JSON: `normal.seasonCount`, `normal.indexSkewness` (number or null), also
  under `detrended.normal`.

Out:
- Non-GBP settlement, amount-field renames, a Java station registry (valid-but-wrong-city files).
- Changing any price; a different significance test (t-distribution critical values); kernel
  density or other skew-aware pricing.

### Acceptance criteria
- [ ] Given the default configuration and city `london`, then 200 and `"currency": "GBP"`; prices unchanged.
- [ ] Given a city with a valid file but not in `pricer.settlement-currency`, then 404 `city_not_found` naming the city and the configured cities, and the file is not read.
- [ ] Given an unconfigured city with an in-window target, then 400 look-ahead (amendment A order kept); given an unknown city with no file, still 404.
- [ ] Given `pricer.settlement-currency.<city>` set to anything but `GBP` (e.g. `EUR`, `gbp`), then the application fails to start with a message naming the city and the value.
- [ ] Given a detrended run, then `slopeTStat` = slope / standard error (tolerance 1e-12) and `slopeSignificant` is true iff |t| ≥ 2; at |t| exactly 2 it is true.
- [ ] Given a perfectly linear trend (standard error 0), then `slopeTStat` is empty/null and `slopeSignificant` is true; given slope 0 and standard error 0, false.
- [ ] Given index values {0, 0, 0, 0, 10}, then `indexSkewness` = √5 (2.2360680, tolerance 1e-9) and `seasonCount` = 5; given a symmetric set, 0 (tolerance 1e-12); given σ = 0, empty/null.
- [ ] Given `targetSeasonYear`, then `detrended.normal` carries `seasonCount` and `indexSkewness` computed on the shifted values.
- [ ] Existing prices, statuses and messages unchanged except the new 404 for unconfigured cities; existing API test classes only add their fixture cities to the currency map (setup change, owner OK).
- [ ] All tests green; README ("How to read the numbers": currency, t-stat, skewness), CHANGELOG, PROGRESS and the development log updated.

### Decision
Owner OK 2026-11-26: configured currency map (only `GBP` for now), boolean + t-stat, n and
skewness. Planner findings (`PLAN: READY`, owner OK 2026-11-26):
- `PricerProperties` gets `Map<String,String> settlementCurrency` (empty when absent);
  `london=GBP` lives in `application.properties`; test classes add their fixture cities via
  `@DynamicPropertySource` (entries merge). Startup fails on a value other than exactly `GBP`
  (names city and value) and on a key not matching `[a-z0-9_-]+` (names the key; owner OK).
  Keys should be written in lower case (relaxed binding may normalise them).
- `PriceService(TemperatureSource, PricerProperties)`; currency check after `rejectLookAhead`,
  before `load()`; message lists the configured cities sorted. Invalid names (`../x`) now get
  their 404 from this check; the loader's own tests still cover its name check.
- `slopeTStat()` empty when the standard error is 0 or NaN; `indexSkewness()` empty when
  m2 = 0 or n < 3. Both are derived methods; record constructors unchanged.
- Existing tests: fixture cities added to the map (PriceEndpointPricingTest,
  PriceEndpointErrorsTest), `PriceServiceTest` passes the properties, and the three exact
  field-set assertions gain the new names; nothing removed (owner OK).
- Known answers: six seasons with means 9.0, 9.75, 8.0, 11.25, 10.75, 11.25 → slope 0.5,
  SE 0.25, t = 2 exactly; five seasons 9.5, 8.5, 10.0, 11.5, 10.5 → t = √3; skewness of
  {0,0,0,0,10} = √5. Reviewers: code + quant.
