package com.weatherderivatives.pricer.model;

import com.weatherderivatives.pricer.contract.HddContract;
import com.weatherderivatives.pricer.contract.HddPayoff;
import com.weatherderivatives.pricer.contract.SettlementIndex;
import com.weatherderivatives.pricer.index.DailyTemps;
import com.weatherderivatives.pricer.index.TemperatureDataException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Historical burn analysis of an HDD contract (spec 003).
 *
 * <p>For each season, the contract is shifted to that season ({@link HddContract#forSeason(int)}),
 * its settlement index and payoff are computed, and the payoffs are summarised. The burn price is
 * the undiscounted mean payoff; no detrending is applied. Seasons and days are processed in date
 * order with fixed summation, so results are bit-identical for the same inputs.
 *
 * <p>Known biases, left to later specs: there is no detrending, so under warming the raw burn
 * overstates HDD (calls and long swaps priced high, puts low); whole calendar months mean a leap
 * season accrues 29 February, so 151- and 152-day Nov–Mar seasons are mixed. The caller chooses the
 * seasons and must exclude the season being priced when back-testing.
 */
public final class BurnAnalysis {

  /** Minimum number of seasons: a sample standard deviation needs two. */
  public static final int MIN_SEASONS = 2;

  private BurnAnalysis() {}

  /**
   * Prices a contract on the seasons {@code firstSeasonYear..lastSeasonYear}.
   *
   * <p>A season is labelled by the year of its first accrual day: for a Nov–Mar contract, season
   * 1991 accrues 1991-11-01..1992-03-31.
   *
   * @param contract the contract terms; its months' years are only a template
   * @param temps daily temperatures covering every season
   * @param firstSeasonYear first season (inclusive)
   * @param lastSeasonYear last season (inclusive)
   * @return the per-season outcomes and their summary
   * @throws NullPointerException if {@code contract} or {@code temps} is null
   * @throws IllegalArgumentException if {@code firstSeasonYear > lastSeasonYear} or fewer than 2
   *     seasons are requested (checked before any data is read)
   * @throws TemperatureDataException if a season's accrual period has a missing day (names the
   *     season and the date; the original exception is the cause)
   */
  public static BurnResult run(
      HddContract contract, DailyTemps temps, int firstSeasonYear, int lastSeasonYear) {
    Objects.requireNonNull(contract, "contract");
    Objects.requireNonNull(temps, "temps");
    checkSeasonRange(firstSeasonYear, lastSeasonYear, MIN_SEASONS);

    List<SeasonOutcome> seasons = new ArrayList<>();
    for (int year = firstSeasonYear; year <= lastSeasonYear; year++) {
      HddContract season = contract.forSeason(year);
      double indexHddDegC;
      try {
        indexHddDegC = SettlementIndex.indexHddDegC(season, temps);
      } catch (TemperatureDataException e) {
        throw seasonError(year, season, e);
      }
      seasons.add(new SeasonOutcome(year, indexHddDegC, HddPayoff.payoffGbp(season, indexHddDegC)));
    }
    return summarise(seasons);
  }

  /**
   * Checks a season range before any data is read.
   *
   * @throws IllegalArgumentException if {@code firstSeasonYear > lastSeasonYear} or the range has
   *     fewer than {@code minSeasons} seasons
   */
  static void checkSeasonRange(int firstSeasonYear, int lastSeasonYear, int minSeasons) {
    if (firstSeasonYear > lastSeasonYear) {
      throw new IllegalArgumentException(
          "firstSeasonYear " + firstSeasonYear + " is after lastSeasonYear " + lastSeasonYear);
    }
    long seasonCount = (long) lastSeasonYear - firstSeasonYear + 1;
    if (seasonCount < minSeasons) {
      throw new IllegalArgumentException(
          "burn analysis needs at least "
              + minSeasons
              + " seasons but got "
              + seasonCount
              + " ("
              + firstSeasonYear
              + ".."
              + lastSeasonYear
              + ")");
    }
  }

  /** Wraps a missing-day error so it names the season and its accrual period; keeps the cause. */
  static TemperatureDataException seasonError(
      int seasonYear, HddContract season, TemperatureDataException cause) {
    return new TemperatureDataException(
        "season "
            + seasonYear
            + " ("
            + season.accrualStart()
            + ".."
            + season.accrualEndInclusive()
            + "): "
            + cause.getMessage(),
        cause);
  }

  /** Two-pass mean and sample standard deviation, in season order. */
  static BurnResult summarise(List<SeasonOutcome> seasons) {
    int n = seasons.size();
    double payoffSum = 0.0;
    double indexSum = 0.0;
    double minPayoffGbp = Double.POSITIVE_INFINITY;
    double maxPayoffGbp = Double.NEGATIVE_INFINITY;
    for (SeasonOutcome s : seasons) {
      payoffSum += s.payoffGbp();
      indexSum += s.indexHddDegC();
      minPayoffGbp = Math.min(minPayoffGbp, s.payoffGbp());
      maxPayoffGbp = Math.max(maxPayoffGbp, s.payoffGbp());
    }
    double meanPayoffGbp = payoffSum / n;
    double squaredDeviations = 0.0;
    for (SeasonOutcome s : seasons) {
      double deviation = s.payoffGbp() - meanPayoffGbp;
      squaredDeviations += deviation * deviation;
    }
    double payoffStdDevGbp = Math.sqrt(squaredDeviations / (n - 1));
    return new BurnResult(
        seasons, meanPayoffGbp, payoffStdDevGbp, minPayoffGbp, maxPayoffGbp, indexSum / n);
  }
}
