package com.weatherderivatives.pricer.model;

import com.weatherderivatives.pricer.contract.HddContract;
import com.weatherderivatives.pricer.index.DailyTemps;

/** Normal fit to seasonal index values (spec 005). Stub. */
public final class IndexDistributionAnalysis {

  /** Minimum seasons for {@link #run}. */
  public static final int MIN_SEASONS = 5;

  /** Minimum seasons for {@link #runDetrended}. */
  public static final int MIN_DETRENDED_SEASONS = 10;

  private IndexDistributionAnalysis() {}

  /**
   * Plain fit.
   *
   * @param contract the contract
   * @param temps the temperatures
   * @param firstSeasonYear first season
   * @param lastSeasonYear last season
   * @return the result
   */
  public static IndexDistributionResult run(
      HddContract contract, DailyTemps temps, int firstSeasonYear, int lastSeasonYear) {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Detrended fit.
   *
   * @param contract the contract
   * @param temps the temperatures
   * @param firstSeasonYear first season
   * @param lastSeasonYear last season
   * @param targetSeasonYear target season
   * @return the result
   */
  public static IndexDistributionResult runDetrended(
      HddContract contract,
      DailyTemps temps,
      int firstSeasonYear,
      int lastSeasonYear,
      int targetSeasonYear) {
    throw new UnsupportedOperationException("not implemented");
  }
}
