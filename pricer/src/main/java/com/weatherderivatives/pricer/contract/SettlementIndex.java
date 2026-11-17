package com.weatherderivatives.pricer.contract;

import com.weatherderivatives.pricer.index.DailyTemps;

/** The settlement index of an HDD contract (spec 003). */
public final class SettlementIndex {

  private SettlementIndex() {}

  /**
   * Computes the contract's cumulative HDD index over its accrual period.
   *
   * @param contract the contract terms
   * @param temps daily temperatures covering the accrual period
   * @return the index in °C·day
   */
  public static double indexHddDegC(HddContract contract, DailyTemps temps) {
    throw new UnsupportedOperationException("not implemented");
  }
}
