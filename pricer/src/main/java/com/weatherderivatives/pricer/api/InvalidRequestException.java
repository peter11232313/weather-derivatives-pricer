package com.weatherderivatives.pricer.api;

/** A request is invalid before any library call (e.g. a missing field); mapped to 400. */
public final class InvalidRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message names the field
   */
  public InvalidRequestException(String message) {
    super(message);
  }

  /**
   * Creates the exception from a library argument error, keeping it as the cause.
   *
   * @param message names the field (the library's message)
   * @param cause the library's exception
   */
  public InvalidRequestException(String message, Throwable cause) {
    super(message, cause);
  }
}
