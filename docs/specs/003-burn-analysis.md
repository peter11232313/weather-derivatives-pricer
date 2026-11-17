# 003 — HDD contract payoffs and burn analysis

## Why
A trader quoting an HDD call, put or swap needs a first, model-free price: what the
contract would have paid in each past season, and the mean and spread of those payoffs
("burn analysis"). This is the first pricing model, and it carries the per-contract
settlement rounding deferred from spec 001.

## Scope
In:
- **HDD contract terms** (package `...contract`): accrual period as whole calendar months,
  base temperature, strike, tick, optional cap, optional daily settlement rounding.
- **Payoffs** for the long side, in GBP: call, put, swap.
- **Settlement index**: cumulative HDD over the contract's accrual period, using spec 001's
  `HddIndex`, with the contract's rounding applied.
- **Burn analysis** (package `...model`): for each historical season in a year range,
  shift the accrual period to that season, compute the index and payoff, then summarise.

Out (later specs):
- Detrending (warming trend) and any bias adjustment between ERA5 and station data.
- Discounting, risk loading, the bid/ask spread. Burn price = undiscounted mean payoff.
- Collars, strips, CDD contracts, index-distribution and daily OU simulation models.
- REST endpoint, CLI.

## Inputs & outputs
### Contract terms (`HddContract`, immutable record)

| field | type | rule |
| --- | --- | --- |
| `type` | `CALL`, `PUT`, `SWAP` | required |
| `firstMonth`, `lastMonth` | `YearMonth` | inclusive, `firstMonth ≤ lastMonth`, at most 12 months |
| `baseC` | double | finite; default 18.0 |
| `strikeHddDegC` | double | finite, ≥ 0 |
| `tickGbpPerDegC` | double | finite, > 0 (GBP per index point, i.e. per °C·day) |
| `capGbp` | `OptionalDouble` | empty = uncapped; else finite, > 0 |
| `dailyHddDecimals` | `OptionalInt` | empty = full precision; else 0..4 |

Whole calendar months mean leap days are handled naturally: Nov–Mar is 152 days in a
leap season and 151 otherwise, as in spec 001.

### Settlement index
`indexHddDegC = Σ dailyHdd` over every UTC day from the first day of `firstMonth` to the
last day of `lastMonth`. With `dailyHddDecimals = d`, each daily HDD is rounded **half-up
to d decimals** (on its shortest decimal representation, `BigDecimal.valueOf`) before
summing, and the total is rounded to d decimals as well, so floating-point noise in the sum
cannot show.

### Payoffs (long side, GBP)

| type | payoff before cap | with cap C |
| --- | --- | --- |
| call | `tick × max(0, I − K)` | `min(C, …)` |
| put | `tick × max(0, K − I)` | `min(C, …)` |
| swap | `tick × (I − K)` | clamped to `[−C, C]` |

### Burn analysis
Input: an `HddContract`, a `DailyTemps` series, and `firstSeasonYear..lastSeasonYear`
(inclusive). A season is labelled by the year of its first accrual day: for a Nov–Mar
contract, season 1991 = 1991-11-01..1992-03-31. Each season shifts `firstMonth` and
`lastMonth` by the same number of years.

Output `BurnResult`:
- `seasons`: one row per season in year order: `seasonYear`, `indexHddDegC`, `payoffGbp`.
- `burnPriceGbp`: mean payoff. `payoffStdDevGbp`: sample standard deviation (n − 1).
- `minPayoffGbp`, `maxPayoffGbp`, `meanIndexHddDegC`, `seasonCount`.

Hand-computed example (January contract, base 18 °C, strike 240, tick £1,000 per °C·day;
every January day has the same temperatures within a season):

| season | Tmax / Tmin | daily mean | daily HDD | index (31 days) |
| --- | --- | --- | --- | --- |
| 2001 | 14.0 / 6.0 | 10.0 | 8.0 | 248.0 |
| 2002 | 13.0 / 5.0 | 9.0 | 9.0 | 279.0 |
| 2003 | 15.0 / 7.0 | 11.0 | 7.0 | 217.0 |

| contract | payoffs 2001, 2002, 2003 | burn price | sample std dev | min / max |
| --- | --- | --- | --- | --- |
| call K=240 | 8,000; 39,000; 0 | **15,666.67** | 20,599.35 | 0 / 39,000 |
| call K=240, cap £30,000 | 8,000; 30,000; 0 | **12,666.67** | 15,534.91 | 0 / 30,000 |
| put K=260 | 12,000; 0; 43,000 | **18,333.33** | 22,188.59 | 0 / 43,000 |
| swap K=240 | 8,000; 39,000; −23,000 | **8,000.00** | 31,000.00 | −23,000 / 39,000 |

## Acceptance criteria
- [ ] Given the three-season January example, when burn analysis runs for a call with K=240 and tick £1,000, then the indices are 248.0, 279.0 and 217.0, the payoffs are 8,000, 39,000 and 0, and the burn price is **15,666.67** (tolerance 0.01) with sample std dev 20,599.35 (tolerance 0.01), min 0 and max 39,000.
- [ ] Given the same data, the capped call (cap £30,000), put (K=260) and swap (K=240) give the burn prices, std devs, mins and maxes in the example table (tolerance 0.01).
- [ ] Given a swap with cap C, then a payoff below −C is clamped to −C and above C to C.
- [ ] Given a payoff exactly at the strike (I = K), then call, put and swap all pay 0.0.
- [ ] Given a Nov–Mar contract, then season 1991 accrues 1991-11-01..1992-03-31 (152 days, leap) and season 1992 accrues 1992-11-01..1993-03-31 (151 days).
- [ ] Given `dailyHddDecimals = 1` and 31 January days with Tmax 14.0, Tmin 5.86 (daily HDD 8.07), then the index is **251.1** (31 × 8.1); with no rounding it is 250.17 (tolerance 1e-9).
- [ ] Given `dailyHddDecimals = 1` and a daily HDD of exactly 8.25 (Tmax 10.0, Tmin 9.5), then it rounds half-up to **8.3**.
- [ ] Given a season with a missing day, then an exception names the missing date and the season (no silent skip).
- [ ] Given fewer than 2 seasons, or `firstSeasonYear > lastSeasonYear`, then `IllegalArgumentException` (a sample std dev needs 2 values).
- [ ] Given invalid terms (`firstMonth > lastMonth`, more than 12 months, negative strike, tick ≤ 0, cap ≤ 0, non-finite values, decimals outside 0..4), then construction fails with `IllegalArgumentException` naming the field.
- [ ] Results are deterministic: the same inputs give bit-identical results (no randomness, fixed summation order).
- [ ] Unit names follow AGENTS.md (`hddDegC`, `payoffGbp`, `tickGbpPerDegC`); public types and methods have Javadoc.

## Open questions
None open. Resolved 2026-11-17:
- **Rounding:** opt-in per contract, half-up rounding of each day's HDD (not of the daily
  mean temperature); default full precision.
- **Season label:** the year of the first accrual day (1991 = winter 1991/92).
- **Detrending:** not in this spec; the raw burn's warming bias is accepted and
  detrending is the next model spec.
