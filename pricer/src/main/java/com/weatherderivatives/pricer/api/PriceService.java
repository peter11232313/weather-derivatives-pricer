package com.weatherderivatives.pricer.api;

import com.weatherderivatives.pricer.contract.ContractType;
import com.weatherderivatives.pricer.contract.HddContract;
import com.weatherderivatives.pricer.data.CityFileNotFoundException;
import com.weatherderivatives.pricer.index.DailyTemps;
import com.weatherderivatives.pricer.index.HddIndex;
import com.weatherderivatives.pricer.model.BurnResult;
import com.weatherderivatives.pricer.model.DetrendedBurnAnalysis;
import com.weatherderivatives.pricer.model.DetrendedBurnResult;
import com.weatherderivatives.pricer.model.IndexDistributionAnalysis;
import com.weatherderivatives.pricer.model.IndexDistributionResult;
import com.weatherderivatives.pricer.model.SeasonOutcome;
import com.weatherderivatives.pricer.model.SeasonTrend;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Maps a {@link PriceRequest} to library calls and the results back to a {@link PriceResponse}. No
 * pricing logic lives here: every number comes from {@link IndexDistributionAnalysis} (which
 * carries the burn) and, with a target season, {@link DetrendedBurnAnalysis}.
 */
@Service
public class PriceService {

  private final TemperatureSource source;
  private final Map<String, String> settlementCurrency;

  /**
   * Creates the service.
   *
   * @param source where temperatures come from
   * @param properties pricer configuration; its settlement-currency map decides which cities are
   *     priced (spec 006 amendment C)
   */
  public PriceService(TemperatureSource source, PricerProperties properties) {
    this.source = source;
    this.settlementCurrency = properties.settlementCurrency();
  }

  /**
   * Prices a request with every method that applies (spec 006, decision 2).
   *
   * @param request the request
   * @return burn and normal prices, plus detrended results when a target season is given
   * @throws InvalidRequestException if a required field is missing, or a contract term or the
   *     season range is invalid (the library's message names the field or the minimum), or {@code
   *     targetSeasonYear} lies inside {@code firstSeasonYear..lastSeasonYear} (look-ahead, spec 006
   *     amendment A; checked before the data are loaded)
   * @throws CityNotFoundException if the city has no settlement currency configured (checked after
   *     the look-ahead guard, before any data are read), or its name is invalid or it has no data
   *     file
   * @throws com.weatherderivatives.pricer.index.TemperatureDataException if the data are invalid or
   *     a season has a missing day
   */
  public PriceResponse price(PriceRequest request) {
    if (request == null) {
      throw new InvalidRequestException("request body is missing");
    }
    HddContract contract = contract(request);
    int first = required(request.firstSeasonYear(), "firstSeasonYear");
    int last = required(request.lastSeasonYear(), "lastSeasonYear");
    rejectLookAhead(request.targetSeasonYear(), first, last);
    String currency = settlementCurrency(request.city());
    DailyTemps temps = load(request.city());

    return clientErrors(() -> {
      IndexDistributionResult plain = IndexDistributionAnalysis.run(contract, temps, first, last);
      DetrendedDto detrended = null;
      if (request.targetSeasonYear() != null) {
        int target = request.targetSeasonYear();
        IndexDistributionResult normal =
            IndexDistributionAnalysis.runDetrended(contract, temps, first, last, target);
        DetrendedBurnResult burn = DetrendedBurnAnalysis.run(contract, temps, first, last, target);
        detrended = detrended(burn, normal);
      }
      return new PriceResponse(
          request.city(),
          currency,
          seasons(plain.burn()),
          burn(plain.burn()),
          normal(plain),
          detrended);
    });
  }

  /**
   * Rejects a target season inside the season window: it would feed both the trend fit and the
   * burn, so its own outcome would enter its price (spec 006 amendment A). A target after the
   * window is a walk-forward price; one before it is allowed but is a hindcast (the trend is fitted
   * on later seasons); with {@code first > last} the window is empty and the library reports the
   * range.
   */
  private static void rejectLookAhead(Integer targetSeasonYear, int first, int last) {
    if (targetSeasonYear == null) {
      return;
    }
    int target = targetSeasonYear;
    if (first <= target && target <= last) {
      throw new InvalidRequestException(
          "targetSeasonYear "
              + target
              + " is inside the season window firstSeasonYear..lastSeasonYear ("
              + first
              + ".."
              + last
              + "): look-ahead (the priced season would feed its own price); for a walk-forward"
              + " back-test use a year after "
              + last
              + " (e.g. "
              + (last + 1L)
              + "); a year before "
              + first
              + " is allowed but is a hindcast fitted on later seasons");
    }
  }

  /**
   * Runs a library call, turning its argument errors (invalid term, season range, a season year
   * outside the calendar) into {@link InvalidRequestException}. Only these calls are wrapped, so an
   * {@code IllegalArgumentException} from anywhere else stays a 500.
   */
  private static <T> T clientErrors(Supplier<T> call) {
    try {
      return call.get();
    } catch (IllegalArgumentException | DateTimeException e) {
      throw new InvalidRequestException(e.getMessage(), e);
    }
  }

  private static HddContract contract(PriceRequest r) {
    required(r.city(), "city");
    ContractType type = required(r.type(), "type");
    YearMonth firstMonth = required(r.firstMonth(), "firstMonth");
    YearMonth lastMonth = required(r.lastMonth(), "lastMonth");
    double strike = required(r.strikeHddDegC(), "strikeHddDegC");
    double tick = required(r.tickGbpPerDegC(), "tickGbpPerDegC");
    return clientErrors(() ->
        new HddContract(
            type,
            firstMonth,
            lastMonth,
            r.baseC() == null ? HddIndex.DEFAULT_BASE_C : r.baseC(),
            strike,
            tick,
            r.capGbp() == null ? OptionalDouble.empty() : OptionalDouble.of(r.capGbp()),
            r.dailyHddDecimals() == null
                ? OptionalInt.empty()
                : OptionalInt.of(r.dailyHddDecimals())));
  }

  /**
   * Returns the city's settlement currency, or rejects a city that is not configured, without
   * reading any file (spec 006 amendment C).
   */
  private String settlementCurrency(String city) {
    String currency = settlementCurrency.get(city);
    if (currency == null) {
      throw new CityNotFoundException(
          "no settlement currency configured for city: "
              + city
              + "; configured cities: "
              + (settlementCurrency.isEmpty()
                  ? "(none)"
                  : String.join(", ", new TreeSet<>(settlementCurrency.keySet()))));
    }
    return currency;
  }

  private DailyTemps load(String city) {
    try {
      return source.load(city);
    } catch (IllegalArgumentException | CityFileNotFoundException e) {
      // Only around the load: an invalid name or a missing file is "no such city".
      throw new CityNotFoundException("no data for city: " + city, e);
    }
  }

  private static <T> T required(T value, String field) {
    if (value == null) {
      throw new InvalidRequestException("missing required field: " + field);
    }
    return value;
  }

  private static List<SeasonDto> seasons(BurnResult burn) {
    return burn.seasons().stream()
        .map(s -> new SeasonDto(s.seasonYear(), s.indexHddDegC(), s.payoffGbp()))
        .toList();
  }

  private static BurnDto burn(BurnResult b) {
    return new BurnDto(
        b.burnPriceGbp(),
        b.burnPriceStdErrorGbp(),
        b.payoffStdDevGbp(),
        b.minPayoffGbp(),
        b.maxPayoffGbp(),
        b.meanIndexHddDegC());
  }

  private static NormalDto normal(IndexDistributionResult r) {
    return new NormalDto(
        r.expectedPayoffGbp(),
        r.meanIndexHddDegC(),
        r.sdIndexHddDegC(),
        r.seasonCount(),
        boxed(r.indexSkewness()));
  }

  private static Double boxed(OptionalDouble value) {
    return value.isPresent() ? value.getAsDouble() : null;
  }

  private static DetrendedDto detrended(DetrendedBurnResult burn, IndexDistributionResult normal) {
    List<SeasonOutcome> outcomes = burn.burn().seasons();
    List<SeasonTrend> trends = burn.seasons();
    List<DetrendedSeasonDto> seasons = new ArrayList<>(outcomes.size());
    for (int i = 0; i < outcomes.size(); i++) {
      SeasonOutcome o = outcomes.get(i);
      SeasonTrend t = trends.get(i);
      if (o.seasonYear() != t.seasonYear()) {
        throw new IllegalStateException("season order mismatch at index " + i);
      }
      seasons.add(
          new DetrendedSeasonDto(
              o.seasonYear(), o.indexHddDegC(), o.payoffGbp(), t.seasonMeanTempC(), t.shiftDegC()));
    }
    return new DetrendedDto(
        burn.targetSeasonYear(),
        burn.slopeDegCPerYear(),
        burn.slopeStdErrDegCPerYear(),
        boxed(burn.slopeTStat()),
        burn.slopeSignificant(),
        List.copyOf(seasons),
        burn(burn.burn()),
        normal(normal));
  }
}
