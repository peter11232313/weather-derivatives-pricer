package com.weatherderivatives.pricer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weatherderivatives.pricer.contract.ContractType;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link PriceService} without Spring. */
class PriceServiceTest {

  private static final TemperatureSource NO_DATA =
      city -> {
        throw new AssertionError("the source must not be read for an invalid contract");
      };

  /** The default configuration's currency map (spec 006 amendment C); the data folder is unused. */
  private static final PricerProperties LONDON_GBP =
      new PricerProperties(Path.of("unused"), Map.of("london", "GBP"));

  @Test
  void invalidContractTerm_invalidRequestKeepsLibraryCause() {
    PriceRequest negativeStrike =
        new PriceRequest(
            "london",
            ContractType.CALL,
            YearMonth.of(2001, 1),
            YearMonth.of(2001, 1),
            -1.0,
            20.0,
            null,
            null,
            null,
            2001,
            2010,
            null);

    InvalidRequestException e =
        assertThrows(
            InvalidRequestException.class,
            () -> new PriceService(NO_DATA, LONDON_GBP).price(negativeStrike));

    IllegalArgumentException cause = assertInstanceOf(IllegalArgumentException.class, e.getCause());
    assertEquals(cause.getMessage(), e.getMessage());
    assertTrue(e.getMessage().contains("strike"), e.getMessage());
  }

  @Test
  void amendmentC_cityNotInCurrencyMap_cityNotFoundNamingCities_sourceNeverRead() {
    TemperatureSource mustNotRead =
        city -> {
          throw new AssertionError("the source must not be read for an unconfigured city: " + city);
        };
    PriceRequest paris =
        new PriceRequest(
            "paris",
            ContractType.CALL,
            YearMonth.of(2001, 1),
            YearMonth.of(2001, 1),
            400.0,
            20.0,
            null,
            null,
            null,
            2001,
            2005,
            null);

    CityNotFoundException e =
        assertThrows(
            CityNotFoundException.class,
            () -> new PriceService(mustNotRead, LONDON_GBP).price(paris));

    assertTrue(e.getMessage().contains("paris"), e.getMessage());
    assertTrue(e.getMessage().contains("london"), e.getMessage());
  }
}
