package com.weatherderivatives.pricer.api;

import java.util.List;

/**
 * Detrended results.
 *
 * @param targetSeasonYear the season the shifted history represents
 * @param slopeDegCPerYear fitted trend, °C per year
 * @param slopeStdErrDegCPerYear standard error of the slope, °C per year
 * @param seasons shifted seasons in year order
 * @param burn detrended burn
 * @param normal detrended normal-model expected payoff
 */
public record DetrendedDto(
    int targetSeasonYear,
    double slopeDegCPerYear,
    double slopeStdErrDegCPerYear,
    List<DetrendedSeasonDto> seasons,
    BurnDto burn,
    NormalDto normal) {}
