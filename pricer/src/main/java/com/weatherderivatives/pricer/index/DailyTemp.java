package com.weatherderivatives.pricer.index;

import java.time.LocalDate;

/**
 * One city's observed daily maximum and minimum temperature for a single UTC calendar day.
 *
 * <p>Construction rejects NaN values and {@code tminC > tmaxC}, naming the date in the error.
 *
 * @param date UTC calendar day
 * @param tmaxC daily maximum temperature in °C
 * @param tminC daily minimum temperature in °C
 */
public record DailyTemp(LocalDate date, double tmaxC, double tminC) {

  /**
   * Validates the day's values.
   *
   * @throws TemperatureDataException if a value is NaN or {@code tminC > tmaxC}
   * @throws NullPointerException if {@code date} is null
   */
  public DailyTemp {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Returns the daily mean temperature, {@code (tmaxC + tminC) / 2}.
   *
   * @return daily mean temperature in °C
   */
  public double meanTempC() {
    throw new UnsupportedOperationException("not implemented");
  }
}
