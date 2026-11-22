package com.weatherderivatives.pricer.api;

import org.springframework.stereotype.Service;

/** Maps a request to library calls and back. Stub. */
@Service
public class PriceService {

  private final TemperatureSource source;

  /**
   * Creates the service.
   *
   * @param source where temperatures come from
   */
  public PriceService(TemperatureSource source) {
    this.source = source;
  }

  /**
   * Prices a request.
   *
   * @param request the request
   * @return the response
   */
  public PriceResponse price(PriceRequest request) {
    throw new UnsupportedOperationException("not implemented: " + source);
  }
}
