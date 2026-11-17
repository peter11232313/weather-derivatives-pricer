package com.weatherderivatives.pricer.model;

import com.weatherderivatives.pricer.contract.HddContract;
import com.weatherderivatives.pricer.index.DailyTemps;

/** Burn analysis on history adjusted for a linear temperature trend (spec 004). */
public final class DetrendedBurnAnalysis {

  /** Minimum number of seasons for a trend fit. */
  public static final int MIN_SEASONS = 10;

  private DetrendedBurnAnalysis() {}

  /**
   * Prices a contract on the seasons {@code firstSeasonYear..lastSeasonYear}, each shifted to the
   * climate of {@code targetSeasonYear}.
   *
   * @param contract the contract terms; its months' years are only a template
   * @param temps daily temperatures covering every season (not modified)
   * @param firstSeasonYear first season (inclusive)
   * @param lastSeasonYear last season (inclusive)
   * @param targetSeasonYear the season the shifted history represents
   * @return the detrended burn
   */
  public static DetrendedBurnResult run(
      HddContract contract,
      DailyTemps temps,
      int firstSeasonYear,
      int lastSeasonYear,
      int targetSeasonYear) {
    throw new UnsupportedOperationException("not implemented");
  }

  /** Mean of the daily mean temperature over the season's accrual days, in °C. */
  static double seasonMeanTempC(HddContract season, DailyTemps temps) {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * OLS slope of {@code seasonMeanTempC[k]} against season year {@code firstSeasonYear + k}.
   *
   * @return the slope in °C per year; exactly +0.0 if all values are equal
   */
  static double olsSlopeDegCPerYear(int firstSeasonYear, double[] seasonMeanTempC) {
    throw new UnsupportedOperationException("not implemented");
  }
}
