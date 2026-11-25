package com.weatherderivatives.pricer.api;

import static com.weatherderivatives.pricer.api.ApiTestSupport.add400;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertContains;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertError;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertOk;
import static com.weatherderivatives.pricer.api.ApiTestSupport.postPrice;
import static com.weatherderivatives.pricer.api.ApiTestSupport.toJson;
import static com.weatherderivatives.pricer.api.ApiTestSupport.workedExampleRequest;
import static com.weatherderivatives.pricer.api.ApiTestSupport.writeCity;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.weatherderivatives.pricer.data.ParquetFixtures;
import com.weatherderivatives.pricer.index.DailyTemp;
import com.weatherderivatives.pricer.model.DetrendedBurnAnalysis;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spec 006 error criteria (AC5, AC6, AC7, AC7b): every error is JSON {@code {error, message}} with
 * the status from the spec's errors table. Each 400 case starts from the valid worked-example
 * request (which prices, see {@link PriceEndpointPricingTest}) and breaks exactly one thing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceEndpointErrorsTest {

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
    // valid files OUTSIDE the data folder: reading either one would give 200, not 404
    writeCity(root, "x", ApiTestSupport.workedExampleDays());
    writeCity(root, "london", ApiTestSupport.workedExampleDays());
  }

  private MockHttpServletResponse send(Map<String, Object> request) throws Exception {
    return postPrice(mvc, request);
  }

  // ---------------------------------------------------------------------------------------------
  // AC5: malformed JSON, missing / unknown field, wrong type, invalid term -> 400 naming the field
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "{\"city\": \"london\", \"type\": \"CALL\",",
        "{city: london}",
        "not json at all",
        "",
        "[]",
        "null",
      })
  void ac5_malformedJson_400InvalidRequest(String body) throws Exception {
    assertError(postPrice(mvc, body), 400, "invalid_request");
  }

  @ParameterizedTest(name = "[{index}] missing {0}")
  @ValueSource(
      strings = {
        "city",
        "type",
        "firstMonth",
        "lastMonth",
        "strikeHddDegC",
        "tickGbpPerDegC",
        "firstSeasonYear",
        "lastSeasonYear",
      })
  void ac5_missingRequiredField_400InvalidRequestNamingField(String field) throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.remove(field);
    assertContains(assertError(send(req), 400, "invalid_request"), field);
  }

  @ParameterizedTest(name = "[{index}] {0}: null")
  @ValueSource(strings = {"city", "type", "strikeHddDegC", "tickGbpPerDegC", "lastSeasonYear"})
  void ac5_requiredFieldExplicitlyNull_400InvalidRequestNamingField(String field) throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put(field, null);
    assertContains(assertError(send(req), 400, "invalid_request"), field);
  }

  @Test
  void ac5_unknownField_strikeHdd_400InvalidRequestNamingIt() throws Exception {
    // every valid field is present too, so only the typo can be the reason
    Map<String, Object> req = workedExampleRequest("london");
    req.put("strikeHdd", 400.0);
    String message = assertError(send(req), 400, "invalid_request");
    assertTrue(
        Pattern.compile("strikeHdd(?!DegC)").matcher(message).find(),
        () -> "expected the unknown field strikeHdd named in: " + message);
  }

  @Test
  void ac5_unknownContractType_FOO_400InvalidRequestNamingType() throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("type", "FOO");
    assertContains(assertError(send(req), 400, "invalid_request"), "type");
  }

  @Test
  void ac5_outOfScopeContractType_COLLAR_400InvalidRequestNamingType() throws Exception {
    // collars are out of scope for spec 006; only CALL / PUT / SWAP exist
    Map<String, Object> req = workedExampleRequest("london");
    req.put("type", "COLLAR");
    assertContains(assertError(send(req), 400, "invalid_request"), "type");
  }

  @Test
  void ac5_strikeAsString_400InvalidRequestNamingStrike() throws Exception {
    // Jackson coerces "400" to 400.0 by default; the spec calls this a wrong type
    Map<String, Object> req = workedExampleRequest("london");
    req.put("strikeHddDegC", "400");
    assertContains(assertError(send(req), 400, "invalid_request"), "strikeHddDegC");
  }

  @Test
  void ac5_strikeAsBoolean_400InvalidRequestNamingStrike() throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("strikeHddDegC", true);
    assertContains(assertError(send(req), 400, "invalid_request"), "strikeHddDegC");
  }

  @Test
  void ac5_baseCAsNaNString_400InvalidRequestNamingBaseC() throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("baseC", "NaN");
    assertContains(assertError(send(req), 400, "invalid_request"), "baseC");
  }

  @Test
  void ac5_strikeOverflowingToInfinity_400InvalidRequestNamingStrike() throws Exception {
    // 1e400 parses to +Infinity; HddContract rejects a non-finite strike
    String body = toJson(workedExampleRequest("london")).replace("400.0", "1e400");
    assertTrue(body.contains("1e400"), body);
    assertContains(assertError(postPrice(mvc, body), 400, "invalid_request"), "strikeHddDegC");
  }

  @Test
  void ac5_dailyHddDecimalsFractional_1_5_400InvalidRequestNamingField() throws Exception {
    // Jackson truncates 1.5 to 1 by default (ACCEPT_FLOAT_AS_INT); the spec calls this a wrong type
    Map<String, Object> req = workedExampleRequest("london");
    req.put("dailyHddDecimals", 1.5);
    assertContains(assertError(send(req), 400, "invalid_request"), "dailyHddDecimals");
  }

  @Test
  void ac5_dailyHddDecimalsOutOfRange_5_400InvalidRequestNamingField() throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("dailyHddDecimals", 5);
    assertContains(assertError(send(req), 400, "invalid_request"), "dailyHddDecimals");
  }

  @Test
  void ac5_firstSeasonYearFractional_400InvalidRequestNamingField() throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("firstSeasonYear", 2001.5);
    assertContains(assertError(send(req), 400, "invalid_request"), "firstSeasonYear");
  }

  @ParameterizedTest(name = "[{index}] tick {0}")
  @ValueSource(doubles = {-20.0, 0.0})
  void ac5_nonPositiveTick_400InvalidRequestNamingTick(double tick) throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("tickGbpPerDegC", tick);
    assertContains(assertError(send(req), 400, "invalid_request"), "tickGbpPerDegC");
  }

  @Test
  void ac5_negativeStrike_400InvalidRequestNamingStrike() throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("strikeHddDegC", -1.0);
    assertContains(assertError(send(req), 400, "invalid_request"), "strikeHddDegC");
  }

  @ParameterizedTest(name = "[{index}] cap {0}")
  @ValueSource(doubles = {-100.0, 0.0})
  void ac5_nonPositiveCap_400InvalidRequestNamingCap(double cap) throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("capGbp", cap);
    assertContains(assertError(send(req), 400, "invalid_request"), "capGbp");
  }

  @Test
  void ac5_lastMonthBeforeFirstMonth_400InvalidRequestNamingMonths() throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("firstMonth", "2001-03");
    req.put("lastMonth", "2000-11");
    String message = assertError(send(req), 400, "invalid_request");
    assertContains(message, "firstMonth");
    assertContains(message, "lastMonth");
  }

  @Test
  void ac5_moreThanTwelveMonths_400InvalidRequestNamingMonths() throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("firstMonth", "2001-01");
    req.put("lastMonth", "2002-01");
    assertContains(assertError(send(req), 400, "invalid_request"), "lastMonth");
  }

  @ParameterizedTest(name = "[{index}] firstMonth {0}")
  @ValueSource(strings = {"2001-13", "2001-1-01", "January", "2001/01"})
  void ac5_unparseableMonth_400InvalidRequestNamingField(String month) throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("firstMonth", month);
    assertContains(assertError(send(req), 400, "invalid_request"), "firstMonth");
  }

  @Test
  void ac5_firstSeasonAfterLastSeason_400InvalidRequestNamingField() throws Exception {
    Map<String, Object> req = workedExampleRequest("london");
    req.put("firstSeasonYear", 2005);
    req.put("lastSeasonYear", 2001);
    assertContains(assertError(send(req), 400, "invalid_request"), "firstSeasonYear");
  }

  // ---------------------------------------------------------------------------------------------
  // AC6: unknown or invalid city -> 404 city_not_found; nothing outside the data folder is read
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac6_unknownCity_paris_404CityNotFoundNamingIt() throws Exception {
    String message = assertError(send(workedExampleRequest("paris")), 404, "city_not_found");
    assertContains(message, "paris");
  }

  @ParameterizedTest(name = "[{index}] city \"{0}\"")
  @ValueSource(
      strings = {"../x", "..\\x", "../london", "London", "LONDON", "london.parquet", "lon don"})
  void ac6_invalidCityName_404CityNotFound_neverReadsOutsideDataDir(String city) throws Exception {
    // root/x.parquet and root/london.parquet are valid; so is pricer-data/london.parquet, which a
    // case-insensitive file system (Windows) would open for "London" if the name were not checked
    assertError(send(workedExampleRequest(city)), 404, "city_not_found");
  }

  @Test
  void ac6_validCityInDataDir_stillPrices_soThe404sAreAboutTheName() throws Exception {
    assertOk(send(workedExampleRequest("london")));
  }

  // ---------------------------------------------------------------------------------------------
  // AC7: bad data -> 422 data_error
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac7_missingDayInsideSeason_422DataErrorNamingSeasonAndDate() throws Exception {
    List<DailyTemp> days = new ArrayList<>(ApiTestSupport.workedExampleDays());
    assertTrue(days.removeIf(d -> d.date().equals(LocalDate.of(2003, 1, 15))));
    writeCity(dataDir(), "gappy", days);

    String message = assertError(send(workedExampleRequest("gappy")), 422, "data_error");
    assertContains(message, "2003-01-15");
    assertContains(message, "season 2003");
  }

  @Test
  void ac7_valueOutsidePhysicalRange_422DataError() throws Exception {
    List<DailyTemp> days = new ArrayList<>();
    for (int y = 2001; y <= 2005; y++) {
      add400(days, y);
    }
    // 70 °C on one January day: beyond the 60 °C limit (spec 001 amendment A)
    days.replaceAll(d -> d.date().equals(LocalDate.of(2002, 1, 10)) ? hot(d.date()) : d);
    writeCity(dataDir(), "hot", days);

    String message = assertError(send(workedExampleRequest("hot")), 422, "data_error");
    assertContains(message, "tmax_c");
    assertContains(message, "2002-01-10");
  }

  @Test
  void ac7_valueBelowPhysicalRange_422DataError() throws Exception {
    List<ParquetFixtures.Row> rows = new ArrayList<>();
    for (DailyTemp d : ApiTestSupport.workedExampleDays()) {
      boolean bad = d.date().equals(LocalDate.of(2004, 1, 2));
      rows.add(new ParquetFixtures.Row(d.date(), d.tmaxC(), bad ? -95.0 : d.tminC()));
    }
    ParquetFixtures.writeTemps(dataDir().resolve("frozen.parquet"), rows);

    assertContains(assertError(send(workedExampleRequest("frozen")), 422, "data_error"), "tmin_c");
  }

  @Test
  void ac7_fileNotParquet_422DataError() throws Exception {
    Files.writeString(dataDir().resolve("garbage.parquet"), "this is not a parquet file");
    assertError(send(workedExampleRequest("garbage")), 422, "data_error");
  }

  @Test
  void ac7_missingRequiredColumn_422DataErrorNamingColumn() throws Exception {
    ParquetFixtures.writeQuery(
        dataDir().resolve("notmin.parquet"),
        "SELECT CAST('2001-01-01' AS DATE) AS date, CAST(5.0 AS DOUBLE) AS tmax_c");
    assertContains(assertError(send(workedExampleRequest("notmin")), 422, "data_error"), "tmin_c");
  }

  @Test
  void amendmentB_fileWithoutWindowMetadata_422DataErrorNamingMissingKey() throws Exception {
    // Same rows as the pricing "london" file, but no key-value metadata (an old 00-24 UTC file).
    List<ParquetFixtures.Row> rows = new ArrayList<>();
    for (DailyTemp d : ApiTestSupport.workedExampleDays()) {
      rows.add(new ParquetFixtures.Row(d.date(), d.tmaxC(), d.tminC()));
    }
    ParquetFixtures.writeTemps(dataDir().resolve("nowindow.parquet"), rows, Map.of());

    String message = assertError(send(workedExampleRequest("nowindow")), 422, "data_error");
    assertContains(message, ParquetFixtures.TMAX_WINDOW_KEY);
  }

  // ---------------------------------------------------------------------------------------------
  // Amendment A: the look-ahead guard runs with the request checks, before the data is loaded
  // ---------------------------------------------------------------------------------------------

  @Test
  void amendmentA_unknownCityWithTargetInsideWindow_400LookAheadNot404() throws Exception {
    // no paris.parquet: loading first would give 404 city_not_found
    Map<String, Object> req = workedExampleRequest("paris");
    req.put("firstSeasonYear", 2001);
    req.put("lastSeasonYear", 2010);
    req.put("targetSeasonYear", 2003);
    assertContains(assertError(send(req), 400, "invalid_request"), "look-ahead");
  }

  @Test
  void amendmentA_fiveSeasonsWithTargetInsideWindow_400LookAheadNotTenSeasonMinimum()
      throws Exception {
    // london has every day for seasons 2001..2005 (prices without a target, see AC6), so with
    // target 2003 the only other possible 400 is the detrended 10-season minimum
    Map<String, Object> req = workedExampleRequest("london");
    req.put("firstSeasonYear", 2001);
    req.put("lastSeasonYear", 2005);
    req.put("targetSeasonYear", 2003);
    String message = assertError(send(req), 400, "invalid_request");
    assertContains(message, "look-ahead");
    // the library's minimum message: "detrended burn analysis (trend fit) needs at least 10 ..."
    assertFalse(
        message.contains("at least " + DetrendedBurnAnalysis.MIN_SEASONS),
        () -> "expected the look-ahead 400, not the 10-season minimum: " + message);
    assertFalse(
        message.contains("trend fit"),
        () -> "expected the look-ahead 400, not the 10-season minimum: " + message);
  }

  // ---------------------------------------------------------------------------------------------
  // AC7b: Spring's own 404 / 405 / 415 keep their status, with the JSON error body
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac7b_unknownRoute_404NotFoundJson() throws Exception {
    MockHttpServletResponse response = mvc.perform(get("/nope")).andReturn().getResponse();
    assertError(response, 404, "not_found");
  }

  @Test
  void ac7b_postToUnknownRoute_404NotFoundJson() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/prices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(workedExampleRequest("london"))))
            .andReturn()
            .getResponse();
    assertError(response, 404, "not_found");
  }

  @Test
  void ac7b_getPrice_405MethodNotAllowedJson() throws Exception {
    MockHttpServletResponse response = mvc.perform(get("/price")).andReturn().getResponse();
    assertError(response, 405, "method_not_allowed");
  }

  @Test
  void ac7b_postPriceAsTextPlain_415UnsupportedMediaTypeJson() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/price")
                    .contentType(MediaType.TEXT_PLAIN)
                    .content(
                        toJson(workedExampleRequest("london")).getBytes(StandardCharsets.UTF_8)))
            .andReturn()
            .getResponse();
    assertError(response, 415, "unsupported_media_type");
  }

  @Test
  void ac7b_postPriceWithoutContentType_415UnsupportedMediaTypeJson() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(post("/price").content(toJson(workedExampleRequest("london"))))
            .andReturn()
            .getResponse();
    assertError(response, 415, "unsupported_media_type");
  }

  private static DailyTemp hot(LocalDate date) {
    return new DailyTemp(date, 70.0, 1.0);
  }
}
