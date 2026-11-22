package com.weatherderivatives.pricer.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Spring Boot entry point for the REST price endpoint (spec 006). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PricerApplication {

  /** Created by Spring. */
  public PricerApplication() {}

  /**
   * Starts the server.
   *
   * @param args command-line arguments, e.g. {@code --pricer.data-dir=...}
   */
  public static void main(String[] args) {
    SpringApplication.run(PricerApplication.class, args);
  }
}
