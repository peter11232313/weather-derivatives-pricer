package com.weatherderivatives.pricer.model;

import com.weatherderivatives.pricer.contract.HddContract;
import com.weatherderivatives.pricer.index.DailyTemps;

/** Historical burn analysis of an HDD contract (spec 003). */
public final class BurnAnalysis {

  private BurnAnalysis() {}

  /**
   * Prices a contract on the seasons {@code firstSeasonYear..lastSeasonYear}.
   *
   * @param contract the contract terms
   * @param temps daily temperatures covering every season
   * @param firstSeasonYear first season (inclusive)
   * @param lastSeasonYear last season (inclusive)
   * @return the per-season outcomes and their summary
   */
  public static BurnResult run(
      HddContract contract, DailyTemps temps, int firstSeasonYear, int lastSeasonYear) {
    throw new UnsupportedOperationException("not implemented");
  }
}
