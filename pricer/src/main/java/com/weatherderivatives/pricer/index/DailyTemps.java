package com.weatherderivatives.pricer.index;

import java.time.LocalDate;
import java.util.Collection;

/** An immutable series of daily temperatures for one city, keyed by UTC calendar day. */
public final class DailyTemps {

  private DailyTemps() {}

  /**
   * Builds a series from individual days.
   *
   * @param days the days, in any order
   * @return an immutable series
   * @throws TemperatureDataException if two entries share a date (the message names the date)
   * @throws NullPointerException if {@code days} or any element is null
   */
  public static DailyTemps of(Collection<DailyTemp> days) {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Returns the entry for a day.
   *
   * @param date UTC calendar day
   * @return the day's temperatures
   * @throws TemperatureDataException if the series has no entry for {@code date} (the message names
   *     the date)
   */
  public DailyTemp get(LocalDate date) {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Returns the number of days in the series.
   *
   * @return number of days
   */
  public int size() {
    throw new UnsupportedOperationException("not implemented");
  }
}
