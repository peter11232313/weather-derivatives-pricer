package com.weatherderivatives.pricer.api;

import com.weatherderivatives.pricer.index.DailyTemps;

/** Loads a city's daily temperatures; the API's only route to data (replaceable in tests). */
@FunctionalInterface
public interface TemperatureSource {

  /**
   * Loads a city.
   *
   * @param city city name
   * @return the city's daily temperatures
   */
  DailyTemps load(String city);
}
