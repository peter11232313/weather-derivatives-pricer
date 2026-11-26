package com.weatherderivatives.pricer.api;

import static com.weatherderivatives.pricer.api.ApiTestSupport.JSON;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertNoFieldAnywhere;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.AssertionFailedError;
import tools.jackson.databind.JsonNode;

/** Proves the spec 006 amendment B helper is not vacuous: it finds the key at every depth. */
class AssertNoFieldAnywhereTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"priceGbp\":1}",
        "{\"burn\":{\"priceGbp\":1}}",
        "{\"normal\":{\"priceGbp\":1}}",
        "{\"detrended\":{\"burn\":{\"priceGbp\":1}}}",
        "{\"detrended\":{\"normal\":{\"priceGbp\":null}}}",
        "{\"seasons\":[{\"payoffGbp\":0},{\"priceGbp\":1}]}"
      })
  void amendmentB_helper_failsWhenKeyPresentAtAnyDepth(String json) {
    JsonNode node = JSON.readTree(json);
    AssertionFailedError e =
        assertThrows(AssertionFailedError.class, () -> assertNoFieldAnywhere(node, "priceGbp"));
    assertTrue(e.getMessage().contains("priceGbp"), e::getMessage);
  }

  @Test
  void amendmentB_helper_passesWhenKeyAbsent_includingSimilarNamesAndValues() {
    // burnPriceGbp / expectedPayoffGbp are different keys; "priceGbp" as a value is not a key
    JsonNode node =
        JSON.readTree(
            "{\"burn\":{\"expectedPayoffGbp\":160,\"burnPriceGbp\":1},"
                + "\"seasons\":[],\"detrended\":null,\"note\":\"priceGbp\"}");
    assertDoesNotThrow(() -> assertNoFieldAnywhere(node, "priceGbp"));
  }
}
