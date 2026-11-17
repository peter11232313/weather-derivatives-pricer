# 0004 — Settlement rounding: daily HDD, half-up, exact decimal

- Status: accepted
- Date: 2026-11-17
- Resolves: spec 001 rounding question; spec 003 open question 1

## Context
Exchange and OTC HDD contracts round to a stated precision before summing the index.
Two conventions exist:
- round the **daily mean temperature** (often to 0.1 °C), then take HDD; or
- round the **daily HDD** itself.

At base 18.0 they differ exactly on ties: mean 9.75 → 9.8 → HDD 8.2, whereas HDD 8.25 →
8.3. At 0 decimals the gap is larger (Tmax 10.0, Tmin 9.0: 279 vs 248 for a January).

## Decision
- The index engine (spec 001) stays at full precision.
- Rounding is a contract term (`HddContract.dailyHddDecimals`, opt-in, 0..4 decimals):
  each **daily HDD** is rounded **half-up**, then summed.
- The rounded daily HDD is computed in **exact decimal** from the stored Tmax/Tmin/base
  (`BigDecimal.valueOf`), not from the binary double, so exact ties round up. Rounding
  the double turned ~10% of 0.1 °C ties down (e.g. 13.65 = 13.649999999999999 → 13.6),
  a one-directional settlement bias found in the spec 003 quant review.

## Consequences
- A term sheet that rounds the daily mean instead needs a new option
  (e.g. `RoundingTarget {DAILY_HDD, DAILY_MEAN}`); check each confirmation against this.
- Ties only occur when temperatures are stored at settlement precision (0.1 °C station
  data). Unrounded ERA5 values almost never hit one.
- The rounded and unrounded paths can differ by ~1e-15 per day before rounding
  (decimal vs binary arithmetic); this never changes a rounded result.
- Detrended burn (spec 004, 2026-11-18): the trend shift is added to Tmax/Tmin in binary
  double before settlement rounding. The shift is an estimate far noisier than 1 ulp, so
  an exact tie after a shift may round either way; this is accepted.
