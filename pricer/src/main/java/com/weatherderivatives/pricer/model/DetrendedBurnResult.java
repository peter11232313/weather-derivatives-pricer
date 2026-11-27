package com.weatherderivatives.pricer.model;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Result of a detrended burn analysis (spec 004).
 *
 * <p>A plain result holder built by {@link DetrendedBurnAnalysis#run}; it does not recheck that
 * {@code seasons} lines up with {@code burn.seasons()}. The burn's standard deviation uses n − 1
 * although the slope used one more degree of freedom, and it excludes the trend's own uncertainty;
 * see {@link #slopeStdErrDegCPerYear()}.
 *
 * @param burn the burn statistics of the shifted seasons
 * @param slopeDegCPerYear fitted linear trend of the season mean temperature, in °C per year
 * @param targetSeasonYear the season the shifted history represents
 * @param seasons one entry per season, in year order (stored as an unmodifiable copy)
 */
public record DetrendedBurnResult(
    BurnResult burn, double slopeDegCPerYear, int targetSeasonYear, List<SeasonTrend> seasons) {

  /**
   * Smallest {@code |t|} at which {@link #slopeSignificant()} treats the trend as real: a rule of
   * thumb (about 95% two-sided for large n), not a t-distribution critical value.
   */
  public static final double SIGNIFICANT_T_STAT = 2.0;

  /**
   * Copies {@code seasons} into an unmodifiable list.
   *
   * @throws NullPointerException if {@code burn}, {@code seasons} or any element is null
   */
  public DetrendedBurnResult {
    Objects.requireNonNull(burn, "burn");
    seasons = List.copyOf(seasons);
  }

  /**
   * Returns the standard error of the fitted slope, {@code sqrt(SSres / (n − 2) / Sxx)}, from the
   * season years and means in {@code seasons}; residuals are about the OLS line through the mean
   * season year and mean temperature.
   *
   * @return the slope's standard error in °C per year, or NaN with fewer than 3 seasons
   */
  public double slopeStdErrDegCPerYear() {
    int n = seasons.size();
    if (n < 3) {
      return Double.NaN;
    }
    double yearSum = 0.0;
    double meanSumC = 0.0;
    for (SeasonTrend s : seasons) {
      yearSum += s.seasonYear();
      meanSumC += s.seasonMeanTempC();
    }
    double meanYear = yearSum / n;
    double meanTempC = meanSumC / n;
    double sxx = 0.0;
    double residualSs = 0.0;
    for (SeasonTrend s : seasons) {
      double x = s.seasonYear() - meanYear;
      double residualC = s.seasonMeanTempC() - (meanTempC + slopeDegCPerYear * x);
      sxx += x * x;
      residualSs += residualC * residualC;
    }
    return Math.sqrt(residualSs / (n - 2) / sxx);
  }

  /**
   * Returns the slope's t-statistic, {@code slope / standard error} (spec 006 amendment C).
   *
   * @return the t-statistic, or empty when the standard error is 0 (an exactly linear history) or
   *     NaN (fewer than 3 seasons)
   */
  public OptionalDouble slopeTStat() {
    double stdErr = slopeStdErrDegCPerYear();
    if (Double.isNaN(stdErr) || stdErr == 0.0) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(slopeDegCPerYear / stdErr);
  }

  /**
   * Returns whether the fitted trend is distinguishable from noise: {@code |t| ≥} {@value
   * #SIGNIFICANT_T_STAT} (spec 006 amendment C). With a standard error of 0 the trend is
   * significant iff the slope is not 0; with fewer than 3 seasons (no standard error) it is not.
   *
   * @return true if the slope is significant by this rule of thumb
   */
  public boolean slopeSignificant() {
    double stdErr = slopeStdErrDegCPerYear();
    if (Double.isNaN(stdErr)) {
      return false;
    }
    if (stdErr == 0.0) {
      return slopeDegCPerYear != 0.0;
    }
    return Math.abs(slopeDegCPerYear / stdErr) >= SIGNIFICANT_T_STAT;
  }
}
