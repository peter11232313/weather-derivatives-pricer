package com.weatherderivatives.pricer.api;

import static com.weatherderivatives.pricer.api.ApiTestSupport.assertContains;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertError;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertOk;
import static com.weatherderivatives.pricer.api.ApiTestSupport.postPrice;
import static com.weatherderivatives.pricer.api.ApiTestSupport.toJson;
import static com.weatherderivatives.pricer.api.ApiTestSupport.workedExampleRequest;
import static com.weatherderivatives.pricer.api.ApiTestSupport.writeCity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spec 006 review follow-ups (code and quant review, owner OK): wrong types that Jackson used to
 * coerce, headers on Spring's own errors, field names for out-of-range numbers, trailing content,
 * 406, and the default base temperature.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceEndpointReviewFollowUpsTest {

  @TempDir static Path root;

  @Autowired MockMvc mvc;

  private static Path dataDir() {
    return root.resolve("pricer-data");
  }

  @DynamicPropertySource
  static void dataDirProperty(DynamicPropertyRegistry registry) {
    registry.add("pricer.data-dir", () -> dataDir().toString());
  }

  @BeforeAll
  static void writeFiles() throws Exception {
    Files.createDirectories(dataDir());
    writeCity(dataDir(), "london", ApiTestSupport.workedExampleDays());
  }

  @Test
  void enumGivenAsNumber_type1_400NamingType() throws Exception {
    // used to be read as the enum's index: 1 priced a PUT with 200
    Map<String, Object> req = workedExampleRequest("london");
    req.put("type", 1);
    assertContains(assertError(postPrice(mvc, req), 400, "invalid_request"), "type");
  }

  @Test
  void enumGivenAsNumericString_type1_400NamingType() throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("type", "1");
    assertContains(assertError(postPrice(mvc, req), 400, "invalid_request"), "type");
  }

  @Test
  void textGivenAsBoolean_cityTrue_400NamingCity() throws Exception {
    // used to become the text "true" and give 404
    Map<String, Object> req = workedExampleRequest("london");
    req.put("city", true);
    assertContains(assertError(postPrice(mvc, req), 400, "invalid_request"), "city");
  }

  @Test
  void getPrice_405CarriesAllowHeaderWithPost() throws Exception {
    MockHttpServletResponse response = mvc.perform(get("/price")).andReturn().getResponse();
    assertError(response, 405, "method_not_allowed");
    String allow = response.getHeader("Allow");
    assertNotNull(allow, "405 must carry an Allow header (RFC 9110)");
    assertTrue(allow.contains("POST"), () -> "Allow header was: " + allow);
  }

  @Test
  void integerTooLargeForField_400NamingField() throws Exception {
    String body =
        toJson(workedExampleRequest("london"))
            .replace("\"firstSeasonYear\":2001", "\"firstSeasonYear\":99999999999");
    assertContains(assertError(postPrice(mvc, body), 400, "invalid_request"), "firstSeasonYear");
  }

  @Test
  void trailingContentAfterObject_400SaysTrailingContent() throws Exception {
    String body = toJson(workedExampleRequest("london")) + " {}";
    String message = assertError(postPrice(mvc, body), 400, "invalid_request");
    assertTrue(
        message.toLowerCase(java.util.Locale.ROOT).contains("after"),
        () -> "expected a message about content after the JSON object but was: " + message);
  }

  @Test
  void healthWithAcceptTextPlain_406NotAcceptable() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get("/health").accept(MediaType.TEXT_PLAIN)).andReturn().getResponse();
    // Status only: the client refused JSON, so no {error, message} body can be sent to it.
    assertEquals(406, response.getStatus(), () -> "status was " + response.getStatus());
  }

  @Test
  void baseCOmitted_equalsBaseC18() throws Exception {
    Map<String, Object> omitted = workedExampleRequest("london");
    omitted.put("dailyHddDecimals", 1);
    Map<String, Object> explicit = workedExampleRequest("london");
    explicit.put("dailyHddDecimals", 1);
    explicit.put("baseC", 18.0);
    assertEquals(assertOk(postPrice(mvc, explicit)), assertOk(postPrice(mvc, omitted)));
  }
}
