package com.weatherderivatives.pricer.model;

import java.util.List;

/**
 * Result of a burn analysis (spec 003).
 *
 * <p>A plain result holder built by {@link BurnAnalysis#run} and {@link DetrendedBurnAnalysis#run};
 * it does not recheck that the statistics match {@code seasons}.
 *
 * @param seasons one outcome per season, in year order (stored as an unmodifiable copy)
 * @param burnPriceGbp mean payoff in GBP (undiscounted, no risk loading)
 * @param payoffStdDevGbp sample standard deviation (n − 1) of the payoffs in GBP
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

  /**
   * Copies {@code seasons} into an unmodifiable list.
   *
   * @throws NullPointerException if {@code seasons} or any element is null
   */
  public BurnResult {
    seasons = List.copyOf(seasons);
  }

  /**
   * Returns the number of seasons.
   *
   * @return {@code seasons().size()}
   */
  public int seasonCount() {
    return seasons.size();
  }

  /**
   * Returns the standard error of the burn price: how far the historical mean payoff may sit from
   * the true expected payoff, given only {@code seasonCount()} seasons.
   *
   * @return {@code payoffStdDevGbp / sqrt(seasonCount())} in GBP
   */
  public double burnPriceStdErrorGbp() {
    return payoffStdDevGbp / Math.sqrt(seasons.size());
  }
}
