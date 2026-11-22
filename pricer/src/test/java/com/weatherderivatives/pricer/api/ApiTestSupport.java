package com.weatherderivatives.pricer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.weatherderivatives.pricer.data.ParquetFixtures;
import com.weatherderivatives.pricer.index.DailyTemp;
import com.weatherderivatives.pricer.model.BurnResult;
import com.weatherderivatives.pricer.model.IndexDistributionResult;
import com.weatherderivatives.pricer.model.SeasonOutcome;
import com.weatherderivatives.pricer.model.SeasonTrend;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixtures and assertions shared by the spec 006 API tests. */
final class ApiTestSupport {

  static final JsonMapper JSON = JsonMapper.shared();

  private ApiTestSupport() {}

  // ---------------------------------------------------------------------------------------------
  // Temperature fixtures (same synthetic seasons as model/IndexDistributionAnalysisTest)
  // ---------------------------------------------------------------------------------------------

  /** Every day in {@code first..lastInclusive} with the same tmax/tmin, appended to {@code out}. */
  static void addConstant(
      List<DailyTemp> out, LocalDate first, LocalDate lastInclusive, double tmaxC, double tminC) {
    for (LocalDate d = first; !d.isAfter(lastInclusive); d = d.plusDays(1)) {
      out.add(new DailyTemp(d, tmaxC, tminC));
    }
  }

  /**
   * January of {@code year}: Jan 1-30 at {@code bulkTmax/bulkTmin}, Jan 31 at {@code
   * lastTmax/lastTmin}, padded with cold December/February days so only January may be summed.
   */
  static void addJanuary(
      List<DailyTemp> out,
      int year,
      double bulkTmaxC,
      double bulkTminC,
      double lastTmaxC,
      double lastTminC) {
    addConstant(out, LocalDate.of(year - 1, 12, 30), LocalDate.of(year - 1, 12, 31), -20.0, -30.0);
    addConstant(out, LocalDate.of(year, 1, 1), LocalDate.of(year, 1, 30), bulkTmaxC, bulkTminC);
    addConstant(out, LocalDate.of(year, 1, 31), LocalDate.of(year, 1, 31), lastTmaxC, lastTminC);
    addConstant(out, LocalDate.of(year, 2, 1), LocalDate.of(year, 2, 2), -20.0, -30.0);
  }

  /** Index 360 = 30 × 12 (T = 6: 10/2) + 1 × 0 (T = 18: 22/14). */
  static void add360(List<DailyTemp> out, int year) {
    addJanuary(out, year, 10.0, 2.0, 22.0, 14.0);
  }

  /** Index 400 = 30 × 13 (T = 5: 9/1) + 1 × 10 (T = 8: 12/4). */
  static void add400(List<DailyTemp> out, int year) {
    addJanuary(out, year, 9.0, 1.0, 12.0, 4.0);
  }

  /** Index 440 = 30 × 14 (T = 4: 8/0) + 1 × 20 (T = −2: 2/−6). */
  static void add440(List<DailyTemp> out, int year) {
    addJanuary(out, year, 8.0, 0.0, 2.0, -6.0);
  }

  /** Spec 006 worked example: seasons 2001..2005 with index 360, 400, 400, 400, 440. */
  static List<DailyTemp> workedExampleDays() {
    List<DailyTemp> days = new ArrayList<>();
    add360(days, 2001);
    add400(days, 2002);
    add400(days, 2003);
    add400(days, 2004);
    add440(days, 2005);
    return days;
  }

  /** Spec 004 trend-plus-noise example: 0.2 °C/yr trend + noise, January seasons 2001..2010. */
  static List<DailyTemp> trendPlusNoiseDays() {
    double[] means = {11.0, 9.2, 9.4, 11.6, 10.8, 11.0, 12.2, 10.4, 10.6, 12.8};
    List<DailyTemp> days = new ArrayList<>();
    for (int k = 0; k < means.length; k++) {
      int y = 2001 + k;
      addConstant(days, LocalDate.of(y, 1, 1), LocalDate.of(y, 1, 31), means[k] + 4, means[k] - 4);
    }
    return days;
  }

  /**
   * Every day from 1999-07-01 to 2014-06-30 (three leap days): a seasonal cycle with its minimum
   * near 20 January, a per-year anomaly and daily noise, rounded to 0.1 °C. Seeded, so the file is
   * the same on every run. Nov–Mar HDD (base 18) is roughly 1,800 °C·day per season.
   */
  static List<DailyTemp> winterDays() {
    Random random = new Random(20061122L);
    List<DailyTemp> days = new ArrayList<>();
    double anomalyC = 0.0;
    for (LocalDate d = LocalDate.of(1999, 7, 1);
        d.isBefore(LocalDate.of(2014, 7, 1));
        d = d.plusDays(1)) {
      if (d.getDayOfYear() == 1) {
        anomalyC = 1.5 * random.nextGaussian();
      }
      double meanC =
          11.0
              - 8.0 * Math.cos(2.0 * Math.PI * (d.getDayOfYear() - 20) / 365.25)
              + anomalyC
              + 3.0 * random.nextGaussian();
      double halfRangeC = 2.0 + 3.0 * random.nextDouble();
      double tmaxC = Math.round((meanC + halfRangeC) * 10.0) / 10.0;
      double tminC = Math.round((meanC - halfRangeC) * 10.0) / 10.0;
      days.add(new DailyTemp(d, tmaxC, tminC));
    }
    return days;
  }

  /** Writes {@code days} to {@code dir/<city>.parquet}. */
  static Path writeCity(Path dir, String city, List<DailyTemp> days) {
    return writeFile(dir.resolve(city + ".parquet"), days);
  }

  /** Writes {@code days} to {@code file}. */
  static Path writeFile(Path file, List<DailyTemp> days) {
    List<ParquetFixtures.Row> rows = new ArrayList<>(days.size());
    for (DailyTemp d : days) {
      rows.add(new ParquetFixtures.Row(d.date(), d.tmaxC(), d.tminC()));
    }
    return ParquetFixtures.writeTemps(file, rows);
  }

  // ---------------------------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------------------------

  /** The worked-example request (spec 006), as a mutable map so tests can edit single fields. */
  static Map<String, Object> workedExampleRequest(String city) {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("city", city);
    req.put("type", "CALL");
    req.put("firstMonth", "2001-01");
    req.put("lastMonth", "2001-01");
    req.put("strikeHddDegC", 400.0);
    req.put("tickGbpPerDegC", 20.0);
    req.put("capGbp", null);
    req.put("dailyHddDecimals", null);
    req.put("firstSeasonYear", 2001);
    req.put("lastSeasonYear", 2005);
    req.put("targetSeasonYear", null);
    return req;
  }

  static String toJson(Map<String, Object> request) {
    return JSON.writeValueAsString(request);
  }

  static MockHttpServletResponse postPrice(MockMvc mvc, String body) throws Exception {
    return mvc.perform(post("/price").contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse();
  }

  static MockHttpServletResponse postPrice(MockMvc mvc, Map<String, Object> request)
      throws Exception {
    return postPrice(mvc, toJson(request));
  }

  static String body(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(StandardCharsets.UTF_8);
  }

  /** Asserts a 200 JSON response and returns its parsed body. */
  static JsonNode assertOk(MockHttpServletResponse response) throws Exception {
    String text = body(response);
    assertEquals(200, response.getStatus(), () -> "expected 200 but body was: " + text);
    return JSON.readTree(text);
  }

  // ---------------------------------------------------------------------------------------------
  // Assertions
  // ---------------------------------------------------------------------------------------------

  /**
   * Asserts the spec 006 error contract: the status, a JSON body with exactly {@code error} and
   * {@code message} (both non-blank strings), and the error code. Returns the message.
   */
  static String assertError(MockHttpServletResponse response, int status, String code)
      throws Exception {
    String text = body(response);
    assertEquals(
        status, response.getStatus(), () -> "expected " + status + " but body was: " + text);
    String contentType = response.getContentType();
    assertNotNull(contentType, () -> "no content type; body was: " + text);
    assertTrue(
        MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON),
        () -> "expected a JSON body but content type was " + contentType + ": " + text);
    assertFalse(text.isBlank(), "error body is empty");
    JsonNode node = JSON.readTree(text);
    assertTrue(node.isObject(), () -> "error body is not a JSON object: " + text);
    assertEquals(Set.of("error", "message"), fieldNames(node), () -> "error body was: " + text);
    assertTrue(node.get("error").isString(), () -> "error is not a string: " + text);
    assertTrue(node.get("message").isString(), () -> "message is not a string: " + text);
    assertEquals(code, node.get("error").asString(), () -> "error body was: " + text);
    String message = node.get("message").asString();
    assertFalse(message.isBlank(), () -> "blank message: " + text);
    return message;
  }

  static void assertContains(String text, String expected) {
    assertTrue(
        text != null && text.contains(expected), () -> "expected '" + expected + "' in: " + text);
  }

  /** Asserts {@code text} contains {@code number} as a whole number (not inside e.g. a year). */
  static void assertMentionsNumber(String text, int number) {
    assertTrue(
        text != null && Pattern.compile("(?<![0-9.])" + number + "(?![0-9])").matcher(text).find(),
        () -> "expected the number " + number + " in: " + text);
  }

  static Set<String> fieldNames(JsonNode node) {
    return new TreeSet<>(node.propertyNames());
  }

  /** The JSON number at {@code node.field}; fails if it is missing or not a number. */
  static double num(JsonNode node, String field) {
    JsonNode value = node.get(field);
    assertNotNull(value, () -> "missing field " + field + " in " + node);
    assertTrue(value.isNumber(), () -> field + " is not a number in " + node);
    return value.asDouble();
  }

  /** Asserts the seasons array equals the library outcomes exactly, in order. */
  static void assertSeasons(List<SeasonOutcome> expected, JsonNode seasons) {
    assertNotNull(seasons, "missing seasons");
    assertTrue(seasons.isArray(), () -> "seasons is not an array: " + seasons);
    assertEquals(expected.size(), seasons.size(), () -> "seasons: " + seasons);
    for (int k = 0; k < expected.size(); k++) {
      SeasonOutcome e = expected.get(k);
      JsonNode s = seasons.get(k);
      assertEquals(Set.of("seasonYear", "indexHddDegC", "payoffGbp"), fieldNames(s));
      assertEquals(e.seasonYear(), s.get("seasonYear").asInt(), "seasonYear at " + k);
      assertEquals(e.indexHddDegC(), num(s, "indexHddDegC"), "indexHddDegC of " + e.seasonYear());
      assertEquals(e.payoffGbp(), num(s, "payoffGbp"), "payoffGbp of " + e.seasonYear());
    }
  }

  /** Asserts a burn object equals the library burn result exactly. */
  static void assertBurn(BurnResult expected, JsonNode burn) {
    assertNotNull(burn, "missing burn");
    assertEquals(
        Set.of(
            "priceGbp",
            "stdErrorGbp",
            "payoffStdDevGbp",
            "minPayoffGbp",
            "maxPayoffGbp",
            "meanIndexHddDegC"),
        fieldNames(burn));
    assertEquals(expected.burnPriceGbp(), num(burn, "priceGbp"), "burn.priceGbp");
    assertEquals(expected.burnPriceStdErrorGbp(), num(burn, "stdErrorGbp"), "burn.stdErrorGbp");
    assertEquals(expected.payoffStdDevGbp(), num(burn, "payoffStdDevGbp"), "burn.payoffStdDevGbp");
    assertEquals(expected.minPayoffGbp(), num(burn, "minPayoffGbp"), "burn.minPayoffGbp");
    assertEquals(expected.maxPayoffGbp(), num(burn, "maxPayoffGbp"), "burn.maxPayoffGbp");
    assertEquals(
        expected.meanIndexHddDegC(), num(burn, "meanIndexHddDegC"), "burn.meanIndexHddDegC");
  }

  /** Asserts a normal object equals the library index-distribution result exactly. */
  static void assertNormal(IndexDistributionResult expected, JsonNode normal) {
    assertNotNull(normal, "missing normal");
    assertEquals(Set.of("priceGbp", "meanIndexHddDegC", "sdIndexHddDegC"), fieldNames(normal));
    assertEquals(expected.expectedPayoffGbp(), num(normal, "priceGbp"), "normal.priceGbp");
    assertEquals(
        expected.meanIndexHddDegC(), num(normal, "meanIndexHddDegC"), "normal.meanIndexHddDegC");
    assertEquals(expected.sdIndexHddDegC(), num(normal, "sdIndexHddDegC"), "normal.sdIndexHddDegC");
  }

  /** Asserts shifted seasons equal the detrended burn's outcomes and trends exactly, in order. */
  static void assertDetrendedSeasons(
      List<SeasonOutcome> outcomes, List<SeasonTrend> trends, JsonNode seasons) {
    assertNotNull(seasons, "missing detrended.seasons");
    assertTrue(seasons.isArray(), () -> "detrended.seasons is not an array: " + seasons);
    assertEquals(outcomes.size(), seasons.size(), () -> "detrended.seasons: " + seasons);
    for (int k = 0; k < outcomes.size(); k++) {
      SeasonOutcome o = outcomes.get(k);
      SeasonTrend t = trends.get(k);
      JsonNode s = seasons.get(k);
      assertEquals(
          Set.of("seasonYear", "indexHddDegC", "payoffGbp", "seasonMeanTempC", "shiftDegC"),
          fieldNames(s));
      assertEquals(o.seasonYear(), s.get("seasonYear").asInt(), "seasonYear at " + k);
      assertEquals(o.indexHddDegC(), num(s, "indexHddDegC"), "shifted index of " + o.seasonYear());
      assertEquals(o.payoffGbp(), num(s, "payoffGbp"), "shifted payoff of " + o.seasonYear());
      assertEquals(
          t.seasonMeanTempC(), num(s, "seasonMeanTempC"), "seasonMeanTempC of " + o.seasonYear());
      assertEquals(t.shiftDegC(), num(s, "shiftDegC"), "shiftDegC of " + o.seasonYear());
    }
  }
}
