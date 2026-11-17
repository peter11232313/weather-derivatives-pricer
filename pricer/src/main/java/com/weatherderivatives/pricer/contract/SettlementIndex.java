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

  private static final BigDecimal TWO = BigDecimal.valueOf(2);

  private SettlementIndex() {}

  /**
   * Computes the contract's cumulative HDD index over its accrual period.
   *
   * <p>Without {@code dailyHddDecimals} this is exactly {@link HddIndex#cumulativeHddDegC} over
   * {@code accrualStart()..accrualEndInclusive()} at the contract's base. With {@code d} decimals,
   * each daily HDD is computed exactly in decimal, {@code max(0, base − (tmax + tmin) / 2)} on the
   * shortest decimal forms of the stored values ({@link BigDecimal#valueOf(double)}), rounded
   * half-up to {@code d} decimals and summed exactly; the total is therefore already at {@code d}
   * decimals and is returned as the nearest double.
   *
   * <p>Computing in decimal keeps exact ties exact: Tmax 6.9, Tmin 1.8 gives HDD 13.65, which
   * rounds to 13.7, whereas the binary double {@code 13.649999999999999} would round to 13.6.
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
    BigDecimal baseC = BigDecimal.valueOf(contract.baseC());
    BigDecimal total = BigDecimal.ZERO.setScale(decimals);
    for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
      DailyTemp day = temps.get(date);
      total = total.add(exactDailyHddDegC(day, baseC).setScale(decimals, RoundingMode.HALF_UP));
    }
    return total.doubleValue();
  }

  /** {@code max(0, base − (tmax + tmin) / 2)} in exact decimal arithmetic. */
  private static BigDecimal exactDailyHddDegC(DailyTemp day, BigDecimal baseC) {
    BigDecimal meanTempC =
        BigDecimal.valueOf(day.tmaxC()).add(BigDecimal.valueOf(day.tminC())).divide(TWO);
    return baseC.subtract(meanTempC).max(BigDecimal.ZERO);
  }
}
