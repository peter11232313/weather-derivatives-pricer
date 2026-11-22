package com.weatherderivatives.pricer.api;

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
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.springframework.stereotype.Service;

/**
 * Maps a {@link PriceRequest} to library calls and the results back to a {@link PriceResponse}. No
 * pricing logic lives here: every number comes from {@link IndexDistributionAnalysis} (which
 * carries the burn) and, with a target season, {@link DetrendedBurnAnalysis}.
 */
@Service
public class PriceService {

  private final TemperatureSource source;

  /**
   * Creates the service.
   *
   * @param source where temperatures come from
   */
  public PriceService(TemperatureSource source) {
    this.source = source;
  }

  /**
   * Prices a request with every method that applies (spec 006, decision 2).
   *
   * @param request the request
   * @return burn and normal prices, plus detrended results when a target season is given
   * @throws InvalidRequestException if a required field is missing (names the field)
   * @throws IllegalArgumentException if a contract term or the season range is invalid (the library
   *     names the field or the minimum)
   * @throws CityNotFoundException if the city name is invalid or has no data file
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
    DailyTemps temps = load(request.city());

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
        request.city(), seasons(plain.burn()), burn(plain.burn()), normal(plain), detrended);
  }

  private static HddContract contract(PriceRequest r) {
    required(r.city(), "city");
    return new HddContract(
        required(r.type(), "type"),
        required(r.firstMonth(), "firstMonth"),
        required(r.lastMonth(), "lastMonth"),
        r.baseC() == null ? HddIndex.DEFAULT_BASE_C : r.baseC(),
        required(r.strikeHddDegC(), "strikeHddDegC"),
        required(r.tickGbpPerDegC(), "tickGbpPerDegC"),
        r.capGbp() == null ? OptionalDouble.empty() : OptionalDouble.of(r.capGbp()),
        r.dailyHddDecimals() == null ? OptionalInt.empty() : OptionalInt.of(r.dailyHddDecimals()));
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
    return new NormalDto(r.expectedPayoffGbp(), r.meanIndexHddDegC(), r.sdIndexHddDegC());
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
        List.copyOf(seasons),
        burn(burn.burn()),
        normal(normal));
  }
}
