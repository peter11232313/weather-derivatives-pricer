package com.weatherderivatives.pricer.model;

/**
 * One historical season of a burn analysis.
 *
 * @param seasonYear year of the season's first accrual day
 * @param indexHddDegC settlement index in °C·day
 * @param payoffGbp long-side payoff in GBP
 */
public record SeasonOutcome(int seasonYear, double indexHddDegC, double payoffGbp) {}
