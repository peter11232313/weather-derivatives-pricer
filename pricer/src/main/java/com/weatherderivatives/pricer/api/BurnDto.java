package com.weatherderivatives.pricer.api;

/**
 * Burn summary.
 *
 * @param expectedPayoffGbp burn price: mean historical payoff, undiscounted, no risk loading, GBP
 * @param stdErrorGbp standard error of the mean payoff, GBP
 * @param payoffStdDevGbp sample standard deviation of payoffs, GBP
 * @param minPayoffGbp smallest payoff, GBP
 * @param maxPayoffGbp largest payoff, GBP
 * @param meanIndexHddDegC mean settlement index, °C·day
 */
public record BurnDto(
    double expectedPayoffGbp,
    double stdErrorGbp,
    double payoffStdDevGbp,
    double minPayoffGbp,
    double maxPayoffGbp,
    double meanIndexHddDegC) {}
