package com.weatherderivatives.pricer.contract;

import com.weatherderivatives.pricer.index.HddIndex;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Terms of an HDD contract (spec 003).
 *
 * <p>The accrual period is whole calendar months, {@code firstMonth..lastMonth} inclusive, at most
 * 12 months. The months' years only place the contract in time: {@link #forSeason(int)} moves both
 * by the same number of years, so a Nov–Mar contract keeps crossing the year end.
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

  /** Longest allowed accrual period in months. */
  public static final int MAX_MONTHS = 12;

  /** Largest allowed {@code dailyHddDecimals}. */
  public static final int MAX_DAILY_HDD_DECIMALS = 4;

  /**
   * Validates the terms.
   *
   * @throws NullPointerException if a component is null (names the field)
   * @throws IllegalArgumentException if a term is invalid (names the field): {@code firstMonth}
   *     after {@code lastMonth}, more than 12 months, non-finite {@code baseC}, strike or tick,
   *     negative strike, tick or cap not positive, or decimals outside 0..4
   */
  public HddContract {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(firstMonth, "firstMonth");
    Objects.requireNonNull(lastMonth, "lastMonth");
    Objects.requireNonNull(capGbp, "capGbp");
    Objects.requireNonNull(dailyHddDecimals, "dailyHddDecimals");
    if (firstMonth.isAfter(lastMonth)) {
      throw new IllegalArgumentException(
          "firstMonth " + firstMonth + " is after lastMonth " + lastMonth);
    }
    long months = firstMonth.until(lastMonth, ChronoUnit.MONTHS) + 1;
    if (months > MAX_MONTHS) {
      throw new IllegalArgumentException(
          "firstMonth "
              + firstMonth
              + " to lastMonth "
              + lastMonth
              + " is "
              + months
              + " months; at most "
              + MAX_MONTHS
              + " allowed");
    }
    if (!Double.isFinite(baseC)) {
      throw new IllegalArgumentException("baseC must be finite but was " + baseC);
    }
    if (!Double.isFinite(strikeHddDegC) || strikeHddDegC < 0) {
      throw new IllegalArgumentException(
          "strikeHddDegC must be finite and >= 0 but was " + strikeHddDegC);
    }
    strikeHddDegC += 0.0; // -0.0 -> +0.0, so equal terms give equal records
    if (!Double.isFinite(tickGbpPerDegC) || !(tickGbpPerDegC > 0)) {
      throw new IllegalArgumentException(
          "tickGbpPerDegC must be finite and > 0 but was " + tickGbpPerDegC);
    }
    if (capGbp.isPresent()) {
      double cap = capGbp.getAsDouble();
      if (!Double.isFinite(cap) || !(cap > 0)) {
        throw new IllegalArgumentException("capGbp must be finite and > 0 but was " + cap);
      }
    }
    if (dailyHddDecimals.isPresent()) {
      int decimals = dailyHddDecimals.getAsInt();
      if (decimals < 0 || decimals > MAX_DAILY_HDD_DECIMALS) {
        throw new IllegalArgumentException(
            "dailyHddDecimals must be in 0.." + MAX_DAILY_HDD_DECIMALS + " but was " + decimals);
      }
    }
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
   * @throws NullPointerException if an argument is null (names the field)
   * @throws IllegalArgumentException if a term is invalid (names the field)
   */
  public static HddContract of(
      ContractType type,
      YearMonth firstMonth,
      YearMonth lastMonth,
      double strikeHddDegC,
      double tickGbpPerDegC,
      OptionalDouble capGbp,
      OptionalInt dailyHddDecimals) {
    return new HddContract(
        type,
        firstMonth,
        lastMonth,
        HddIndex.DEFAULT_BASE_C,
        strikeHddDegC,
        tickGbpPerDegC,
        capGbp,
        dailyHddDecimals);
  }

  /**
   * Returns the first accrual day.
   *
   * @return the first day of {@code firstMonth}
   */
  public LocalDate accrualStart() {
    return firstMonth.atDay(1);
  }

  /**
   * Returns the last accrual day.
   *
   * @return the last day of {@code lastMonth}
   */
  public LocalDate accrualEndInclusive() {
    return lastMonth.atEndOfMonth();
  }

  /**
   * Returns this contract shifted to another season.
   *
   * @param seasonYear year of the shifted contract's first accrual day
   * @return the same terms with both months moved by the same number of years
   * @throws java.time.DateTimeException if the shifted year is outside {@link YearMonth}'s range
   */
  public HddContract forSeason(int seasonYear) {
    long shiftYears = (long) seasonYear - firstMonth.getYear();
    return new HddContract(
        type,
        firstMonth.plusYears(shiftYears),
        lastMonth.plusYears(shiftYears),
        baseC,
        strikeHddDegC,
        tickGbpPerDegC,
        capGbp,
        dailyHddDecimals);
  }
}
