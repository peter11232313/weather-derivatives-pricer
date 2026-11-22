package com.weatherderivatives.pricer.data;

import com.weatherderivatives.pricer.index.DailyTemp;
import com.weatherderivatives.pricer.index.DailyTemps;
import com.weatherderivatives.pricer.index.TemperatureDataException;
import dev.hardwood.InputFile;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Loads one city's daily temperatures from {@code <processedDir>/<city>.parquet}.
 *
 * <p>Required columns: {@code date} (Parquet DATE), {@code tmax_c} and {@code tmin_c} (float64, °C;
 * float32 is accepted and widened exactly). Extra columns are ignored. Every row is validated at
 * load, so one bad day fails the whole file. Finite values outside {@value #MIN_TEMP_C}..{@value
 * #MAX_TEMP_C} °C (inclusive) are rejected. This catches corrupt files and kelvin or doubly
 * converted values, but it is not a general units check: a winter-only Fahrenheit file can stay
 * inside the limits.
 *
 * <p>When a row breaks several rules, the first in this order is reported: a non-finite value (left
 * to {@link DailyTemp}), then {@code tmax_c} out of range, then {@code tmin_c} out of range, then
 * {@code tminC > tmaxC}.
 */
public final class ParquetTemperatureLoader {

  static final String DATE_COLUMN = "date";
  static final String TMAX_COLUMN = "tmax_c";
  static final String TMIN_COLUMN = "tmin_c";

  /** Lowest physically plausible daily temperature, °C (world record −89.2 °C). */
  static final double MIN_TEMP_C = -90.0;

  /** Highest physically plausible daily temperature, °C (world record 56.7 °C). */
  static final double MAX_TEMP_C = 60.0;

  private static final Pattern CITY_NAME = Pattern.compile("[a-z0-9_-]+");

  private final Path processedDir;

  /**
   * Creates a loader reading from a directory of processed files.
   *
   * @param processedDir directory holding {@code <city>.parquet} files, e.g. {@code data/pricer}
   * @throws NullPointerException if {@code processedDir} is null
   */
  public ParquetTemperatureLoader(Path processedDir) {
    this.processedDir = Objects.requireNonNull(processedDir, "processedDir");
  }

  /**
   * Loads a city's file.
   *
   * @param city city name matching {@code [a-z0-9_-]+}, e.g. {@code london}
   * @return the city's daily temperatures
   * @throws IllegalArgumentException if {@code city} does not match {@code [a-z0-9_-]+}
   * @throws CityFileNotFoundException if the file is missing (names the path)
   * @throws TemperatureDataException if a required column is missing or has the wrong type (names
   *     the column), a cell is null (names the date and column), a finite value lies outside
   *     {@value #MIN_TEMP_C}..{@value #MAX_TEMP_C} °C (names the date, column, value and file;
   *     checked before {@code tminC > tmaxC}), a date repeats or a row's values are invalid (names
   *     the date)
   */
  public DailyTemps load(String city) {
    if (city == null || !CITY_NAME.matcher(city).matches()) {
      throw new IllegalArgumentException("city must match [a-z0-9_-]+ but was: " + city);
    }
    Path file = processedDir.resolve(city + ".parquet");
    if (!Files.isRegularFile(file)) {
      throw new CityFileNotFoundException("temperature file not found: " + file);
    }
    try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file))) {
      FileSchema schema = reader.getFileSchema();
      requireDateColumn(schema, file);
      boolean tmaxIsFloat = requireTemperatureColumn(schema, TMAX_COLUMN, file);
      boolean tminIsFloat = requireTemperatureColumn(schema, TMIN_COLUMN, file);
      return DailyTemps.of(readRows(reader, tmaxIsFloat, tminIsFloat, file));
    } catch (TemperatureDataException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      // Hardwood signals unsupported codecs and corrupt pages with unchecked exceptions.
      throw new TemperatureDataException("cannot read temperature file: " + file, e);
    }
  }

  private static List<DailyTemp> readRows(
      ParquetFileReader reader, boolean tmaxIsFloat, boolean tminIsFloat, Path file) {
    List<DailyTemp> days = new ArrayList<>();
    ColumnProjection projection = ColumnProjection.columns(DATE_COLUMN, TMAX_COLUMN, TMIN_COLUMN);
    try (RowReader rows = reader.buildRowReader().projection(projection).build()) {
      long rowIndex = 0;
      while (rows.hasNext()) {
        rows.next();
        if (rows.isNull(DATE_COLUMN)) {
          throw new TemperatureDataException(
              "null " + DATE_COLUMN + " in row " + rowIndex + " of " + file);
        }
        LocalDate date = rows.getDate(DATE_COLUMN);
        double tmaxC = readTempC(rows, TMAX_COLUMN, tmaxIsFloat, date, file);
        double tminC = readTempC(rows, TMIN_COLUMN, tminIsFloat, date, file);
        // Non-finite values are reported by DailyTemp; range-check only when both are finite.
        if (Double.isFinite(tmaxC) && Double.isFinite(tminC)) {
          requireInRange(tmaxC, TMAX_COLUMN, date, file);
          requireInRange(tminC, TMIN_COLUMN, date, file);
        }
        days.add(new DailyTemp(date, tmaxC, tminC));
        rowIndex++;
      }
    }
    return days;
  }

  /** Reads a temperature cell; float32 widens to double exactly. */
  private static double readTempC(
      RowReader rows, String column, boolean isFloat, LocalDate date, Path file) {
    if (rows.isNull(column)) {
      throw new TemperatureDataException("null " + column + " on " + date + " in " + file);
    }
    return isFloat ? rows.getFloat(column) : rows.getDouble(column);
  }

  /** Rejects a finite value outside the physical range, naming the date, column and file. */
  private static void requireInRange(double tempC, String column, LocalDate date, Path file) {
    if (tempC < MIN_TEMP_C || tempC > MAX_TEMP_C) {
      throw new TemperatureDataException(
          column
              + "="
              + tempC
              + " outside physical range ["
              + MIN_TEMP_C
              + ", "
              + MAX_TEMP_C
              + "] °C on "
              + date
              + " in "
              + file);
    }
  }

  private static void requireDateColumn(FileSchema schema, Path file) {
    ColumnSchema column = findColumn(schema, DATE_COLUMN, file);
    if (column.type() != PhysicalType.INT32
        || !(column.logicalType() instanceof LogicalType.DateType)) {
      throw new TemperatureDataException(
          "column "
              + DATE_COLUMN
              + " must be a Parquet DATE but is "
              + describe(column)
              + " in "
              + file);
    }
  }

  /**
   * Checks a temperature column's type.
   *
   * @return true if the column is float32, false if float64
   */
  private static boolean requireTemperatureColumn(FileSchema schema, String name, Path file) {
    ColumnSchema column = findColumn(schema, name, file);
    if (column.logicalType() == null) {
      if (column.type() == PhysicalType.DOUBLE) {
        return false;
      }
      if (column.type() == PhysicalType.FLOAT) {
        return true;
      }
    }
    throw new TemperatureDataException(
        "column "
            + name
            + " must be float64 or float32 but is "
            + describe(column)
            + " in "
            + file);
  }

  private static ColumnSchema findColumn(FileSchema schema, String name, Path file) {
    for (ColumnSchema column : schema.getColumns()) {
      if (column.fieldPath().toString().equals(name)) {
        return column;
      }
    }
    throw new TemperatureDataException("missing required column " + name + " in " + file);
  }

  private static String describe(ColumnSchema column) {
    return column.logicalType() == null
        ? column.type().toString()
        : column.type() + " (" + column.logicalType() + ")";
  }
}
