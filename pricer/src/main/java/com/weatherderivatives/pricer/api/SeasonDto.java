package com.weatherderivatives.pricer.api;

/**
 * One season's settlement index and payoff.
 *
 * @param seasonYear season label (first accrual year)
 * @param indexHddDegC settlement index, °C·day
 * @param payoffGbp payoff, GBP
 */
public record SeasonDto(int seasonYear, double indexHddDegC, double payoffGbp) {}
