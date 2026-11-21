package com.weatherderivatives.pricer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weatherderivatives.pricer.contract.ContractType;
import com.weatherderivatives.pricer.contract.HddContract;
import com.weatherderivatives.pricer.contract.HddPayoff;
import com.weatherderivatives.pricer.index.DailyTemp;
import com.weatherderivatives.pricer.index.DailyTemps;
import com.weatherderivatives.pricer.index.TemperatureDataException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Spec 005 acceptance criteria 7-11 for fitting a normal to seasonal index values. */
class IndexDistributionAnalysisTest {

  /** Spec tolerance for the fit example price. */
  private static final double PRICE_TOL = 1e-6;

  private static final double TOL = 1e-9;

  // ---------------------------------------------------------------------------------------------
  // Helpers (same style as BurnAnalysisTest / DetrendedBurnAnalysisTest)
  // ---------------------------------------------------------------------------------------------

  /** Every day in {@code first..lastInclusive} with the same tmax/tmin, appended to {@code out}. */
  private static void addConstant(
      List<DailyTemp> out, LocalDate first, LocalDate lastInclusive, double tmaxC, double tminC) {
    for (LocalDate d = first; !d.isAfter(lastInclusive); d = d.plusDays(1)) {
      out.add(new DailyTemp(d, tmaxC, tminC));
    }
  }

  private static void assertMessageContains(Throwable ex, String expected) {
    String msg = ex.getMessage();
    assertTrue(
        msg != null && msg.contains(expected),
        () -> "expected message to contain '" + expected + "' but was: " + msg);
  }

  /**
   * January of {@code year}: Jan 1-30 at {@code bulkTmax/bulkTmin}, Jan 31 at {@code
   * lastTmax/lastTmin}, padded with cold December/February days so only January may be summed.
   */
  private static void addJanuary(
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
  private static void add360(List<DailyTemp> out, int year) {
    addJanuary(out, year, 10.0, 2.0, 22.0, 14.0);
  }

  /** Index 400 = 30 × 13 (T = 5: 9/1) + 1 × 10 (T = 8: 12/4). */
  private static void add400(List<DailyTemp> out, int year) {
    addJanuary(out, year, 9.0, 1.0, 12.0, 4.0);
  }

  /** Index 440 = 30 × 14 (T = 4: 8/0) + 1 × 20 (T = −2: 2/−6). */
  private static void add440(List<DailyTemp> out, int year) {
    addJanuary(out, year, 8.0, 0.0, 2.0, -6.0);
  }

  /** Spec fit example: seasons 2001..2005 with index 360, 400, 400, 400, 440. */
  private static List<DailyTemp> fitExampleDays() {
    List<DailyTemp> days = new ArrayList<>();
    add360(days, 2001);
    add400(days, 2002);
    add400(days, 2003);
    add400(days, 2004);
    add440(days, 2005);
    return days;
  }

  private static DailyTemps fitExample() {
    return DailyTemps.of(fitExampleDays());
  }

  /** January contract (base 18), written for 2010: months' years are only a template. */
  private static HddContract january(
      ContractType type, double strike, double tick, OptionalDouble cap, OptionalInt decimals) {
    YearMonth jan2010 = YearMonth.of(2010, 1);
    return HddContract.of(type, jan2010, jan2010, strike, tick, cap, decimals);
  }

  private static HddContract januaryCall(double strike, double tick) {
    return january(ContractType.CALL, strike, tick, OptionalDouble.empty(), OptionalInt.empty());
  }

  /** Two-pass sample mean of the season index values. */
  private static double meanIndex(List<SeasonOutcome> seasons) {
    double sum = 0.0;
    for (SeasonOutcome s : seasons) {
      sum += s.indexHddDegC();
    }
    return sum / seasons.size();
  }

  /** Two-pass sample standard deviation (n − 1) of the season index values. */
  private static double sampleSdIndex(List<SeasonOutcome> seasons) {
    double mean = meanIndex(seasons);
    double ss = 0.0;
    for (SeasonOutcome s : seasons) {
      double dev = s.indexHddDegC() - mean;
      ss += dev * dev;
    }
    return Math.sqrt(ss / (seasons.size() - 1));
  }

  // ---------------------------------------------------------------------------------------------
  // AC7: fit example -> μ = 400, σ = 28.284271, call K=400 tick 20 = 225.675833; burn() == burn
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac7_fiveSeasonFitExample_mean400_sd28_284271_callK400Price225_675833() {
    HddContract call = januaryCall(400.0, 20.0);
    DailyTemps temps = fitExample();

    IndexDistributionResult r = IndexDistributionAnalysis.run(call, temps, 2001, 2005);

    // the synthetic temperatures really give 360, 400, 400, 400, 440
    double[] expected = {360.0, 400.0, 400.0, 400.0, 440.0};
    assertEquals(5, r.burn().seasonCount());
    for (int k = 0; k < 5; k++) {
      assertEquals(2001 + k, r.burn().seasons().get(k).seasonYear());
      assertEquals(expected[k], r.burn().seasons().get(k).indexHddDegC(), 0.0);
    }
    // mean 2000 / 5 = 400
    assertEquals(400.0, r.meanIndexHddDegC(), TOL);
    // deviations -40, 0, 0, 0, +40 -> SS 3200 / (n-1 = 4) = 800 -> sqrt = 28.284271247461902
    assertEquals(28.284271247461902, r.sdIndexHddDegC(), TOL);
    // d = 0: 20 · 28.284271247461902 · φ(0) = 225.67583341910253
    assertEquals(225.675833, r.expectedPayoffGbp(), PRICE_TOL);
    assertEquals(
        IndexDistributionPricing.expectedPayoffGbp(call, r.meanIndexHddDegC(), r.sdIndexHddDegC()),
        r.expectedPayoffGbp(),
        TOL);
    // record equality with plain burn on the same input
    assertEquals(BurnAnalysis.run(call, temps, 2001, 2005), r.burn());
  }

  @Test
  void ac7_minimumConstantsMatchSpecDecision1() {
    assertEquals(5, IndexDistributionAnalysis.MIN_SEASONS);
    assertEquals(10, IndexDistributionAnalysis.MIN_DETRENDED_SEASONS);
  }

  // ---------------------------------------------------------------------------------------------
  // AC8: the fit uses the rounded settlement index (same values BurnAnalysis reports)
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac8_dailyHddRounding_fitUsesRoundedSettlementIndex() {
    // season 2001+k: Tmax 10, Tmin 3 - 2k -> T = 6.5 - k -> daily HDD 11.5 + k (a .5 tie).
    // dailyHddDecimals = 0 rounds half-up to 12 + k -> index 31 · (12 + k) = 372, 403, ..., 496.
    // rounded mean 31 · 14 = 434; unrounded mean 31 · 13.5 = 418.5; sd 31 · sqrt(2.5) = 49.015...
    List<DailyTemp> days = new ArrayList<>();
    for (int k = 0; k < 5; k++) {
      int y = 2001 + k;
      addConstant(days, LocalDate.of(y, 1, 1), LocalDate.of(y, 1, 31), 10.0, 3.0 - 2.0 * k);
    }
    DailyTemps temps = DailyTemps.of(days);
    HddContract rounded =
        january(ContractType.CALL, 420.0, 1.0, OptionalDouble.empty(), OptionalInt.of(0));

    IndexDistributionResult r = IndexDistributionAnalysis.run(rounded, temps, 2001, 2005);

    assertEquals(r.burn().meanIndexHddDegC(), r.meanIndexHddDegC(), TOL);
    assertEquals(434.0, r.meanIndexHddDegC(), TOL);
    assertNotEquals(418.5, r.meanIndexHddDegC(), 1.0);
    assertEquals(sampleSdIndex(r.burn().seasons()), r.sdIndexHddDegC(), TOL);
    assertEquals(31.0 * Math.sqrt(2.5), r.sdIndexHddDegC(), TOL);
    assertEquals(BurnAnalysis.run(rounded, temps, 2001, 2005), r.burn());
  }

  // ---------------------------------------------------------------------------------------------
  // AC9: runDetrended fits the shifted index values of DetrendedBurnAnalysis
  // ---------------------------------------------------------------------------------------------

  /** Spec 004 trend-plus-noise example: 0.2 °C/yr trend + noise, seasons 2001..2010. */
  private static DailyTemps trendPlusNoise() {
    double[] means = {11.0, 9.2, 9.4, 11.6, 10.8, 11.0, 12.2, 10.4, 10.6, 12.8};
    List<DailyTemp> days = new ArrayList<>();
    for (int k = 0; k < means.length; k++) {
      int y = 2001 + k;
      addConstant(days, LocalDate.of(y, 1, 1), LocalDate.of(y, 1, 31), means[k] + 4, means[k] - 4);
    }
    return DailyTemps.of(days);
  }

  @Test
  void ac9_runDetrended_fitsShiftedIndexValuesAndCarriesDetrendedBurn() {
    DailyTemps temps = trendPlusNoise();
    HddContract call = januaryCall(200.0, 1000.0);

    IndexDistributionResult r =
        IndexDistributionAnalysis.runDetrended(call, temps, 2001, 2010, 2011);
    DetrendedBurnResult detrended = DetrendedBurnAnalysis.run(call, temps, 2001, 2010, 2011);

    assertEquals(detrended.burn(), r.burn());
    assertEquals(meanIndex(detrended.burn().seasons()), r.meanIndexHddDegC(), TOL);
    assertEquals(sampleSdIndex(detrended.burn().seasons()), r.sdIndexHddDegC(), TOL);
    // spec 004 AC2: shifted indices 155,217,217,155,186,186,155,217,217,155
    // -> mean 186, SS = 8 · 31² = 7688, sd = sqrt(7688 / 9) = 29.227080289043965
    assertEquals(186.0, r.meanIndexHddDegC(), TOL);
    assertEquals(Math.sqrt(7688.0 / 9.0), r.sdIndexHddDegC(), TOL);
    // call K=200 tick 1000: 1000 · (-14 · Φ(d) + σ φ(d)), d = -14 / σ -> 5,972.591120248703
    assertEquals(5_972.591120, r.expectedPayoffGbp(), PRICE_TOL);
    // and it is not the plain (un-detrended) fit
    double rawMean = BurnAnalysis.run(call, temps, 2001, 2010).meanIndexHddDegC();
    assertNotEquals(rawMean, r.meanIndexHddDegC(), 1.0);
  }

  // ---------------------------------------------------------------------------------------------
  // AC10: too few seasons / reversed range -> IAE before any data is read; missing day named
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac10_fourSeasonsForRun_throwsIaeBeforeReadingData() {
    // empty data: if the range were not checked first, a TemperatureDataException would escape
    DailyTemps empty = DailyTemps.of(List.of());
    HddContract call = januaryCall(400.0, 20.0);
    assertThrows(
        IllegalArgumentException.class,
        () -> IndexDistributionAnalysis.run(call, empty, 2001, 2004));
    // also with data present (burn alone would accept 4 seasons)
    assertThrows(
        IllegalArgumentException.class,
        () -> IndexDistributionAnalysis.run(call, fitExample(), 2001, 2004));
  }

  @Test
  void ac10_nineSeasonsForRunDetrended_throwsIaeBeforeReadingData() {
    DailyTemps empty = DailyTemps.of(List.of());
    HddContract call = januaryCall(200.0, 1000.0);
    assertThrows(
        IllegalArgumentException.class,
        () -> IndexDistributionAnalysis.runDetrended(call, empty, 2001, 2009, 2011));
  }

  @Test
  void ac10_firstSeasonAfterLast_throwsIaeBeforeReadingData() {
    DailyTemps empty = DailyTemps.of(List.of());
    HddContract call = januaryCall(400.0, 20.0);
    assertThrows(
        IllegalArgumentException.class,
        () -> IndexDistributionAnalysis.run(call, empty, 2005, 2001));
    assertThrows(
        IllegalArgumentException.class,
        () -> IndexDistributionAnalysis.runDetrended(call, empty, 2010, 2001, 2011));
  }

  @Test
  void ac10_missingDay_run_exceptionNamesSeasonAndDate() {
    List<DailyTemp> days = fitExampleDays();
    days.removeIf(d -> d.date().equals(LocalDate.of(2003, 1, 15)));
    DailyTemps temps = DailyTemps.of(days);
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () -> IndexDistributionAnalysis.run(januaryCall(400.0, 20.0), temps, 2001, 2005));
    assertMessageContains(ex, "season 2003");
    assertMessageContains(ex, "2003-01-15");
  }

  @Test
  void ac10_missingDay_runDetrended_exceptionNamesSeasonAndDate() {
    List<DailyTemp> days = new ArrayList<>();
    for (int y = 2001; y <= 2010; y++) {
      addConstant(days, LocalDate.of(y, 1, 1), LocalDate.of(y, 1, 31), 14.0, 6.0);
    }
    days.removeIf(d -> d.date().equals(LocalDate.of(2005, 1, 15)));
    DailyTemps temps = DailyTemps.of(days);
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () ->
                IndexDistributionAnalysis.runDetrended(
                    januaryCall(200.0, 1000.0), temps, 2001, 2010, 2011));
    assertMessageContains(ex, "season 2005");
    assertMessageContains(ex, "2005-01-15");
    assertInstanceOf(TemperatureDataException.class, ex.getCause());
  }

  @Test
  void nullArguments_throwNullPointerException() {
    HddContract call = januaryCall(400.0, 20.0);
    DailyTemps temps = fitExample();
    assertThrows(
        NullPointerException.class, () -> IndexDistributionAnalysis.run(null, temps, 2001, 2005));
    assertThrows(
        NullPointerException.class, () -> IndexDistributionAnalysis.run(call, null, 2001, 2005));
    assertThrows(
        NullPointerException.class,
        () -> IndexDistributionAnalysis.runDetrended(null, temps, 2001, 2010, 2011));
    assertThrows(
        NullPointerException.class,
        () -> IndexDistributionAnalysis.runDetrended(call, null, 2001, 2010, 2011));
  }

  // ---------------------------------------------------------------------------------------------
  // AC11: identical seasons -> σ exactly 0, price = that season's payoff
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac11_identicalSeasons_sdExactlyZero_priceIsSeasonPayoff() {
    // every January 2001..2005 at 14/6 -> HDD 8 -> index 248; call K=240 tick 1000 -> 8,000
    List<DailyTemp> days = new ArrayList<>();
    for (int y = 2001; y <= 2005; y++) {
      addConstant(days, LocalDate.of(y, 1, 1), LocalDate.of(y, 1, 31), 14.0, 6.0);
    }
    DailyTemps temps = DailyTemps.of(days);
    for (ContractType type : ContractType.values()) {
      HddContract c = january(type, 240.0, 1000.0, OptionalDouble.of(5000.0), OptionalInt.empty());
      IndexDistributionResult r = IndexDistributionAnalysis.run(c, temps, 2001, 2005);
      assertEquals(0.0, r.sdIndexHddDegC(), 0.0, type.toString());
      assertEquals(248.0, r.meanIndexHddDegC(), 0.0, type.toString());
      // call/swap: min(5000, 8000) = 5000; put: 0
      assertEquals(HddPayoff.payoffGbp(c, 248.0), r.expectedPayoffGbp(), 0.0, type.toString());
    }
    HddContract uncapped = januaryCall(240.0, 1000.0);
    assertEquals(
        8_000.0,
        IndexDistributionAnalysis.run(uncapped, temps, 2001, 2005).expectedPayoffGbp(),
        0.0);
  }

  @Test
  void ac11_identicalSeasons_runDetrended_sdExactlyZero() {
    // flat history: slope 0, shifts 0 -> every shifted index 248 -> σ = 0, price 8,000
    List<DailyTemp> days = new ArrayList<>();
    for (int y = 2001; y <= 2010; y++) {
      addConstant(days, LocalDate.of(y, 1, 1), LocalDate.of(y, 1, 31), 14.0, 6.0);
    }
    DailyTemps temps = DailyTemps.of(days);
    IndexDistributionResult r =
        IndexDistributionAnalysis.runDetrended(januaryCall(240.0, 1000.0), temps, 2001, 2010, 2011);
    assertEquals(0.0, r.sdIndexHddDegC(), 0.0);
    assertEquals(248.0, r.meanIndexHddDegC(), 0.0);
    assertEquals(8_000.0, r.expectedPayoffGbp(), 0.0);
  }

  // ---------------------------------------------------------------------------------------------
  // Result record
  // ---------------------------------------------------------------------------------------------

  @Test
  void result_nullBurn_throwsNullPointerException() {
    assertThrows(
        NullPointerException.class, () -> new IndexDistributionResult(400.0, 28.0, 225.0, null));
  }
}
