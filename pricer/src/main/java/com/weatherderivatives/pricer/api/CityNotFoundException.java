package com.weatherderivatives.pricer.api;

/** No data for the requested city (invalid name or missing file); mapped to 404. */
public final class CityNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message names the city
   * @param cause the loader's exception
   */
  public CityNotFoundException(String message, Throwable cause) {
    super(message, cause);
  }
}
