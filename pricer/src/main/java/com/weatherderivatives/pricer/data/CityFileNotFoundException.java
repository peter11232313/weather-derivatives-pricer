package com.weatherderivatives.pricer.data;

import com.weatherderivatives.pricer.index.TemperatureDataException;

/**
 * A city's temperature file does not exist. A subclass of {@link TemperatureDataException} so
 * existing callers are unaffected, while the REST layer can tell a missing file (404) from bad data
 * (422) without matching messages (spec 006).
 */
public final class CityFileNotFoundException extends TemperatureDataException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message names the missing path
   */
  public CityFileNotFoundException(String message) {
    super(message);
  }
}
