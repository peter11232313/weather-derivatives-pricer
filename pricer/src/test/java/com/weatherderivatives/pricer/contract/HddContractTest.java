package com.weatherderivatives.pricer.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weatherderivatives.pricer.index.HddIndex;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Spec 003 acceptance criteria for {@link HddContract} terms (AC5 dates, AC10 validation). */
class HddContractTest {

  private static final YearMonth NOV_1991 = YearMonth.of(1991, 11);
  private static final YearMonth MAR_1992 = YearMonth.of(1992, 3);

  private static void assertMessageContains(Throwable ex, String expected) {
    String msg = ex.getMessage();
    assertTrue(
        msg != null && msg.contains(expected),
        () -> "expected message to contain '" + expected + "' but was: " + msg);
  }

  /** A valid Nov-Mar call, so each invalid case varies exactly one field. */
  private static HddContract contract(
      ContractType type,
      YearMonth firstMonth,
      YearMonth lastMonth,
      double baseC,
      double strikeHddDegC,
      double tickGbpPerDegC,
      OptionalDouble capGbp,
      OptionalInt dailyHddDecimals) {
    return new HddContract(
        type,
        firstMonth,
        lastMonth,
        baseC,
        strikeHddDegC,
        tickGbpPerDegC,
        capGbp,
        dailyHddDecimals);
  }

  private static HddContract novMar1991() {
    return HddContract.of(
        ContractType.CALL,
        NOV_1991,
        MAR_1992,
        1500.0,
        20.0,
        OptionalDouble.of(1_000_000.0),
        OptionalInt.of(1));
  }

  // --- AC10: invalid terms -> IllegalArgumentException naming the field ---

  private static Arguments invalid(String fieldName, Executable construct) {
    return Arguments.of(fieldName, construct);
  }

  static Stream<Arguments> invalidTerms() {
    ContractType c = ContractType.CALL;
    OptionalDouble noCap = OptionalDouble.empty();
    OptionalInt noRound = OptionalInt.empty();
    return Stream.of(
        // firstMonth > lastMonth (Mar 1992 .. Nov 1991)
        invalid(
            "firstMonth",
            () -> contract(c, MAR_1992, NOV_1991, 18.0, 1500.0, 20.0, noCap, noRound)),
        // 13 months: Nov 1991 .. Nov 1992
        invalid(
            "lastMonth",
            () ->
                contract(c, NOV_1991, YearMonth.of(1992, 11), 18.0, 1500.0, 20.0, noCap, noRound)),
        // 24 months
        invalid(
            "lastMonth",
            () ->
                contract(
                    c,
                    YearMonth.of(2000, 1),
                    YearMonth.of(2001, 12),
                    18.0,
                    1500.0,
                    20.0,
                    noCap,
                    noRound)),
        invalid(
            "baseC",
            () -> contract(c, NOV_1991, MAR_1992, Double.NaN, 1500.0, 20.0, noCap, noRound)),
        invalid(
            "baseC",
            () ->
                contract(
                    c, NOV_1991, MAR_1992, Double.POSITIVE_INFINITY, 1500.0, 20.0, noCap, noRound)),
        invalid(
            "baseC",
            () ->
                contract(
                    c, NOV_1991, MAR_1992, Double.NEGATIVE_INFINITY, 1500.0, 20.0, noCap, noRound)),
        invalid(
            "strikeHddDegC",
            () -> contract(c, NOV_1991, MAR_1992, 18.0, -0.01, 20.0, noCap, noRound)),
        invalid(
            "strikeHddDegC",
            () -> contract(c, NOV_1991, MAR_1992, 18.0, Double.NaN, 20.0, noCap, noRound)),
        invalid(
            "strikeHddDegC",
            () ->
                contract(
                    c, NOV_1991, MAR_1992, 18.0, Double.POSITIVE_INFINITY, 20.0, noCap, noRound)),
        invalid(
            "tickGbpPerDegC",
            () -> contract(c, NOV_1991, MAR_1992, 18.0, 1500.0, 0.0, noCap, noRound)),
        invalid(
            "tickGbpPerDegC",
            () -> contract(c, NOV_1991, MAR_1992, 18.0, 1500.0, -0.0, noCap, noRound)),
        invalid(
            "tickGbpPerDegC",
            () -> contract(c, NOV_1991, MAR_1992, 18.0, 1500.0, -20.0, noCap, noRound)),
        invalid(
            "tickGbpPerDegC",
            () -> contract(c, NOV_1991, MAR_1992, 18.0, 1500.0, Double.NaN, noCap, noRound)),
        invalid(
            "tickGbpPerDegC",
            () ->
                contract(
                    c, NOV_1991, MAR_1992, 18.0, 1500.0, Double.POSITIVE_INFINITY, noCap, noRound)),
        invalid(
            "capGbp",
            () ->
                contract(
                    c, NOV_1991, MAR_1992, 18.0, 1500.0, 20.0, OptionalDouble.of(0.0), noRound)),
        invalid(
            "capGbp",
            () ->
                contract(
                    c, NOV_1991, MAR_1992, 18.0, 1500.0, 20.0, OptionalDouble.of(-0.0), noRound)),
        invalid(
            "capGbp",
            () ->
                contract(
                    c,
                    NOV_1991,
                    MAR_1992,
                    18.0,
                    1500.0,
                    20.0,
                    OptionalDouble.of(-30_000.0),
                    noRound)),
        invalid(
            "capGbp",
            () ->
                contract(
                    c,
                    NOV_1991,
                    MAR_1992,
                    18.0,
                    1500.0,
                    20.0,
                    OptionalDouble.of(Double.NaN),
                    noRound)),
        invalid(
            "capGbp",
            () ->
                contract(
                    c,
                    NOV_1991,
                    MAR_1992,
                    18.0,
                    1500.0,
                    20.0,
                    OptionalDouble.of(Double.POSITIVE_INFINITY),
                    noRound)),
        invalid(
            "dailyHddDecimals",
            () -> contract(c, NOV_1991, MAR_1992, 18.0, 1500.0, 20.0, noCap, OptionalInt.of(-1))),
        invalid(
            "dailyHddDecimals",
            () -> contract(c, NOV_1991, MAR_1992, 18.0, 1500.0, 20.0, noCap, OptionalInt.of(5))));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("invalidTerms")
  void ac10_invalidTerms_throwIllegalArgumentExceptionNamingField(
      String fieldName, Executable construct) {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, construct);
    // firstMonth > lastMonth and the 12-month span involve both months: either name is fine.
    if (fieldName.equals("firstMonth") || fieldName.equals("lastMonth")) {
      String msg = ex.getMessage();
      assertTrue(
          msg != null && (msg.contains("firstMonth") || msg.contains("lastMonth")),
          () -> "expected message to name firstMonth or lastMonth but was: " + msg);
    } else {
      assertMessageContains(ex, fieldName);
    }
  }

  @Test
  void ac10_boundaryTermsAreValid() {
    OptionalDouble noCap = OptionalDouble.empty();
    OptionalInt noRound = OptionalInt.empty();
    ContractType c = ContractType.CALL;
    // single month (firstMonth == lastMonth)
    assertDoesNotThrow(() ->
        contract(
            c, YearMonth.of(2001, 1), YearMonth.of(2001, 1), 18.0, 240.0, 1.0, noCap, noRound));
    // exactly 12 months, crossing a year: Nov 1991 .. Oct 1992
    assertDoesNotThrow(
        () -> contract(c, NOV_1991, YearMonth.of(1992, 10), 18.0, 240.0, 1.0, noCap, noRound));
    // strike 0 is allowed (>= 0); negative base is a finite base
    assertDoesNotThrow(() -> contract(c, NOV_1991, MAR_1992, -5.0, 0.0, 1.0, noCap, noRound));
    // tiny positive tick and cap
    assertDoesNotThrow(() ->
        contract(
            c,
            NOV_1991,
            MAR_1992,
            18.0,
            240.0,
            Double.MIN_VALUE,
            OptionalDouble.of(0.01),
            noRound));
    // decimals at both ends of 0..4
    assertDoesNotThrow(
        () -> contract(c, NOV_1991, MAR_1992, 18.0, 240.0, 1.0, noCap, OptionalInt.of(0)));
    assertDoesNotThrow(
        () -> contract(c, NOV_1991, MAR_1992, 18.0, 240.0, 1.0, noCap, OptionalInt.of(4)));
  }

  static Stream<Arguments> nullComponents() {
    OptionalDouble noCap = OptionalDouble.empty();
    OptionalInt noRound = OptionalInt.empty();
    ContractType c = ContractType.CALL;
    return Stream.of(
        invalid("type", () -> contract(null, NOV_1991, MAR_1992, 18.0, 1.0, 1.0, noCap, noRound)),
        invalid("firstMonth", () -> contract(c, null, MAR_1992, 18.0, 1.0, 1.0, noCap, noRound)),
        invalid("lastMonth", () -> contract(c, NOV_1991, null, 18.0, 1.0, 1.0, noCap, noRound)),
        invalid("capGbp", () -> contract(c, NOV_1991, MAR_1992, 18.0, 1.0, 1.0, null, noRound)),
        invalid(
            "dailyHddDecimals",
            () -> contract(c, NOV_1991, MAR_1992, 18.0, 1.0, 1.0, noCap, null)));
  }

  @ParameterizedTest(name = "[{index}] null {0}")
  @MethodSource("nullComponents")
  void ac10_nullComponent_throwsNullPointerExceptionNamingField(
      String fieldName, Executable construct) {
    NullPointerException ex = assertThrows(NullPointerException.class, construct);
    assertMessageContains(ex, fieldName);
  }

  // --- Factory of(): default base 18.0 ---

  @Test
  void of_usesDefaultBase18AndKeepsOtherTerms() {
    HddContract c = novMar1991();
    assertEquals(18.0, HddIndex.DEFAULT_BASE_C);
    assertEquals(18.0, c.baseC());
    assertEquals(ContractType.CALL, c.type());
    assertEquals(NOV_1991, c.firstMonth());
    assertEquals(MAR_1992, c.lastMonth());
    assertEquals(1500.0, c.strikeHddDegC());
    assertEquals(20.0, c.tickGbpPerDegC());
    assertEquals(OptionalDouble.of(1_000_000.0), c.capGbp());
    assertEquals(OptionalInt.of(1), c.dailyHddDecimals());
  }

  @Test
  void of_validatesLikeTheConstructor() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                HddContract.of(
                    ContractType.PUT,
                    NOV_1991,
                    MAR_1992,
                    -1.0,
                    20.0,
                    OptionalDouble.empty(),
                    OptionalInt.empty()));
    assertMessageContains(ex, "strikeHddDegC");
  }

  // --- AC5: Nov-Mar season 1991 -> 1991-11-01..1992-03-31; 1992 -> 1992-11-01..1993-03-31 ---

  @Test
  void ac5_novMarSeason1991_accrues1991_11_01to1992_03_31() {
    HddContract c = novMar1991();
    assertEquals(LocalDate.of(1991, 11, 1), c.accrualStart());
    assertEquals(LocalDate.of(1992, 3, 31), c.accrualEndInclusive());
    // forSeason of its own season year is the identity
    assertEquals(c, c.forSeason(1991));
  }

  @Test
  void ac5_novMarSeason1992_accrues1992_11_01to1993_03_31_otherTermsUnchanged() {
    HddContract c = novMar1991();
    HddContract s1992 = c.forSeason(1992);
    assertEquals(YearMonth.of(1992, 11), s1992.firstMonth());
    assertEquals(YearMonth.of(1993, 3), s1992.lastMonth());
    assertEquals(LocalDate.of(1992, 11, 1), s1992.accrualStart());
    assertEquals(LocalDate.of(1993, 3, 31), s1992.accrualEndInclusive());
    assertEquals(
        new HddContract(
            c.type(),
            YearMonth.of(1992, 11),
            YearMonth.of(1993, 3),
            c.baseC(),
            c.strikeHddDegC(),
            c.tickGbpPerDegC(),
            c.capGbp(),
            c.dailyHddDecimals()),
        s1992);
  }

  @Test
  void ac5_forSeason_shiftsBackwardsToEarlierYears() {
    // contract written for 1991, shifted back 11 years -> Nov 1980 .. Mar 1981
    HddContract s1980 = novMar1991().forSeason(1980);
    assertEquals(LocalDate.of(1980, 11, 1), s1980.accrualStart());
    assertEquals(LocalDate.of(1981, 3, 31), s1980.accrualEndInclusive());
  }

  @Test
  void ac5_decFebContractCrossingLeapFebruary_endsOn29th() {
    // Dec 2023 .. Feb 2024: 2024 is a leap year -> last day 2024-02-29
    HddContract c =
        HddContract.of(
            ContractType.SWAP,
            YearMonth.of(2023, 12),
            YearMonth.of(2024, 2),
            1200.0,
            10.0,
            OptionalDouble.empty(),
            OptionalInt.empty());
    assertEquals(LocalDate.of(2023, 12, 1), c.accrualStart());
    assertEquals(LocalDate.of(2024, 2, 29), c.accrualEndInclusive());
    // season 2024: Dec 2024 .. Feb 2025, non-leap -> 2025-02-28
    assertEquals(LocalDate.of(2024, 12, 1), c.forSeason(2024).accrualStart());
    assertEquals(LocalDate.of(2025, 2, 28), c.forSeason(2024).accrualEndInclusive());
    // season 1899: Feb 1900 is NOT leap (century rule) -> 1900-02-28
    assertEquals(LocalDate.of(1900, 2, 28), c.forSeason(1899).accrualEndInclusive());
    // season 1999: Feb 2000 IS leap (400-year rule) -> 2000-02-29
    assertEquals(LocalDate.of(2000, 2, 29), c.forSeason(1999).accrualEndInclusive());
  }
}
