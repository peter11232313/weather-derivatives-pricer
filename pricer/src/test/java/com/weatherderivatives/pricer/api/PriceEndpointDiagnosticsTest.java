package com.weatherderivatives.pricer.api;

import static com.weatherderivatives.pricer.api.ApiTestSupport.addConstant;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertContains;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertError;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertNormal;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertOk;
import static com.weatherderivatives.pricer.api.ApiTestSupport.fieldNames;
import static com.weatherderivatives.pricer.api.ApiTestSupport.num;
import static com.weatherderivatives.pricer.api.ApiTestSupport.postPrice;
import static com.weatherderivatives.pricer.api.ApiTestSupport.workedExampleRequest;
import static com.weatherderivatives.pricer.api.ApiTestSupport.writeCity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weatherderivatives.pricer.contract.ContractType;
import com.weatherderivatives.pricer.contract.HddContract;
import com.weatherderivatives.pricer.index.DailyTemp;
import com.weatherderivatives.pricer.index.DailyTemps;
import com.weatherderivatives.pricer.index.HddIndex;
import com.weatherderivatives.pricer.model.DetrendedBurnAnalysis;
import com.weatherderivatives.pricer.model.DetrendedBurnResult;
import com.weatherderivatives.pricer.model.IndexDistributionAnalysis;
import com.weatherderivatives.pricer.model.IndexDistributionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * Spec 006 amendment C through {@code POST /price}: settlement currency (configured map, 404 for an
 * unconfigured city), trend significance ({@code detrended.slopeTStat}, {@code slopeSignificant})
 * and normal-fit diagnostics ({@code normal.seasonCount}, {@code normal.indexSkewness}). Expected
 * values are hand-computed or the library's own result on the same {@link DailyTemps}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceEndpointDiagnosticsTest {

  /** Spec tolerance for the worked-example normal price. */
  private static final double PRICE_TOL = 1e-6;

  @TempDir static Path root;

  @Autowired MockMvc mvc;

  private static Path dataDir() {
    return root.resolve("pricer-data");
  }

  /**
   * Data folder, plus this class's fixture cities in the currency map; {@code london=GBP} comes
   * from application.properties (map entries merge). {@code unlisted} and {@code unlisted2} are
   * deliberately left out; {@code nofile} is configured but has no file.
   */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("pricer.data-dir", () -> dataDir().toString());
    for (String city : List.of("trend", "linear", "nofile")) {
      registry.add("pricer.settlement-currency." + city, () -> "GBP");
    }
  }

  @BeforeAll
  static void writeFiles() throws Exception {
    Files.createDirectories(dataDir());
    writeCity(dataDir(), "london", ApiTestSupport.workedExampleDays());
    writeCity(dataDir(), "trend", ApiTestSupport.trendPlusNoiseDays());
    writeCity(dataDir(), "linear", linearDays());
    // a valid file for a city not in the currency map: pricing it would give 200
    writeCity(dataDir(), "unlisted", ApiTestSupport.workedExampleDays());
    // an unreadable file for a city not in the currency map: reading it would give 422
    Files.writeString(dataDir().resolve("unlisted2.parquet"), "this is not a parquet file");
  }

  /**
   * January seasons 2001..2010 with season mean 9.0 + 0.5k °C exactly (tmax/tmin = mean ± 4): the
   * trend fit has slope 0.5 °C/yr and every residual is 0, so the slope's standard error is 0.
   */
  private static List<DailyTemp> linearDays() {
    List<DailyTemp> days = new ArrayList<>();
    for (int k = 0; k < 10; k++) {
      int y = 2001 + k;
      double meanC = 9.0 + 0.5 * k;
      addConstant(days, LocalDate.of(y, 1, 1), LocalDate.of(y, 1, 31), meanC + 4, meanC - 4);
    }
    return days;
  }

  /** The trend request (spec 004 AC2): CALL January, K 200, tick 1,000, 2001..2010, target 2011. */
  private static Map<String, Object> trendRequest(String city) {
    Map<String, Object> req = workedExampleRequest(city);
    req.put("strikeHddDegC", 200.0);
    req.put("tickGbpPerDegC", 1000.0);
    req.put("firstSeasonYear", 2001);
    req.put("lastSeasonYear", 2010);
    req.put("targetSeasonYear", 2011);
    return req;
  }

  private static HddContract trendContract() {
    return new HddContract(
        ContractType.CALL,
        YearMonth.of(2001, 1),
        YearMonth.of(2001, 1),
        HddIndex.DEFAULT_BASE_C,
        200.0,
        1000.0,
        OptionalDouble.empty(),
        OptionalInt.empty());
  }

  // ---------------------------------------------------------------------------------------------
  // Currency: london -> "GBP", prices unchanged
  // ---------------------------------------------------------------------------------------------

  @Test
  void amendmentC_defaultConfigLondon_200CurrencyGbp_pricesUnchanged() throws Exception {
    JsonNode r = assertOk(postPrice(mvc, workedExampleRequest("london")));

    assertEquals(
        Set.of("city", "currency", "seasons", "burn", "normal", "detrended"), fieldNames(r));
    JsonNode currency = r.get("currency");
    assertTrue(currency.isString(), () -> "currency is not a string: " + currency);
    assertEquals("GBP", currency.asString());
    // worked example (spec 006 AC1): burn 800 / 5 = 160; normal 20 · 28.284271 · φ(0)
    assertEquals(160.0, num(r.get("burn"), "expectedPayoffGbp"), 1e-9);
    assertEquals(225.675833, num(r.get("normal"), "expectedPayoffGbp"), PRICE_TOL);
    assertEquals(28.284271, num(r.get("normal"), "sdIndexHddDegC"), PRICE_TOL);
  }

  // ---------------------------------------------------------------------------------------------
  // Normal-fit diagnostics
  // ---------------------------------------------------------------------------------------------

  @Test
  void amendmentC_workedExample_normalSeasonCountFive_skewnessZero() throws Exception {
    JsonNode normal = assertOk(postPrice(mvc, workedExampleRequest("london"))).get("normal");

    // indices 360, 400, 400, 400, 440: five seasons, symmetric about 400 -> G1 = 0
    assertEquals(5, normal.get("seasonCount").asInt());
    assertTrue(normal.get("seasonCount").isIntegralNumber(), () -> "seasonCount: " + normal);
    assertEquals(0.0, num(normal, "indexSkewness"), 1e-12);
  }

  @Test
  void amendmentC_targetSeasonYear_detrendedNormalDiagnosticsOnShiftedValues() throws Exception {
    JsonNode r = assertOk(postPrice(mvc, trendRequest("trend")));

    DailyTemps temps = DailyTemps.of(ApiTestSupport.trendPlusNoiseDays());
    IndexDistributionResult libDetrended =
        IndexDistributionAnalysis.runDetrended(trendContract(), temps, 2001, 2010, 2011);
    IndexDistributionResult libRaw =
        IndexDistributionAnalysis.run(trendContract(), temps, 2001, 2010);

    JsonNode d = r.get("detrended");
    assertNotNull(d, "missing detrended");
    assertNormal(libDetrended, d.get("normal"));
    assertNormal(libRaw, r.get("normal"));
    // shifted indices 155, 217, 217, 155, 186, 186, 155, 217, 217, 155 (spec 004 AC): symmetric
    // about 186 -> G1 = 0. The raw indices 31 · (18 − mean) are not symmetric: G1 = −0.0947730452
    // (hand/Python calculation of G1 on 217, 272.8, 266.6, 198.4, 223.2, 217, 179.8, 235.6, 229.4,
    // 161.2), so a skewness taken on the raw values would fail here.
    JsonNode dn = d.get("normal");
    assertEquals(10, dn.get("seasonCount").asInt());
    assertEquals(0.0, num(dn, "indexSkewness"), 1e-9);
    assertEquals(10, r.get("normal").get("seasonCount").asInt());
    assertEquals(-0.0947730452, num(r.get("normal"), "indexSkewness"), 1e-9);
  }

  @Test
  void amendmentC_detrendedSigmaZero_indexSkewnessNull() throws Exception {
    // linear fixture shifted to 2011: every season mean becomes 14.0 °C -> every index 31 · 4 = 124
    JsonNode r = assertOk(postPrice(mvc, trendRequest("linear")));

    JsonNode dn = r.get("detrended").get("normal");
    assertEquals(0.0, num(dn, "sdIndexHddDegC"), 1e-9);
    assertTrue(dn.has("indexSkewness"), () -> "indexSkewness missing: " + dn);
    assertTrue(dn.get("indexSkewness").isNull(), () -> "expected null skewness: " + dn);
    assertEquals(10, dn.get("seasonCount").asInt());
  }

  // ---------------------------------------------------------------------------------------------
  // Trend significance
  // ---------------------------------------------------------------------------------------------

  @Test
  void amendmentC_detrendedRun_slopeTStatIsSlopeOverSe_significantIffAbsTAtLeastTwo()
      throws Exception {
    JsonNode r = assertOk(postPrice(mvc, trendRequest("trend")));

    DetrendedBurnResult lib =
        DetrendedBurnAnalysis.run(
            trendContract(), DailyTemps.of(ApiTestSupport.trendPlusNoiseDays()), 2001, 2010, 2011);
    JsonNode d = r.get("detrended");
    assertEquals(
        Set.of(
            "targetSeasonYear",
            "slopeDegCPerYear",
            "slopeStdErrDegCPerYear",
            "slopeTStat",
            "slopeSignificant",
            "seasons",
            "burn",
            "normal"),
        fieldNames(d));

    double t = num(d, "slopeTStat");
    assertEquals(lib.slopeDegCPerYear() / lib.slopeStdErrDegCPerYear(), t, 1e-12);
    assertEquals(lib.slopeTStat().getAsDouble(), t);
    // spec 004 trend-plus-noise: slope 0.2, residuals ±1.0 (eight) and 0 (two) -> SSres = 8,
    // SE = sqrt(8 / 8 / 82.5); t = 0.2 · sqrt(82.5) = 1.8166 < 2 -> not significant
    assertEquals(0.2 * Math.sqrt(82.5), t, 1e-9);
    JsonNode significant = d.get("slopeSignificant");
    assertTrue(significant.isBoolean(), () -> "slopeSignificant is not a boolean: " + d);
    assertFalse(significant.asBoolean());
    assertEquals(lib.slopeSignificant(), significant.asBoolean());
  }

  @Test
  void amendmentC_perfectlyLinearTrend_seZero_slopeTStatNull_significantTrue() throws Exception {
    DetrendedBurnResult lib =
        DetrendedBurnAnalysis.run(trendContract(), DailyTemps.of(linearDays()), 2001, 2010, 2011);
    // precondition: the fixture really is an exact line
    assertEquals(0.5, lib.slopeDegCPerYear(), 0.0);
    assertEquals(0.0, lib.slopeStdErrDegCPerYear(), 0.0);

    JsonNode d = assertOk(postPrice(mvc, trendRequest("linear"))).get("detrended");
    assertTrue(d.has("slopeTStat"), () -> "slopeTStat missing: " + d);
    assertTrue(d.get("slopeTStat").isNull(), () -> "expected null slopeTStat: " + d);
    assertTrue(d.get("slopeSignificant").isBoolean(), () -> "slopeSignificant: " + d);
    assertTrue(d.get("slopeSignificant").asBoolean());
  }

  // ---------------------------------------------------------------------------------------------
  // Unconfigured cities
  // ---------------------------------------------------------------------------------------------

  @Test
  void amendmentC_validFileNotInCurrencyMap_404CityNotFoundNamingCityAndConfiguredCities()
      throws Exception {
    String message =
        assertError(postPrice(mvc, workedExampleRequest("unlisted")), 404, "city_not_found");
    assertContains(message, "unlisted");
    // configured cities, sorted: linear, london, nofile, trend
    assertContains(message, "london");
    int linear = message.indexOf("linear");
    int london = message.indexOf("london");
    int nofile = message.indexOf("nofile");
    int trend = message.indexOf("trend");
    assertTrue(
        0 <= linear && linear < london && london < nofile && nofile < trend,
        () ->
            "expected the configured cities sorted (linear, london, nofile, trend) in: " + message);
  }

  @Test
  void amendmentC_unreadableFileNotInCurrencyMap_404NotDataError_fileNotRead() throws Exception {
    // reading unlisted2.parquet would be a 422 data_error
    String message =
        assertError(postPrice(mvc, workedExampleRequest("unlisted2")), 404, "city_not_found");
    assertContains(message, "unlisted2");
  }

  @Test
  void amendmentC_unconfiguredCityWithTargetInsideWindow_400LookAheadNot404() throws Exception {
    Map<String, Object> req = workedExampleRequest("unlisted");
    req.put("firstSeasonYear", 2001);
    req.put("lastSeasonYear", 2010);
    req.put("targetSeasonYear", 2003);
    assertContains(assertError(postPrice(mvc, req), 400, "invalid_request"), "look-ahead");
  }

  @Test
  void amendmentC_configuredCityWithoutFile_404CityNotFound() throws Exception {
    String message =
        assertError(postPrice(mvc, workedExampleRequest("nofile")), 404, "city_not_found");
    assertContains(message, "nofile");
  }
}
