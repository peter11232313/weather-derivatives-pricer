package com.weatherderivatives.pricer.api;

import static com.weatherderivatives.pricer.api.ApiTestSupport.add400;
import static com.weatherderivatives.pricer.api.ApiTestSupport.add440;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertBurn;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertContains;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertDetrendedSeasons;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertError;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertMentionsNumber;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertNoFieldAnywhere;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertNormal;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertOk;
import static com.weatherderivatives.pricer.api.ApiTestSupport.assertSeasons;
import static com.weatherderivatives.pricer.api.ApiTestSupport.fieldNames;
import static com.weatherderivatives.pricer.api.ApiTestSupport.num;
import static com.weatherderivatives.pricer.api.ApiTestSupport.postPrice;
import static com.weatherderivatives.pricer.api.ApiTestSupport.workedExampleRequest;
import static com.weatherderivatives.pricer.api.ApiTestSupport.writeCity;
import static com.weatherderivatives.pricer.api.ApiTestSupport.writeFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * Spec 006 pricing criteria (AC1, AC2, AC3, AC3b, AC4, AC10, AC11) through {@code POST /price},
 * reading real Parquet files from a temp data folder set by {@code pricer.data-dir}. Expected
 * values are either hand-computed (worked example) or the library's own result on the same {@link
 * DailyTemps}, compared exactly: the API holds no pricing logic.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceEndpointPricingTest {

  /** Spec tolerance for the worked-example normal price and σ. */
  private static final double PRICE_TOL = 1e-6;

  @TempDir static Path root;

  @Autowired MockMvc mvc;

  @Autowired PricerProperties properties;

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
    writeCity(dataDir(), "trend", ApiTestSupport.trendPlusNoiseDays());
    writeCity(dataDir(), "winter", ApiTestSupport.winterDays());
    List<DailyTemp> flat = new ArrayList<>();
    for (int y = 2001; y <= 2005; y++) {
      add400(flat, y);
    }
    writeCity(dataDir(), "flat", flat);
  }

  // ---------------------------------------------------------------------------------------------
  // AC1: worked example
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac1_workedExample_burn160_normal225_675833_mean400_sd28_284271_detrendedNull()
      throws Exception {
    JsonNode r = assertOk(postPrice(mvc, workedExampleRequest("london")));

    assertEquals(Set.of("city", "seasons", "burn", "normal", "detrended"), fieldNames(r));
    assertEquals("london", r.get("city").asString());

    // indices 360, 400, 400, 400, 440 in year order; call K=400 tick 20 -> payoffs 0,0,0,0,800
    JsonNode seasons = r.get("seasons");
    assertEquals(5, seasons.size());
    double[] index = {360.0, 400.0, 400.0, 400.0, 440.0};
    double[] payoff = {0.0, 0.0, 0.0, 0.0, 800.0};
    for (int k = 0; k < 5; k++) {
      assertEquals(2001 + k, seasons.get(k).get("seasonYear").asInt());
      assertEquals(index[k], num(seasons.get(k), "indexHddDegC"), 1e-9);
      assertEquals(payoff[k], num(seasons.get(k), "payoffGbp"), 1e-9);
    }

    // burn = 800 / 5 = 160; payoff sd = sqrt(4·160² + 640²)/2 = sqrt(512000/4) = 357.770876...
    JsonNode burn = r.get("burn");
    assertEquals(160.0, num(burn, "expectedPayoffGbp"), 1e-9);
    assertEquals(Math.sqrt(128_000.0), num(burn, "payoffStdDevGbp"), 1e-9);
    assertEquals(Math.sqrt(128_000.0) / Math.sqrt(5.0), num(burn, "stdErrorGbp"), 1e-9);
    assertEquals(0.0, num(burn, "minPayoffGbp"), 1e-9);
    assertEquals(800.0, num(burn, "maxPayoffGbp"), 1e-9);
    assertEquals(400.0, num(burn, "meanIndexHddDegC"), 1e-9);

    // μ = 400, σ = sqrt(3200 / 4) = 28.284271; call at the money: 20 · σ · φ(0) = 225.675833
    JsonNode normal = r.get("normal");
    assertEquals(225.675833, num(normal, "expectedPayoffGbp"), PRICE_TOL);
    assertEquals(400.0, num(normal, "meanIndexHddDegC"), 1e-9);
    assertEquals(28.284271, num(normal, "sdIndexHddDegC"), PRICE_TOL);

    assertTrue(r.get("detrended").isNull(), () -> "detrended: " + r.get("detrended"));
  }

  @Test
  void ac1_zeroVolatility_allSeasonsEqual_normalIsIntrinsicValue() throws Exception {
    // five seasons of exactly 400; call K=390 tick 20 -> every payoff 200, σ = 0 -> normal = 200
    Map<String, Object> req = workedExampleRequest("flat");
    req.put("strikeHddDegC", 390.0);
    JsonNode r = assertOk(postPrice(mvc, req));

    assertEquals(200.0, num(r.get("burn"), "expectedPayoffGbp"), 1e-9);
    assertEquals(0.0, num(r.get("burn"), "payoffStdDevGbp"), 0.0);
    assertEquals(400.0, num(r.get("normal"), "meanIndexHddDegC"), 0.0);
    assertEquals(0.0, num(r.get("normal"), "sdIndexHddDegC"), 0.0);
    assertEquals(200.0, num(r.get("normal"), "expectedPayoffGbp"), 1e-9);
  }

  // ---------------------------------------------------------------------------------------------
  // AC2: targetSeasonYear with >= 10 seasons -> detrended equals the library
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac2_targetSeasonYear_tenSeasons_detrendedEqualsLibrary_knownAnswer() throws Exception {
    Map<String, Object> req = workedExampleRequest("trend");
    req.put("strikeHddDegC", 200.0);
    req.put("tickGbpPerDegC", 1000.0);
    req.put("firstSeasonYear", 2001);
    req.put("lastSeasonYear", 2010);
    req.put("targetSeasonYear", 2011);
    JsonNode r = assertOk(postPrice(mvc, req));

    DailyTemps temps = DailyTemps.of(ApiTestSupport.trendPlusNoiseDays());
    HddContract call =
        contract(ContractType.CALL, "2001-01", "2001-01", 200, 1000, null, null, null);
    DetrendedBurnResult lib = DetrendedBurnAnalysis.run(call, temps, 2001, 2010, 2011);
    IndexDistributionResult libNormal =
        IndexDistributionAnalysis.runDetrended(call, temps, 2001, 2010, 2011);

    JsonNode d = r.get("detrended");
    assertNotNull(d, "missing detrended");
    assertTrue(d.isObject(), () -> "detrended: " + d);
    assertEquals(
        Set.of(
            "targetSeasonYear",
            "slopeDegCPerYear",
            "slopeStdErrDegCPerYear",
            "seasons",
            "burn",
            "normal"),
        fieldNames(d));
    assertEquals(2011, d.get("targetSeasonYear").asInt());
    // Σx·m = 16.5, Sxx = 82.5 -> slope 0.2 °C/yr (spec 004)
    assertEquals(0.2, num(d, "slopeDegCPerYear"), 1e-12);
    assertEquals(lib.slopeDegCPerYear(), num(d, "slopeDegCPerYear"));
    assertEquals(lib.slopeStdErrDegCPerYear(), num(d, "slopeStdErrDegCPerYear"));
    assertDetrendedSeasons(lib.burn().seasons(), lib.seasons(), d.get("seasons"));
    assertBurn(lib.burn(), d.get("burn"));
    assertNormal(libNormal, d.get("normal"));
    // shifted indices 155/217 -> four payoffs of 17,000 -> detrended burn 6,800 (spec 004 AC2)
    assertEquals(6_800.0, num(d.get("burn"), "expectedPayoffGbp"), 1e-6);
    // spec 005 AC9: detrended normal 5,972.591120
    assertEquals(5_972.591120, num(d.get("normal"), "expectedPayoffGbp"), PRICE_TOL);

    // the top level stays the un-detrended analysis of the same seasons
    IndexDistributionResult raw = IndexDistributionAnalysis.run(call, temps, 2001, 2010);
    assertSeasons(raw.burn().seasons(), r.get("seasons"));
    assertBurn(raw.burn(), r.get("burn"));
    assertNormal(raw, r.get("normal"));
  }

  @ParameterizedTest(name = "[{index}] {0} {1}..{2} K={3} cap={5} target={9}")
  @CsvSource(
      nullValues = "null",
      value = {
        // type, first, last, strike, tick, cap, baseC, decimals, seasons first..last, target
        "PUT,  2000-11, 2001-03, 1900, 15, 4000, null, 1,    2000, 2011, 2013",
        "SWAP, 1990-12, 1991-02, 1300, 10, null, 17.0, null, 2000, 2012, 2014",
        // target before the window (spec 006 amendment A: allowed, no look-ahead)
        "CALL, 2005-11, 2006-03, 1700, 20, null, 18.5, 2,    2001, 2010, 2000",
      })
  void ac2_targetSeasonYear_variousTerms_detrendedEqualsLibrary(
      ContractType type,
      String first,
      String last,
      double strike,
      double tick,
      Double cap,
      Double baseC,
      Integer decimals,
      int firstSeason,
      int lastSeason,
      int target)
      throws Exception {
    Map<String, Object> req =
        request("winter", type, first, last, strike, tick, cap, baseC, decimals);
    req.put("firstSeasonYear", firstSeason);
    req.put("lastSeasonYear", lastSeason);
    req.put("targetSeasonYear", target);
    JsonNode r = assertOk(postPrice(mvc, req));

    DailyTemps temps = DailyTemps.of(ApiTestSupport.winterDays());
    HddContract c = contract(type, first, last, strike, tick, cap, baseC, decimals);
    DetrendedBurnResult lib = DetrendedBurnAnalysis.run(c, temps, firstSeason, lastSeason, target);
    IndexDistributionResult libNormal =
        IndexDistributionAnalysis.runDetrended(c, temps, firstSeason, lastSeason, target);

    JsonNode d = r.get("detrended");
    assertEquals(target, d.get("targetSeasonYear").asInt());
    assertEquals(lib.slopeDegCPerYear(), num(d, "slopeDegCPerYear"));
    assertEquals(lib.slopeStdErrDegCPerYear(), num(d, "slopeStdErrDegCPerYear"));
    assertDetrendedSeasons(lib.burn().seasons(), lib.seasons(), d.get("seasons"));
    assertBurn(lib.burn(), d.get("burn"));
    assertNormal(libNormal, d.get("normal"));
  }

  // ---------------------------------------------------------------------------------------------
  // Amendment A: look-ahead guard -> a target inside firstSeasonYear..lastSeasonYear is a 400
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "[{index}] target {0} inside 2001..2010")
  @ValueSource(ints = {2001, 2005, 2010})
  void amendmentA_targetInsideWindow_400LookAhead(int target) throws Exception {
    // same request as the AC2 known answer (which prices with target 2011), only the target moves
    // into the window: first (2001), inside (2005), last (2010)
    Map<String, Object> req = workedExampleRequest("trend");
    req.put("strikeHddDegC", 200.0);
    req.put("tickGbpPerDegC", 1000.0);
    req.put("firstSeasonYear", 2001);
    req.put("lastSeasonYear", 2010);
    req.put("targetSeasonYear", target);
    String message = assertError(postPrice(mvc, req), 400, "invalid_request");
    assertContains(message, "targetSeasonYear");
    assertContains(message, "look-ahead");
    // the window bounds as whole numbers. Not assertMentionsNumber: its lookbehind rejects a
    // preceding '.', so it would miss 2010 in a "2001..2010" range. The suggested "e.g. 2011"
    // cannot satisfy either check (2011 contains neither 2001 nor 2010).
    assertWholeNumber(message, 2001);
    assertWholeNumber(message, 2010);
    // for target 2001 the echoed target alone would satisfy 2001; the literal window proves it
    assertContains(message, "2001..2010");
  }

  /** Asserts {@code text} contains {@code number} with no digit directly before or after it. */
  private static void assertWholeNumber(String text, int number) {
    assertTrue(
        Pattern.compile("(?<![0-9])" + number + "(?![0-9])").matcher(text).find(),
        () -> "expected the number " + number + " in: " + text);
  }

  // ---------------------------------------------------------------------------------------------
  // AC3 / AC3b: season-range minimums -> 400 invalid_request naming the minimum
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "[{index}] {0} seasons with a target")
  @ValueSource(ints = {5, 9})
  void ac3_targetSeasonYear_fewerThanTenSeasons_400InvalidRequestNamingTen(int seasons)
      throws Exception {
    // data covers 2001..2010, so only the season count can be the reason
    Map<String, Object> req = workedExampleRequest("trend");
    req.put("firstSeasonYear", 2001);
    req.put("lastSeasonYear", 2000 + seasons);
    req.put("targetSeasonYear", 2011);
    String message = assertError(postPrice(mvc, req), 400, "invalid_request");
    assertMentionsNumber(message, 10);
  }

  @ParameterizedTest(name = "[{index}] {0} seasons")
  @ValueSource(ints = {1, 2, 4})
  void ac3b_fewerThanFiveSeasons_400InvalidRequestNamingFive(int seasons) throws Exception {
    // burn alone would accept 2..4 seasons; the always-computed normal needs 5
    Map<String, Object> req = workedExampleRequest("london");
    req.put("firstSeasonYear", 2001);
    req.put("lastSeasonYear", 2000 + seasons);
    String message = assertError(postPrice(mvc, req), 400, "invalid_request");
    assertMentionsNumber(message, 5);
  }

  // ---------------------------------------------------------------------------------------------
  // AC4: every priced field, call/put/swap, with and without cap -> equals the library
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "[{index}] {0} {1}..{2} K={3} tick={4} cap={5} base={6} dp={7}")
  @CsvSource(
      nullValues = "null",
      value = {
        // type, first, last, strike, tick, cap, baseC, decimals, seasons first..last
        "CALL, 2000-11, 2001-03, 1800, 20,   null, null, null, 2000, 2010",
        "CALL, 2000-11, 2001-03, 1750, 20,   3000, null, 1,    2000, 2010",
        "CALL, 2030-11, 2031-03, 1850.5, 7.5, null, 15.5, 0,   2001, 2012",
        "CALL, 2000-01, 2000-01, 0,    1,    null, null, null, 2000, 2013",
        "PUT,  2000-11, 2001-03, 1900, 15,   null, null, null, 2000, 2012",
        "PUT,  2000-11, 2001-03, 1900, 15,   2500, 18.0, 2,    2003, 2009",
        "PUT,  2003-12, 2004-02, 1100, 25,   null, null, 4,    2000, 2012",
        "PUT,  2003-12, 2004-02, 1100, 25,   900,  16.0, null, 2000, 2012",
        "SWAP, 2000-11, 2001-03, 1800, 10,   null, null, null, 2000, 2012",
        "SWAP, 2000-11, 2001-03, 1800, 10,   1500, 19.0, 3,    2000, 2012",
        "SWAP, 2000-10, 2001-04, 2200, 5,    null, 20.0, 1,    2001, 2006",
        "SWAP, 2000-10, 2001-04, 2200, 5,    800,  null, null, 2000, 2012",
      })
  void ac4_pricedFields_callPutSwap_cappedAndUncapped_equalLibrary(
      ContractType type,
      String first,
      String last,
      double strike,
      double tick,
      Double cap,
      Double baseC,
      Integer decimals,
      int firstSeason,
      int lastSeason)
      throws Exception {
    Map<String, Object> req =
        request("winter", type, first, last, strike, tick, cap, baseC, decimals);
    req.put("firstSeasonYear", firstSeason);
    req.put("lastSeasonYear", lastSeason);
    if (baseC == null) {
      req.remove("baseC"); // optional field omitted entirely
    }
    JsonNode r = assertOk(postPrice(mvc, req));

    DailyTemps temps = DailyTemps.of(ApiTestSupport.winterDays());
    HddContract c = contract(type, first, last, strike, tick, cap, baseC, decimals);
    IndexDistributionResult lib = IndexDistributionAnalysis.run(c, temps, firstSeason, lastSeason);

    assertEquals("winter", r.get("city").asString());
    assertSeasons(lib.burn().seasons(), r.get("seasons"));
    assertBurn(lib.burn(), r.get("burn"));
    assertNormal(lib, r.get("normal"));
    assertTrue(r.get("detrended").isNull(), () -> "detrended: " + r.get("detrended"));
  }

  @Test
  void ac4_explicitNullOptionalFields_meanDefaults_base18_uncapped_fullPrecision()
      throws Exception {
    Map<String, Object> req =
        request("winter", ContractType.CALL, "2000-11", "2001-03", 1800, 20, null, null, null);
    req.put("baseC", null);
    req.put("firstSeasonYear", 2000);
    req.put("lastSeasonYear", 2012);
    JsonNode r = assertOk(postPrice(mvc, req));

    DailyTemps temps = DailyTemps.of(ApiTestSupport.winterDays());
    HddContract c = contract(ContractType.CALL, "2000-11", "2001-03", 1800, 20, null, null, null);
    IndexDistributionResult lib = IndexDistributionAnalysis.run(c, temps, 2000, 2012);
    assertSeasons(lib.burn().seasons(), r.get("seasons"));
    assertBurn(lib.burn(), r.get("burn"));
    assertNormal(lib, r.get("normal"));
  }

  // ---------------------------------------------------------------------------------------------
  // AC10: files are read from pricer.data-dir
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac10_dataDirFromConfiguration_isBoundAndUsed() throws Exception {
    assertEquals(dataDir(), properties.dataDir());
    // a city that exists only in the configured folder prices (AC1 checks the values)
    assertOk(postPrice(mvc, workedExampleRequest("london")));
  }

  // ---------------------------------------------------------------------------------------------
  // AC11: a republished file is used by the next request without a restart
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac11_republishedFile_nextRequestUsesIt() throws Exception {
    writeCity(dataDir(), "repub", ApiTestSupport.workedExampleDays());
    JsonNode before = assertOk(postPrice(mvc, workedExampleRequest("repub")));
    assertEquals(160.0, num(before.get("burn"), "expectedPayoffGbp"), 1e-9);

    // republish: five seasons of 440 -> every payoff 20 · 40 = 800, σ = 0 -> both prices 800.
    // Written beside the folder, then moved in (Windows may lock a file still memory-mapped).
    List<DailyTemp> hot = new ArrayList<>();
    for (int y = 2001; y <= 2005; y++) {
      add440(hot, y);
    }
    Path staged = writeFile(root.resolve("repub-staged.parquet"), hot);
    Files.move(staged, dataDir().resolve("repub.parquet"), StandardCopyOption.REPLACE_EXISTING);

    JsonNode after = assertOk(postPrice(mvc, workedExampleRequest("repub")));
    assertEquals(800.0, num(after.get("burn"), "expectedPayoffGbp"), 1e-9);
    assertEquals(800.0, num(after.get("normal"), "expectedPayoffGbp"), 1e-9);
  }

  // ---------------------------------------------------------------------------------------------
  // Amendment B: priceGbp renamed expectedPayoffGbp (clean break, no alias)
  // ---------------------------------------------------------------------------------------------

  @Test
  void amendmentB_validRequest_noTarget_hasExpectedPayoffGbp_andNoPriceGbpAnywhere()
      throws Exception {
    JsonNode r = assertOk(postPrice(mvc, workedExampleRequest("london")));
    assertNoFieldAnywhere(r, "priceGbp");

    // worked example (AC1): burn 800 / 5 = 160; normal 20 · 28.284271 · φ(0) = 225.675833
    assertEquals(160.0, num(r.get("burn"), "expectedPayoffGbp"), 1e-9);
    assertEquals(225.675833, num(r.get("normal"), "expectedPayoffGbp"), PRICE_TOL);
  }

  @Test
  void amendmentB_validRequest_withTarget_detrendedHasExpectedPayoffGbp_andNoPriceGbpAnywhere()
      throws Exception {
    Map<String, Object> req = workedExampleRequest("trend");
    req.put("strikeHddDegC", 200.0);
    req.put("tickGbpPerDegC", 1000.0);
    req.put("firstSeasonYear", 2001);
    req.put("lastSeasonYear", 2010);
    req.put("targetSeasonYear", 2011);
    JsonNode r = assertOk(postPrice(mvc, req));

    JsonNode d = r.get("detrended");
    assertNotNull(d, "missing detrended");
    assertTrue(d.isObject(), () -> "detrended: " + d);
    // covers top level, burn, normal, detrended.burn, detrended.normal and the season arrays
    assertNoFieldAnywhere(r, "priceGbp");
    // spec 004 AC2: detrended burn 6,800; spec 005 AC9: detrended normal 5,972.591120
    assertEquals(6_800.0, num(d.get("burn"), "expectedPayoffGbp"), 1e-6);
    assertEquals(5_972.591120, num(d.get("normal"), "expectedPayoffGbp"), PRICE_TOL);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private static Map<String, Object> request(
      String city,
      ContractType type,
      String first,
      String last,
      double strike,
      double tick,
      Double cap,
      Double baseC,
      Integer decimals) {
    Map<String, Object> req = workedExampleRequest(city);
    req.put("type", type.name());
    req.put("firstMonth", first);
    req.put("lastMonth", last);
    req.put("strikeHddDegC", strike);
    req.put("tickGbpPerDegC", tick);
    req.put("capGbp", cap);
    req.put("baseC", baseC);
    req.put("dailyHddDecimals", decimals);
    return req;
  }

  private static HddContract contract(
      ContractType type,
      String first,
      String last,
      double strike,
      double tick,
      Double cap,
      Double baseC,
      Integer decimals) {
    return new HddContract(
        type,
        YearMonth.parse(first),
        YearMonth.parse(last),
        baseC == null ? HddIndex.DEFAULT_BASE_C : baseC,
        strike,
        tick,
        cap == null ? OptionalDouble.empty() : OptionalDouble.of(cap),
        decimals == null ? OptionalInt.empty() : OptionalInt.of(decimals));
  }

  // --- default base temperature

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
