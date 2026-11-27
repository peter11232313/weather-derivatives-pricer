package com.weatherderivatives.pricer.api;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration under {@code pricer.*}.
 *
 * @param dataDir folder holding {@code <city>.parquet}; default {@code data/pricer}
 * @param settlementCurrency settlement currency per city, from {@code
 *     pricer.settlement-currency.<city>=<code>} (spec 006 amendment C); empty when no entry is
 *     configured. Only {@code GBP} is accepted while the amount fields are named {@code …Gbp}.
 */
@ConfigurationProperties("pricer")
public record PricerProperties(
    @DefaultValue("data/pricer") Path dataDir,
    @DefaultValue Map<String, String> settlementCurrency) {

  /** The only settlement currency the API can report today. */
  public static final String SUPPORTED_CURRENCY = "GBP";

  /** Same rule as the loader's city names, so every configured city can match a request. */
  private static final Pattern CITY_KEY = Pattern.compile("[a-z0-9_-]+");

  /**
   * Validates and copies the currency map; a failure here stops the application at startup.
   *
   * @throws NullPointerException if {@code settlementCurrency} is null
   * @throws IllegalArgumentException if a city key does not match {@code [a-z0-9_-]+} (names the
   *     key) or a value is not exactly {@code GBP} (names the city and the value)
   */
  public PricerProperties {
    Objects.requireNonNull(settlementCurrency, "settlementCurrency");
    for (Map.Entry<String, String> e : settlementCurrency.entrySet()) {
      String city = e.getKey();
      if (city == null || !CITY_KEY.matcher(city).matches()) {
        throw new IllegalArgumentException(
            "pricer.settlement-currency key must match [a-z0-9_-]+ but was: '" + city + "'");
      }
      if (!SUPPORTED_CURRENCY.equals(e.getValue())) {
        throw new IllegalArgumentException(
            "pricer.settlement-currency."
                + city
                + " must be "
                + SUPPORTED_CURRENCY
                + " but was: '"
                + e.getValue()
                + "'");
      }
    }
    settlementCurrency = Map.copyOf(settlementCurrency);
  }
}
