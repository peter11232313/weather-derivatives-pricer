package com.weatherderivatives.pricer.api;

import java.util.List;

/**
 * Detrended results.
 *
 * @param targetSeasonYear the season the shifted history represents
 * @param slopeDegCPerYear fitted trend, °C per year
 * @param slopeStdErrDegCPerYear standard error of the slope, °C per year
 * @param slopeTStat slope / its standard error, or null when the standard error is 0 (spec 006
 *     amendment C)
 * @param slopeSignificant whether |t| ≥ 2 (exact trend: slope ≠ 0); a false value means the
 *     detrending shift is mostly noise
 * @param seasons shifted seasons in year order
 * @param burn detrended burn
 * @param normal detrended normal-model expected payoff
 */
public record DetrendedDto(
    int targetSeasonYear,
    double slopeDegCPerYear,
    double slopeStdErrDegCPerYear,
    Double slopeTStat,
    boolean slopeSignificant,
    List<DetrendedSeasonDto> seasons,
    BurnDto burn,
    NormalDto normal) {}
