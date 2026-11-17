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
 * the undiscounted mean payoff; no detrending is applied. Seasons are processed in year order with
 * plain sequential sums, so results are bit-identical for the same inputs.
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
    if (firstSeasonYear > lastSeasonYear) {
      throw new IllegalArgumentException(
          "firstSeasonYear " + firstSeasonYear + " is after lastSeasonYear " + lastSeasonYear);
    }
    long seasonCount = (long) lastSeasonYear - firstSeasonYear + 1;
    if (seasonCount < MIN_SEASONS) {
      throw new IllegalArgumentException(
          "burn analysis needs at least "
              + MIN_SEASONS
              + " seasons but got "
              + seasonCount
              + " ("
              + firstSeasonYear
              + ".."
              + lastSeasonYear
              + ")");
    }

    List<SeasonOutcome> seasons = new ArrayList<>();
    for (int year = firstSeasonYear; year <= lastSeasonYear; year++) {
      HddContract season = contract.forSeason(year);
      double indexHddDegC;
      try {
        indexHddDegC = SettlementIndex.indexHddDegC(season, temps);
      } catch (TemperatureDataException e) {
        throw new TemperatureDataException(
            "season "
                + year
                + " ("
                + season.accrualStart()
                + ".."
                + season.accrualEndInclusive()
                + "): "
                + e.getMessage(),
            e);
      }
      seasons.add(new SeasonOutcome(year, indexHddDegC, HddPayoff.payoffGbp(season, indexHddDegC)));
    }
    return summarise(seasons);
  }

  /** Two-pass mean and sample standard deviation, in season order. */
  private static BurnResult summarise(List<SeasonOutcome> seasons) {
    int n = seasons.size();
    double payoffSum = 0.0;
    double indexSum = 0.0;
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    for (SeasonOutcome s : seasons) {
      payoffSum += s.payoffGbp();
      indexSum += s.indexHddDegC();
      min = Math.min(min, s.payoffGbp());
      max = Math.max(max, s.payoffGbp());
    }
    double meanPayoffGbp = payoffSum / n;
    double squaredDeviations = 0.0;
    for (SeasonOutcome s : seasons) {
      double deviation = s.payoffGbp() - meanPayoffGbp;
      squaredDeviations += deviation * deviation;
    }
    double stdDevGbp = Math.sqrt(squaredDeviations / (n - 1));
    return new BurnResult(seasons, meanPayoffGbp, stdDevGbp, min, max, indexSum / n);
  }
}
