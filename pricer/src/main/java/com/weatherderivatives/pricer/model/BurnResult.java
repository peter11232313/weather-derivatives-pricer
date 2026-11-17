package com.weatherderivatives.pricer.model;

import java.util.List;

/**
 * Result of a burn analysis.
 *
 * @param seasons one outcome per season, in year order
 * @param burnPriceGbp mean payoff in GBP (undiscounted)
 * @param payoffStdDevGbp sample standard deviation of the payoffs in GBP
 * @param minPayoffGbp smallest payoff in GBP
 * @param maxPayoffGbp largest payoff in GBP
 * @param meanIndexHddDegC mean settlement index in °C·day
 */
public record BurnResult(
    List<SeasonOutcome> seasons,
    double burnPriceGbp,
    double payoffStdDevGbp,
    double minPayoffGbp,
    double maxPayoffGbp,
    double meanIndexHddDegC) {

  /** Copies {@code seasons}. */
  public BurnResult {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Returns the number of seasons.
   *
   * @return {@code seasons().size()}
   */
  public int seasonCount() {
    throw new UnsupportedOperationException("not implemented");
  }
}
