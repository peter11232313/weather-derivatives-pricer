package com.weatherderivatives.pricer.index;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable series of daily temperatures for one city, keyed by settlement day under the
 * station's observation windows (ADR 0005).
 */
public final class DailyTemps {

  private final Map<LocalDate, DailyTemp> byDate;

  private DailyTemps(Map<LocalDate, DailyTemp> byDate) {
    this.byDate = Map.copyOf(byDate);
  }

  /**
   * Builds a series from individual days.
   *
   * @param days the days, in any order
   * @return an immutable series
   * @throws TemperatureDataException if two entries share a date (the message names the date)
   * @throws NullPointerException if {@code days} or any element is null
   */
  public static DailyTemps of(Collection<DailyTemp> days) {
    Objects.requireNonNull(days, "days");
    Map<LocalDate, DailyTemp> byDate = new HashMap<>(days.size() * 2);
    for (DailyTemp day : days) {
      Objects.requireNonNull(day, "day");
      if (byDate.putIfAbsent(day.date(), day) != null) {
        throw new TemperatureDataException("duplicate date " + day.date());
      }
    }
    return new DailyTemps(byDate);
  }

  /**
   * Returns the entry for a day.
   *
   * @param date settlement day D under the station's Tmax/Tmin observation windows (ADR 0005)
   * @return the day's temperatures
   * @throws TemperatureDataException if the series has no entry for {@code date} (the message names
   *     the date)
   */
  public DailyTemp get(LocalDate date) {
    DailyTemp day = byDate.get(date);
    if (day == null) {
      throw new TemperatureDataException("missing temperature for " + date);
    }
    return day;
  }

  /**
   * Returns the number of days in the series.
   *
   * @return number of days
   */
  public int size() {
    return byDate.size();
  }
}
