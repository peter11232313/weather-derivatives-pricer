package com.weatherderivatives.pricer.data;

import com.weatherderivatives.pricer.index.TemperatureDataException;

/** A city's temperature file does not exist (spec 006). Stub: not thrown yet. */
public class CityFileNotFoundException extends TemperatureDataException {

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
