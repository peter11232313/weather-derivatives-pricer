package com.weatherderivatives.pricer.model;

/**
 * Result of an index-distribution analysis (spec 005). Stub.
 *
 * @param meanIndexHddDegC fitted mean in °C·day
 * @param sdIndexHddDegC fitted sample standard deviation in °C·day
 * @param expectedPayoffGbp expected payoff in GBP
 * @param burn burn result for the same seasons
 */
public record IndexDistributionResult(
    double meanIndexHddDegC, double sdIndexHddDegC, double expectedPayoffGbp, BurnResult burn) {

  /** Stub constructor. */
  public IndexDistributionResult {}
}
