package com.weatherderivatives.pricer.contract;

/** Long-side payoffs of HDD contracts in GBP (spec 003). */
public final class HddPayoff {

  private HddPayoff() {}

  /**
   * Computes the long side's payoff.
   *
   * @param contract the contract terms
   * @param indexHddDegC the settlement index in °C·day
   * @return the payoff in GBP
   */
  public static double payoffGbp(HddContract contract, double indexHddDegC) {
    throw new UnsupportedOperationException("not implemented");
  }
}
