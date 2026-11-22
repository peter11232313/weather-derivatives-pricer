# 005 — Index-distribution pricing (normal)

_Status: approved by the owner 2026-11-22; implemented._

## Why
Burn prices a contract as the average of its past payoffs, so with 10–33 seasons the
price depends on which few seasons happened to land in the money. Fitting a distribution
to the past seasonal index values and taking the expected payoff gives a smoother price
from the same data. This is the standard next step after burn analysis.

## Scope
In:
- Fit a **normal distribution** to the seasonal settlement index values (with the contract's
  daily-HDD rounding). Mean = sample mean; standard deviation = sample standard deviation
  (n − 1).
- Two inputs for the fit:
  - plain: the seasons' index values as in `BurnAnalysis`;
  - detrended: the shifted index values from `DetrendedBurnAnalysis` for a target season.
- Price = **expected payoff under the fitted normal, in closed form**, for call, put and swap,
  with or without a cap. Undiscounted and with no risk loading, the same as burn.
- The result also carries the burn result for the same seasons, so model and burn can be
  compared.

Out (later specs):
- Other distributions (kernel density, skewed), goodness-of-fit tests and choosing a
  distribution automatically.
- Discounting, risk loading, bid/ask.
- Uncertainty of the fitted mean and standard deviation (parameter error).
- Truncating the normal at 0 HDD (see "Negative index" below).

## Inputs & outputs
Package `...model`.

```java
// Pure pricing function: no data, no seasons.
double IndexDistributionPricing.expectedPayoffGbp(HddContract contract, double meanIndexHddDegC, double sdIndexHddDegC);

IndexDistributionResult IndexDistributionAnalysis.run(HddContract contract, DailyTemps temps, int firstSeasonYear, int lastSeasonYear);
IndexDistributionResult IndexDistributionAnalysis.runDetrended(HddContract contract, DailyTemps temps, int firstSeasonYear, int lastSeasonYear, int targetSeasonYear);

record IndexDistributionResult(double meanIndexHddDegC, double sdIndexHddDegC, double expectedPayoffGbp, BurnResult burn)
```

Formulas use index `I ~ N(μ, σ²)`, strike `K`, tick `t` (£/°C·day), cap `C` (£) and
`L = C / t` (the cap in index points). `Φ` and `φ` are the standard normal CDF and density,
and `d = (μ − K) / σ`.

| Contract | Expected payoff |
| --- | --- |
| Call | `t · c(K)`, where `c(K) = (μ − K) Φ(d) + σ φ(d)` |
| Put | `t · p(K)`, where `p(K) = (K − μ) Φ(−d) + σ φ(d)` |
| Call with cap | `t · (c(K) − c(K + L))` |
| Put with cap | `t · (p(K) − p(K − L))` |
| Swap | `t · (μ − K)` |
| Swap with cap (clipped to ±C, as in `HddPayoff`) | `t · ((μ − K) − c(K + L) + p(K − L))` |

When `σ = 0`, the price is the payoff at `μ`.

**Worked example** (μ = 400, σ = 50, tick £20). Each value was also checked by numerical
integration to 1e-8.

| Contract | Expected payoff (£) |
| --- | --- |
| Call, K = 400 | **398.942280** (= 20 · 50 · φ(0)) |
| Put, K = 420 | **630.438837** |
| Call, K = 400, cap £500 | **201.145723** |
| Put, K = 420, cap £500 | **279.503506** |
| Swap, K = 380 | **400.000000** |
| Swap, K = 380, cap £500 | **149.495806** |

**Fit example:** five seasons with index 360, 400, 400, 400, 440 give μ = 400 and
σ = √(3200 / 4) = 28.284271 (n − 1). A call at K = 400 with a £20 tick prices at **225.675833**.

**Negative index:** the normal puts some probability below 0 HDD, which a real index
cannot reach. For London (μ ≥ 8σ: January μ ≈ 414, σ ≈ 35; Nov–Mar μ ≈ 1,850,
σ ≈ 110) that probability is below 1e-15, so it is documented rather than truncated.

## Acceptance criteria
- [x] Given μ = 400, σ = 50, tick £20, then `expectedPayoffGbp` returns each value in the worked-example table (tolerance 1e-6).
- [x] Given σ = 0, then the price equals `HddPayoff` at μ for call, put and swap, with and without a cap (e.g. call K = 380 on μ = 400 → £400; with cap £300 → £300).
- [x] Given a non-finite or negative μ, or a non-finite or negative σ, then `IllegalArgumentException` names the argument.
- [x] Given put-call parity without a cap, then call(K) − put(K) = t · (μ − K) (tolerance 1e-9, several K including far out of the money).
- [x] Given a cap, then the price is never above the cap and never above the uncapped price (call, put; swap within ±cap).
- [x] Given σ → large for an at-the-money call, then the price grows like `t · σ · φ(0)` (with σ = 1e4: £79,788.456 at a £20 tick).
- [x] Given five seasons with index 360, 400, 400, 400, 440 (synthetic temperatures), when `run` is called, then μ = 400, σ = 28.284271 and a call at K = 400 with a £20 tick prices at **225.675833** (tolerance 1e-6); `burn()` equals `BurnAnalysis.run` on the same input.
- [x] Given the contract's daily-HDD rounding, then the fit uses the rounded settlement index (the same index values as `BurnAnalysis` reports).
- [x] Given `runDetrended`, then μ and σ are fitted to the shifted index values that `DetrendedBurnAnalysis` reports for the same target, and `burn()` equals its burn.
- [x] Given fewer seasons than the minimum (5 for `run`, 10 for `runDetrended`) or `firstSeasonYear > lastSeasonYear`, then `IllegalArgumentException` before any data is read; a missing day names the season and date (as burn does).
- [x] Given all seasons with the same index, then σ = 0 and the price equals that season's payoff.
- [x] Javadoc on every public method with units; the formulas and the negative-index note are in the class Javadoc; README has a usage example.

## Decisions (approved 2026-11-22)
1. **Minimum seasons:** 5 for `run` (a normal fitted to 2 points means little; burn allows 2)
   and 10 for `runDetrended` (same as the detrended burn).
2. **Normal only in v1.** Kernel density may follow in a later amendment behind the same
   result type.
3. **No parameter uncertainty in v1.** With 10 seasons σ is itself uncertain by about ±25%;
   a later spec may add a standard error for the price.
4. **Planner findings (owner OK 2026-11-22):** the fit example uses five seasons (two would
   be below the minimum of 5; μ, σ and the price are unchanged). Φ is written in-house
   (W. J. Cody's erfc, package-private in `model`), not taken from a new dependency, because
   parity at 1e-9 needs Φ(d) + Φ(−d) = 1 to about 1e-13. Deep out-of-the-money prices need
   only absolute accuracy in v1.

## Open questions
None.
