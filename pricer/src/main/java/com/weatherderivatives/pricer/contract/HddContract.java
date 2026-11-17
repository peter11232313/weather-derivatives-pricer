package com.weatherderivatives.pricer.contract;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Terms of an HDD contract (spec 003).
 *
 * @param type call, put or swap
 * @param firstMonth first accrual month (inclusive)
 * @param lastMonth last accrual month (inclusive)
 * @param baseC HDD base temperature in °C
 * @param strikeHddDegC strike in °C·day
 * @param tickGbpPerDegC GBP paid per index point (°C·day)
 * @param capGbp maximum payout in GBP; empty means uncapped
 * @param dailyHddDecimals decimals each daily HDD is rounded to; empty means full precision
 */
public record HddContract(
    ContractType type,
    YearMonth firstMonth,
    YearMonth lastMonth,
    double baseC,
    double strikeHddDegC,
    double tickGbpPerDegC,
    OptionalDouble capGbp,
    OptionalInt dailyHddDecimals) {

  /**
   * Validates the terms.
   *
   * @throws NullPointerException if a component is null
   * @throws IllegalArgumentException if a term is invalid
   */
  public HddContract {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Creates a contract with the default base temperature of 18 °C.
   *
   * @param type call, put or swap
   * @param firstMonth first accrual month (inclusive)
   * @param lastMonth last accrual month (inclusive)
   * @param strikeHddDegC strike in °C·day
   * @param tickGbpPerDegC GBP paid per index point
   * @param capGbp maximum payout in GBP; empty means uncapped
   * @param dailyHddDecimals decimals each daily HDD is rounded to; empty means full precision
   * @return the contract
   */
  public static HddContract of(
      ContractType type,
      YearMonth firstMonth,
      YearMonth lastMonth,
      double strikeHddDegC,
      double tickGbpPerDegC,
      OptionalDouble capGbp,
      OptionalInt dailyHddDecimals) {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Returns the first accrual day.
   *
   * @return the first day of {@code firstMonth}
   */
  public LocalDate accrualStart() {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Returns the last accrual day.
   *
   * @return the last day of {@code lastMonth}
   */
  public LocalDate accrualEndInclusive() {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Returns this contract shifted to another season.
   *
   * @param seasonYear year of the shifted contract's first accrual day
   * @return the same terms with both months moved by the same number of years
   */
  public HddContract forSeason(int seasonYear) {
    throw new UnsupportedOperationException("not implemented");
  }
}
