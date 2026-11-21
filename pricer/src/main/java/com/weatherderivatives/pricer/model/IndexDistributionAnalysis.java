package com.weatherderivatives.pricer.model;

import com.weatherderivatives.pricer.contract.HddContract;
import com.weatherderivatives.pricer.index.DailyTemps;
import java.util.List;
import java.util.Objects;

/**
 * Prices a contract by fitting a normal distribution to its past seasonal settlement index values
 * (spec 005).
 *
 * <p>The index values are exactly those the burn analyses report: {@link BurnAnalysis} for {@link
 * #run}, and the shifted values of {@link DetrendedBurnAnalysis} for {@link #runDetrended}, both
 * with the contract's daily-HDD rounding. The mean is the burn's mean index; the standard deviation
 * is the two-pass sample standard deviation (n − 1). The price is {@link
 * IndexDistributionPricing#expectedPayoffGbp} under that normal.
 */
public final class IndexDistributionAnalysis {

  /** Minimum seasons for {@link #run}: a normal fitted to fewer points means little. */
  public static final int MIN_SEASONS = 5;

  /** Minimum seasons for {@link #runDetrended}, the same as {@link DetrendedBurnAnalysis}. */
  public static final int MIN_DETRENDED_SEASONS = 10;

  private IndexDistributionAnalysis() {}

  /**
   * Fits a normal to the seasons {@code firstSeasonYear..lastSeasonYear} and prices the contract.
   *
   * @param contract the contract terms; its months' years are only a template
   * @param temps daily temperatures covering every season
   * @param firstSeasonYear first season (inclusive), labelled by its first accrual year
   * @param lastSeasonYear last season (inclusive)
   * @return the fitted mean and standard deviation, the expected payoff and the burn result
   * @throws NullPointerException if {@code contract} or {@code temps} is null
   * @throws IllegalArgumentException if {@code firstSeasonYear > lastSeasonYear} or fewer than
   *     {@value #MIN_SEASONS} seasons are requested (checked before any data is read)
   * @throws com.weatherderivatives.pricer.index.TemperatureDataException if a season's accrual
   *     period has a missing day (names the season and the date)
   */
  public static IndexDistributionResult run(
      HddContract contract, DailyTemps temps, int firstSeasonYear, int lastSeasonYear) {
    Objects.requireNonNull(contract, "contract");
    Objects.requireNonNull(temps, "temps");
    BurnAnalysis.checkSeasonRange(
        firstSeasonYear, lastSeasonYear, MIN_SEASONS, "index-distribution analysis");
    return fit(contract, BurnAnalysis.run(contract, temps, firstSeasonYear, lastSeasonYear));
  }

  /**
   * Fits a normal to the seasons {@code firstSeasonYear..lastSeasonYear}, each shifted to the
   * climate of {@code targetSeasonYear} as in {@link DetrendedBurnAnalysis}, and prices the
   * contract.
   *
   * @param contract the contract terms; its months' years are only a template
   * @param temps daily temperatures covering every season
   * @param firstSeasonYear first season (inclusive)
   * @param lastSeasonYear last season (inclusive)
   * @param targetSeasonYear the season the shifted history represents
   * @return the fitted mean and standard deviation, the expected payoff and the detrended burn
   * @throws NullPointerException if {@code contract} or {@code temps} is null
   * @throws IllegalArgumentException if {@code firstSeasonYear > lastSeasonYear} or fewer than
   *     {@value #MIN_DETRENDED_SEASONS} seasons are requested (checked before any data is read)
   * @throws com.weatherderivatives.pricer.index.TemperatureDataException if a season's accrual
   *     period has a missing day (names the season and the date)
   */
  public static IndexDistributionResult runDetrended(
      HddContract contract,
      DailyTemps temps,
      int firstSeasonYear,
      int lastSeasonYear,
      int targetSeasonYear) {
    Objects.requireNonNull(contract, "contract");
    Objects.requireNonNull(temps, "temps");
    BurnAnalysis.checkSeasonRange(
        firstSeasonYear,
        lastSeasonYear,
        MIN_DETRENDED_SEASONS,
        "detrended index-distribution analysis");
    DetrendedBurnResult detrended =
        DetrendedBurnAnalysis.run(
            contract, temps, firstSeasonYear, lastSeasonYear, targetSeasonYear);
    return fit(contract, detrended.burn());
  }

  /**
   * Fits the normal to a burn result's season index values and prices the contract. When every
   * season has the same index, the mean is that index exactly (a rounded sum / n could be 1 ulp
   * off) and the standard deviation is exactly 0.
   */
  private static IndexDistributionResult fit(HddContract contract, BurnResult burn) {
    List<SeasonOutcome> seasons = burn.seasons();
    double firstHddDegC = seasons.get(0).indexHddDegC();
    boolean allEqual = seasons.stream().allMatch(s -> s.indexHddDegC() == firstHddDegC);
    double meanHddDegC = allEqual ? firstHddDegC : burn.meanIndexHddDegC();
    double sdHddDegC = allEqual ? 0.0 : sampleSdIndexHddDegC(seasons, meanHddDegC);
    double priceGbp = IndexDistributionPricing.expectedPayoffGbp(contract, meanHddDegC, sdHddDegC);
    return new IndexDistributionResult(meanHddDegC, sdHddDegC, priceGbp, burn);
  }

  /** Two-pass sample standard deviation (n − 1) of the seasons' index values. */
  static double sampleSdIndexHddDegC(List<SeasonOutcome> seasons, double meanHddDegC) {
    double squaredDeviations = 0.0;
    for (SeasonOutcome s : seasons) {
      double deviation = s.indexHddDegC() - meanHddDegC;
      squaredDeviations += deviation * deviation;
    }
    return Math.sqrt(squaredDeviations / (seasons.size() - 1));
  }
}
