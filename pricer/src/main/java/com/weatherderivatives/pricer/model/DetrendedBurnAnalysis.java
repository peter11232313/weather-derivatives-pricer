package com.weatherderivatives.pricer.model;

import com.weatherderivatives.pricer.contract.HddContract;
import com.weatherderivatives.pricer.contract.HddPayoff;
import com.weatherderivatives.pricer.contract.SettlementIndex;
import com.weatherderivatives.pricer.index.DailyTemp;
import com.weatherderivatives.pricer.index.DailyTemps;
import com.weatherderivatives.pricer.index.TemperatureDataException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Burn analysis on history adjusted for a linear temperature trend (spec 004).
 *
 * <p>For each season, the mean daily temperature {@code (tmax + tmin) / 2} over its accrual days is
 * computed. An ordinary least-squares line through those season means against the season year gives
 * {@code slopeDegCPerYear}. Each season's daily Tmax and Tmin are then shifted by {@code slope ×
 * (targetSeasonYear − seasonYear)}, so older seasons are warmed to the target season's climate, and
 * the settlement index (with the contract's rounding) and payoff are recomputed from the shifted
 * days. The burn statistics are those of {@link BurnAnalysis}.
 *
 * <p>Detrending temperatures rather than the HDD index keeps {@code HDD = max(0, base − T)} honest:
 * a shift that lifts days above the base gives 0, never a negative contribution. The shift is added
 * in binary double before any settlement rounding (ADR 0004), so an exact tie after a shift may
 * round either way; the trend estimate is far less precise than that.
 *
 * <p>Look-ahead: for a back-test the window must end before {@code targetSeasonYear}, otherwise the
 * priced season feeds both the trend and the burn. Normally {@code targetSeasonYear =
 * lastSeasonYear + 1} (or {@code + 2} if the latest season is incomplete). The fitted slope is
 * uncertain ({@link DetrendedBurnResult#slopeStdErrDegCPerYear()}); with few seasons the shift of
 * the oldest seasons can be dominated by noise, and the burn's standard deviation does not include
 * that trend uncertainty.
 */
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
   * @param targetSeasonYear the season the shifted history represents; any year
   * @return the detrended burn, the fitted slope and each season's mean and shift
   * @throws NullPointerException if {@code contract} or {@code temps} is null
   * @throws IllegalArgumentException if {@code firstSeasonYear > lastSeasonYear} or fewer than 10
   *     seasons are requested (checked before any data is read)
   * @throws TemperatureDataException if a season's accrual period has a missing day (names the
   *     season and the date; the original exception is the cause)
   */
  public static DetrendedBurnResult run(
      HddContract contract,
      DailyTemps temps,
      int firstSeasonYear,
      int lastSeasonYear,
      int targetSeasonYear) {
    Objects.requireNonNull(contract, "contract");
    Objects.requireNonNull(temps, "temps");
    BurnAnalysis.checkSeasonRange(
        firstSeasonYear, lastSeasonYear, MIN_SEASONS, "detrended burn analysis (trend fit)");

    // Read every season first. The list grows with the data, so a huge range fails on its first
    // missing day instead of allocating up front.
    List<Double> means = new ArrayList<>();
    for (long year = firstSeasonYear; year <= lastSeasonYear; year++) {
      HddContract season = contract.forSeason((int) year);
      try {
        means.add(seasonMeanTempC(season, temps));
      } catch (TemperatureDataException e) {
        throw BurnAnalysis.seasonError((int) year, season, e);
      }
    }
    int n = means.size();
    double[] seasonMeansC = new double[n];
    for (int k = 0; k < n; k++) {
      seasonMeansC[k] = means.get(k);
    }
    double slopeDegCPerYear = olsSlopeDegCPerYear(firstSeasonYear, seasonMeansC);

    List<SeasonOutcome> outcomes = new ArrayList<>(n);
    List<SeasonTrend> trends = new ArrayList<>(n);
    for (int k = 0; k < n; k++) {
      int year = firstSeasonYear + k;
      HddContract season = contract.forSeason(year);
      // + 0.0 turns -0.0 (zero slope, target before the season) into +0.0
      double shiftDegC = slopeDegCPerYear * (double) ((long) targetSeasonYear - year) + 0.0;
      double indexHddDegC;
      try {
        indexHddDegC = SettlementIndex.indexHddDegC(season, shifted(season, temps, shiftDegC));
      } catch (TemperatureDataException e) {
        throw BurnAnalysis.seasonError(year, season, e); // e.g. a shift overflowing to infinity
      }
      outcomes.add(
          new SeasonOutcome(year, indexHddDegC, HddPayoff.payoffGbp(season, indexHddDegC)));
      trends.add(new SeasonTrend(year, seasonMeansC[k], shiftDegC));
    }
    return new DetrendedBurnResult(
        BurnAnalysis.summarise(outcomes), slopeDegCPerYear, targetSeasonYear, trends);
  }

  /**
   * Mean of the daily mean temperature over the season's accrual days, in °C, summed in date order.
   *
   * @throws TemperatureDataException if an accrual day is missing (names the date)
   */
  static double seasonMeanTempC(HddContract season, DailyTemps temps) {
    LocalDate end = season.accrualEndInclusive();
    double sumC = 0.0;
    long days = 0;
    for (LocalDate date = season.accrualStart(); !date.isAfter(end); date = date.plusDays(1)) {
      sumC += temps.get(date).meanTempC();
      days++;
    }
    return sumC / days;
  }

  /**
   * OLS slope of {@code seasonMeanTempC[k]} against season year {@code firstSeasonYear + k}.
   *
   * <p>Years are centred on their mean, so each centred year {@code x_k} is an exact integer or
   * half-integer and {@code x_k = −x_(n−1−k)}. The numerator is summed as {@code Σ x_(n−1−k) ·
   * (m_(n−1−k) − m_k)} over the symmetric pairs, which equals {@code Σ x (m − mean(m))} but gives
   * exactly {@code +0.0} when all values are equal (a flat history then reproduces the raw burn bit
   * for bit). The slope does not depend on {@code firstSeasonYear}, only on the spacing.
   *
   * @param firstSeasonYear year of {@code seasonMeanTempC[0]}
   * @param seasonMeanTempC season means in °C, at least two
   * @return the slope in °C per year; exactly +0.0 if all values are equal
   */
  static double olsSlopeDegCPerYear(int firstSeasonYear, double[] seasonMeanTempC) {
    int n = seasonMeanTempC.length;
    if (n < 2) {
      throw new IllegalArgumentException("a trend needs at least 2 seasons but got " + n);
    }
    double centre = (n - 1) / 2.0;
    double sxx = 0.0;
    for (int k = 0; k < n; k++) {
      double x = k - centre;
      sxx += x * x;
    }
    double sxy = 0.0;
    for (int k = 0; k < n / 2; k++) {
      int mirror = n - 1 - k;
      sxy += (mirror - centre) * (seasonMeanTempC[mirror] - seasonMeanTempC[k]);
    }
    return sxy / sxx;
  }

  /** A season's accrual days with Tmax and Tmin shifted by {@code shiftDegC}; input unchanged. */
  private static DailyTemps shifted(HddContract season, DailyTemps temps, double shiftDegC) {
    List<DailyTemp> days = new ArrayList<>();
    LocalDate end = season.accrualEndInclusive();
    for (LocalDate date = season.accrualStart(); !date.isAfter(end); date = date.plusDays(1)) {
      DailyTemp day = temps.get(date);
      days.add(new DailyTemp(date, day.tmaxC() + shiftDegC, day.tminC() + shiftDegC));
    }
    return DailyTemps.of(days);
  }
}
