package com.weatherderivatives.pricer.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.YearMonth;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Spec 003 acceptance criteria for long-side payoffs in GBP (AC3, AC4, example payoffs). */
class HddPayoffTest {

  private static final double TOL = 1e-9;

  private static final YearMonth JAN_2001 = YearMonth.of(2001, 1);

  private static HddContract contract(ContractType type, double strike, OptionalDouble capGbp) {
    return HddContract.of(type, JAN_2001, JAN_2001, strike, 1000.0, capGbp, OptionalInt.empty());
  }

  private static HddContract uncapped(ContractType type, double strike) {
    return contract(type, strike, OptionalDouble.empty());
  }

  // --- Example payoffs from the spec table (tick 1,000) ---

  @Test
  void call_k240_examplePayoffs() {
    // 1000 x max(0, I - 240): 248 -> 8,000; 279 -> 39,000; 217 -> 0
    HddContract c = uncapped(ContractType.CALL, 240.0);
    assertEquals(8_000.0, HddPayoff.payoffGbp(c, 248.0), TOL);
    assertEquals(39_000.0, HddPayoff.payoffGbp(c, 279.0), TOL);
    assertEquals(0.0, HddPayoff.payoffGbp(c, 217.0));
  }

  @Test
  void put_k260_examplePayoffs() {
    // 1000 x max(0, 260 - I): 248 -> 12,000; 279 -> 0; 217 -> 43,000
    HddContract c = uncapped(ContractType.PUT, 260.0);
    assertEquals(12_000.0, HddPayoff.payoffGbp(c, 248.0), TOL);
    assertEquals(0.0, HddPayoff.payoffGbp(c, 279.0));
    assertEquals(43_000.0, HddPayoff.payoffGbp(c, 217.0), TOL);
  }

  @Test
  void swap_k240_examplePayoffs() {
    // 1000 x (I - 240): 248 -> 8,000; 279 -> 39,000; 217 -> -23,000
    HddContract c = uncapped(ContractType.SWAP, 240.0);
    assertEquals(8_000.0, HddPayoff.payoffGbp(c, 248.0), TOL);
    assertEquals(39_000.0, HddPayoff.payoffGbp(c, 279.0), TOL);
    assertEquals(-23_000.0, HddPayoff.payoffGbp(c, 217.0), TOL);
  }

  @Test
  void fractionalIndexAndTick() {
    // tick 20: call K=240, I=250.17 -> 20 x 10.17 = 203.4
    HddContract c =
        HddContract.of(
            ContractType.CALL,
            JAN_2001,
            JAN_2001,
            240.0,
            20.0,
            OptionalDouble.empty(),
            OptionalInt.empty());
    assertEquals(203.4, HddPayoff.payoffGbp(c, 250.17), TOL);
  }

  @Test
  void put_zeroIndex_paysTickTimesStrike() {
    // warmest possible season: I = 0 -> 1000 x 260 = 260,000 (put payoff bounded by tick x K)
    assertEquals(260_000.0, HddPayoff.payoffGbp(uncapped(ContractType.PUT, 260.0), 0.0), TOL);
  }

  // --- Caps on call / put: min(C, ...) ---

  @Test
  void call_cap30000_capsOnlyAboveCap() {
    // 39,000 -> 30,000; 8,000 unchanged; 0 unchanged
    HddContract c = contract(ContractType.CALL, 240.0, OptionalDouble.of(30_000.0));
    assertEquals(30_000.0, HddPayoff.payoffGbp(c, 279.0), TOL);
    assertEquals(8_000.0, HddPayoff.payoffGbp(c, 248.0), TOL);
    assertEquals(0.0, HddPayoff.payoffGbp(c, 217.0));
    // exactly at the cap: 1000 x (270 - 240) = 30,000
    assertEquals(30_000.0, HddPayoff.payoffGbp(c, 270.0), TOL);
  }

  @Test
  void put_cap10000_capsOnlyAboveCap() {
    // K=260: I=217 -> 43,000 -> 10,000; I=255 -> 5,000 unchanged; I=279 -> 0
    HddContract c = contract(ContractType.PUT, 260.0, OptionalDouble.of(10_000.0));
    assertEquals(10_000.0, HddPayoff.payoffGbp(c, 217.0), TOL);
    assertEquals(5_000.0, HddPayoff.payoffGbp(c, 255.0), TOL);
    assertEquals(0.0, HddPayoff.payoffGbp(c, 279.0));
  }

  // --- AC3: swap with cap C clamps below -C to -C and above C to C ---

  @Test
  void ac3_swapWithCap_clampsBelowMinusCapAndAboveCap() {
    // cap 10,000, K=240: I=279 -> +39,000 -> +10,000; I=217 -> -23,000 -> -10,000
    HddContract c = contract(ContractType.SWAP, 240.0, OptionalDouble.of(10_000.0));
    assertEquals(10_000.0, HddPayoff.payoffGbp(c, 279.0), TOL);
    assertEquals(-10_000.0, HddPayoff.payoffGbp(c, 217.0), TOL);
  }

  @Test
  void ac3_swapWithCap_insideBandUnchangedAndBoundariesExact() {
    HddContract c = contract(ContractType.SWAP, 240.0, OptionalDouble.of(10_000.0));
    // inside [-C, C]: 248 -> 8,000; 235 -> -5,000
    assertEquals(8_000.0, HddPayoff.payoffGbp(c, 248.0), TOL);
    assertEquals(-5_000.0, HddPayoff.payoffGbp(c, 235.0), TOL);
    // exactly at +/-C: 250 -> 10,000; 230 -> -10,000
    assertEquals(10_000.0, HddPayoff.payoffGbp(c, 250.0), TOL);
    assertEquals(-10_000.0, HddPayoff.payoffGbp(c, 230.0), TOL);
  }

  // --- AC4: I == K -> call, put and swap all pay exactly 0.0 ---

  @ParameterizedTest
  @EnumSource(ContractType.class)
  void ac4_indexEqualsStrike_paysExactlyZero(ContractType type) {
    // assertEquals(double, double) without delta compares bits, so -0.0 fails.
    assertEquals(0.0, HddPayoff.payoffGbp(uncapped(type, 240.0), 240.0));
    assertEquals(0.0, HddPayoff.payoffGbp(contract(type, 240.0, OptionalDouble.of(5.0)), 240.0));
    // strike 0 and index 0
    assertEquals(0.0, HddPayoff.payoffGbp(uncapped(type, 0.0), 0.0));
    // non-integral strike
    assertEquals(0.0, HddPayoff.payoffGbp(uncapped(type, 250.17), 250.17));
  }

  @Test
  void outOfTheMoney_callAndPayPositiveZero() {
    // call with I < K and put with I > K must be +0.0, not -0.0
    assertEquals(0.0, HddPayoff.payoffGbp(uncapped(ContractType.CALL, 240.0), 100.0));
    assertEquals(0.0, HddPayoff.payoffGbp(uncapped(ContractType.PUT, 240.0), 300.0));
  }

  @Test
  void negativeIndex_throwsIllegalArgumentExceptionNamingField() {
    // an HDD index is a sum of max(0, ...) terms and can never be negative
    for (ContractType type : ContractType.values()) {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> HddPayoff.payoffGbp(uncapped(type, 240.0), -0.5));
      assertTrue(ex.getMessage().contains("indexHddDegC"), ex.getMessage());
    }
  }

  @Test
  void nullContract_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> HddPayoff.payoffGbp(null, 240.0));
  }

  // --- Non-finite index -> IllegalArgumentException ---

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
  void nonFiniteIndex_throwsIllegalArgumentException(double indexHddDegC) {
    for (ContractType type : ContractType.values()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> HddPayoff.payoffGbp(uncapped(type, 240.0), indexHddDegC),
          () -> type + " with index " + indexHddDegC);
    }
  }
}
