package com.weatherderivatives.pricer.index;

import java.time.LocalDate;

/**
 * Heating Degree Day (HDD) index calculations (spec 001).
 *
 * <p>Daily mean {@code T = (tmaxC + tminC) / 2}; daily {@code HDD = max(0, baseC − T)}. The
 * cumulative index sums daily HDD over an inclusive range of UTC calendar days.
 */
public final class HddIndex {

  /** Default HDD base temperature in °C (European exchange convention). */
  public static final double DEFAULT_BASE_C = 18.0;

  private HddIndex() {}

  /**
   * Computes one day's HDD.
   *
   * @param tmaxC daily maximum temperature in °C
   * @param tminC daily minimum temperature in °C
   * @param baseC base temperature in °C
   * @return {@code max(0, baseC − (tmaxC + tminC) / 2)} in °C·day
   * @throws IllegalArgumentException if any argument is NaN or {@code tminC > tmaxC}
   */
  public static double dailyHddDegC(double tmaxC, double tminC, double baseC) {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Computes one day's HDD with the default 18 °C base.
   *
   * @param tmaxC daily maximum temperature in °C
   * @param tminC daily minimum temperature in °C
   * @return daily HDD in °C·day
   * @throws IllegalArgumentException if any argument is NaN or {@code tminC > tmaxC}
   * @see #DEFAULT_BASE_C
   */
  public static double dailyHddDegC(double tmaxC, double tminC) {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Sums daily HDD over {@code start..endInclusive}, both ends included.
   *
   * @param temps the city's daily temperatures
   * @param start first accrual day (UTC)
   * @param endInclusive last accrual day (UTC), included
   * @param baseC base temperature in °C
   * @return cumulative HDD in °C·day, unrounded
   * @throws IllegalArgumentException if {@code start} is after {@code endInclusive}
   * @throws TemperatureDataException if a day in the period is missing (the message names it)
   */
  public static double cumulativeHddDegC(
      DailyTemps temps, LocalDate start, LocalDate endInclusive, double baseC) {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Sums daily HDD over {@code start..endInclusive} with the default 18 °C base.
   *
   * @param temps the city's daily temperatures
   * @param start first accrual day (UTC)
   * @param endInclusive last accrual day (UTC), included
   * @return cumulative HDD in °C·day, unrounded
   * @throws IllegalArgumentException if {@code start} is after {@code endInclusive}
   * @throws TemperatureDataException if a day in the period is missing (the message names it)
   * @see #DEFAULT_BASE_C
   */
  public static double cumulativeHddDegC(
      DailyTemps temps, LocalDate start, LocalDate endInclusive) {
    throw new UnsupportedOperationException("not implemented");
  }
}
