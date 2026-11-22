package com.weatherderivatives.pricer.api;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.MutableCoercionConfig;
import tools.jackson.databind.type.LogicalType;

/**
 * Strict JSON reading for requests: an unknown field, a number sent as a string or boolean, a
 * fraction for an integer field, or trailing content is a 400, never silently accepted (spec 006).
 */
@Configuration
public class StrictJsonConfig {

  /** Created by Spring. */
  public StrictJsonConfig() {}

  /**
   * Tightens Spring Boot's default Jackson settings.
   *
   * @return the customizer
   */
  @Bean
  public JsonMapperBuilderCustomizer strictJson() {
    return builder ->
        builder
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfig(LogicalType.Integer, StrictJsonConfig::numbersOnly)
            .withCoercionConfig(LogicalType.Float, StrictJsonConfig::numbersOnly);
  }

  private static void numbersOnly(MutableCoercionConfig config) {
    config
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
  }
}
