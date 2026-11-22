package com.weatherderivatives.pricer.api;

/**
 * One shifted season.
 *
 * @param seasonYear season label
 * @param indexHddDegC shifted settlement index, °C·day
 * @param payoffGbp payoff on the shifted index, GBP
 * @param seasonMeanTempC the season's mean temperature before the shift, °C
 * @param shiftDegC shift added to every day, °C
 */
public record DetrendedSeasonDto(
    int seasonYear,
    double indexHddDegC,
    double payoffGbp,
    double seasonMeanTempC,
    double shiftDegC) {}
