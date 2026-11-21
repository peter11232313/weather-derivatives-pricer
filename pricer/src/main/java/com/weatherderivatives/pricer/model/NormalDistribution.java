package com.weatherderivatives.pricer.model;

/**
 * Standard normal distribution: cumulative distribution function and density.
 *
 * <p>The CDF is {@code Φ(x) = erfc(−x / √2) / 2}, with {@code erfc} from W. J. Cody's rational
 * Chebyshev approximations ("Rational Chebyshev approximations for the error function", Math. Comp.
 * 23 (1969) 631–637; netlib SPECFUN routine {@code CALERF}). The approximation is accurate to a few
 * units in the last place in all three of its intervals ({@code |u| ≤ 0.46875}, {@code ≤ 4}, {@code
 * > 4}), including the far left tail, where {@code Φ} is computed directly rather than as {@code 1
 * − Φ(−x)}. Written in-house rather than taken from a library to avoid a dependency for one
 * function (spec 005, decision 4).
 */
final class NormalDistribution {

  /** {@code 1 / √(2π)}. */
  private static final double INV_SQRT_2PI = 0.3989422804014327;

  /** {@code 1 / √2}. */
  private static final double INV_SQRT_2 = 0.7071067811865476;

  /** {@code 1 / √π}. */
  private static final double INV_SQRT_PI = 0.5641895835477563;

  private static final double THRESHOLD = 0.46875;

  /** Above this, {@code erfc(u)} underflows to 0 in double precision. */
  private static final double U_BIG = 26.543;

  private static final double[] A = {
    3.16112374387056560e00,
    1.13864154151050156e02,
    3.77485237685302021e02,
    3.20937758913846947e03,
    1.85777706184603153e-1
  };
  private static final double[] B = {
    2.36012909523441209e01, 2.44024637934444173e02, 1.28261652607737228e03, 2.84423683343917062e03
  };
  private static final double[] C = {
    5.64188496988670089e-1,
    8.88314979438837594e00,
    6.61191906371416295e01,
    2.98635138197400131e02,
    8.81952221241769090e02,
    1.71204761263407058e03,
    2.05107837782607147e03,
    1.23033935479799725e03,
    2.15311535474403846e-8
  };
  private static final double[] D = {
    1.57449261107098347e01,
    1.17693950891312499e02,
    5.37181101862009858e02,
    1.62138957456669019e03,
    3.29079923573345963e03,
    4.36261909014324716e03,
    3.43936767414372164e03,
    1.23033935480374942e03
  };
  private static final double[] P = {
    3.05326634961232344e-1,
    3.60344899949804439e-1,
    1.25781726111229246e-1,
    1.60837851487422766e-2,
    6.58749161529837803e-4,
    1.63153871373020978e-2
  };
  private static final double[] Q = {
    2.56852019228982242e00,
    1.87295284992346725e00,
    5.27905102951428412e-1,
    6.05183413124413191e-2,
    2.33520497626869185e-3
  };

  private NormalDistribution() {}

  /**
   * Standard normal cumulative distribution function.
   *
   * @param x any value
   * @return {@code Φ(x)} in [0, 1]; 0 at −∞, 1 at +∞, NaN for NaN
   */
  static double cdf(double x) {
    return 0.5 * erfc(-x * INV_SQRT_2);
  }

  /**
   * Standard normal density.
   *
   * @param x any value
   * @return {@code φ(x) = exp(−x²/2) / √(2π)}; 0 at ±∞, NaN for NaN
   */
  static double pdf(double x) {
    return INV_SQRT_2PI * Math.exp(-0.5 * x * x);
  }

  /** Complementary error function (Cody, CALERF with JINT = 1). */
  private static double erfc(double u) {
    double y = Math.abs(u);
    if (y <= THRESHOLD) {
      double ysq = y * y;
      double num = A[4] * ysq;
      double den = ysq;
      for (int i = 0; i < 3; i++) {
        num = (num + A[i]) * ysq;
        den = (den + B[i]) * ysq;
      }
      return 1.0 - u * (num + A[3]) / (den + B[3]);
    }
    double result;
    if (y <= 4.0) {
      double num = C[8] * y;
      double den = y;
      for (int i = 0; i < 7; i++) {
        num = (num + C[i]) * y;
        den = (den + D[i]) * y;
      }
      result = scaledByGaussian(y, (num + C[7]) / (den + D[7]));
    } else if (y >= U_BIG) {
      result = 0.0;
    } else {
      double ysq = 1.0 / (y * y);
      double num = P[5] * ysq;
      double den = ysq;
      for (int i = 0; i < 4; i++) {
        num = (num + P[i]) * ysq;
        den = (den + Q[i]) * ysq;
      }
      double r = ysq * (num + P[4]) / (den + Q[4]);
      result = scaledByGaussian(y, (INV_SQRT_PI - r) / y);
    }
    // NaN fails every comparison above and propagates through the arithmetic.
    return u < 0 ? 2.0 - result : result;
  }

  /**
   * Returns {@code exp(−y²) · r}, splitting {@code y²} so the exponent is computed accurately:
   * {@code y = s + (y − s)} with {@code s} = y truncated to a multiple of 1/16.
   */
  private static double scaledByGaussian(double y, double r) {
    double s = Math.floor(y * 16.0) / 16.0;
    double del = (y - s) * (y + s);
    return Math.exp(-s * s) * Math.exp(-del) * r;
  }
}
