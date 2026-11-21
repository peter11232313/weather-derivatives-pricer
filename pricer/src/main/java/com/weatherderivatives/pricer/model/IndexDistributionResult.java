package com.weatherderivatives.pricer.model;

import java.util.Objects;

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
}
