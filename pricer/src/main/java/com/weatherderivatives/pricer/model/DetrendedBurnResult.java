package com.weatherderivatives.pricer.model;

import java.util.List;
import java.util.Objects;

/**
 * Result of a detrended burn analysis (spec 004).
 *
 * @param burn the burn statistics of the shifted seasons
 * @param slopeDegCPerYear fitted linear trend of the season mean temperature, in °C per year
 * @param targetSeasonYear the season the shifted history represents
 * @param seasons one entry per season, in year order (stored as an unmodifiable copy)
 */
public record DetrendedBurnResult(
    BurnResult burn, double slopeDegCPerYear, int targetSeasonYear, List<SeasonTrend> seasons) {

  /**
   * Copies {@code seasons} into an unmodifiable list.
   *
   * @throws NullPointerException if {@code burn}, {@code seasons} or any element is null
   */
  public DetrendedBurnResult {
    Objects.requireNonNull(burn, "burn");
    seasons = List.copyOf(seasons);
  }
}
