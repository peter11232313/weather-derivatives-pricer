package com.weatherderivatives.pricer.index;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One city's observed daily maximum and minimum temperature for a single settlement day: day D
 * under the station's Tmax/Tmin observation windows, which are defined in UTC (ADR 0005), not the
 * UTC calendar day.
 *
 * <p>Construction rejects non-finite values (NaN, ±∞) and {@code tminC > tmaxC}, naming the date in
 * the error.
 *
 * @param date settlement day D under the station's observation windows (ADR 0005)
 * @param tmaxC daily maximum temperature in °C
 * @param tminC daily minimum temperature in °C
 */
public record DailyTemp(LocalDate date, double tmaxC, double tminC) {

  /**
   * Validates the day's values.
   *
   * @throws TemperatureDataException if a value is non-finite or {@code tminC > tmaxC}
   * @throws NullPointerException if {@code date} is null
   */
  public DailyTemp {
    Objects.requireNonNull(date, "date");
    if (!Double.isFinite(tmaxC) || !Double.isFinite(tminC)) {
      throw new TemperatureDataException(
          "non-finite temperature on " + date + ": tmaxC=" + tmaxC + ", tminC=" + tminC);
    }
    if (tminC > tmaxC) {
      throw new TemperatureDataException(
          "tminC > tmaxC on " + date + ": tmaxC=" + tmaxC + ", tminC=" + tminC);
    }
  }

  /**
   * Returns the daily mean temperature, {@code (tmaxC + tminC) / 2}.
   *
   * @return daily mean temperature in °C
   */
  public double meanTempC() {
    return meanTempC(tmaxC, tminC);
  }

  /**
   * {@code (tmaxC + tminC) / 2}, computed as {@code tmaxC / 2 + tminC / 2} so finite inputs never
   * overflow. Halving is exact for normal doubles, so for any physical temperature the result is
   * bit-identical to the direct formula.
   */
  static double meanTempC(double tmaxC, double tminC) {
    return tmaxC / 2 + tminC / 2;
  }
}
