package com.weatherderivatives.pricer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Spec 005 Decision 4: the in-house standard normal CDF {@code Φ} (Cody's erfc) and density {@code
 * φ}. Parity at 1e-9 needs {@code Φ(d) + Φ(−d) = 1} to about 1e-13, so the CDF is checked to near
 * machine precision.
 *
 * <p>Reference values are the correctly rounded doubles of {@code 0.5 · erfc(−x / √2)}, computed
 * with an 80+ digit Taylor series of erf in Python {@code decimal} and cross-checked against {@code
 * math.erfc}.
 */
class NormalDistributionTest {

  /** Relative tolerance for the CDF reference values. */
  private static final double REL_TOL = 1e-14;

  private static void assertRelative(double expected, double actual, double relTol) {
    assertTrue(
        Math.abs(actual - expected) <= relTol * Math.abs(expected),
        () -> "expected " + expected + " (rel " + relTol + ") but was " + actual);
  }

  // --- CDF known answers ---

  @Test
  void cdf_atZero_isExactlyOneHalf() {
    assertEquals(0.5, NormalDistribution.cdf(0.0));
  }

  @Test
  void cdf_knownAnswers_relative1e14() {
    // 0.84134474606854294858... / 0.15865525393145705141...
    assertRelative(0.8413447460685429, NormalDistribution.cdf(1.0), REL_TOL);
    assertRelative(0.15865525393145705, NormalDistribution.cdf(-1.0), REL_TOL);
    // 0.97500210485177956586...
    assertRelative(0.9750021048517795, NormalDistribution.cdf(1.96), REL_TOL);
    // 2.86651571879193911673...e-7 (left tail: needs relative, not just absolute, accuracy)
    assertRelative(2.866515718791939e-7, NormalDistribution.cdf(-5.0), REL_TOL);
    // 7.61985302416052606597...e-24
    assertRelative(7.619853024160526e-24, NormalDistribution.cdf(-10.0), REL_TOL);
  }

  @Test
  void cdf_symmetry_phiXPlusPhiMinusXIsOne_within1e15_onGridMinus8To8() {
    for (int i = -800; i <= 800; i++) {
      double x = i * 0.01;
      double sum = NormalDistribution.cdf(x) + NormalDistribution.cdf(-x);
      assertEquals(1.0, sum, 1e-15, "x = " + x);
    }
  }

  @Test
  void cdf_monotoneNonDecreasing_acrossCodyBreakpoints() {
    // Cody's erfc switches approximation at |z| = 0.5 and |z| = 4, i.e. x = ±0.5·√2 and ±4·√2.
    double[] breakpoints = {
      -4.0 * Math.sqrt(2.0), -0.5 * Math.sqrt(2.0), 0.5 * Math.sqrt(2.0), 4.0 * Math.sqrt(2.0)
    };
    for (double b : breakpoints) {
      double previous = NormalDistribution.cdf(b - 1e-4);
      for (int i = -999; i <= 1000; i++) {
        double x = b + i * 1e-7;
        double current = NormalDistribution.cdf(x);
        assertTrue(
            current >= previous,
            "not monotone near breakpoint "
                + b
                + " at x = "
                + x
                + ": "
                + current
                + " < "
                + previous);
        previous = current;
      }
    }
    // and a coarse sweep over the whole range
    double previous = NormalDistribution.cdf(-12.0);
    for (int i = -11_999; i <= 12_000; i++) {
      double x = i * 1e-3;
      double current = NormalDistribution.cdf(x);
      assertTrue(current >= previous, "not monotone at x = " + x);
      previous = current;
    }
  }

  @Test
  void cdf_valuesStayInUnitInterval() {
    for (int i = -4000; i <= 4000; i++) {
      double x = i * 0.01;
      double p = NormalDistribution.cdf(x);
      assertTrue(p >= 0.0 && p <= 1.0, "cdf(" + x + ") = " + p);
    }
  }

  // --- CDF edge cases: far tails, infinities, NaN ---

  @Test
  void cdf_farLeftTail_underflowsToZeroOrTiny_neverNegative() {
    // true value ~3.6e-350 is below the smallest subnormal, so 0.0 (or a subnormal) is right
    double p = NormalDistribution.cdf(-40.0);
    assertTrue(p >= 0.0 && p < 1e-300, "cdf(-40) = " + p);
    assertEquals(1.0, NormalDistribution.cdf(40.0));
  }

  @Test
  void cdf_infinities_andNaN() {
    assertEquals(1.0, NormalDistribution.cdf(Double.POSITIVE_INFINITY));
    assertEquals(0.0, NormalDistribution.cdf(Double.NEGATIVE_INFINITY), 0.0);
    assertTrue(Double.isNaN(NormalDistribution.cdf(Double.NaN)));
  }

  // --- density ---

  @Test
  void pdf_atZero_isOneOverSqrtTwoPi() {
    // 1 / sqrt(2π) = 0.39894228040143267794...
    assertEquals(0.3989422804014327, NormalDistribution.pdf(0.0), 1e-16);
  }

  @Test
  void pdf_atOne_knownAnswer() {
    // exp(-1/2) / sqrt(2π) = 0.24197072451914337
    assertRelative(0.24197072451914337, NormalDistribution.pdf(1.0), REL_TOL);
  }

  @Test
  void pdf_isSymmetric() {
    for (int i = 0; i <= 1000; i++) {
      double x = i * 0.01;
      assertEquals(NormalDistribution.pdf(x), NormalDistribution.pdf(-x), 0.0, "x = " + x);
    }
  }

  @Test
  void pdf_infinitiesAreZero_andNaNIsNaN() {
    assertEquals(0.0, NormalDistribution.pdf(Double.POSITIVE_INFINITY), 0.0);
    assertEquals(0.0, NormalDistribution.pdf(Double.NEGATIVE_INFINITY), 0.0);
    assertTrue(Double.isNaN(NormalDistribution.pdf(Double.NaN)));
  }
}
