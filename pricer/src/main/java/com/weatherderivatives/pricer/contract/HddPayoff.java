package com.weatherderivatives.pricer.contract;

import java.util.Objects;

/**
 * Long-side payoffs of HDD contracts in GBP (spec 003).
 *
 * <p>With index {@code I}, strike {@code K}, tick and optional cap {@code C}:
 *
 * <ul>
 *   <li>call: {@code min(C, tick × max(0, I − K))}
 *   <li>put: {@code min(C, tick × max(0, K − I))}
 *   <li>swap: {@code tick × (I − K)} clamped to {@code [−C, C]}
 * </ul>
 */
public final class HddPayoff {

  private HddPayoff() {}

  /**
   * Computes the long side's payoff.
   *
   * @param contract the contract terms
   * @param indexHddDegC the settlement index in °C·day
   * @return the payoff in GBP; exactly {@code +0.0} when nothing is paid
   * @throws NullPointerException if {@code contract} is null
   * @throws IllegalArgumentException if {@code indexHddDegC} is negative or not finite
   */
  public static double payoffGbp(HddContract contract, double indexHddDegC) {
    Objects.requireNonNull(contract, "contract");
    if (!Double.isFinite(indexHddDegC) || indexHddDegC < 0) {
      throw new IllegalArgumentException(
          "indexHddDegC must be finite and >= 0 but was " + indexHddDegC);
    }
    double strikeHddDegC = contract.strikeHddDegC();
    double tickGbpPerDegC = contract.tickGbpPerDegC();
    double payoffGbp =
        switch (contract.type()) {
          case CALL -> tickGbpPerDegC * Math.max(0.0, indexHddDegC - strikeHddDegC);
          case PUT -> tickGbpPerDegC * Math.max(0.0, strikeHddDegC - indexHddDegC);
          case SWAP -> tickGbpPerDegC * (indexHddDegC - strikeHddDegC);
        };
    if (contract.capGbp().isPresent()) {
      double capGbp = contract.capGbp().getAsDouble();
      // Call and put payoffs are >= 0, so the lower bound only ever binds for a swap.
      payoffGbp = Math.max(-capGbp, Math.min(capGbp, payoffGbp));
    }
    return payoffGbp;
  }
}
