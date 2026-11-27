package com.weatherderivatives.pricer.model;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Result of an index-distribution analysis (spec 005): the normal fitted to the seasons' settlement
 * index values, the expected payoff under it, and the burn result for the same seasons.
 *
 * @param meanIndexHddDegC fitted mean of the settlement index, °C·day
 * @param sdIndexHddDegC fitted sample standard deviation (n − 1) of the settlement index, °C·day
 * @param expectedPayoffGbp expected payoff under the fitted normal, GBP (undiscounted)
 * @param burn burn (or detrended burn) result for the same seasons, for comparison
 */
public record IndexDistributionResult(
    double meanIndexHddDegC, double sdIndexHddDegC, double expectedPayoffGbp, BurnResult burn) {

  /**
   * Checks the burn result is present.
   *
   * @throws NullPointerException if {@code burn} is null
   */
  public IndexDistributionResult {
    Objects.requireNonNull(burn, "burn");
  }

  /**
   * Returns the number of seasons the normal was fitted to (spec 006 amendment C).
   *
   * @return the season count of {@link #burn()}
   */
  public int seasonCount() {
    return burn.seasons().size();
  }

  /**
   * Returns the adjusted Fisher–Pearson sample skewness of the settlement index values the normal
   * was fitted to (the shifted values for a detrended result): {@code G1 = sqrt(n(n − 1)) / (n − 2)
   * · m3 / m2^(3/2)}, with {@code m_k = (1/n) Σ (x − x̄)^k} (spec 006 amendment C). A value far
   * from 0 means the symmetric normal misdescribes the index.
   *
   * @return the skewness, or empty with fewer than 3 seasons or when all values are equal
   */
  public OptionalDouble indexSkewness() {
    List<SeasonOutcome> seasons = burn.seasons();
    int n = seasons.size();
    if (n < 3) {
      return OptionalDouble.empty();
    }
    // Same rule as the fit's sd = 0: compare the inputs exactly, because sum / n can be 1 ulp off
    // and leave tiny non-zero deviations that would give a meaningless G1 near ±1.
    double first = seasons.get(0).indexHddDegC();
    boolean allEqual = true;
    double sum = 0.0;
    for (SeasonOutcome s : seasons) {
      allEqual &= s.indexHddDegC() == first;
      sum += s.indexHddDegC();
    }
    if (allEqual) {
      return OptionalDouble.empty();
    }
    double mean = sum / n;
    double m2 = 0.0;
    double m3 = 0.0;
    for (SeasonOutcome s : seasons) {
      double d = s.indexHddDegC() - mean;
      m2 += d * d;
      m3 += d * d * d;
    }
    m2 /= n;
    m3 /= n;
    if (m2 == 0.0) {
      return OptionalDouble.empty();
    }
    double g1 = m3 / Math.pow(m2, 1.5);
    return OptionalDouble.of(Math.sqrt((double) n * (n - 1)) / (n - 2) * g1);
  }
}
