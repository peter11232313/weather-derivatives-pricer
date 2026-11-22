package com.weatherderivatives.pricer.api;

/**
 * Normal-model price.
 *
 * @param priceGbp expected payoff under the fitted normal, GBP
 * @param meanIndexHddDegC fitted mean, °C·day
 * @param sdIndexHddDegC fitted standard deviation, °C·day
 */
public record NormalDto(double priceGbp, double meanIndexHddDegC, double sdIndexHddDegC) {}
