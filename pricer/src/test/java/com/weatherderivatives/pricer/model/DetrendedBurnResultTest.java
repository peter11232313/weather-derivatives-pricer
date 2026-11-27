package com.weatherderivatives.pricer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * Spec 006 amendment C: trend significance on {@link DetrendedBurnResult} ({@code slopeTStat()},
 * {@code slopeSignificant()}). Results are built by hand from season means, so every number is a
 * hand calculation; all inputs are dyadic, so the standard error and t are exact in binary.
 */
class DetrendedBurnResultTest {

  /**
   * A result with seasons {@code firstSeasonYear, firstSeasonYear + 1, ...} at the given means,
   * zero shift, and the given slope; the burn is a placeholder (not used by the methods tested).
   */
  private static DetrendedBurnResult result(
      int firstSeasonYear, double slopeDegCPerYear, double... seasonMeansC) {
    List<SeasonTrend> trends = new ArrayList<>();
    List<SeasonOutcome> outcomes = new ArrayList<>();
    for (int k = 0; k < seasonMeansC.length; k++) {
      trends.add(new SeasonTrend(firstSeasonYear + k, seasonMeansC[k], 0.0));
      outcomes.add(new SeasonOutcome(firstSeasonYear + k, 0.0, 0.0));
    }
    BurnResult burn = new BurnResult(outcomes, 0.0, 0.0, 0.0, 0.0, 0.0);
    return new DetrendedBurnResult(
        burn, slopeDegCPerYear, firstSeasonYear + seasonMeansC.length, trends);
  }

  @Test
  void amendmentC_significantTStat_isTwo() {
    assertEquals(2.0, DetrendedBurnResult.SIGNIFICANT_T_STAT, 0.0);
  }

  // ---------------------------------------------------------------------------------------------
  // t = slope / SE; significant iff |t| >= 2, true at exactly 2
  // ---------------------------------------------------------------------------------------------

  @Test
  void amendmentC_sixSeasons_slopeHalf_seQuarter_tStatExactlyTwo_significant() {
    // Spec 006 amendment C decision: years 2001..2006 -> x = -2.5..2.5, Sxx = 17.5; mean 10.0;
    // Sxy = 8.75 -> slope 0.5. Fitted 8.75, 9.25, ..., 11.25 -> residuals 0.25, 0.5, -1.75, 1.0,
    // 0, 0 -> SSres = 4.375; SE = sqrt(4.375 / 4 / 17.5) = sqrt(0.0625) = 0.25; t = 0.5 / 0.25 = 2.
    DetrendedBurnResult r = result(2001, 0.5, 9.0, 9.75, 8.0, 11.25, 10.75, 11.25);
    assertEquals(0.25, r.slopeStdErrDegCPerYear(), 0.0); // precondition: the known SE

    OptionalDouble t = r.slopeTStat();
    assertTrue(t.isPresent(), "slopeTStat empty");
    assertEquals(2.0, t.getAsDouble(), 0.0);
    assertTrue(r.slopeSignificant(), "|t| = 2 exactly must be significant");
  }

  @Test
  void amendmentC_sixSeasonsReversed_slopeMinusHalf_tStatExactlyMinusTwo_significant() {
    // mirror image of the case above: same residuals reversed, SE 0.25, t = -0.5 / 0.25 = -2
    DetrendedBurnResult r = result(2001, -0.5, 11.25, 10.75, 11.25, 8.0, 9.75, 9.0);
    assertEquals(0.25, r.slopeStdErrDegCPerYear(), 0.0); // precondition

    OptionalDouble t = r.slopeTStat();
    assertTrue(t.isPresent(), "slopeTStat empty");
    assertEquals(-2.0, t.getAsDouble(), 0.0);
    assertTrue(r.slopeSignificant(), "|t| = 2 exactly (negative slope) must be significant");
  }

  @Test
  void amendmentC_fiveSeasons_tStatRootThree_notSignificant() {
    // years 2001..2005 -> x = -2..2, Sxx = 10; mean 10.0; Sxy = 5 -> slope 0.5. Fitted 9, 9.5,
    // 10, 10.5, 11 -> residuals 0.5, -1, 0, 1, -0.5 -> SSres = 2.5; SE = sqrt(2.5 / 3 / 10) =
    // sqrt(1/12); t = 0.5 · sqrt(12) = sqrt(3) = 1.732... < 2.
    DetrendedBurnResult r = result(2001, 0.5, 9.5, 8.5, 10.0, 11.5, 10.5);
    assertEquals(Math.sqrt(1.0 / 12.0), r.slopeStdErrDegCPerYear(), 1e-15); // precondition

    OptionalDouble t = r.slopeTStat();
    assertTrue(t.isPresent(), "slopeTStat empty");
    assertEquals(Math.sqrt(3.0), t.getAsDouble(), 1e-12);
    assertFalse(r.slopeSignificant(), "|t| = sqrt(3) < 2 must not be significant");
  }

  // ---------------------------------------------------------------------------------------------
  // SE = 0: t empty; significant iff slope != 0
  // ---------------------------------------------------------------------------------------------

  @Test
  void amendmentC_perfectlyLinearTrend_seZero_tStatEmpty_significant() {
    // means 9.0 + 0.5k, k = 0..9, lie exactly on the line -> every residual 0 -> SE 0
    double[] means = new double[10];
    for (int k = 0; k < 10; k++) {
      means[k] = 9.0 + 0.5 * k;
    }
    DetrendedBurnResult r = result(2001, 0.5, means);
    assertEquals(0.0, r.slopeStdErrDegCPerYear(), 0.0); // precondition

    assertTrue(r.slopeTStat().isEmpty(), () -> "expected empty t but was " + r.slopeTStat());
    assertTrue(r.slopeSignificant(), "an exact non-zero trend must be significant");
  }

  @Test
  void amendmentC_flatHistory_slopeZero_seZero_tStatEmpty_notSignificant() {
    double[] means = new double[10];
    Arrays.fill(means, 10.0);
    DetrendedBurnResult r = result(2001, 0.0, means);
    assertEquals(0.0, r.slopeStdErrDegCPerYear(), 0.0); // precondition

    assertTrue(r.slopeTStat().isEmpty(), () -> "expected empty t but was " + r.slopeTStat());
    assertFalse(r.slopeSignificant(), "slope 0 with SE 0 must not be significant");
  }

  @Test
  void amendmentC_twoSeasons_seNaN_tStatEmpty() {
    // planner finding: slopeTStat() is empty when the standard error is NaN (fewer than 3 seasons)
    DetrendedBurnResult r = result(2001, 1.0, 9.0, 10.0);
    assertTrue(Double.isNaN(r.slopeStdErrDegCPerYear()), "precondition: SE is NaN");

    assertTrue(r.slopeTStat().isEmpty(), () -> "expected empty t but was " + r.slopeTStat());
    // quant review: two points are no evidence of a trend
    assertFalse(r.slopeSignificant(), "fewer than 3 seasons must not be significant");
  }

  @Test
  void amendmentC_linearTrendWithRoundingNoise_tStatEmpty_significant() {
    // review round 1: decimal means 10.1 + 0.1k are linear in exact arithmetic, but binary
    // rounding leaves residuals near 1e-15, so the standard error is tiny but not 0; t must still
    // be empty (an exact trend), not ~1e14.
    DetrendedBurnResult r =
        result(2001, 0.1, 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9, 11.0);
    double se = r.slopeStdErrDegCPerYear();
    assertTrue(se > 0.0 && se < 1e-12 * 0.1, () -> "precondition: tiny non-zero SE but was " + se);

    assertTrue(r.slopeTStat().isEmpty(), () -> "expected empty t but was " + r.slopeTStat());
    assertTrue(r.slopeSignificant(), "an exact non-zero trend must be significant");
  }
}
