package com.weatherderivatives.pricer.index;

/**
 * Thrown when temperature input data is missing, malformed or inconsistent.
 *
 * <p>The message always names the offending date, column or file path so the bad input can be
 * located without a debugger.
 */
public class TemperatureDataException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception with a message naming the offending date, column or path.
   *
   * @param message description of the problem, including the date, column or path
   */
  public TemperatureDataException(String message) {
    super(message);
  }

  /**
   * Creates an exception wrapping a lower-level cause.
   *
   * @param message description of the problem, including the date, column or path
   * @param cause the underlying exception
   */
  public TemperatureDataException(String message, Throwable cause) {
    super(message, cause);
  }
}
