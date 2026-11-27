package com.weatherderivatives.pricer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * Spec 006 amendment C: normal-fit diagnostics on {@link IndexDistributionResult} ({@code
 * seasonCount()}, {@code indexSkewness()}), on results built by hand from settlement index values.
 */
class IndexDistributionResultTest {

  /**
   * A result whose burn has one season per index value (years from 2001); the fitted mean, σ and
   * price are placeholders: the diagnostics read the burn's index values only.
   */
  private static IndexDistributionResult result(double... indexHddDegC) {
    List<SeasonOutcome> outcomes = new ArrayList<>();
    for (int k = 0; k < indexHddDegC.length; k++) {
      outcomes.add(new SeasonOutcome(2001 + k, indexHddDegC[k], 0.0));
    }
    BurnResult burn = new BurnResult(outcomes, 0.0, 0.0, 0.0, 0.0, 0.0);
    return new IndexDistributionResult(0.0, 0.0, 0.0, burn);
  }

  @Test
  void amendmentC_indexValues000010_skewnessRootFive_seasonCountFive() {
    // Spec 006 amendment C: mean 2, deviations -2, -2, -2, -2, 8.
    // m2 = (4 · 4 + 64) / 5 = 16; m3 = (4 · (-8) + 512) / 5 = 96; g1 = 96 / 16^1.5 = 1.5;
    // G1 = sqrt(5 · 4) / 3 · 1.5 = sqrt(20) / 2 = sqrt(5) = 2.2360680
    IndexDistributionResult r = result(0.0, 0.0, 0.0, 0.0, 10.0);

    assertEquals(5, r.seasonCount());
    OptionalDouble skew = r.indexSkewness();
    assertTrue(skew.isPresent(), "indexSkewness empty");
    assertEquals(Math.sqrt(5.0), skew.getAsDouble(), 1e-9);
  }

  @Test
  void amendmentC_mirroredValues_skewnessMinusRootFive() {
    // {0, 10, 10, 10, 10} = 10 − {10, 0, 0, 0, 0}: same G1 with the sign flipped
    IndexDistributionResult r = result(0.0, 10.0, 10.0, 10.0, 10.0);

    OptionalDouble skew = r.indexSkewness();
    assertTrue(skew.isPresent(), "indexSkewness empty");
    assertEquals(-Math.sqrt(5.0), skew.getAsDouble(), 1e-9);
  }

  @Test
  void amendmentC_symmetricWorkedExample_skewnessZero() {
    // spec 006 worked example indices: symmetric about 400 -> m3 = 0
    IndexDistributionResult r = result(360.0, 400.0, 400.0, 400.0, 440.0);

    assertEquals(5, r.seasonCount());
    OptionalDouble skew = r.indexSkewness();
    assertTrue(skew.isPresent(), "indexSkewness empty");
    assertEquals(0.0, skew.getAsDouble(), 1e-12);
  }

  @Test
  void amendmentC_allValuesEqual_sigmaZero_skewnessEmpty() {
    IndexDistributionResult r = result(400.0, 400.0, 400.0, 400.0, 400.0);

    assertEquals(5, r.seasonCount());
    assertTrue(
        r.indexSkewness().isEmpty(), () -> "expected empty skewness but was " + r.indexSkewness());
  }

  @Test
  void amendmentC_twoSeasons_skewnessEmpty() {
    // planner finding: empty when n < 3 (G1 divides by n − 2)
    IndexDistributionResult r = result(0.0, 10.0);

    assertEquals(2, r.seasonCount());
    assertTrue(
        r.indexSkewness().isEmpty(), () -> "expected empty skewness but was " + r.indexSkewness());
  }
}
