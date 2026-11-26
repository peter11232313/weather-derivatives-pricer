package com.weatherderivatives.pricer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weatherderivatives.pricer.contract.ContractType;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link PriceService} without Spring. */
class PriceServiceTest {

  private static final TemperatureSource NO_DATA =
      city -> {
        throw new AssertionError("the source must not be read for an invalid contract");
      };

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
            InvalidRequestException.class, () -> new PriceService(NO_DATA).price(negativeStrike));

    IllegalArgumentException cause = assertInstanceOf(IllegalArgumentException.class, e.getCause());
    assertEquals(cause.getMessage(), e.getMessage());
    assertTrue(e.getMessage().contains("strike"), e.getMessage());
  }
}
