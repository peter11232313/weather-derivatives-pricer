package com.weatherderivatives.pricer.api;

/**
 * Normal-model expected payoff.
 *
 * @param expectedPayoffGbp expected payoff under the fitted normal, undiscounted, no risk loading,
 *     GBP
 * @param meanIndexHddDegC fitted mean, °C·day
 * @param sdIndexHddDegC fitted standard deviation, °C·day
 * @param seasonCount number of seasons the normal was fitted to (spec 006 amendment C)
 * @param indexSkewness adjusted Fisher–Pearson skewness of those index values, or null with all
 *     values equal (spec 006 amendment C)
 */
public record NormalDto(
    double expectedPayoffGbp,
    double meanIndexHddDegC,
    double sdIndexHddDegC,
    int seasonCount,
    Double indexSkewness) {}
