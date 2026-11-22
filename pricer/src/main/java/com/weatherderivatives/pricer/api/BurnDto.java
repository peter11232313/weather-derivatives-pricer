package com.weatherderivatives.pricer.api;

/**
 * Burn summary.
 *
 * @param priceGbp burn price (mean payoff), GBP
 * @param stdErrorGbp standard error of the burn price, GBP
 * @param payoffStdDevGbp sample standard deviation of payoffs, GBP
 * @param minPayoffGbp smallest payoff, GBP
 * @param maxPayoffGbp largest payoff, GBP
 * @param meanIndexHddDegC mean settlement index, °C·day
 */
public record BurnDto(
    double priceGbp,
    double stdErrorGbp,
    double payoffStdDevGbp,
    double minPayoffGbp,
    double maxPayoffGbp,
    double meanIndexHddDegC) {}
