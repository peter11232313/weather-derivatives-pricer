package com.weatherderivatives.pricer.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Spec 001 acceptance criteria for the HDD index. */
class HddIndexTest {

  private static final double TOL = 1e-9;

  /** Spec 001 5-day example (base 18: daily HDD 12, 10, 0, 14, 8). */
  private static List<DailyTemp> fiveDayExample() {
    return List.of(
        new DailyTemp(LocalDate.of(2024, 1, 1), 10.0, 2.0),
        new DailyTemp(LocalDate.of(2024, 1, 2), 12.0, 4.0),
        new DailyTemp(LocalDate.of(2024, 1, 3), 20.0, 18.0),
        new DailyTemp(LocalDate.of(2024, 1, 4), 9.0, -1.0),
        new DailyTemp(LocalDate.of(2024, 1, 5), 15.0, 5.0));
  }

  /** Every day in {@code first..lastInclusive} with the same tmax/tmin. */
  private static DailyTemps constant(
      LocalDate first, LocalDate lastInclusive, double tmaxC, double tminC) {
    List<DailyTemp> days = new ArrayList<>();
    for (LocalDate d = first; !d.isAfter(lastInclusive); d = d.plusDays(1)) {
      days.add(new DailyTemp(d, tmaxC, tminC));
    }
    return DailyTemps.of(days);
  }

  private static void assertMessageContains(Throwable ex, String expected) {
    String msg = ex.getMessage();
    assertTrue(
        msg != null && msg.contains(expected),
        () -> "expected message to contain '" + expected + "' but was: " + msg);
  }

  // --- Criterion: 5-day example, base 18 -> 44.0 ---

  @Test
  void fiveDayExampleBase18_cumulativeIs44() {
    // 12 + 10 + 0 + 14 + 8 = 44
    DailyTemps temps = DailyTemps.of(fiveDayExample());
    double hddDegC =
        HddIndex.cumulativeHddDegC(temps, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5), 18.0);
    assertEquals(44.0, hddDegC, TOL);
  }

  @Test
  void fiveDayExample_defaultBaseOverloadUses18_cumulativeIs44() {
    DailyTemps temps = DailyTemps.of(fiveDayExample());
    double hddDegC =
        HddIndex.cumulativeHddDegC(temps, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));
    assertEquals(18.0, HddIndex.DEFAULT_BASE_C);
    assertEquals(44.0, hddDegC, TOL);
  }

  // --- Criterion: January 2024, tmax 14 / tmin 6, base 18 -> 248.0 ---

  @Test
  void fullMonthJanuary2024_tmax14Tmin6Base18_cumulativeIs248() {
    // mean 10.0 -> daily HDD 18 - 10 = 8.0; 31 days x 8.0 = 248.0 (not 31 x 10 = 310)
    DailyTemps temps = constant(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31), 14.0, 6.0);
    double hddDegC =
        HddIndex.cumulativeHddDegC(
            temps, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31), 18.0);
    assertEquals(248.0, hddDegC, TOL);
  }

  // --- Criterion: mean above base -> daily HDD 0.0, never negative ---

  @Test
  void meanAboveBase_dailyHddIsZeroNeverNegative() {
    // 2024-01-03: tmax 20, tmin 18 -> mean 19 > 18 -> max(0, -1) = 0.0
    assertEquals(0.0, HddIndex.dailyHddDegC(20.0, 18.0, 18.0));
    assertEquals(0.0, HddIndex.dailyHddDegC(20.0, 18.0));
    // and accrued as 0 in the cumulative index for that single day
    DailyTemps temps = DailyTemps.of(fiveDayExample());
    LocalDate jan3 = LocalDate.of(2024, 1, 3);
    assertEquals(0.0, HddIndex.cumulativeHddDegC(temps, jan3, jan3, 18.0), TOL);
  }

  @Test
  void meanFarAboveBase_heatwave_dailyHddIsZero() {
    // mean 35 vs base 18 -> 0.0, not -17
    assertEquals(0.0, HddIndex.dailyHddDegC(40.0, 30.0, 18.0));
  }

  // --- Criterion: mean exactly equal to base -> 0.0 ---

  @Test
  void meanEqualToBase_dailyHddIsZero() {
    // tmax 20, tmin 16 -> mean 18.0 == base -> 0.0
    assertEquals(0.0, HddIndex.dailyHddDegC(20.0, 16.0, 18.0));
    // tmax == tmin == base (zero diurnal range)
    assertEquals(0.0, HddIndex.dailyHddDegC(15.5, 15.5, 15.5));
  }

  // --- Criterion: base 15.5, same 5 days -> 34.0 ---

  @Test
  void fiveDayExampleBase15_5_cumulativeIs34() {
    // means 6, 8, 19, 4, 10 -> 9.5 + 7.5 + 0 + 11.5 + 5.5 = 34.0
    DailyTemps temps = DailyTemps.of(fiveDayExample());
    double hddDegC =
        HddIndex.cumulativeHddDegC(temps, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5), 15.5);
    assertEquals(34.0, hddDegC, TOL);
  }

  // --- Criterion: winter season day counts (leap / non-leap) ---

  @Test
  void winterSeasonLeapYear_2023_11_01to2024_03_31_accrues152Days() {
    // Nov 30 + Dec 31 + Jan 31 + Feb 29 + Mar 31 = 152; each day HDD = 18 - 17 = 1.0.
    // Series extends beyond the period on both sides so only the period must be summed.
    DailyTemps temps = constant(LocalDate.of(2023, 10, 1), LocalDate.of(2024, 4, 30), 17.0, 17.0);
    double hddDegC =
        HddIndex.cumulativeHddDegC(
            temps, LocalDate.of(2023, 11, 1), LocalDate.of(2024, 3, 31), 18.0);
    assertEquals(152.0, hddDegC, TOL);
  }

  @Test
  void winterSeasonNonLeapYear_2022_11_01to2023_03_31_accrues151Days() {
    // Nov 30 + Dec 31 + Jan 31 + Feb 28 + Mar 31 = 151; each day HDD = 1.0.
    DailyTemps temps = constant(LocalDate.of(2022, 10, 1), LocalDate.of(2023, 4, 30), 17.0, 17.0);
    double hddDegC =
        HddIndex.cumulativeHddDegC(
            temps, LocalDate.of(2022, 11, 1), LocalDate.of(2023, 3, 31), 18.0);
    assertEquals(151.0, hddDegC, TOL);
  }

  @Test
  void leapDayOnly_isAccrued() {
    // 2024-02-29 alone: tmax 4, tmin 0 -> mean 2 -> HDD 16
    LocalDate leapDay = LocalDate.of(2024, 2, 29);
    DailyTemps temps = DailyTemps.of(List.of(new DailyTemp(leapDay, 4.0, 0.0)));
    assertEquals(16.0, HddIndex.cumulativeHddDegC(temps, leapDay, leapDay, 18.0), TOL);
  }

  @Test
  void periodAcrossYearEnd_accruesBothYears() {
    // 2023-12-30..2024-01-02 = 4 days x 1.0
    DailyTemps temps = constant(LocalDate.of(2023, 12, 30), LocalDate.of(2024, 1, 2), 17.0, 17.0);
    assertEquals(
        4.0,
        HddIndex.cumulativeHddDegC(
            temps, LocalDate.of(2023, 12, 30), LocalDate.of(2024, 1, 2), 18.0),
        TOL);
  }

  @Test
  void periodSpanningEuropeanDstChange_countsCalendarDaysNotHours() {
    // 2024-03-30..2024-04-01 spans the EU spring-forward (2024-03-31). UTC days: 3 x 1.0 = 3.0
    DailyTemps temps = constant(LocalDate.of(2024, 3, 30), LocalDate.of(2024, 4, 1), 17.0, 17.0);
    assertEquals(
        3.0,
        HddIndex.cumulativeHddDegC(
            temps, LocalDate.of(2024, 3, 30), LocalDate.of(2024, 4, 1), 18.0),
        TOL);
  }

  // --- Criterion: start == end -> exactly one day ---

  @Test
  void startEqualsEnd_accruesExactlyOneDay() {
    // 2024-01-04 alone: mean 4 -> 14.0 (neighbours 10.0 and 8.0 must not be included)
    DailyTemps temps = DailyTemps.of(fiveDayExample());
    LocalDate jan4 = LocalDate.of(2024, 1, 4);
    assertEquals(14.0, HddIndex.cumulativeHddDegC(temps, jan4, jan4, 18.0), TOL);
  }

  // --- Criterion: start after end -> IllegalArgumentException ---

  @Test
  void startAfterEnd_throwsIllegalArgumentException() {
    DailyTemps temps = DailyTemps.of(fiveDayExample());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HddIndex.cumulativeHddDegC(
                temps, LocalDate.of(2024, 1, 5), LocalDate.of(2024, 1, 4), 18.0));
  }

  // --- Criterion: missing day inside period -> exception names the date ---

  @Test
  void missingDayInsidePeriod_exceptionNamesMissingDate() {
    List<DailyTemp> days = new ArrayList<>(fiveDayExample());
    days.removeIf(d -> d.date().equals(LocalDate.of(2024, 1, 3)));
    DailyTemps temps = DailyTemps.of(days);
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () ->
                HddIndex.cumulativeHddDegC(
                    temps, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5), 18.0));
    assertMessageContains(ex, "2024-01-03");
  }

  @Test
  void missingLastDayOfPeriod_exceptionNamesMissingDate() {
    DailyTemps temps = DailyTemps.of(fiveDayExample());
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () ->
                HddIndex.cumulativeHddDegC(
                    temps, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 6), 18.0));
    assertMessageContains(ex, "2024-01-06");
  }

  @Test
  void emptySeries_exceptionNamesFirstMissingDate() {
    DailyTemps temps = DailyTemps.of(List.of());
    LocalDate day = LocalDate.of(2024, 2, 29);
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () -> HddIndex.cumulativeHddDegC(temps, day, day, 18.0));
    assertMessageContains(ex, "2024-02-29");
  }

  // --- Criterion: NaN tmaxC or tminC -> exception names the date ---

  @Test
  void nanTmaxC_exceptionNamesDate() {
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () -> new DailyTemp(LocalDate.of(2024, 1, 2), Double.NaN, 4.0));
    assertMessageContains(ex, "2024-01-02");
  }

  @Test
  void nanTminC_exceptionNamesDate() {
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () -> new DailyTemp(LocalDate.of(2024, 1, 2), 12.0, Double.NaN));
    assertMessageContains(ex, "2024-01-02");
  }

  @Test
  void dailyHdd_nanArgument_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> HddIndex.dailyHddDegC(Double.NaN, 4.0, 18));
    assertThrows(IllegalArgumentException.class, () -> HddIndex.dailyHddDegC(12.0, Double.NaN, 18));
    assertThrows(
        IllegalArgumentException.class, () -> HddIndex.dailyHddDegC(12.0, 4.0, Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> HddIndex.dailyHddDegC(Double.NaN, 4.0));
  }

  // --- Criterion: tminC > tmaxC -> exception names the date ---

  @Test
  void tminAboveTmax_exceptionNamesDate() {
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () -> new DailyTemp(LocalDate.of(2023, 12, 31), 5.0, 5.1));
    assertMessageContains(ex, "2023-12-31");
  }

  @Test
  void dailyHdd_tminAboveTmax_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> HddIndex.dailyHddDegC(5.0, 5.1, 18.0));
    assertThrows(IllegalArgumentException.class, () -> HddIndex.dailyHddDegC(5.0, 5.1));
  }

  @Test
  void tminEqualsTmax_isAccepted() {
    // zero diurnal range is valid: mean 17 -> HDD 1.0
    DailyTemp day = new DailyTemp(LocalDate.of(2024, 1, 1), 17.0, 17.0);
    assertEquals(17.0, day.meanTempC(), TOL);
    assertEquals(1.0, HddIndex.dailyHddDegC(17.0, 17.0, 18.0), TOL);
  }

  // --- Additional known answers for the daily formula ---

  @Test
  void dailyHdd_knownAnswers() {
    // (10 + 2) / 2 = 6 -> 18 - 6 = 12
    assertEquals(12.0, HddIndex.dailyHddDegC(10.0, 2.0, 18.0), TOL);
    // sub-zero: (-2 + -10) / 2 = -6 -> 18 - (-6) = 24
    assertEquals(24.0, HddIndex.dailyHddDegC(-2.0, -10.0, 18.0), TOL);
    // default base overload: (9 + -1) / 2 = 4 -> 14
    assertEquals(14.0, HddIndex.dailyHddDegC(9.0, -1.0), TOL);
  }

  @Test
  void dailyTemp_meanTempC_isAverageOfTmaxAndTmin() {
    // (9 + -1) / 2 = 4.0
    assertEquals(4.0, new DailyTemp(LocalDate.of(2024, 1, 4), 9.0, -1.0).meanTempC(), TOL);
  }

  // --- Review follow-ups: non-finite inputs, mean-based HDD, -0.0 ---

  @Test
  void infiniteTemperature_exceptionNamesDate() {
    TemperatureDataException plus =
        assertThrows(
            TemperatureDataException.class,
            () -> new DailyTemp(LocalDate.of(2024, 1, 7), Double.POSITIVE_INFINITY, 2.0));
    assertMessageContains(plus, "2024-01-07");
    TemperatureDataException minus =
        assertThrows(
            TemperatureDataException.class,
            () -> new DailyTemp(LocalDate.of(2024, 1, 8), 10.0, Double.NEGATIVE_INFINITY));
    assertMessageContains(minus, "2024-01-08");
  }

  @Test
  void dailyHdd_infiniteArgument_throwsIllegalArgumentException() {
    double inf = Double.POSITIVE_INFINITY;
    assertThrows(IllegalArgumentException.class, () -> HddIndex.dailyHddDegC(inf, -inf, 18.0));
    assertThrows(IllegalArgumentException.class, () -> HddIndex.dailyHddDegC(inf, 5.0, 18.0));
    assertThrows(IllegalArgumentException.class, () -> HddIndex.dailyHddDegC(5.0, -inf, 18.0));
    assertThrows(IllegalArgumentException.class, () -> HddIndex.dailyHddDegC(10.0, 2.0, inf));
    assertThrows(IllegalArgumentException.class, () -> HddIndex.dailyHddDegC(10.0, 2.0, -inf));
  }

  @Test
  void cumulative_infiniteBase_throwsIllegalArgumentException() {
    DailyTemps temps = DailyTemps.of(fiveDayExample());
    LocalDate jan1 = LocalDate.of(2024, 1, 1);
    LocalDate jan5 = LocalDate.of(2024, 1, 5);
    assertThrows(
        IllegalArgumentException.class,
        () -> HddIndex.cumulativeHddDegC(temps, jan1, jan5, Double.POSITIVE_INFINITY));
    assertThrows(
        IllegalArgumentException.class,
        () -> HddIndex.cumulativeHddDegC(temps, jan1, jan5, Double.NEGATIVE_INFINITY));
  }

  @Test
  void dayStraddlingBase_usesMeanNotAverageOfClippedTmaxTminHdd() {
    // Base 18. (20, 16): mean 18 -> 0, whereas averaging clipped HDDs would give (0 + 2) / 2 = 1.
    // (19, 13): mean 16 -> 2, whereas averaging clipped HDDs would give (0 + 5) / 2 = 2.5.
    DailyTemps temps =
        DailyTemps.of(
            List.of(
                new DailyTemp(LocalDate.of(2024, 3, 1), 20.0, 16.0),
                new DailyTemp(LocalDate.of(2024, 3, 2), 19.0, 13.0)));
    assertEquals(
        2.0,
        HddIndex.cumulativeHddDegC(temps, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 2), 18.0),
        TOL);
  }

  @Test
  void dailyHdd_negativeZeroBase_returnsPositiveZero() {
    // base -0.0 with mean +0.0 is the one input whose raw difference is -0.0.
    // assertEquals(double, double) compares bits, so -0.0 would fail.
    assertEquals(0.0, HddIndex.dailyHddDegC(0.0, 0.0, -0.0));
  }
}
