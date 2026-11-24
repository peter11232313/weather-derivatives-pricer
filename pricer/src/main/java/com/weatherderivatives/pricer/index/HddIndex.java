package com.weatherderivatives.pricer.index;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Heating Degree Day (HDD) index calculations (spec 001).
 *
 * <p>Daily mean {@code T = (tmaxC + tminC) / 2}; daily {@code HDD = max(0, baseC − T)}. The
 * cumulative index sums daily HDD over an inclusive range of settlement days (each day D under the
 * station's observation windows, ADR 0005).
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
   * @throws IllegalArgumentException if any argument is non-finite or {@code tminC > tmaxC}
   */
  public static double dailyHddDegC(double tmaxC, double tminC, double baseC) {
    if (!Double.isFinite(tmaxC) || !Double.isFinite(tminC) || !Double.isFinite(baseC)) {
      throw new IllegalArgumentException(
          "non-finite argument: tmaxC=" + tmaxC + ", tminC=" + tminC + ", baseC=" + baseC);
    }
    if (tminC > tmaxC) {
      throw new IllegalArgumentException("tminC > tmaxC: tmaxC=" + tmaxC + ", tminC=" + tminC);
    }
    return hddDegC(DailyTemp.meanTempC(tmaxC, tminC), baseC);
  }

  /** Daily HDD from an already-validated mean; {@code Math.max} maps -0.0 to +0.0. */
  private static double hddDegC(double meanTempC, double baseC) {
    return Math.max(0.0, baseC - meanTempC);
  }

  /**
   * Computes one day's HDD with the default 18 °C base.
   *
   * @param tmaxC daily maximum temperature in °C
   * @param tminC daily minimum temperature in °C
   * @return daily HDD in °C·day
   * @throws IllegalArgumentException if any argument is non-finite or {@code tminC > tmaxC}
   * @see #DEFAULT_BASE_C
   */
  public static double dailyHddDegC(double tmaxC, double tminC) {
    return dailyHddDegC(tmaxC, tminC, DEFAULT_BASE_C);
  }

  /**
   * Sums daily HDD over {@code start..endInclusive}, both ends included.
   *
   * @param temps the city's daily temperatures
   * @param start first accrual day (settlement day)
   * @param endInclusive last accrual day (settlement day), included
   * @param baseC base temperature in °C
   * @return cumulative HDD in °C·day, unrounded
   * @throws IllegalArgumentException if {@code baseC} is non-finite or {@code start} is after
   *     {@code endInclusive}
   * @throws TemperatureDataException if a day in the period is missing (the message names it)
   */
  public static double cumulativeHddDegC(
      DailyTemps temps, LocalDate start, LocalDate endInclusive, double baseC) {
    Objects.requireNonNull(temps, "temps");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(endInclusive, "endInclusive");
    if (!Double.isFinite(baseC)) {
      throw new IllegalArgumentException("baseC must be finite but was " + baseC);
    }
    if (start.isAfter(endInclusive)) {
      throw new IllegalArgumentException("start " + start + " is after end " + endInclusive);
    }
    // DailyTemp guarantees finite tmax/tmin, so each mean and daily HDD is finite.
    return start
        .datesUntil(endInclusive.plusDays(1))
        .mapToDouble(date -> hddDegC(temps.get(date).meanTempC(), baseC))
        .sum();
  }

  /**
   * Sums daily HDD over {@code start..endInclusive} with the default 18 °C base.
   *
   * @param temps the city's daily temperatures
   * @param start first accrual day (settlement day)
   * @param endInclusive last accrual day (settlement day), included
   * @return cumulative HDD in °C·day, unrounded
   * @throws IllegalArgumentException if {@code start} is after {@code endInclusive}
   * @throws TemperatureDataException if a day in the period is missing (the message names it)
   * @see #DEFAULT_BASE_C
   */
  public static double cumulativeHddDegC(
      DailyTemps temps, LocalDate start, LocalDate endInclusive) {
    return cumulativeHddDegC(temps, start, endInclusive, DEFAULT_BASE_C);
  }
}
