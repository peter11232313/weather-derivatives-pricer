package com.weatherderivatives.pricer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weatherderivatives.pricer.contract.ContractType;
import com.weatherderivatives.pricer.contract.HddContract;
import com.weatherderivatives.pricer.contract.HddPayoff;
import java.time.YearMonth;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spec 005 acceptance criteria 1-6 for the closed-form expected payoff under a fitted normal.
 *
 * <p>Notation as in the spec: {@code I ~ N(μ, σ²)}, {@code d = (μ − K) / σ}, {@code c(K) = (μ − K)
 * Φ(d) + σ φ(d)}, {@code p(K) = (K − μ) Φ(−d) + σ φ(d)}, {@code L = cap / tick}.
 */
class IndexDistributionPricingTest {

  /** Spec tolerance for the worked-example table. */
  private static final double TABLE_TOL = 1e-6;

  /** Spec tolerance for put-call parity. */
  private static final double PARITY_TOL = 1e-9;

  /** Slack for "never above the cap": allows only floating-point rounding, not model error. */
  private static final double CAP_SLACK = 1e-9;

  private static final double MU = 400.0;
  private static final double SIGMA = 50.0;
  private static final double TICK = 20.0;

  private static HddContract january(
      ContractType type, double strike, double tick, OptionalDouble capGbp) {
    YearMonth jan2010 = YearMonth.of(2010, 1);
    return HddContract.of(type, jan2010, jan2010, strike, tick, capGbp, OptionalInt.empty());
  }

  private static HddContract january(ContractType type, double strike, OptionalDouble capGbp) {
    return january(type, strike, TICK, capGbp);
  }

  private static double price(HddContract c, double mu, double sigma) {
    return IndexDistributionPricing.expectedPayoffGbp(c, mu, sigma);
  }

  private static void assertMessageContains(Throwable ex, String expected) {
    String msg = ex.getMessage();
    assertTrue(
        msg != null && msg.contains(expected),
        () -> "expected message to contain '" + expected + "' but was: " + msg);
  }

  // ---------------------------------------------------------------------------------------------
  // AC1: worked example, μ = 400, σ = 50, tick £20 (tolerance 1e-6)
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac1_workedExample_callK400_398_942280() {
    // d = 0: 20 · 50 · φ(0) = 1000 · 0.3989422804014327 = 398.9422804014327
    assertEquals(
        398.942280,
        price(january(ContractType.CALL, 400.0, OptionalDouble.empty()), MU, SIGMA),
        TABLE_TOL);
  }

  @Test
  void ac1_workedExample_putK420_630_438837() {
    // d = -0.4: 20 · (20 · Φ(0.4) + 50 · φ(0.4)) = 630.4388369474531
    assertEquals(
        630.438837,
        price(january(ContractType.PUT, 420.0, OptionalDouble.empty()), MU, SIGMA),
        TABLE_TOL);
  }

  @Test
  void ac1_workedExample_callK400Cap500_201_145723() {
    // L = 25: 20 · (c(400) − c(425)) = 201.14572300012665
    assertEquals(
        201.145723,
        price(january(ContractType.CALL, 400.0, OptionalDouble.of(500.0)), MU, SIGMA),
        TABLE_TOL);
  }

  @Test
  void ac1_workedExample_putK420Cap500_279_503506() {
    // L = 25: 20 · (p(420) − p(395)) = 279.50350574273835
    assertEquals(
        279.503506,
        price(january(ContractType.PUT, 420.0, OptionalDouble.of(500.0)), MU, SIGMA),
        TABLE_TOL);
  }

  @Test
  void ac1_workedExample_swapK380_400_000000() {
    // 20 · (400 − 380) = 400
    assertEquals(
        400.0,
        price(january(ContractType.SWAP, 380.0, OptionalDouble.empty()), MU, SIGMA),
        TABLE_TOL);
  }

  @Test
  void ac1_workedExample_swapK380Cap500_149_495806() {
    // L = 25: 20 · ((400 − 380) − c(405) + p(355)) = 149.49580588195653
    assertEquals(
        149.495806,
        price(january(ContractType.SWAP, 380.0, OptionalDouble.of(500.0)), MU, SIGMA),
        TABLE_TOL);
  }

  // ---------------------------------------------------------------------------------------------
  // AC2: σ = 0 -> price equals HddPayoff at μ (exact)
  // ---------------------------------------------------------------------------------------------

  /** Exact numeric equality; delta 0 also accepts +0.0 vs -0.0. */
  private static void assertSigmaZeroEqualsPayoff(HddContract c, double mu, double expectedGbp) {
    assertEquals(expectedGbp, HddPayoff.payoffGbp(c, mu), 0.0, "HddPayoff sanity");
    assertEquals(HddPayoff.payoffGbp(c, mu), price(c, mu, 0.0), 0.0, c.toString());
  }

  @Test
  void ac2_sigmaZero_callEqualsPayoffAtMean_withAndWithoutCap() {
    // call K=380 on μ=400: 20 · 20 = 400; cap 300 -> 300
    assertSigmaZeroEqualsPayoff(january(ContractType.CALL, 380.0, OptionalDouble.empty()), MU, 400);
    assertSigmaZeroEqualsPayoff(
        january(ContractType.CALL, 380.0, OptionalDouble.of(300.0)), MU, 300);
    // out of the money and at the money pay 0
    assertSigmaZeroEqualsPayoff(january(ContractType.CALL, 420.0, OptionalDouble.empty()), MU, 0);
    assertSigmaZeroEqualsPayoff(january(ContractType.CALL, 400.0, OptionalDouble.empty()), MU, 0);
  }

  @Test
  void ac2_sigmaZero_putEqualsPayoffAtMean_withAndWithoutCap() {
    // put K=420 on μ=400: 20 · 20 = 400; cap 300 -> 300; K=380 -> 0
    assertSigmaZeroEqualsPayoff(january(ContractType.PUT, 420.0, OptionalDouble.empty()), MU, 400);
    assertSigmaZeroEqualsPayoff(
        january(ContractType.PUT, 420.0, OptionalDouble.of(300.0)), MU, 300);
    assertSigmaZeroEqualsPayoff(january(ContractType.PUT, 380.0, OptionalDouble.empty()), MU, 0);
  }

  @Test
  void ac2_sigmaZero_swapEqualsPayoffAtMean_withAndWithoutCap() {
    // swap K=380: +400, capped +300; swap K=420: -400, capped -300
    assertSigmaZeroEqualsPayoff(january(ContractType.SWAP, 380.0, OptionalDouble.empty()), MU, 400);
    assertSigmaZeroEqualsPayoff(
        january(ContractType.SWAP, 380.0, OptionalDouble.of(300.0)), MU, 300);
    assertSigmaZeroEqualsPayoff(
        january(ContractType.SWAP, 420.0, OptionalDouble.empty()), MU, -400);
    assertSigmaZeroEqualsPayoff(
        january(ContractType.SWAP, 420.0, OptionalDouble.of(300.0)), MU, -300);
  }

  @Test
  void ac2_sigmaZero_zeroMeanIsAccepted() {
    // μ = 0 is a valid (non-negative) mean: put K=10 pays 20 · 10 = 200
    assertSigmaZeroEqualsPayoff(january(ContractType.PUT, 10.0, OptionalDouble.empty()), 0.0, 200);
  }

  @Test
  void sigmaSubnormal_isFiniteAndEqualsPayoffAtMean() {
    // σ = Double.MIN_VALUE: d = ±inf (or 0 at the money); must not produce NaN from inf · 0
    for (ContractType type : ContractType.values()) {
      for (double strike : new double[] {380.0, 400.0, 420.0}) {
        for (OptionalDouble cap :
            new OptionalDouble[] {OptionalDouble.empty(), OptionalDouble.of(300.0)}) {
          HddContract c = january(type, strike, cap);
          double p = price(c, MU, Double.MIN_VALUE);
          assertTrue(Double.isFinite(p), c + " -> " + p);
          assertEquals(HddPayoff.payoffGbp(c, MU), p, 1e-9, c.toString());
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // AC3: non-finite or negative μ / σ -> IAE naming the argument
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0})
  void ac3_invalidMean_throwsIaeNamingMeanIndexHddDegC(double mu) {
    HddContract c = january(ContractType.CALL, 400.0, OptionalDouble.empty());
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> price(c, mu, SIGMA));
    assertMessageContains(ex, "meanIndexHddDegC");
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0})
  void ac3_invalidSd_throwsIaeNamingSdIndexHddDegC(double sigma) {
    HddContract c = january(ContractType.CALL, 400.0, OptionalDouble.empty());
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> price(c, MU, sigma));
    assertMessageContains(ex, "sdIndexHddDegC");
  }

  @Test
  void nullContract_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> price(null, MU, SIGMA));
  }

  // ---------------------------------------------------------------------------------------------
  // AC4: put-call parity without a cap: call(K) − put(K) = t · (μ − K), tolerance 1e-9
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac4_putCallParity_severalStrikesIncludingFarOutOfTheMoney() {
    // K = 0 and 2000 are 8σ and 32σ away from μ = 400
    for (double k : new double[] {0, 100, 380, 400, 420, 700, 2000}) {
      double call = price(january(ContractType.CALL, k, OptionalDouble.empty()), MU, SIGMA);
      double put = price(january(ContractType.PUT, k, OptionalDouble.empty()), MU, SIGMA);
      assertEquals(TICK * (MU - k), call - put, PARITY_TOL, "K = " + k);
      // the uncapped swap is the same forward
      double swap = price(january(ContractType.SWAP, k, OptionalDouble.empty()), MU, SIGMA);
      assertEquals(TICK * (MU - k), swap, PARITY_TOL, "swap K = " + k);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // AC5: capped price <= cap and <= uncapped (call, put); swap within ±cap
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac5_cappedPrice_neverAboveCapNorUncapped_swapWithinPlusMinusCap() {
    // includes K − L < 0 (e.g. put K=100, cap 5000 -> L = 250) and deep ITM (call K=0, put K=2000).
    // Note: these bounds hold by construction (the implementation clamps capped prices), so this
    // test guards the clamp; the capped formulas themselves are checked by the AC1 known answers.
    double[] strikes = {0, 100, 380, 400, 420, 700, 2000};
    double[] caps = {1, 100, 500, 5000, 1e6};
    for (double k : strikes) {
      for (double cap : caps) {
        for (ContractType type : new ContractType[] {ContractType.CALL, ContractType.PUT}) {
          double uncapped = price(january(type, k, OptionalDouble.empty()), MU, SIGMA);
          double capped = price(january(type, k, OptionalDouble.of(cap)), MU, SIGMA);
          String where = type + " K=" + k + " cap=" + cap + ": " + capped;
          assertTrue(capped >= -CAP_SLACK, where);
          assertTrue(capped <= cap + CAP_SLACK, where + " above cap");
          assertTrue(capped <= uncapped + CAP_SLACK, where + " above uncapped " + uncapped);
        }
        double swap = price(january(ContractType.SWAP, k, OptionalDouble.of(cap)), MU, SIGMA);
        String where = "SWAP K=" + k + " cap=" + cap + ": " + swap;
        assertTrue(Math.abs(swap) <= cap + CAP_SLACK, where);
      }
    }
  }

  @Test
  void ac5_deepInTheMoneyCappedCall_isTheCap() {
    // call K=0, cap 500 (L = 25): P(I < 25) = Φ(-7.5) ~ 3e-14, so price = 500 within 1e-6
    assertEquals(
        500.0,
        price(january(ContractType.CALL, 0.0, OptionalDouble.of(500.0)), MU, SIGMA),
        TABLE_TOL);
  }

  @Test
  void capWhoseIndexWidthOverflows_equalsUncapped() {
    // cap 1e308 / tick 1e-10 -> L = 1e318 = +inf; c(K + L), p(K − L) must be 0, not NaN
    double tick = 1e-10;
    for (ContractType type : ContractType.values()) {
      for (double k : new double[] {380.0, 400.0, 420.0}) {
        double uncapped = price(january(type, k, tick, OptionalDouble.empty()), MU, SIGMA);
        double capped = price(january(type, k, tick, OptionalDouble.of(1e308)), MU, SIGMA);
        assertTrue(Double.isFinite(capped), type + " K=" + k + " -> " + capped);
        assertEquals(uncapped, capped, 1e-15 * Math.max(1.0, Math.abs(uncapped)), type + " K=" + k);
      }
    }
  }

  @Test
  void capFiniteButStrikePlusCapWidthOverflows_equalsUncapped() {
    // K = 1e307, cap 1.7e308, tick 1: L = 1.7e308 is finite but K + L overflows to +inf; the
    // price must equal the uncapped one, not NaN (quant review).
    for (ContractType type : ContractType.values()) {
      double uncapped = price(january(type, 1e307, 1.0, OptionalDouble.empty()), MU, SIGMA);
      double capped = price(january(type, 1e307, 1.0, OptionalDouble.of(1.7e308)), MU, SIGMA);
      assertTrue(Double.isFinite(capped), type + " -> " + capped);
      assertEquals(uncapped, capped, 1e-15 * Math.max(1.0, Math.abs(uncapped)), type.toString());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // AC6: large σ, at-the-money call grows like t · σ · φ(0)
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac6_largeSigmaAtTheMoneyCall_79788_456() {
    // 20 · 1e4 · 0.3989422804014327 = 79,788.45608028655
    HddContract atm = january(ContractType.CALL, 400.0, OptionalDouble.empty());
    double p4 = price(atm, MU, 1e4);
    assertEquals(79_788.456, p4, 1e-3);
    // exactly linear in σ at the money: 10× σ -> 10× price
    double p3 = price(atm, MU, 1e3);
    assertEquals(10.0, p4 / p3, 1e-12);
  }

  // ---------------------------------------------------------------------------------------------
  // Edge: deep out of the money (absolute accuracy only, per Decision 4)
  // ---------------------------------------------------------------------------------------------

  @Test
  void deepOutOfTheMoneyCall_isNonNegativeFiniteAndTiny() {
    // K = 1000 at μ = 400, σ = 50 (d = -12): true value 20 · 7.3e-33 ~ 1.46e-31
    double p = price(january(ContractType.CALL, 1000.0, OptionalDouble.empty()), MU, SIGMA);
    assertTrue(Double.isFinite(p) && p >= 0.0, "price = " + p);
    assertTrue(p < 1e-12, "price = " + p);
  }

  @Test
  void deepOutOfTheMoneyPut_isNonNegativeFiniteAndTiny() {
    // put K=0 at μ = 400, σ = 50 (d = 8): pays only if I < 0, value ~ 6e-14
    double p = price(january(ContractType.PUT, 0.0, OptionalDouble.empty()), MU, SIGMA);
    assertTrue(Double.isFinite(p) && p >= 0.0, "price = " + p);
    assertTrue(p < 1e-9, "price = " + p);
  }
}
