package com.weatherderivatives.pricer.contract;

import com.weatherderivatives.pricer.index.DailyTemp;
import com.weatherderivatives.pricer.index.DailyTemps;
import com.weatherderivatives.pricer.index.HddIndex;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/** The settlement index of an HDD contract (spec 003). */
public final class SettlementIndex {

  private SettlementIndex() {}

  /**
   * Computes the contract's cumulative HDD index over its accrual period.
   *
   * <p>Without {@code dailyHddDecimals} this is exactly {@link HddIndex#cumulativeHddDegC} over
   * {@code accrualStart()..accrualEndInclusive()} at the contract's base. With {@code d} decimals,
   * each daily HDD is rounded half-up to {@code d} decimals and summed exactly in decimal; the
   * total is therefore already at {@code d} decimals and is returned as the nearest double.
   *
   * <p>Rounding works on each daily HDD's shortest decimal representation ({@link
   * BigDecimal#valueOf(double)}), so {@code 1.005} rounds to {@code 1.01} at 2 decimals. A value
   * that arithmetic left just below a tie (e.g. {@code 8.249999999999998}) rounds down.
   *
   * @param contract the contract terms
   * @param temps daily temperatures covering the accrual period
   * @return the index in °C·day
   * @throws NullPointerException if an argument is null
   * @throws com.weatherderivatives.pricer.index.TemperatureDataException if a day in the accrual
   *     period is missing (names the date)
   */
  public static double indexHddDegC(HddContract contract, DailyTemps temps) {
    Objects.requireNonNull(contract, "contract");
    Objects.requireNonNull(temps, "temps");
    LocalDate start = contract.accrualStart();
    LocalDate end = contract.accrualEndInclusive();
    if (contract.dailyHddDecimals().isEmpty()) {
      return HddIndex.cumulativeHddDegC(temps, start, end, contract.baseC());
    }
    int decimals = contract.dailyHddDecimals().getAsInt();
    BigDecimal total = BigDecimal.ZERO.setScale(decimals);
    for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
      DailyTemp day = temps.get(date);
      double dailyHddDegC = HddIndex.dailyHddDegC(day.tmaxC(), day.tminC(), contract.baseC());
      total = total.add(BigDecimal.valueOf(dailyHddDegC).setScale(decimals, RoundingMode.HALF_UP));
    }
    return total.doubleValue();
  }
}
