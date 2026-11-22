package com.weatherderivatives.pricer.model;

import com.weatherderivatives.pricer.contract.HddContract;
import com.weatherderivatives.pricer.contract.HddPayoff;
import java.util.Objects;

/**
 * Expected payoff of an HDD contract when the settlement index is normally distributed (spec 005).
 *
 * <p>With index {@code I ~ N(μ, σ²)}, strike {@code K}, tick {@code t} (£ per °C·day), cap {@code
 * C} (£), {@code L = C / t} and {@code d = (μ − K) / σ}:
 *
 * <ul>
 *   <li>call: {@code t · c(K)}, with {@code c(K) = (μ − K) Φ(d) + σ φ(d)}
 *   <li>put: {@code t · p(K)}, with {@code p(K) = (K − μ) Φ(−d) + σ φ(d)}
 *   <li>capped call: {@code t · (c(K) − c(K + L))}; capped put: {@code t · (p(K) − p(K − L))}
 *   <li>swap: {@code t · (μ − K)}; capped swap (clipped to ±C, as in {@link HddPayoff}): {@code t ·
 *       ((μ − K) − c(K + L) + p(K − L))}
 * </ul>
 *
 * <p>The price is undiscounted, with no risk loading. With {@code σ = 0} it is {@link
 * HddPayoff#payoffGbp} at {@code μ}. A cap so large that {@code K + L} is not finite counts as no
 * cap: it can never bind ({@code K − L} stays finite because {@code K ≥ 0}). Rounding can leave a
 * capped price a few ulp outside its bounds, so call and put prices are clamped to {@code [0,
 * min(C, uncapped)]} and capped swaps to {@code [−C, C]}.
 *
 * <p><b>Negative index:</b> the normal gives some probability to {@code I < 0}, which a real HDD
 * index cannot reach. When {@code μ ≥ 8σ} it is below 1e-15 (London: January μ ≈ 414, σ ≈ 36;
 * Nov–Mar μ ≈ 1,850, σ ≈ 111), so the distribution is not truncated.
 *
 * <p>Deep out-of-the-money prices are accurate in absolute terms (far below £1e-6), not relative to
 * their size.
 */
public final class IndexDistributionPricing {

  private IndexDistributionPricing() {}

  /**
   * Returns the expected payoff of {@code contract} under {@code N(mean, sd²)}.
   *
   * @param contract the contract terms (type, strike, tick, optional cap; month years are ignored)
   * @param meanIndexHddDegC mean of the settlement index, °C·day; finite and ≥ 0
   * @param sdIndexHddDegC standard deviation of the settlement index, °C·day; finite and ≥ 0
   * @return the expected payoff in GBP (negative only for a swap)
   * @throws NullPointerException if {@code contract} is null
   * @throws IllegalArgumentException if the mean or standard deviation is negative or not finite
   *     (names the argument)
   */
  public static double expectedPayoffGbp(
      HddContract contract, double meanIndexHddDegC, double sdIndexHddDegC) {
    Objects.requireNonNull(contract, "contract");
    requireFiniteNonNegative(meanIndexHddDegC, "meanIndexHddDegC");
    requireFiniteNonNegative(sdIndexHddDegC, "sdIndexHddDegC");
    if (sdIndexHddDegC == 0.0) {
      return HddPayoff.payoffGbp(contract, meanIndexHddDegC);
    }
    double mu = meanIndexHddDegC;
    double sigma = sdIndexHddDegC;
    double k = contract.strikeHddDegC();
    double tick = contract.tickGbpPerDegC();

    double capGbp = Double.POSITIVE_INFINITY;
    double capIndex = Double.POSITIVE_INFINITY;
    if (contract.capGbp().isPresent()) {
      capGbp = contract.capGbp().getAsDouble();
      capIndex = capGbp / tick;
    }
    // K + L overflowing (L itself infinite, or finite but huge) means the cap can never bind.
    boolean capped = Double.isFinite(k + capIndex);

    return switch (contract.type()) {
      case CALL -> {
        double uncapped = tick * call(mu, sigma, k);
        yield capped
            ? clamp(
                tick * (call(mu, sigma, k) - call(mu, sigma, k + capIndex)),
                0.0,
                Math.min(capGbp, uncapped))
            : uncapped;
      }
      case PUT -> {
        double uncapped = tick * put(mu, sigma, k);
        yield capped
            ? clamp(
                tick * (put(mu, sigma, k) - put(mu, sigma, k - capIndex)),
                0.0,
                Math.min(capGbp, uncapped))
            : uncapped;
      }
      case SWAP -> {
        if (!capped) {
          yield tick * (mu - k);
        }
        double clipped = (mu - k) - call(mu, sigma, k + capIndex) + put(mu, sigma, k - capIndex);
        yield clamp(tick * clipped, -capGbp, capGbp);
      }
    };
  }

  /** {@code E[(I − K)⁺]}, never negative. */
  private static double call(double mu, double sigma, double k) {
    double d = (mu - k) / sigma;
    double value = (mu - k) * NormalDistribution.cdf(d) + sigma * NormalDistribution.pdf(d);
    return Math.max(0.0, value);
  }

  /** {@code E[(K − I)⁺]}, never negative; {@code Φ(−d)} is taken directly, not as {@code 1 − Φ}. */
  private static double put(double mu, double sigma, double k) {
    double d = (mu - k) / sigma;
    double value = (k - mu) * NormalDistribution.cdf(-d) + sigma * NormalDistribution.pdf(d);
    return Math.max(0.0, value);
  }

  /** Clamps a capped price to {@code [low, high]} (rounding can leave it a few ulp outside). */
  private static double clamp(double value, double low, double high) {
    return Math.max(low, Math.min(high, value));
  }

  private static void requireFiniteNonNegative(double value, String name) {
    if (!Double.isFinite(value) || value < 0.0) {
      throw new IllegalArgumentException(name + " must be finite and >= 0 but was " + value);
    }
  }
}
