package com.weatherderivatives.pricer.api;

import java.util.List;

/**
 * JSON body of a successful {@code POST /price}.
 *
 * @param city city name
 * @param seasons seasons in year order
 * @param burn burn summary
 * @param normal normal-model expected payoff
 * @param detrended detrended results, or null without a target season
 */
public record PriceResponse(
    String city, List<SeasonDto> seasons, BurnDto burn, NormalDto normal, DetrendedDto detrended) {}
