# 004 — Detrended burn analysis

## Why
The raw burn (spec 003) treats 1991 winters as if they could happen again in the season
being priced. Under warming, older seasons were colder, so the raw burn overstates HDD:
calls and long swaps are priced too high and puts too low. A desk adjusts history to
today's climate before averaging payoffs.

## Scope
In:
- **Linear trend in temperature**, fitted by ordinary least squares on one value per
  season: the mean daily temperature `(Tmax + Tmin) / 2` over that season's accrual
  period, against the season year.
- **Temperature detrending**: each historical season's daily Tmax and Tmin are shifted
  by `shiftDegC(y) = slope × (targetSeasonYear − y)`; the settlement index (with the
  contract's rounding) and payoff are then recomputed from the shifted days.
- **Detrended burn**: same statistics as spec 003, plus the fitted trend and each
  season's shift. The raw burn (spec 003) is unchanged.

Out (later specs):
- Non-linear trends (quadratic, LOESS, step changes), trend significance tests, a trend
  window different from the burn window, index-level detrending.
- ERA5-vs-station bias, leap-day policy, discounting, risk loading.

## Inputs & outputs
Input: an `HddContract`, `DailyTemps`, `firstSeasonYear..lastSeasonYear` (the burn and
trend window, **at least 10 seasons**), and `targetSeasonYear` (the season the shifted
history represents, normally the season being priced; any year is accepted).

Steps:
1. For each season y, `seasonMeanTempC(y)` = mean of `(tmax + tmin) / 2` over the
   accrual days (missing day → error naming season and date, as spec 003).
2. OLS on points `(y, seasonMeanTempC(y))` gives `slopeDegCPerYear`.
3. For each season, shift every accrual day's Tmax and Tmin by `shiftDegC(y)`, compute
   the settlement index and payoff, then the spec 003 statistics.

Output `DetrendedBurnResult`: the `BurnResult` of the shifted seasons, plus
`slopeDegCPerYear`, `targetSeasonYear`, and per season `seasonMeanTempC` and `shiftDegC`.

Why temperature rather than index detrending: HDD is `max(0, base − T)`, so a warming
shift that lifts days above the base must give 0, not a negative contribution. A linear
trend on the index ignores this (third example below).

### Examples (January contract, base 18 °C, tick £1,000; seasons 2001–2010; target 2011)
Every January day in a season has the same Tmax/Tmin, with Tmax = mean + 4, Tmin = mean − 4.

**Exact trend** — season means 10.0, 10.1, …, 10.9 (Tmax/Tmin 14.0/6.0 … 14.9/6.9):
slope **0.1 °C/yr**; shifts 1.0, 0.9, …, 0.1; every shifted mean is 11.0, so each index
is 31 × 7.0 = **217.0**. Call K=210: payoffs 7,000 each → detrended burn **7,000.00**,
std dev 0. (Raw indices 248.0 … 220.1 → raw burn **24,050.00**.)

**Trend plus noise** — season means 11.0, 9.2, 9.4, 11.6, 10.8, 11.0, 12.2, 10.4, 10.6,
12.8 (a 0.2 °C/yr trend plus noise +1, −1, −1, +1, 0, 0, +1, −1, −1, +1, which does not
change the slope): slope **0.2 °C/yr**; shifted means 13, 11, 11, 13, 12, 12, 13, 11, 11,
13 → indices 155, 217, 217, 155, 186, 186, 155, 217, 217, 155. Call K=200: payoffs 0;
17,000; 17,000; 0; 0; 0; 0; 17,000; 17,000; 0 → detrended burn **6,800.00**, std dev
**8,778.76** (raw burn 26,160.00).

**Crossing the base** — season means 14.0, 14.5, …, 18.5 (Tmax = Tmin): slope 0.5 °C/yr;
every shifted mean is 19.0 → every index **0.0**. (Raw indices 124.0, 108.5, …, 0, 0; a
linear trend on the index would extrapolate below zero.)

## Acceptance criteria
- [ ] Given the exact-trend example, then the slope is 0.1 °C/yr (tolerance 1e-9), the shifts are 1.0, 0.9, …, 0.1 (tolerance 1e-9), every index is 217.0 (tolerance 1e-9), and the call K=210 detrended burn is **7,000.00** (tolerance 0.01) with std dev 0 (tolerance 1e-6); the raw burn on the same data is 24,050.00.
- [ ] Given the trend-plus-noise example, then the slope is 0.2 °C/yr (tolerance 1e-9), the indices are 155, 217, 217, 155, 186, 186, 155, 217, 217, 155 (tolerance 1e-9), and the call K=200 detrended burn is **6,800.00** with std dev 8,778.76 (tolerance 0.01).
- [ ] Given the crossing-the-base example, then every detrended index is 0.0, never negative.
- [ ] Given a flat history (identical seasons, so the slope is exactly 0), then for any `targetSeasonYear` every shift is 0 and the detrended burn equals the raw spec 003 burn bit for bit.
- [ ] Given a contract with `dailyHddDecimals = 1`, then rounding is applied to the shifted daily HDD (settlement on the adjusted temperatures).
- [ ] Given a Nov–Mar contract, then the season mean uses all accrual days of that season (152 in a leap season, 151 otherwise), across the year end.
- [ ] Given fewer than 10 seasons, or `firstSeasonYear > lastSeasonYear`, then `IllegalArgumentException` before any data is read.
- [ ] Given a missing day in any season, then an exception names the season and the date (no silent skip).
- [ ] Shifting keeps each day's `tmin ≤ tmax` and finite values; the input `DailyTemps` is not modified.
- [ ] Results are deterministic: the same inputs give bit-identical results.
- [ ] Unit names follow AGENTS.md (`slopeDegCPerYear`, `shiftDegC`, `seasonMeanTempC`); public types and methods have Javadoc.

## Open questions
None open. Resolved 2026-11-18:
- **What to detrend:** daily temperatures (shift Tmax and Tmin), not the seasonal index.
- **Trend input:** each season's accrual-period mean temperature, not the calendar-year mean.
- **Minimum seasons:** 10, to avoid fitting a trend to noise.
- **Target season:** an explicit `targetSeasonYear`.
