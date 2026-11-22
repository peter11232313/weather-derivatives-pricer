package com.weatherderivatives.pricer.api;

import com.weatherderivatives.pricer.contract.ContractType;
import java.time.YearMonth;

/**
 * JSON body of {@code POST /price}. Boxed types so a missing field is detected, not read as 0.
 *
 * @param city city name, e.g. {@code london}
 * @param type call, put or swap
 * @param firstMonth first accrual month
 * @param lastMonth last accrual month
 * @param strikeHddDegC strike, °C·day
 * @param tickGbpPerDegC GBP per index point
 * @param capGbp cap in GBP; null means uncapped
 * @param dailyHddDecimals daily HDD rounding; null means full precision
 * @param baseC base temperature, °C; null means 18
 * @param firstSeasonYear first season (inclusive)
 * @param lastSeasonYear last season (inclusive)
 * @param targetSeasonYear detrending target; null means no detrended results
 */
public record PriceRequest(
    String city,
    ContractType type,
    YearMonth firstMonth,
    YearMonth lastMonth,
    Double strikeHddDegC,
    Double tickGbpPerDegC,
    Double capGbp,
    Integer dailyHddDecimals,
    Double baseC,
    Integer firstSeasonYear,
    Integer lastSeasonYear,
    Integer targetSeasonYear) {}
