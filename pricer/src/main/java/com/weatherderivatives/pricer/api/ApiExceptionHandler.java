package com.weatherderivatives.pricer.api;

import com.weatherderivatives.pricer.index.TemperatureDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Maps exceptions to the JSON error body {@code {"error", "message"}} (spec 006 errors table).
 *
 * <ul>
 *   <li>400 {@code invalid_request}: unreadable JSON, missing or unknown field, wrong type, invalid
 *       term or season range.
 *   <li>404 {@code city_not_found}: invalid city name or no data file.
 *   <li>422 {@code data_error}: invalid data or a missing day.
 *   <li>Spring's own 404 / 405 / 406 / 415 keep their status and headers (e.g. {@code Allow}), with
 *       the same JSON body; Spring 5xx errors are logged.
 *   <li>500 {@code internal_error}: anything else, logged, with a fixed message.
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** Created by Spring. */
  public ApiExceptionHandler() {}

  /**
   * Unreadable body: malformed JSON, unknown field or a value of the wrong type.
   *
   * @param e the exception
   * @return 400
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
    return error(HttpStatus.BAD_REQUEST, "invalid_request", describe(e));
  }

  /**
   * Missing field, invalid term or season range. {@link PriceService} turns the library's argument
   * errors into {@link InvalidRequestException}; any other {@code IllegalArgumentException} is a
   * bug and goes to 500.
   *
   * @param e the exception
   * @return 400 with the library's message
   */
  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<ApiError> invalid(InvalidRequestException e) {
    return error(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
  }

  /**
   * Unknown city.
   *
   * @param e the exception
   * @return 404
   */
  @ExceptionHandler(CityNotFoundException.class)
  public ResponseEntity<ApiError> cityNotFound(CityNotFoundException e) {
    return error(HttpStatus.NOT_FOUND, "city_not_found", e.getMessage());
  }

  /**
   * Invalid data or a missing day.
   *
   * @param e the exception
   * @return 422 with the library's message (names the season and date)
   */
  @ExceptionHandler(TemperatureDataException.class)
  public ResponseEntity<ApiError> dataError(TemperatureDataException e) {
    return error(HttpStatus.UNPROCESSABLE_CONTENT, "data_error", e.getMessage());
  }

  /** Spring MVC's own errors (unknown route, wrong method, wrong content type): status kept. */
  private static ResponseEntity<ApiError> springError(ErrorResponse e) {
    HttpStatusCode status = e.getStatusCode();
    String code =
        switch (status.value()) {
          case 404 -> "not_found";
          case 405 -> "method_not_allowed";
          case 415 -> "unsupported_media_type";
          case 406 -> "not_acceptable";
          case 400 -> "invalid_request";
          default -> status.is4xxClientError() ? "invalid_request" : "internal_error";
        };
    String message =
        switch (status.value()) {
          case 404 -> "no such endpoint";
          case 405 -> "method not allowed for this endpoint";
          case 415 -> "content type must be application/json";
          case 406 -> "responses are application/json only";
          default -> "request rejected";
        };
    if (status.is5xxServerError()) {
      LOG.error("server error from Spring MVC", (Throwable) e);
    }
    return ResponseEntity.status(status)
        .headers(e.getHeaders()) // e.g. Allow on 405, Accept on 415
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ApiError(code, message));
  }

  /**
   * Spring MVC's own errors ({@link ErrorResponse}: unknown route, wrong method, wrong content
   * type) keep their status; anything else is logged and returned as 500 with a fixed message,
   * never the exception's message or stack trace.
   *
   * @param e the exception
   * @return the Spring status, or 500
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> unexpected(Exception e) {
    if (e instanceof ErrorResponse spring) {
      return springError(spring);
    }
    LOG.error("unexpected error while pricing", e);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "internal error");
  }

  private static ResponseEntity<ApiError> error(HttpStatusCode status, String code, String msg) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ApiError(code, msg));
  }

  /** A client-facing description naming the field, without class names. */
  private static String describe(HttpMessageNotReadableException e) {
    Throwable cause = e.getCause();
    if (cause instanceof UnrecognizedPropertyException u) {
      return "unknown field: " + u.getPropertyName();
    }
    if (cause instanceof InputCoercionException c) {
      String field = currentField(c);
      return field == null
          ? "number out of range for its field"
          : "number out of range for field: " + field;
    }
    if (cause instanceof StreamReadException) {
      return "malformed JSON";
    }
    if (cause instanceof MismatchedInputException m
        && m.getPath().isEmpty()
        && m.getOriginalMessage() != null
        && m.getOriginalMessage().startsWith("Trailing token")) {
      return "unexpected content after the JSON object";
    }
    if (cause instanceof JacksonException j) {
      String field = fieldPath(j);
      return field.isEmpty()
          ? "request body must be a JSON object"
          : "invalid value for field: " + field;
    }
    return "request body is missing or unreadable";
  }

  private static String currentField(InputCoercionException e) {
    try {
      return e.processor() == null ? null : e.processor().currentName();
    } catch (RuntimeException ignored) {
      return null; // the parser may already be closed; the message still says what went wrong
    }
  }

  private static String fieldPath(JacksonException e) {
    StringBuilder path = new StringBuilder();
    for (JacksonException.Reference ref : e.getPath()) {
      if (ref.getPropertyName() != null) {
        if (!path.isEmpty()) {
          path.append('.');
        }
        path.append(ref.getPropertyName());
      }
    }
    return path.toString();
  }
}
