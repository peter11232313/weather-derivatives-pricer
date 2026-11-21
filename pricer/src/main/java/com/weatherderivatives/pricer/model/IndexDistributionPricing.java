package com.weatherderivatives.pricer.model;

import com.weatherderivatives.pricer.contract.HddContract;

/** Expected payoff of an HDD contract under a normal index distribution (spec 005). Stub. */
public final class IndexDistributionPricing {

  private IndexDistributionPricing() {}

  /**
   * Expected payoff under {@code I ~ N(mean, sd^2)}.
   *
   * @param contract the contract terms
   * @param meanIndexHddDegC mean of the index in °C·day
   * @param sdIndexHddDegC standard deviation of the index in °C·day
   * @return the expected payoff in GBP
   */
  public static double expectedPayoffGbp(
      HddContract contract, double meanIndexHddDegC, double sdIndexHddDegC) {
    throw new UnsupportedOperationException("not implemented");
  }
}
