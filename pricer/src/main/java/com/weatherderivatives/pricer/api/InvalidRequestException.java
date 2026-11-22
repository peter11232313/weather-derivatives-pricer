package com.weatherderivatives.pricer.api;

/** A request is invalid before any library call (e.g. a missing field); mapped to 400. */
public class InvalidRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message names the field
   */
  public InvalidRequestException(String message) {
    super(message);
  }
}
