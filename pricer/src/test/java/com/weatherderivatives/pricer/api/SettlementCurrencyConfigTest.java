package com.weatherderivatives.pricer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Spec 006 amendment C: {@code pricer.settlement-currency.<city>} is validated at startup. Only
 * {@code GBP} is accepted, and keys must match {@code [a-z0-9_-]+}; anything else stops the
 * application with a message naming the city (and the value). The context holds the pricer
 * properties, the service and the default temperature source, so the check fails startup wherever
 * it lives among them.
 */
class SettlementCurrencyConfigTest {

  /** The pricer beans without the web layer. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PricerProperties.class)
  @Import({TemperatureSourceConfig.class, PriceService.class})
  static class PricerBeans {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PricerBeans.class);

  private static Throwable rootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }

  private static void assertMessageContains(Throwable ex, String expected) {
    String msg = ex.getMessage();
    assertTrue(
        msg != null && msg.contains(expected),
        () -> "expected message to contain '" + expected + "' but was: " + msg);
  }

  @Test
  void amendmentC_gbpValues_startAndBind() {
    runner
        .withPropertyValues(
            "pricer.settlement-currency.london=GBP", "pricer.settlement-currency.paris=GBP")
        .run(context -> {
          assertNull(context.getStartupFailure(), () -> "startup failed: " + context);
          assertEquals(
              Map.of("london", "GBP", "paris", "GBP"),
              context.getBean(PricerProperties.class).settlementCurrency());
        });
  }

  @Test
  void amendmentC_noCurrencyProperties_bindsEmptyMap() {
    runner.run(context -> {
      assertNull(context.getStartupFailure(), () -> "startup failed: " + context);
      assertEquals(Map.of(), context.getBean(PricerProperties.class).settlementCurrency());
    });
  }

  @Test
  void amendmentC_defaultApplicationConfiguration_mapIsLondonGbp() {
    runner
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(context -> {
          assertNull(context.getStartupFailure(), () -> "startup failed: " + context);
          assertEquals(
              Map.of("london", "GBP"),
              context.getBean(PricerProperties.class).settlementCurrency());
        });
  }

  @ParameterizedTest(name = "[{index}] paris={0}")
  @CsvSource(
      ignoreLeadingAndTrailingWhitespace = false,
      value = {"EUR", "gbp", "Gbp", "USD"})
  void amendmentC_currencyNotGbp_startupFailsNamingCityAndValue(String value) {
    runner
        .withPropertyValues("pricer.settlement-currency.paris=" + value)
        .run(context -> {
          Throwable failure = context.getStartupFailure();
          assertNotNull(failure, () -> "expected startup to fail for paris=" + value);
          Throwable root = rootCause(failure);
          assertMessageContains(root, "paris");
          assertMessageContains(root, value);
        });
  }

  @Test
  void amendmentC_blankCurrency_startupFailsNamingCity() {
    runner
        .withPropertyValues("pricer.settlement-currency.paris=")
        .run(context -> {
          Throwable failure = context.getStartupFailure();
          assertNotNull(failure, "expected startup to fail for a blank currency");
          assertMessageContains(rootCause(failure), "paris");
        });
  }

  @ParameterizedTest(name = "[{index}] key [{0}]")
  @CsvSource({"Paris", "a.b"})
  void amendmentC_keyNotLowerCaseName_startupFailsNamingKey(String key) {
    // bracket notation keeps the key as written (no relaxed-binding normalisation)
    runner
        .withPropertyValues("pricer.settlement-currency.[" + key + "]=GBP")
        .run(context -> {
          Throwable failure = context.getStartupFailure();
          assertNotNull(failure, () -> "expected startup to fail for key " + key);
          assertMessageContains(rootCause(failure), key);
        });
  }
}
