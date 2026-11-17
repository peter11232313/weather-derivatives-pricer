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
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Spec 003 acceptance criteria for burn analysis (AC1, AC2, AC5, AC8, AC9, AC11). */
class BurnAnalysisTest {

  /** Spec tolerance for GBP amounts in the example table. */
  private static final double GBP_TOL = 0.01;

  private static final double TOL = 1e-9;

  /** Every day in {@code first..lastInclusive} with the same tmax/tmin, appended to {@code out}. */
  private static void addConstant(
      List<DailyTemp> out, LocalDate first, LocalDate lastInclusive, double tmaxC, double tminC) {
    for (LocalDate d = first; !d.isAfter(lastInclusive); d = d.plusDays(1)) {
      out.add(new DailyTemp(d, tmaxC, tminC));
    }
  }

  private static DailyTemps constant(
      LocalDate first, LocalDate lastInclusive, double tmaxC, double tminC) {
    List<DailyTemp> days = new ArrayList<>();
    addConstant(days, first, lastInclusive, tmaxC, tminC);
    return DailyTemps.of(days);
  }

  private static void assertMessageContains(Throwable ex, String expected) {
    String msg = ex.getMessage();
    assertTrue(
        msg != null && msg.contains(expected),
        () -> "expected message to contain '" + expected + "' but was: " + msg);
  }

  /**
   * Spec three-season January example. Daily HDD (base 18): 2001 14/6 -> 8.0, 2002 13/5 -> 9.0,
   * 2003 15/7 -> 7.0; 31-day indices 248, 279, 217. Includes some December/February days so only
   * January must be summed.
   */
  private static List<DailyTemp> januaryExampleDays() {
    List<DailyTemp> days = new ArrayList<>();
    addConstant(days, LocalDate.of(2000, 12, 25), LocalDate.of(2000, 12, 31), 0.0, -5.0);
    addConstant(days, LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 14.0, 6.0);
    addConstant(days, LocalDate.of(2001, 2, 1), LocalDate.of(2001, 2, 3), 0.0, -5.0);
    addConstant(days, LocalDate.of(2002, 1, 1), LocalDate.of(2002, 1, 31), 13.0, 5.0);
    addConstant(days, LocalDate.of(2003, 1, 1), LocalDate.of(2003, 1, 31), 15.0, 7.0);
    return days;
  }

  private static DailyTemps januaryExample() {
    return DailyTemps.of(januaryExampleDays());
  }

  /** January contract, tick 1,000, written for 2010 to show seasons are shifted from any year. */
  private static HddContract january(ContractType type, double strike, OptionalDouble capGbp) {
    YearMonth jan2010 = YearMonth.of(2010, 1);
    return HddContract.of(type, jan2010, jan2010, strike, 1000.0, capGbp, OptionalInt.empty());
  }

  private static void assertSeason(
      SeasonOutcome s, int seasonYear, double indexHddDegC, double payoffGbp) {
    assertEquals(seasonYear, s.seasonYear());
    assertEquals(indexHddDegC, s.indexHddDegC(), TOL);
    assertEquals(payoffGbp, s.payoffGbp(), GBP_TOL);
  }

  // --- AC1: call K=240, tick 1,000 -> indices 248/279/217, payoffs 8,000/39,000/0,
  //          burn 15,666.67, sample std dev 20,599.35, min 0, max 39,000 ---

  @Test
  void ac1_januaryExampleCallK240_burnPrice15666_67() {
    BurnResult r =
        BurnAnalysis.run(
            january(ContractType.CALL, 240.0, OptionalDouble.empty()),
            januaryExample(),
            2001,
            2003);

    assertEquals(3, r.seasonCount());
    assertEquals(3, r.seasons().size());
    assertSeason(r.seasons().get(0), 2001, 248.0, 8_000.0);
    assertSeason(r.seasons().get(1), 2002, 279.0, 39_000.0);
    assertSeason(r.seasons().get(2), 2003, 217.0, 0.0);

    // mean (8,000 + 39,000 + 0) / 3 = 15,666.67
    assertEquals(15_666.67, r.burnPriceGbp(), GBP_TOL);
    // deviations -7,666.67, 23,333.33, -15,666.67; SS = 848,666,667; / (n-1)=2 -> sqrt = 20,599.35
    assertEquals(20_599.35, r.payoffStdDevGbp(), GBP_TOL);
    assertEquals(0.0, r.minPayoffGbp(), GBP_TOL);
    assertEquals(39_000.0, r.maxPayoffGbp(), GBP_TOL);
    // (248 + 279 + 217) / 3 = 248.0
    assertEquals(248.0, r.meanIndexHddDegC(), TOL);
  }

  // --- AC2: capped call, put, swap rows of the example table ---

  @Test
  void ac2_januaryExampleCallK240Cap30000() {
    // payoffs 8,000; 30,000; 0 -> mean 12,666.67; std dev 15,534.91
    BurnResult r =
        BurnAnalysis.run(
            january(ContractType.CALL, 240.0, OptionalDouble.of(30_000.0)),
            januaryExample(),
            2001,
            2003);
    assertSeason(r.seasons().get(0), 2001, 248.0, 8_000.0);
    assertSeason(r.seasons().get(1), 2002, 279.0, 30_000.0);
    assertSeason(r.seasons().get(2), 2003, 217.0, 0.0);
    assertEquals(12_666.67, r.burnPriceGbp(), GBP_TOL);
    assertEquals(15_534.91, r.payoffStdDevGbp(), GBP_TOL);
    assertEquals(0.0, r.minPayoffGbp(), GBP_TOL);
    assertEquals(30_000.0, r.maxPayoffGbp(), GBP_TOL);
  }

  @Test
  void ac2_januaryExamplePutK260() {
    // payoffs 12,000; 0; 43,000 -> mean 18,333.33; std dev 22,188.59
    BurnResult r =
        BurnAnalysis.run(
            january(ContractType.PUT, 260.0, OptionalDouble.empty()), januaryExample(), 2001, 2003);
    assertSeason(r.seasons().get(0), 2001, 248.0, 12_000.0);
    assertSeason(r.seasons().get(1), 2002, 279.0, 0.0);
    assertSeason(r.seasons().get(2), 2003, 217.0, 43_000.0);
    assertEquals(18_333.33, r.burnPriceGbp(), GBP_TOL);
    assertEquals(22_188.59, r.payoffStdDevGbp(), GBP_TOL);
    assertEquals(0.0, r.minPayoffGbp(), GBP_TOL);
    assertEquals(43_000.0, r.maxPayoffGbp(), GBP_TOL);
  }

  @Test
  void ac2_januaryExampleSwapK240() {
    // payoffs 8,000; 39,000; -23,000 -> mean 8,000; deviations 0, +31,000, -31,000
    // -> SS 1.922e9 / 2 = 9.61e8 -> std dev 31,000
    BurnResult r =
        BurnAnalysis.run(
            january(ContractType.SWAP, 240.0, OptionalDouble.empty()),
            januaryExample(),
            2001,
            2003);
    assertSeason(r.seasons().get(0), 2001, 248.0, 8_000.0);
    assertSeason(r.seasons().get(1), 2002, 279.0, 39_000.0);
    assertSeason(r.seasons().get(2), 2003, 217.0, -23_000.0);
    assertEquals(8_000.0, r.burnPriceGbp(), GBP_TOL);
    assertEquals(31_000.0, r.payoffStdDevGbp(), GBP_TOL);
    assertEquals(-23_000.0, r.minPayoffGbp(), GBP_TOL);
    assertEquals(39_000.0, r.maxPayoffGbp(), GBP_TOL);
    assertEquals(248.0, r.meanIndexHddDegC(), TOL);
  }

  // --- AC5 (burn level): Nov-Mar seasons 1991 (152 days, leap) and 1992 (151 days) ---

  @Test
  void ac5_novMarBurn_leapAndNonLeapSeasons() {
    // Tmax = Tmin = 17 -> daily HDD 1.0, so index == day count: 1991 -> 152, 1992 -> 151.
    // Swap K=150, tick 1: payoffs 2, 1 -> mean 1.5, sample std dev |2-1|/sqrt(2) = 0.70710678...
    DailyTemps temps = constant(LocalDate.of(1991, 11, 1), LocalDate.of(1993, 3, 31), 17.0, 17.0);
    HddContract c =
        HddContract.of(
            ContractType.SWAP,
            YearMonth.of(1991, 11),
            YearMonth.of(1992, 3),
            150.0,
            1.0,
            OptionalDouble.empty(),
            OptionalInt.empty());
    BurnResult r = BurnAnalysis.run(c, temps, 1991, 1992);
    assertEquals(2, r.seasonCount());
    assertSeason(r.seasons().get(0), 1991, 152.0, 2.0);
    assertSeason(r.seasons().get(1), 1992, 151.0, 1.0);
    assertEquals(1.5, r.burnPriceGbp(), TOL);
    assertEquals(Math.sqrt(0.5), r.payoffStdDevGbp(), TOL);
    assertEquals(151.5, r.meanIndexHddDegC(), TOL);
  }

  // --- Edge cases: two seasons, zero volatility ---

  @Test
  void twoSeasons_sampleStdDevUsesNMinusOne() {
    // call K=240 for 2001..2002: payoffs 8,000; 39,000 -> mean 23,500;
    // std dev = 31,000 / sqrt(2) = 21,920.31 (population would be 15,500)
    BurnResult r =
        BurnAnalysis.run(
            january(ContractType.CALL, 240.0, OptionalDouble.empty()),
            januaryExample(),
            2001,
            2002);
    assertEquals(2, r.seasonCount());
    assertEquals(23_500.0, r.burnPriceGbp(), GBP_TOL);
    assertEquals(21_920.31, r.payoffStdDevGbp(), GBP_TOL);
  }

  @Test
  void identicalSeasons_zeroVolatility_stdDevIsExactlyZero() {
    // every January 2001..2003 has HDD 8.0 -> index 248 each -> call K=240 pays 8,000 each.
    // std dev must be exactly 0.0 (not NaN, not a tiny negative-variance artefact).
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2003, 1, 31), 14.0, 6.0);
    BurnResult r =
        BurnAnalysis.run(
            january(ContractType.CALL, 240.0, OptionalDouble.empty()), temps, 2001, 2003);
    assertEquals(8_000.0, r.burnPriceGbp(), TOL);
    assertEquals(0.0, r.payoffStdDevGbp());
    assertEquals(8_000.0, r.minPayoffGbp(), TOL);
    assertEquals(8_000.0, r.maxPayoffGbp(), TOL);
  }

  @Test
  void allSeasonsOutOfTheMoney_burnAndStdDevAreZero() {
    // call K=1,000 never pays -> burn 0.0, std dev 0.0, min = max = 0.0
    BurnResult r =
        BurnAnalysis.run(
            january(ContractType.CALL, 1000.0, OptionalDouble.empty()),
            januaryExample(),
            2001,
            2003);
    assertEquals(0.0, r.burnPriceGbp());
    assertEquals(0.0, r.payoffStdDevGbp());
    assertEquals(0.0, r.minPayoffGbp());
    assertEquals(0.0, r.maxPayoffGbp());
  }

  @Test
  void contractRoundingIsAppliedPerSeason() {
    // Tmax 14.0 / Tmin 5.86 every January 2001..2002 -> HDD 8.07 -> 8.1 at 1 decimal -> 251.1
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2002, 1, 31), 14.0, 5.86);
    HddContract c =
        HddContract.of(
            ContractType.SWAP,
            YearMonth.of(2001, 1),
            YearMonth.of(2001, 1),
            250.0,
            10.0,
            OptionalDouble.empty(),
            OptionalInt.of(1));
    BurnResult r = BurnAnalysis.run(c, temps, 2001, 2002);
    // payoff 10 x (251.1 - 250) = 11.0 each season
    assertEquals(251.1, r.seasons().get(0).indexHddDegC());
    assertEquals(251.1, r.seasons().get(1).indexHddDegC());
    assertEquals(11.0, r.burnPriceGbp(), TOL);
  }

  // --- AC8: missing day -> exception names the season and the date, cause preserved ---

  @Test
  void ac8_missingLeapDayInNovMarSeason_exceptionNamesSeasonAndDate() {
    // seasons 1990 (1990-11..1991-03) and 1991 (1991-11..1992-03); 1992-02-29 is missing.
    List<DailyTemp> days = new ArrayList<>();
    addConstant(days, LocalDate.of(1990, 11, 1), LocalDate.of(1992, 3, 31), 17.0, 17.0);
    days.removeIf(d -> d.date().equals(LocalDate.of(1992, 2, 29)));
    DailyTemps temps = DailyTemps.of(days);
    HddContract c =
        HddContract.of(
            ContractType.CALL,
            YearMonth.of(1991, 11),
            YearMonth.of(1992, 3),
            150.0,
            1.0,
            OptionalDouble.empty(),
            OptionalInt.empty());

    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> BurnAnalysis.run(c, temps, 1990, 1991));
    assertMessageContains(ex, "season 1991");
    assertMessageContains(ex, "1992-02-29");
    TemperatureDataException cause =
        assertInstanceOf(TemperatureDataException.class, ex.getCause());
    assertMessageContains(cause, "1992-02-29");
  }

  @Test
  void ac8_missingDayInMiddleSeason_noSilentSkip() {
    // January example with 2002-01-15 removed: must fail for season 2002, not average 2 seasons.
    List<DailyTemp> days = januaryExampleDays();
    days.removeIf(d -> d.date().equals(LocalDate.of(2002, 1, 15)));
    DailyTemps temps = DailyTemps.of(days);
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () ->
                BurnAnalysis.run(
                    january(ContractType.CALL, 240.0, OptionalDouble.empty()), temps, 2001, 2003));
    assertMessageContains(ex, "season 2002");
    assertMessageContains(ex, "2002-01-15");
  }

  @Test
  void ac8_seasonOutsideData_exceptionNamesSeasonAndFirstMissingDate() {
    // data ends with January 2003; season 2004 needs 2004-01-01 onwards
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () ->
                BurnAnalysis.run(
                    january(ContractType.CALL, 240.0, OptionalDouble.empty()),
                    januaryExample(),
                    2002,
                    2004));
    assertMessageContains(ex, "season 2004");
    assertMessageContains(ex, "2004-01-01");
  }

  @Test
  void ac8_missingDayWithRoundedContract_exceptionNamesSeasonAndDate() {
    // same as the middle-season case, but on the rounded (decimal) index path
    List<DailyTemp> days = januaryExampleDays();
    days.removeIf(d -> d.date().equals(LocalDate.of(2002, 1, 15)));
    DailyTemps temps = DailyTemps.of(days);
    YearMonth jan2010 = YearMonth.of(2010, 1);
    HddContract rounded =
        HddContract.of(
            ContractType.CALL,
            jan2010,
            jan2010,
            240.0,
            1000.0,
            OptionalDouble.empty(),
            OptionalInt.of(1));
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class, () -> BurnAnalysis.run(rounded, temps, 2001, 2003));
    assertMessageContains(ex, "season 2002");
    assertMessageContains(ex, "2002-01-15");
  }

  @Test
  void nullArguments_throwNullPointerException() {
    HddContract c = january(ContractType.CALL, 240.0, OptionalDouble.empty());
    assertThrows(
        NullPointerException.class, () -> BurnAnalysis.run(null, januaryExample(), 2001, 2003));
    assertThrows(NullPointerException.class, () -> BurnAnalysis.run(c, null, 2001, 2003));
  }

  // --- AC9: fewer than 2 seasons or firstSeasonYear > lastSeasonYear -> IAE ---

  @Test
  void ac9_firstSeasonAfterLast_throwsIllegalArgumentException() {
    // empty data: if the range were not checked first, a TemperatureDataException would escape
    DailyTemps empty = DailyTemps.of(List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BurnAnalysis.run(
                january(ContractType.CALL, 240.0, OptionalDouble.empty()), empty, 2003, 2001));
  }

  @Test
  void ac9_singleSeason_throwsIllegalArgumentException() {
    // one season cannot give a sample std dev (n - 1 = 0); rejected before data access
    DailyTemps empty = DailyTemps.of(List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BurnAnalysis.run(
                january(ContractType.CALL, 240.0, OptionalDouble.empty()), empty, 2001, 2001));
    // and with valid data for that single season
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BurnAnalysis.run(
                january(ContractType.CALL, 240.0, OptionalDouble.empty()),
                januaryExample(),
                2002,
                2002));
  }

  // --- AC11: deterministic, bit-identical results ---

  @Test
  void ac11_sameInputsGiveBitIdenticalResults() {
    HddContract c = january(ContractType.PUT, 260.0, OptionalDouble.of(30_000.0));
    BurnResult first = BurnAnalysis.run(c, januaryExample(), 2001, 2003);
    BurnResult second = BurnAnalysis.run(c, januaryExample(), 2001, 2003);
    // record equals compares double components with Double.compare (bitwise semantics)
    assertEquals(first, second);
    assertEquals(
        Double.doubleToRawLongBits(first.burnPriceGbp()),
        Double.doubleToRawLongBits(second.burnPriceGbp()));
    assertEquals(
        Double.doubleToRawLongBits(first.payoffStdDevGbp()),
        Double.doubleToRawLongBits(second.payoffStdDevGbp()));
  }

  @Test
  void ac11_inputInsertionOrderDoesNotChangeResult() {
    // Same days supplied in reverse order: fixed (date-ordered) summation must give equal bits.
    // Non-representable 5.86 makes daily HDD values inexact so summation order would matter.
    List<DailyTemp> days = new ArrayList<>();
    addConstant(days, LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 14.0, 5.86);
    addConstant(days, LocalDate.of(2002, 1, 1), LocalDate.of(2002, 1, 31), 13.1, 4.77);
    addConstant(days, LocalDate.of(2003, 1, 1), LocalDate.of(2003, 1, 31), 15.3, 6.91);
    List<DailyTemp> reversed = new ArrayList<>(days);
    java.util.Collections.reverse(reversed);
    HddContract c = january(ContractType.SWAP, 240.0, OptionalDouble.empty());
    assertEquals(
        BurnAnalysis.run(c, DailyTemps.of(days), 2001, 2003),
        BurnAnalysis.run(c, DailyTemps.of(reversed), 2001, 2003));
  }

  // --- BurnResult record: defensive copy, derived seasonCount ---

  @Test
  void burnResult_defensivelyCopiesSeasonsAndDerivesSeasonCount() {
    List<SeasonOutcome> rows = new ArrayList<>();
    rows.add(new SeasonOutcome(2001, 248.0, 8_000.0));
    rows.add(new SeasonOutcome(2002, 279.0, 39_000.0));
    BurnResult r = new BurnResult(rows, 23_500.0, 21_920.31, 8_000.0, 39_000.0, 263.5);
    rows.add(new SeasonOutcome(2003, 217.0, 0.0));
    assertEquals(2, r.seasons().size());
    assertEquals(2, r.seasonCount());
    assertThrows(
        UnsupportedOperationException.class,
        () -> r.seasons().add(new SeasonOutcome(2004, 0.0, 0.0)));
  }

  // AC12 (unit names, Javadoc) is review-only and has no test.
}
