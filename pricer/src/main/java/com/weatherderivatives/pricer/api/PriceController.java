package com.weatherderivatives.pricer.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /price} and {@code GET /health} (spec 006); a thin layer over {@link PriceService}.
 */
@RestController
public class PriceController {

  private final PriceService service;

  /**
   * Creates the controller.
   *
   * @param service the price service
   */
  public PriceController(PriceService service) {
    this.service = service;
  }

  /**
   * Prices a contract.
   *
   * @param request the JSON request
   * @return the prices
   */
  @PostMapping(path = "/price", consumes = "application/json", produces = "application/json")
  public PriceResponse price(@RequestBody PriceRequest request) {
    return service.price(request);
  }

  /**
   * Liveness check.
   *
   * @return {@code {"status":"ok"}}
   */
  @GetMapping(path = "/health", produces = "application/json")
  public Map<String, String> health() {
    return Map.of("status", "ok");
  }
}
