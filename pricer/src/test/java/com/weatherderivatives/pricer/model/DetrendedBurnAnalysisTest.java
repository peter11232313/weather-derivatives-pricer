package com.weatherderivatives.pricer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weatherderivatives.pricer.contract.ContractType;
import com.weatherderivatives.pricer.contract.HddContract;
import com.weatherderivatives.pricer.index.DailyTemp;
import com.weatherderivatives.pricer.index.DailyTemps;
import com.weatherderivatives.pricer.index.TemperatureDataException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Spec 004 acceptance criteria for detrended burn analysis (AC1-AC10; AC11 is review-only). */
class DetrendedBurnAnalysisTest {

  /** Spec tolerance for GBP amounts. */
  private static final double GBP_TOL = 0.01;

  private static final double TOL = 1e-9;

  private static final int FIRST = 2001;
  private static final int LAST = 2010;
  private static final int TARGET = 2011;

  // ---------------------------------------------------------------------------------------------
  // Helpers (same style as BurnAnalysisTest)
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
   * January of each season {@code FIRST + k} with Tmax = mean + spread, Tmin = mean - spread, where
   * mean = {@code seasonMeansC[k]}. Adds a few cold December/February days around each January so
   * only the accrual month may be used.
   */
  private static List<DailyTemp> januaryDays(double[] seasonMeansC, double spreadC) {
    List<DailyTemp> days = new ArrayList<>();
    for (int k = 0; k < seasonMeansC.length; k++) {
      int y = FIRST + k;
      double mean = seasonMeansC[k];
      addConstant(days, LocalDate.of(y - 1, 12, 29), LocalDate.of(y - 1, 12, 31), -20.0, -30.0);
      addConstant(
          days, LocalDate.of(y, 1, 1), LocalDate.of(y, 1, 31), mean + spreadC, mean - spreadC);
      addConstant(days, LocalDate.of(y, 2, 1), LocalDate.of(y, 2, 2), -20.0, -30.0);
    }
    return days;
  }

  private static DailyTemps januaryTemps(double[] seasonMeansC, double spreadC) {
    return DailyTemps.of(januaryDays(seasonMeansC, spreadC));
  }

  /** January contract (base 18), tick 1,000, written for 2010: months' years are a template. */
  private static HddContract january(ContractType type, double strike, OptionalInt decimals) {
    YearMonth jan2010 = YearMonth.of(2010, 1);
    return HddContract.of(type, jan2010, jan2010, strike, 1000.0, OptionalDouble.empty(), decimals);
  }

  private static HddContract januaryCall(double strike) {
    return january(ContractType.CALL, strike, OptionalInt.empty());
  }

  /** Nov-Mar contract (base 18), tick 1, template season 1991. */
  private static HddContract novMar() {
    return HddContract.of(
        ContractType.CALL,
        YearMonth.of(1991, 11),
        YearMonth.of(1992, 3),
        100.0,
        1.0,
        OptionalDouble.empty(),
        OptionalInt.empty());
  }

  /** Exact-trend example: season means 10.0, 10.1, ..., 10.9. */
  private static double[] exactTrendMeans() {
    double[] m = new double[10];
    for (int k = 0; k < 10; k++) {
      m[k] = 10.0 + 0.1 * k;
    }
    return m;
  }

  /** Trend-plus-noise example: 0.2 degC/yr trend + noise +1,-1,-1,+1,0,0,+1,-1,-1,+1. */
  private static double[] noiseMeans() {
    return new double[] {11.0, 9.2, 9.4, 11.6, 10.8, 11.0, 12.2, 10.4, 10.6, 12.8};
  }

  // ---------------------------------------------------------------------------------------------
  // AC1: exact trend -> slope 0.1, shifts 1.0..0.1, indices 217.0, call K=210 burn 7,000.00,
  //      std dev 0; raw burn 24,050.00
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac1_exactTrend_slope0_1_indices217_detrendedBurn7000_rawBurn24050() {
    double[] means = exactTrendMeans();
    DailyTemps temps = januaryTemps(means, 4.0);
    HddContract call = januaryCall(210.0);

    DetrendedBurnResult r = DetrendedBurnAnalysis.run(call, temps, FIRST, LAST, TARGET);

    assertEquals(0.1, r.slopeDegCPerYear(), TOL);
    assertEquals(TARGET, r.targetSeasonYear());
    assertEquals(10, r.seasons().size());
    assertEquals(10, r.burn().seasonCount());
    for (int k = 0; k < 10; k++) {
      SeasonTrend t = r.seasons().get(k);
      assertEquals(FIRST + k, t.seasonYear());
      // unshifted season mean 10.0 + 0.1k
      assertEquals(means[k], t.seasonMeanTempC(), TOL);
      // shift = 0.1 x (2011 - y): 1.0, 0.9, ..., 0.1
      assertEquals(0.1 * (10 - k), t.shiftDegC(), TOL);
      // shifted mean 11.0 -> daily HDD 7.0 -> 31 x 7.0 = 217.0; call K=210 pays 7 x 1,000
      SeasonOutcome s = r.burn().seasons().get(k);
      assertEquals(FIRST + k, s.seasonYear());
      assertEquals(217.0, s.indexHddDegC(), TOL);
      assertEquals(7_000.0, s.payoffGbp(), GBP_TOL);
    }
    assertEquals(7_000.0, r.burn().burnPriceGbp(), GBP_TOL);
    assertEquals(0.0, r.burn().payoffStdDevGbp(), 1e-6);
    assertEquals(7_000.0, r.burn().minPayoffGbp(), GBP_TOL);
    assertEquals(7_000.0, r.burn().maxPayoffGbp(), GBP_TOL);
    assertEquals(217.0, r.burn().meanIndexHddDegC(), TOL);

    // raw: indices 248.0, 244.9, ..., 220.1 -> payoffs 38,000 .. 10,100 -> mean (38,000+10,100)/2
    BurnResult raw = BurnAnalysis.run(call, temps, FIRST, LAST);
    assertEquals(24_050.0, raw.burnPriceGbp(), GBP_TOL);
  }

  // ---------------------------------------------------------------------------------------------
  // AC2: trend plus noise -> slope 0.2, indices 155,217,217,155,186,186,155,217,217,155,
  //      call K=200 burn 6,800.00, std dev 8,778.76
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac2_trendPlusNoise_slope0_2_detrendedBurn6800_stdDev8778_76() {
    DailyTemps temps = januaryTemps(noiseMeans(), 4.0);
    HddContract call = januaryCall(200.0);

    DetrendedBurnResult r = DetrendedBurnAnalysis.run(call, temps, FIRST, LAST, TARGET);

    // noise has zero covariance with the centred years (-4.5+3.5+2.5-1.5+0+0+1.5-2.5-3.5+4.5 = 0)
    assertEquals(0.2, r.slopeDegCPerYear(), TOL);
    // shifted means 12 + noise = 13,11,11,13,12,12,13,11,11,13 -> HDD 5,7,7,5,6,6,5,7,7,5 (x31)
    double[] indices = {155, 217, 217, 155, 186, 186, 155, 217, 217, 155};
    double[] payoffs = {0, 17_000, 17_000, 0, 0, 0, 0, 17_000, 17_000, 0};
    for (int k = 0; k < 10; k++) {
      assertEquals(0.2 * (10 - k), r.seasons().get(k).shiftDegC(), TOL);
      assertEquals(noiseMeans()[k], r.seasons().get(k).seasonMeanTempC(), TOL);
      assertEquals(indices[k], r.burn().seasons().get(k).indexHddDegC(), TOL);
      assertEquals(payoffs[k], r.burn().seasons().get(k).payoffGbp(), GBP_TOL);
    }
    // mean 68,000 / 10 = 6,800
    assertEquals(6_800.0, r.burn().burnPriceGbp(), GBP_TOL);
    // SS = 4 x 10,200^2 + 6 x 6,800^2 = 416,160,000 + 277,440,000 = 693,600,000
    // / 9 = 77,066,666.67 -> sqrt = 8,778.762...
    assertEquals(8_778.76, r.burn().payoffStdDevGbp(), GBP_TOL);
    assertEquals(0.0, r.burn().minPayoffGbp(), GBP_TOL);
    assertEquals(17_000.0, r.burn().maxPayoffGbp(), GBP_TOL);

    // raw indices 217, 272.8, 266.6, 198.4, 223.2, 217, 179.8, 235.6, 229.4, 161.2
    // payoffs 17,000+72,800+66,600+0+23,200+17,000+0+35,600+29,400+0 = 261,600 -> 26,160
    assertEquals(26_160.0, BurnAnalysis.run(call, temps, FIRST, LAST).burnPriceGbp(), GBP_TOL);
  }

  // ---------------------------------------------------------------------------------------------
  // AC3: crossing the base -> every detrended index 0.0, never negative
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac3_crossingTheBase_everyIndexZeroNeverNegative() {
    double[] means = new double[10];
    for (int k = 0; k < 10; k++) {
      means[k] = 14.0 + 0.5 * k; // 14.0, 14.5, ..., 18.5
    }
    DailyTemps temps = januaryTemps(means, 0.0); // Tmax = Tmin
    HddContract swap = january(ContractType.SWAP, 100.0, OptionalInt.empty());

    DetrendedBurnResult r = DetrendedBurnAnalysis.run(swap, temps, FIRST, LAST, TARGET);

    assertEquals(0.5, r.slopeDegCPerYear(), TOL);
    // shifted mean 14 + 0.5k + 0.5(10 - k) = 19.0 > 18 -> HDD max(0, -1) = 0 every day
    for (SeasonOutcome s : r.burn().seasons()) {
      assertTrue(s.indexHddDegC() >= 0.0, () -> "negative index " + s);
      assertEquals(0.0, s.indexHddDegC(), TOL);
      // swap K=100 pays 1,000 x (0 - 100) = -100,000
      assertEquals(-100_000.0, s.payoffGbp(), GBP_TOL);
    }
    assertEquals(0.0, r.burn().meanIndexHddDegC(), TOL);
    assertEquals(-100_000.0, r.burn().burnPriceGbp(), GBP_TOL);
  }

  // ---------------------------------------------------------------------------------------------
  // AC4: flat history -> slope exactly 0, every shift 0, burn equals raw burn bit for bit
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(ints = {1900, 2001, 2005, 2010, 2011, 3000})
  void ac4_flatHistory_zeroShiftsAndBurnEqualsRawBitForBit(int targetSeasonYear) {
    // Inexact values (13.7 / 5.86) so any non-zero shift or reordering would change bits.
    // mean (13.7 + 5.86) / 2 = 9.78 -> HDD 8.22 -> index 254.82 -> call K=240 pays 14,820
    List<DailyTemp> days = new ArrayList<>();
    for (int y = FIRST; y <= LAST; y++) {
      addConstant(days, LocalDate.of(y, 1, 1), LocalDate.of(y, 1, 31), 13.7, 5.86);
    }
    DailyTemps temps = DailyTemps.of(days);
    HddContract call = januaryCall(240.0);

    DetrendedBurnResult r = DetrendedBurnAnalysis.run(call, temps, FIRST, LAST, targetSeasonYear);

    assertEquals(0.0, r.slopeDegCPerYear()); // exactly +0.0
    assertEquals(targetSeasonYear, r.targetSeasonYear());
    for (SeasonTrend t : r.seasons()) {
      // delta form so that -0.0 (0.0 x negative) is also accepted as "zero shift"
      assertEquals(0.0, t.shiftDegC(), 0.0);
    }
    assertEquals(BurnAnalysis.run(call, temps, FIRST, LAST), r.burn());
  }

  // ---------------------------------------------------------------------------------------------
  // AC5: dailyHddDecimals = 1 -> rounding applied to the shifted daily HDD
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac5_roundingAppliedToShiftedDailyHdd_specExample() {
    // means 10.04 + 0.1k, slope 0.1 -> shifted mean 11.04 every season -> HDD 6.96
    // rounded (1 dp): 7.0 -> index 31 x 7.0 = 217.0; unrounded: 31 x 6.96 = 215.76
    double[] means = new double[10];
    for (int k = 0; k < 10; k++) {
      means[k] = 10.04 + 0.1 * k;
    }
    DailyTemps temps = januaryTemps(means, 4.0);

    DetrendedBurnResult rounded =
        DetrendedBurnAnalysis.run(
            january(ContractType.SWAP, 200.0, OptionalInt.of(1)), temps, FIRST, LAST, TARGET);
    DetrendedBurnResult unrounded =
        DetrendedBurnAnalysis.run(
            january(ContractType.SWAP, 200.0, OptionalInt.empty()), temps, FIRST, LAST, TARGET);

    for (int k = 0; k < 10; k++) {
      assertEquals(217.0, rounded.burn().seasons().get(k).indexHddDegC(), TOL);
      assertEquals(215.76, unrounded.burn().seasons().get(k).indexHddDegC(), TOL);
    }
    // swap K=200: 1,000 x 17.0 = 17,000 (rounded) vs 1,000 x 15.76 = 15,760 (unrounded)
    assertEquals(17_000.0, rounded.burn().burnPriceGbp(), GBP_TOL);
    assertEquals(15_760.0, unrounded.burn().burnPriceGbp(), GBP_TOL);
  }

  @Test
  void ac5_roundingAfterShift_notRoundRawThenShift() {
    // The 0.1 slope example cannot tell "round shifted HDD" from "round raw HDD then subtract the
    // shift" (both give 7.0). Slope 0.13 does: means 10.04 + 0.13k, shifted mean 10.04 + 1.3 =
    // 11.34 every season -> HDD 6.66 -> 6.7 -> index 31 x 6.7 = 207.7 for every season.
    // Wrong order for 2002: raw HDD 18 - 10.17 = 7.83 -> 7.8, minus shift 1.17 -> 6.63 -> 205.53.
    double[] means = new double[10];
    for (int k = 0; k < 10; k++) {
      means[k] = 10.04 + 0.13 * k;
    }
    DailyTemps temps = januaryTemps(means, 4.0);
    DetrendedBurnResult r =
        DetrendedBurnAnalysis.run(
            january(ContractType.SWAP, 200.0, OptionalInt.of(1)), temps, FIRST, LAST, TARGET);
    assertEquals(0.13, r.slopeDegCPerYear(), TOL);
    for (SeasonOutcome s : r.burn().seasons()) {
      assertEquals(207.7, s.indexHddDegC(), TOL, () -> "season " + s.seasonYear());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // AC6: Nov-Mar season mean uses all accrual days (152 leap, 151 otherwise) across year end
  // ---------------------------------------------------------------------------------------------

  /**
   * Nov-Mar seasons 1991..2000. Nov-Dec: Tmax 12 / Tmin 8 (mean 10.0); Jan-Mar: Tmax 6 / Tmin 2
   * (mean 4.0); Feb 29: Tmax 34 / Tmin 26 (mean 30.0). Leap seasons: 1991 (1992), 1995 (1996), 1999
   * (2000, divisible by 400).
   */
  private static List<DailyTemp> novMarDays() {
    List<DailyTemp> days = new ArrayList<>();
    for (int y = 1991; y <= 2000; y++) {
      addConstant(days, LocalDate.of(y, 11, 1), LocalDate.of(y, 12, 31), 12.0, 8.0);
      addConstant(days, LocalDate.of(y + 1, 1, 1), LocalDate.of(y + 1, 3, 31), 6.0, 2.0);
    }
    days.replaceAll(d ->
        d.date().getMonthValue() == 2 && d.date().getDayOfMonth() == 29
            ? new DailyTemp(d.date(), 34.0, 26.0)
            : d);
    return days;
  }

  // Leap season (152 days): Nov 30 + Dec 31 = 61 days x 10.0 = 610; Jan 31 + Feb 28 + Mar 31 =
  // 90 days x 4.0 = 360; Feb 29 x 30.0 = 30 -> 1,000 / 152 = 6.578947368421...
  private static final double LEAP_SEASON_MEAN_C = 1000.0 / 152.0;

  // Non-leap season (151 days): 61 x 10.0 + 90 x 4.0 = 970 -> 970 / 151 = 6.423841059602...
  private static final double NON_LEAP_SEASON_MEAN_C = 970.0 / 151.0;

  @Test
  void ac6_novMarSeasonMeanTempC_helper_leap152AndNonLeap151Days() {
    DailyTemps temps = DailyTemps.of(novMarDays());
    HddContract c = novMar();
    assertEquals(
        LEAP_SEASON_MEAN_C, DetrendedBurnAnalysis.seasonMeanTempC(c.forSeason(1991), temps), 1e-12);
    assertEquals(
        NON_LEAP_SEASON_MEAN_C,
        DetrendedBurnAnalysis.seasonMeanTempC(c.forSeason(1992), temps),
        1e-12);
    // 1999 season spans 2000-02-29 (century leap year)
    assertEquals(
        LEAP_SEASON_MEAN_C, DetrendedBurnAnalysis.seasonMeanTempC(c.forSeason(1999), temps), 1e-12);
  }

  @Test
  void ac6_novMarRun_seasonTrendMeansUseAllAccrualDays() {
    DailyTemps temps = DailyTemps.of(novMarDays());
    DetrendedBurnResult r = DetrendedBurnAnalysis.run(novMar(), temps, 1991, 2000, 2001);
    assertEquals(10, r.seasons().size());
    for (SeasonTrend t : r.seasons()) {
      boolean leap = YearMonth.of(t.seasonYear() + 1, 2).isLeapYear();
      double expected = leap ? LEAP_SEASON_MEAN_C : NON_LEAP_SEASON_MEAN_C;
      assertEquals(expected, t.seasonMeanTempC(), 1e-12, () -> "season " + t.seasonYear());
    }
    assertEquals(1991, r.seasons().get(0).seasonYear());
    assertEquals(2000, r.seasons().get(9).seasonYear());
  }

  // ---------------------------------------------------------------------------------------------
  // AC7: fewer than 10 seasons or first > last -> IAE before any data is read
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac7_nineSeasons_throwsIllegalArgumentExceptionBeforeDataAccess() {
    // empty data: if the range were not checked first, a TemperatureDataException would escape
    DailyTemps empty = DailyTemps.of(List.of());
    assertMessageContains(
        assertThrows(
            IllegalArgumentException.class,
            () -> DetrendedBurnAnalysis.run(januaryCall(210.0), empty, 2001, 2009, 2011)),
        "needs at least 10 seasons but got 9");
  }

  @Test
  void ac7_firstSeasonAfterLast_throwsIllegalArgumentExceptionBeforeDataAccess() {
    DailyTemps empty = DailyTemps.of(List.of());
    assertMessageContains(
        assertThrows(
            IllegalArgumentException.class,
            () -> DetrendedBurnAnalysis.run(januaryCall(210.0), empty, 2010, 2001, 2011)),
        "firstSeasonYear 2010 is after lastSeasonYear 2001");
  }

  @Test
  void ac7_exactlyTenSeasonsIsAccepted_soEmptyDataFailsOnData() {
    // boundary: 10 seasons pass the range check, so the empty series fails on the missing day
    DailyTemps empty = DailyTemps.of(List.of());
    assertEquals(10, DetrendedBurnAnalysis.MIN_SEASONS);
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () -> DetrendedBurnAnalysis.run(januaryCall(210.0), empty, 2001, 2010, 2011));
    assertMessageContains(ex, "season 2001");
    assertMessageContains(ex, "2001-01-01");
  }

  // ---------------------------------------------------------------------------------------------
  // AC8: missing day -> exception names season and date (no silent skip), cause preserved
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac8_missingDayInMiddleSeason_exceptionNamesSeasonAndDate() {
    List<DailyTemp> days = januaryDays(exactTrendMeans(), 4.0);
    days.removeIf(d -> d.date().equals(LocalDate.of(2005, 1, 15)));
    DailyTemps temps = DailyTemps.of(days);
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () -> DetrendedBurnAnalysis.run(januaryCall(210.0), temps, FIRST, LAST, TARGET));
    assertMessageContains(ex, "season 2005");
    assertMessageContains(ex, "2005-01-15");
    TemperatureDataException cause =
        assertInstanceOf(TemperatureDataException.class, ex.getCause());
    assertMessageContains(cause, "2005-01-15");
  }

  @Test
  void ac8_missingLeapDayInNovMarRun_exceptionNamesSeasonAndDate() {
    List<DailyTemp> days = novMarDays();
    days.removeIf(d -> d.date().equals(LocalDate.of(1992, 2, 29)));
    DailyTemps temps = DailyTemps.of(days);
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () -> DetrendedBurnAnalysis.run(novMar(), temps, 1991, 2000, 2001));
    assertMessageContains(ex, "season 1991");
    assertMessageContains(ex, "1992-02-29");
    TemperatureDataException cause =
        assertInstanceOf(TemperatureDataException.class, ex.getCause());
    assertMessageContains(cause, "1992-02-29");
  }

  @Test
  void ac8_seasonMeanTempCHelper_missingDayNamesDate() {
    List<DailyTemp> days = novMarDays();
    days.removeIf(d -> d.date().equals(LocalDate.of(1992, 2, 29)));
    DailyTemps temps = DailyTemps.of(days);
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () -> DetrendedBurnAnalysis.seasonMeanTempC(novMar().forSeason(1991), temps));
    assertMessageContains(ex, "1992-02-29");
  }

  // ---------------------------------------------------------------------------------------------
  // AC9: shifting keeps tmin <= tmax and finite values; input DailyTemps not modified
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac9_inputDailyTempsNotModified() {
    List<DailyTemp> days = januaryDays(noiseMeans(), 4.0);
    DailyTemps temps = DailyTemps.of(days);
    int sizeBefore = temps.size();
    LocalDate[] samples = {
      LocalDate.of(2001, 1, 1), LocalDate.of(2005, 1, 15), LocalDate.of(2010, 1, 31)
    };
    DailyTemp[] before = new DailyTemp[samples.length];
    for (int i = 0; i < samples.length; i++) {
      before[i] = temps.get(samples[i]);
    }

    DetrendedBurnAnalysis.run(januaryCall(200.0), temps, FIRST, LAST, TARGET);

    assertEquals(sizeBefore, temps.size());
    assertEquals(days.size(), temps.size());
    for (int i = 0; i < samples.length; i++) {
      assertEquals(before[i], temps.get(samples[i]));
    }
    // 2001 season mean 11.0 -> Tmax 15.0 / Tmin 7.0, unchanged despite a +2.0 shift
    assertEquals(new DailyTemp(LocalDate.of(2001, 1, 1), 15.0, 7.0), temps.get(samples[0]));
  }

  @Test
  void ac9_largePositiveShift_tmaxEqualsTmin_noException_indexZero() {
    // means 5k (0, 5, ..., 45), slope 5; target 3000 -> shifted mean 5 x (3000 - 2001) = 4,995
    double[] means = new double[10];
    for (int k = 0; k < 10; k++) {
      means[k] = 5.0 * k;
    }
    DetrendedBurnResult r =
        DetrendedBurnAnalysis.run(januaryCall(100.0), januaryTemps(means, 0.0), FIRST, LAST, 3000);
    assertEquals(5.0, r.slopeDegCPerYear(), TOL);
    for (SeasonOutcome s : r.burn().seasons()) {
      assertEquals(0.0, s.indexHddDegC(), TOL);
    }
  }

  @Test
  void ac9_largeNegativeShift_tmaxEqualsTmin_noException_finiteIndex() {
    // means 5k, slope 5; target 1000 -> shifted mean 5 x (1000 - 2001) = -5,005
    // -> HDD 18 + 5,005 = 5,023 -> index 31 x 5,023 = 155,713
    double[] means = new double[10];
    for (int k = 0; k < 10; k++) {
      means[k] = 5.0 * k;
    }
    DetrendedBurnResult r =
        DetrendedBurnAnalysis.run(januaryCall(100.0), januaryTemps(means, 0.0), FIRST, LAST, 1000);
    for (SeasonOutcome s : r.burn().seasons()) {
      assertTrue(Double.isFinite(s.indexHddDegC()));
      assertEquals(155_713.0, s.indexHddDegC(), 1e-6);
    }
  }

  @Test
  void ac9_tmaxOneUlpAboveTmin_shiftKeepsOrder() {
    // Tmax = nextUp(Tmin): adding the same shift to both must not produce tmin > tmax
    // (a new DailyTemp would throw). Trend 0.37/yr, shifts up to 3.7.
    List<DailyTemp> days = new ArrayList<>();
    for (int k = 0; k < 10; k++) {
      double tmin = 3.3 + 0.37 * k;
      addConstant(
          days,
          LocalDate.of(FIRST + k, 1, 1),
          LocalDate.of(FIRST + k, 1, 31),
          Math.nextUp(tmin),
          tmin);
    }
    DetrendedBurnResult r =
        DetrendedBurnAnalysis.run(januaryCall(100.0), DailyTemps.of(days), FIRST, LAST, 1950);
    assertEquals(0.37, r.slopeDegCPerYear(), 1e-9);
  }

  // ---------------------------------------------------------------------------------------------
  // AC10: deterministic, bit-identical results
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac10_sameInputsGiveBitIdenticalResults() {
    HddContract c = january(ContractType.PUT, 220.0, OptionalInt.of(1));
    DetrendedBurnResult first =
        DetrendedBurnAnalysis.run(c, januaryTemps(noiseMeans(), 3.37), FIRST, LAST, TARGET);
    DetrendedBurnResult second =
        DetrendedBurnAnalysis.run(c, januaryTemps(noiseMeans(), 3.37), FIRST, LAST, TARGET);
    assertEquals(first, second);
    assertEquals(
        Double.doubleToRawLongBits(first.slopeDegCPerYear()),
        Double.doubleToRawLongBits(second.slopeDegCPerYear()));
    assertEquals(
        Double.doubleToRawLongBits(first.burn().burnPriceGbp()),
        Double.doubleToRawLongBits(second.burn().burnPriceGbp()));
  }

  @Test
  void ac10_inputInsertionOrderDoesNotChangeResult() {
    List<DailyTemp> days = januaryDays(noiseMeans(), 3.37);
    List<DailyTemp> reversed = new ArrayList<>(days);
    Collections.reverse(reversed);
    HddContract c = january(ContractType.SWAP, 200.0, OptionalInt.empty());
    assertEquals(
        DetrendedBurnAnalysis.run(c, DailyTemps.of(days), FIRST, LAST, TARGET),
        DetrendedBurnAnalysis.run(c, DailyTemps.of(reversed), FIRST, LAST, TARGET));
  }

  // ---------------------------------------------------------------------------------------------
  // olsSlopeDegCPerYear helper
  // ---------------------------------------------------------------------------------------------

  @Test
  void olsSlope_exactLine_oddCount() {
    // 11 points 2.0 + 0.5k -> slope 0.5
    double[] v = new double[11];
    for (int k = 0; k < 11; k++) {
      v[k] = 2.0 + 0.5 * k;
    }
    assertEquals(0.5, DetrendedBurnAnalysis.olsSlopeDegCPerYear(1991, v), 1e-12);
  }

  @Test
  void olsSlope_exactLine_evenCount() {
    // 10 points 10.0 + 0.1k -> slope 0.1
    assertEquals(0.1, DetrendedBurnAnalysis.olsSlopeDegCPerYear(2001, exactTrendMeans()), 1e-12);
  }

  @Test
  void olsSlope_negativeSlope() {
    // 10 points -1.0 - 0.3k -> slope -0.3
    double[] v = new double[10];
    for (int k = 0; k < 10; k++) {
      v[k] = -1.0 - 0.3 * k;
    }
    assertEquals(-0.3, DetrendedBurnAnalysis.olsSlopeDegCPerYear(2001, v), 1e-12);
  }

  @Test
  void olsSlope_noiseOrthogonalToYears_givesTrendSlope() {
    assertEquals(0.2, DetrendedBurnAnalysis.olsSlopeDegCPerYear(2001, noiseMeans()), 1e-12);
  }

  @Test
  void olsSlope_doesNotDependOnYearOrigin() {
    // x is only centred, so year 1 and year 2001 give the same slope
    assertEquals(
        DetrendedBurnAnalysis.olsSlopeDegCPerYear(1, noiseMeans()),
        DetrendedBurnAnalysis.olsSlopeDegCPerYear(2001, noiseMeans()),
        1e-12);
  }

  @Test
  void olsSlope_flatValues_exactlyPositiveZero() {
    // 9.78 is not representable: still exactly +0.0, never -0.0 or a rounding residue
    double[] v = new double[10];
    Arrays.fill(v, 9.78);
    double slope = DetrendedBurnAnalysis.olsSlopeDegCPerYear(2001, v);
    assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(slope));
  }

  // ---------------------------------------------------------------------------------------------
  // Null arguments
  // ---------------------------------------------------------------------------------------------

  @Test
  void nullArguments_throwNullPointerException() {
    DailyTemps temps = januaryTemps(exactTrendMeans(), 4.0);
    assertMessageContains(
        assertThrows(
            NullPointerException.class,
            () -> DetrendedBurnAnalysis.run(null, temps, FIRST, LAST, TARGET)),
        "contract");
    assertMessageContains(
        assertThrows(
            NullPointerException.class,
            () -> DetrendedBurnAnalysis.run(januaryCall(210.0), null, FIRST, LAST, TARGET)),
        "temps");
  }

  // ---------------------------------------------------------------------------------------------
  // Review follow-ups
  // ---------------------------------------------------------------------------------------------

  @Test
  void slopeStdErr_noiseExample_is0_110() {
    // residuals = noise (+1,-1,-1,+1,0,0,+1,-1,-1,+1) -> SS 8; SE = sqrt(8 / (n-2) / Sxx)
    // = sqrt(1 / 82.5) = 0.11009637651263607
    DetrendedBurnResult r =
        DetrendedBurnAnalysis.run(
            januaryCall(200.0), januaryTemps(noiseMeans(), 4.0), FIRST, LAST, TARGET);
    assertEquals(Math.sqrt(1.0 / 82.5), r.slopeStdErrDegCPerYear(), TOL);
  }

  @Test
  void slopeStdErr_exactTrend_isZero() {
    DetrendedBurnResult r =
        DetrendedBurnAnalysis.run(
            januaryCall(210.0), januaryTemps(exactTrendMeans(), 4.0), FIRST, LAST, TARGET);
    assertEquals(0.0, r.slopeStdErrDegCPerYear(), 1e-9);
  }

  @Test
  void flatHistory_targetBeforeSeasons_shiftIsPositiveZero() {
    // slope +0.0 times a negative year gap must be reported as +0.0, not -0.0
    double[] flat = new double[10];
    Arrays.fill(flat, 9.78);
    DetrendedBurnResult r =
        DetrendedBurnAnalysis.run(januaryCall(240.0), januaryTemps(flat, 4.0), FIRST, LAST, 1900);
    for (SeasonTrend t : r.seasons()) {
      assertEquals(0L, Double.doubleToRawLongBits(t.shiftDegC()), () -> "season " + t);
    }
  }

  @Test
  void hugeSeasonRange_failsOnDataNotMemory() {
    // the range passes the minimum check; it must fail on the first missing day, not allocate
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () ->
                DetrendedBurnAnalysis.run(
                    januaryCall(240.0), DailyTemps.of(List.of()), 0, Integer.MAX_VALUE, 2011));
    assertMessageContains(ex, "season 0");
  }

  @Test
  void tooFewSeasons_messageNamesDetrendedBurn() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DetrendedBurnAnalysis.run(
                    januaryCall(240.0), DailyTemps.of(List.of()), 2001, 2009, 2011));
    assertMessageContains(ex, "detrended");
  }

  // AC11 (unit names, Javadoc) is review-only and has no test.
}
