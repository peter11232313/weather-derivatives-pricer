package com.weatherderivatives.pricer.api;

/**
 * Normal-model expected payoff.
 *
 * @param expectedPayoffGbp expected payoff under the fitted normal, undiscounted, no risk loading,
 *     GBP
 * @param meanIndexHddDegC fitted mean, °C·day
 * @param sdIndexHddDegC fitted standard deviation, °C·day
 */
public record NormalDto(double expectedPayoffGbp, double meanIndexHddDegC, double sdIndexHddDegC) {}
