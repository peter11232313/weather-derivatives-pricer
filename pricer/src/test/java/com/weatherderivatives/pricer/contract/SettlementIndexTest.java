package com.weatherderivatives.pricer.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weatherderivatives.pricer.index.DailyTemp;
import com.weatherderivatives.pricer.index.DailyTemps;
import com.weatherderivatives.pricer.index.HddIndex;
import com.weatherderivatives.pricer.index.TemperatureDataException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Spec 003 acceptance criteria for the contract settlement index (AC5, AC6, AC7, AC8). */
class SettlementIndexTest {

  private static final double TOL = 1e-9;

  private static final YearMonth JAN_2001 = YearMonth.of(2001, 1);

  /** Every day in {@code first..lastInclusive} with the same tmax/tmin. */
  private static List<DailyTemp> constantDays(
      LocalDate first, LocalDate lastInclusive, double tmaxC, double tminC) {
    List<DailyTemp> days = new ArrayList<>();
    for (LocalDate d = first; !d.isAfter(lastInclusive); d = d.plusDays(1)) {
      days.add(new DailyTemp(d, tmaxC, tminC));
    }
    return days;
  }

  private static DailyTemps constant(
      LocalDate first, LocalDate lastInclusive, double tmaxC, double tminC) {
    return DailyTemps.of(constantDays(first, lastInclusive, tmaxC, tminC));
  }

  private static void assertMessageContains(Throwable ex, String expected) {
    String msg = ex.getMessage();
    assertTrue(
        msg != null && msg.contains(expected),
        () -> "expected message to contain '" + expected + "' but was: " + msg);
  }

  private static HddContract januaryCall(YearMonth month, OptionalInt decimals) {
    return HddContract.of(
        ContractType.CALL, month, month, 240.0, 1000.0, OptionalDouble.empty(), decimals);
  }

  private static HddContract novMarCall1991(OptionalInt decimals) {
    return HddContract.of(
        ContractType.CALL,
        YearMonth.of(1991, 11),
        YearMonth.of(1992, 3),
        150.0,
        1.0,
        OptionalDouble.empty(),
        decimals);
  }

  // --- AC5: Nov-Mar season 1991 = 152 days (leap), season 1992 = 151 days ---

  @Test
  void ac5_novMarSeason1991Leap_accrues152Days() {
    // Tmax = Tmin = 17 -> daily HDD 1.0, so index == day count.
    // Nov 30 + Dec 31 + Jan 31 + Feb 29 + Mar 31 = 152. Data extends past both ends.
    DailyTemps temps = constant(LocalDate.of(1991, 10, 1), LocalDate.of(1993, 4, 30), 17.0, 17.0);
    assertEquals(
        152.0, SettlementIndex.indexHddDegC(novMarCall1991(OptionalInt.empty()), temps), TOL);
  }

  @Test
  void ac5_novMarSeason1992NonLeap_accrues151Days() {
    // Nov 30 + Dec 31 + Jan 31 + Feb 28 + Mar 31 = 151
    DailyTemps temps = constant(LocalDate.of(1991, 10, 1), LocalDate.of(1993, 4, 30), 17.0, 17.0);
    HddContract season1992 = novMarCall1991(OptionalInt.empty()).forSeason(1992);
    assertEquals(151.0, SettlementIndex.indexHddDegC(season1992, temps), TOL);
  }

  @Test
  void ac5_leapDayIsAccruedWithRounding() {
    // same as above but decimals=0: each day 1.0 -> 152 exactly (rounded total)
    DailyTemps temps = constant(LocalDate.of(1991, 10, 1), LocalDate.of(1992, 4, 30), 17.0, 17.0);
    assertEquals(152.0, SettlementIndex.indexHddDegC(novMarCall1991(OptionalInt.of(0)), temps));
  }

  @Test
  void accrualUsesContractBaseNotDefault() {
    // base 15.5, Tmax 14 / Tmin 6 -> mean 10 -> daily HDD 5.5; Jan 31 days -> 170.5
    HddContract c =
        new HddContract(
            ContractType.CALL,
            JAN_2001,
            JAN_2001,
            15.5,
            0.0,
            1.0,
            OptionalDouble.empty(),
            OptionalInt.empty());
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 14.0, 6.0);
    assertEquals(170.5, SettlementIndex.indexHddDegC(c, temps), TOL);
  }

  // --- AC6: decimals=1, Tmax 14.0 / Tmin 5.86 (daily HDD 8.07) -> 251.1; unrounded 250.17 ---

  @Test
  void ac6_dailyHdd8_07RoundedToOneDecimal_indexIs251_1() {
    // mean (14.0 + 5.86) / 2 = 9.93 -> HDD 8.07 -> rounds to 8.1; 31 x 8.1 = 251.1.
    // Total is itself rounded to 1 decimal, so the result is exactly the double nearest 251.1.
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 14.0, 5.86);
    double indexHddDegC =
        SettlementIndex.indexHddDegC(januaryCall(JAN_2001, OptionalInt.of(1)), temps);
    assertEquals(251.1, indexHddDegC);
  }

  @Test
  void ac6_noRounding_indexIs250_17() {
    // 31 x 8.07 = 250.17
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 14.0, 5.86);
    double indexHddDegC =
        SettlementIndex.indexHddDegC(januaryCall(JAN_2001, OptionalInt.empty()), temps);
    assertEquals(250.17, indexHddDegC, TOL);
  }

  @Test
  void ac6_noRounding_equalsHddIndexCumulativeBitwise() {
    // Full precision must be exactly spec 001's index (same days, same base, same order).
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 14.0, 5.86);
    double expected =
        HddIndex.cumulativeHddDegC(
            temps, LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 18.0);
    // assertEquals(double, double) without delta compares bits.
    assertEquals(
        expected, SettlementIndex.indexHddDegC(januaryCall(JAN_2001, OptionalInt.empty()), temps));
  }

  // --- AC7: daily HDD exactly 8.25 (Tmax 10.0, Tmin 9.5) rounds half-up to 8.3 ---

  @Test
  void ac7_dailyHdd8_25RoundsHalfUpTo8_3_fullJanuaryIs257_3() {
    // mean 9.75 -> HDD 8.25 (exact in binary). HALF_UP -> 8.3 (HALF_EVEN would give 8.2).
    // Contracts are whole months: 31 x 8.3 = 257.3 (HALF_EVEN would give 254.2).
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 10.0, 9.5);
    assertEquals(
        257.3, SettlementIndex.indexHddDegC(januaryCall(JAN_2001, OptionalInt.of(1)), temps));
  }

  @Test
  void ac7_dailyHdd8_25ZeroDecimals_roundsTo8_fullJanuaryIs248() {
    // 8.25 -> 0 decimals -> 8; 31 x 8 = 248
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 10.0, 9.5);
    assertEquals(
        248.0, SettlementIndex.indexHddDegC(januaryCall(JAN_2001, OptionalInt.of(0)), temps));
  }

  @Test
  void ac7_roundingUsesShortestDecimalRepresentation() {
    // base 1.005, Tmax = Tmin = 0 -> daily HDD is the double 1.005 (binary 1.00499999999999989...).
    // BigDecimal.valueOf(1.005) = 1.005 -> HALF_UP 2dp -> 1.01; 31 x 1.01 = 31.31.
    // new BigDecimal(1.005) or Math.round(x * 100) / 100 would give 1.00 -> 31.00.
    HddContract c =
        new HddContract(
            ContractType.CALL,
            JAN_2001,
            JAN_2001,
            1.005,
            0.0,
            1.0,
            OptionalDouble.empty(),
            OptionalInt.of(2));
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 0.0, 0.0);
    assertEquals(31.31, SettlementIndex.indexHddDegC(c, temps));
  }

  @Test
  void rounding_exactDecimalTieThatIsInexactInBinary_roundsUp() {
    // Tmax 6.9 / Tmin 1.8: exact HDD 18 - 4.35 = 13.65 -> HALF_UP 13.7; 31 x 13.7 = 424.7.
    // The double 18 - (6.9/2 + 1.8/2) is 13.649999999999999, which would round to 13.6 (421.6).
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 6.9, 1.8);
    assertEquals(
        424.7, SettlementIndex.indexHddDegC(januaryCall(JAN_2001, OptionalInt.of(1)), temps));
    // Tmax 7.9 / Tmin 1.8: exact 13.15 -> 13.2; 31 x 13.2 = 409.2 (double path would give 406.1)
    DailyTemps temps2 = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 7.9, 1.8);
    assertEquals(
        409.2, SettlementIndex.indexHddDegC(januaryCall(JAN_2001, OptionalInt.of(1)), temps2));
  }

  @Test
  void nullArguments_throwNullPointerException() {
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 14.0, 6.0);
    assertMessageContains(
        assertThrows(NullPointerException.class, () -> SettlementIndex.indexHddDegC(null, temps)),
        "contract");
    assertMessageContains(
        assertThrows(
            NullPointerException.class,
            () -> SettlementIndex.indexHddDegC(januaryCall(JAN_2001, OptionalInt.empty()), null)),
        "temps");
  }

  @Test
  void roundingIsAppliedToDailyHddNotMeanTemperature() {
    // decimals=0, Tmax 10.0 / Tmin 9.0 -> mean 9.5 -> HDD 8.5.
    // HDD rounded HALF_UP -> 9 (31 x 9 = 279); mean rounded HALF_UP -> 10 -> HDD 8 (248).
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 10.0, 9.0);
    assertEquals(
        279.0, SettlementIndex.indexHddDegC(januaryCall(JAN_2001, OptionalInt.of(0)), temps));
  }

  @Test
  void rounding_zeroHddDaysStayZero() {
    // mean above base every day -> index 0.0 (positive zero) with or without rounding
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31), 25.0, 20.0);
    assertEquals(
        0.0, SettlementIndex.indexHddDegC(januaryCall(JAN_2001, OptionalInt.of(2)), temps));
    assertEquals(
        0.0, SettlementIndex.indexHddDegC(januaryCall(JAN_2001, OptionalInt.empty()), temps));
  }

  // --- AC8 (index level): missing day -> TemperatureDataException naming the date ---

  @Test
  void ac8_missingLeapDayInAccrual_exceptionNamesDate() {
    List<DailyTemp> days =
        constantDays(LocalDate.of(1991, 11, 1), LocalDate.of(1992, 3, 31), 17.0, 17.0);
    days.removeIf(d -> d.date().equals(LocalDate.of(1992, 2, 29)));
    DailyTemps temps = DailyTemps.of(days);
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () -> SettlementIndex.indexHddDegC(novMarCall1991(OptionalInt.of(1)), temps));
    assertMessageContains(ex, "1992-02-29");
  }

  @Test
  void ac8_missingLastDayOfMonth_exceptionNamesDate() {
    // data stops on 2001-01-30; the contract needs 2001-01-31
    DailyTemps temps = constant(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 30), 14.0, 6.0);
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () -> SettlementIndex.indexHddDegC(januaryCall(JAN_2001, OptionalInt.empty()), temps));
    assertMessageContains(ex, "2001-01-31");
  }

  @Test
  void ac8_emptySeries_exceptionNamesFirstAccrualDay() {
    TemperatureDataException ex =
        assertThrows(
            TemperatureDataException.class,
            () ->
                SettlementIndex.indexHddDegC(
                    januaryCall(JAN_2001, OptionalInt.empty()), DailyTemps.of(List.of())));
    assertMessageContains(ex, "2001-01-01");
  }
}
