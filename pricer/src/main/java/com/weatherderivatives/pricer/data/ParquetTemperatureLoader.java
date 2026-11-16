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
 * load, so one bad day fails the whole file.
 */
public final class ParquetTemperatureLoader {

  static final String DATE_COLUMN = "date";
  static final String TMAX_COLUMN = "tmax_c";
  static final String TMIN_COLUMN = "tmin_c";

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
   * @throws TemperatureDataException if the file is missing (names the path), a required column is
   *     missing or has the wrong type (names the column), a cell is null (names the date and
   *     column), a date repeats or a row's values are invalid (names the date)
   */
  public DailyTemps load(String city) {
    if (city == null || !CITY_NAME.matcher(city).matches()) {
      throw new IllegalArgumentException("city must match [a-z0-9_-]+ but was: " + city);
    }
    Path file = processedDir.resolve(city + ".parquet");
    if (!Files.isRegularFile(file)) {
      throw new TemperatureDataException("temperature file not found: " + file);
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
