package com.weatherderivatives.pricer.data;

import com.weatherderivatives.pricer.index.DailyTemps;
import java.nio.file.Path;

/**
 * Loads one city's daily temperatures from {@code <processedDir>/<city>.parquet}.
 *
 * <p>Required columns: {@code date} (Parquet DATE), {@code tmax_c} and {@code tmin_c} (float64,
 * °C). Extra columns are ignored. Every row is validated at load, so one bad day fails the whole
 * file.
 */
public final class ParquetTemperatureLoader {

  /**
   * Creates a loader reading from a directory of processed files.
   *
   * @param processedDir directory holding {@code <city>.parquet} files, e.g. {@code data/processed}
   * @throws NullPointerException if {@code processedDir} is null
   */
  public ParquetTemperatureLoader(Path processedDir) {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Loads a city's file.
   *
   * @param city city name matching {@code [a-z0-9_-]+}, e.g. {@code london}
   * @return the city's daily temperatures
   * @throws IllegalArgumentException if {@code city} does not match {@code [a-z0-9_-]+}
   * @throws com.weatherderivatives.pricer.index.TemperatureDataException if the file is missing
   *     (names the path), a required column is missing or has the wrong type (names the column), a
   *     cell is null (names the date and column), a date repeats or a row's values are invalid
   *     (names the date)
   */
  public DailyTemps load(String city) {
    throw new UnsupportedOperationException("not implemented");
  }
}
