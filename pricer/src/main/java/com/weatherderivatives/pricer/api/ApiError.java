package com.weatherderivatives.pricer.api;

/**
 * JSON error body.
 *
 * @param error machine-readable code, e.g. {@code invalid_request}
 * @param message human-readable message
 */
public record ApiError(String error, String message) {}
