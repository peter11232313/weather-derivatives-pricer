package com.weatherderivatives.pricer.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weatherderivatives.pricer.data.ParquetFixtures.Row;
import com.weatherderivatives.pricer.index.DailyTemp;
import com.weatherderivatives.pricer.index.DailyTemps;
import com.weatherderivatives.pricer.index.TemperatureDataException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spec 001 loader criteria. Every fixture is written by the test itself into a {@link TempDir};
 * fixture writing happens before the loader is touched, so a fixture problem cannot masquerade as a
 * loader result.
 */
class ParquetTemperatureLoaderTest {

  @TempDir Path processedDir;

  private static void assertMessageContains(Throwable ex, String expected) {
    String msg = ex.getMessage();
    assertTrue(
        msg != null && msg.contains(expected),
        () -> "expected message to contain '" + expected + "' but was: " + msg);
  }

  private Path cityFile(String city) {
    return processedDir.resolve(city + ".parquet");
  }

  // --- Criterion: 3-row round trip, keyed by LocalDate, values unchanged ---

  @Test
  void threeRowFile_roundTripsEachRowKeyedByLocalDateWithUnchangedValues() {
    // Unordered rows, a leap day, negative values and non-terminating binary fractions.
    Row a = new Row(LocalDate.of(2024, 2, 29), 12.345678901234567, -3.1);
    Row b = new Row(LocalDate.of(2023, 12, 31), 0.1, -0.30000000000000004);
    Row c = new Row(LocalDate.of(2024, 1, 1), -1.0E-12, -40.25);
    ParquetFixtures.writeTemps(cityFile("london"), List.of(a, b, c));

    DailyTemps temps = new ParquetTemperatureLoader(processedDir).load("london");

    assertEquals(3, temps.size());
    for (Row r : List.of(a, b, c)) {
      DailyTemp day = temps.get(r.date());
      assertEquals(r.date(), day.date());
      // exact equality: the loader must not round or convert units
      assertTrue(day.tmaxC() == r.tmaxC(), () -> r.date() + " tmax_c was " + day.tmaxC());
      assertTrue(day.tminC() == r.tminC(), () -> r.date() + " tmin_c was " + day.tminC());
    }
  }

  @Test
  void extraColumns_areIgnored() {
    ParquetFixtures.writeQuery(
        cityFile("paris"),
        "SELECT CAST('2024-01-01' AS DATE) AS date, CAST(10.0 AS DOUBLE) AS tmax_c,"
            + " CAST(2.0 AS DOUBLE) AS tmin_c, CAST(51.5 AS DOUBLE) AS lat,"
            + " CAST('x' AS VARCHAR) AS source");
    DailyTemps temps = new ParquetTemperatureLoader(processedDir).load("paris");
    assertEquals(1, temps.size());
    assertEquals(
        new DailyTemp(LocalDate.of(2024, 1, 1), 10.0, 2.0), temps.get(LocalDate.of(2024, 1, 1)));
  }

  @Test
  void emptyFileWithCorrectSchema_loadsEmptySeries() {
    ParquetFixtures.writeTemps(cityFile("oslo"), List.of());
    assertEquals(0, new ParquetTemperatureLoader(processedDir).load("oslo").size());
  }

  // --- Criterion: missing required column -> exception names the column ---

  @ParameterizedTest
  @ValueSource(strings = {"date", "tmax_c", "tmin_c"})
  void missingRequiredColumn_exceptionNamesColumn(String missing) {
    String date = "CAST('2024-01-01' AS DATE) AS date";
    String tmax = "CAST(10.0 AS DOUBLE) AS tmax_c";
    String tmin = "CAST(2.0 AS DOUBLE) AS tmin_c";
    String cols =
        switch (missing) {
          case "date" -> tmax + ", " + tmin;
          case "tmax_c" -> date + ", " + tmin;
          default -> date + ", " + tmax;
        };
    ParquetFixtures.writeQuery(cityFile("berlin"), "SELECT " + cols);

    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> loader.load("berlin"));
    assertMessageContains(ex, missing);
  }

  @Test
  void mistypedTemperatureColumn_exceptionNamesColumn() {
    ParquetFixtures.writeQuery(
        cityFile("madrid"),
        "SELECT CAST('2024-01-01' AS DATE) AS date, CAST(10.0 AS DOUBLE) AS tmax_c,"
            + " CAST('2.0' AS VARCHAR) AS tmin_c");
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> loader.load("madrid"));
    assertMessageContains(ex, "tmin_c");
  }

  @Test
  void float32TemperatureColumns_areWidenedExactlyToDouble() {
    // 0.1f is not 0.1: widening must give the float's exact value, not a decimal re-parse.
    ParquetFixtures.writeQuery(
        cityFile("vienna"),
        "SELECT CAST('2024-01-01' AS DATE) AS date, CAST(0.1 AS FLOAT) AS tmax_c,"
            + " CAST(-2.5 AS FLOAT) AS tmin_c");
    assertEquals("FLOAT", ParquetFixtures.schemaOf(cityFile("vienna")).get("tmax_c"));

    DailyTemp day =
        new ParquetTemperatureLoader(processedDir).load("vienna").get(LocalDate.of(2024, 1, 1));

    assertEquals((double) 0.1f, day.tmaxC());
    assertEquals(0.10000000149011612, day.tmaxC());
    assertEquals(-2.5, day.tminC());
  }

  @Test
  void dateStoredAsTimestamp_isRejectedNamingDateColumn() {
    // A TIMESTAMP is not a UTC calendar day; silently truncating it could shift days.
    ParquetFixtures.writeQuery(
        cityFile("rome"),
        "SELECT CAST('2024-01-01 00:00:00' AS TIMESTAMP) AS date,"
            + " CAST(10.0 AS DOUBLE) AS tmax_c, CAST(2.0 AS DOUBLE) AS tmin_c");
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> loader.load("rome"));
    assertMessageContains(ex, "date");
  }

  @Test
  void nullTemperatureCell_exceptionNamesDateAndColumn() {
    ParquetFixtures.writeQuery(
        cityFile("vienna"),
        "SELECT * FROM (VALUES"
            + " (CAST('2024-01-01' AS DATE), CAST(10.0 AS DOUBLE), CAST(2.0 AS DOUBLE)),"
            + " (CAST('2024-01-02' AS DATE), CAST(NULL AS DOUBLE), CAST(4.0 AS DOUBLE)))"
            + " AS v(date, tmax_c, tmin_c)");
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> loader.load("vienna"));
    assertMessageContains(ex, "2024-01-02");
    assertMessageContains(ex, "tmax_c");
  }

  @Test
  void nanTemperatureInFile_exceptionNamesDate() {
    ParquetFixtures.writeTemps(
        cityFile("prague"),
        List.of(
            new Row(LocalDate.of(2024, 1, 1), 10.0, 2.0),
            new Row(LocalDate.of(2024, 1, 2), 12.0, Double.NaN)));
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> loader.load("prague"));
    assertMessageContains(ex, "2024-01-02");
  }

  @Test
  void infiniteTemperatureInFile_exceptionNamesDate() {
    ParquetFixtures.writeTemps(
        cityFile("warsaw"),
        List.of(
            new Row(LocalDate.of(2024, 1, 1), 10.0, 2.0),
            new Row(LocalDate.of(2024, 1, 2), Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)));
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> loader.load("warsaw"));
    assertMessageContains(ex, "2024-01-02");
  }

  @Test
  void unsupportedCompression_isTemperatureDataExceptionNamingPath() {
    // zstd (polars' default) needs zstd-jni, which is not on the classpath; the failure must
    // still honour load()'s contract rather than leak a library exception.
    ParquetFixtures.writeQuery(
        cityFile("zurich"),
        "SELECT CAST('2024-01-01' AS DATE) AS date, CAST(10.0 AS DOUBLE) AS tmax_c,"
            + " CAST(2.0 AS DOUBLE) AS tmin_c",
        "COMPRESSION zstd");
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> loader.load("zurich"));
    assertMessageContains(ex, cityFile("zurich").toString());
  }

  @Test
  void integerTemperatureColumn_isRejectedNamingColumn() {
    ParquetFixtures.writeQuery(
        cityFile("lisbon"),
        "SELECT CAST('2024-01-01' AS DATE) AS date, CAST(10 AS INTEGER) AS tmax_c,"
            + " CAST(2.0 AS DOUBLE) AS tmin_c");
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> loader.load("lisbon"));
    assertMessageContains(ex, "tmax_c");
  }

  @Test
  void mixedDoubleAndFloat32Columns_eachReadWithItsOwnType() {
    // tmax DOUBLE, tmin FLOAT with a non-representable value: catches swapped type flags.
    ParquetFixtures.writeQuery(
        cityFile("athens"),
        "SELECT CAST('2024-01-01' AS DATE) AS date, CAST(0.1 AS DOUBLE) AS tmax_c,"
            + " CAST(-0.3 AS FLOAT) AS tmin_c");
    DailyTemp day =
        new ParquetTemperatureLoader(processedDir).load("athens").get(LocalDate.of(2024, 1, 1));
    assertEquals(0.1, day.tmaxC());
    assertEquals((double) -0.3f, day.tminC());
  }

  @Test
  void pre1970Date_roundTrips() {
    // ERA5 starts in 1940; Parquet DATE stores days since 1970-01-01, so these are negative.
    Row early = new Row(LocalDate.of(1963, 1, 15), -5.5, -14.25);
    Row first = new Row(LocalDate.of(1940, 1, 1), 3.0, -1.0);
    ParquetFixtures.writeTemps(cityFile("bern"), List.of(early, first));
    DailyTemps temps = new ParquetTemperatureLoader(processedDir).load("bern");
    assertEquals(new DailyTemp(early.date(), -5.5, -14.25), temps.get(early.date()));
    assertEquals(new DailyTemp(first.date(), 3.0, -1.0), temps.get(first.date()));
  }

  @Test
  void tminAboveTmaxInFile_exceptionNamesDate() {
    ParquetFixtures.writeTemps(
        cityFile("dublin"), List.of(new Row(LocalDate.of(2024, 3, 31), 5.0, 6.0)));
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> loader.load("dublin"));
    assertMessageContains(ex, "2024-03-31");
  }

  // --- Criterion: duplicate dates in the file -> exception names the date ---

  @Test
  void duplicateDatesInFile_exceptionNamesDate() {
    ParquetFixtures.writeTemps(
        cityFile("amsterdam"),
        List.of(
            new Row(LocalDate.of(2024, 1, 1), 10.0, 2.0),
            new Row(LocalDate.of(2024, 1, 2), 12.0, 4.0),
            new Row(LocalDate.of(2024, 1, 2), 11.0, 3.0)));
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> loader.load("amsterdam"));
    assertMessageContains(ex, "2024-01-02");
  }

  // --- Criterion: data/processed/<city>.parquet does not exist -> exception names the path ---

  @Test
  void missingCityFile_exceptionNamesPath() {
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> loader.load("atlantis"));
    assertMessageContains(ex, cityFile("atlantis").toString());
  }

  // --- Loader input validation (Javadoc contract) ---

  @ParameterizedTest
  @ValueSource(strings = {"", "London", "../london", "lon don", "a/b", "a\\b", "london.parquet"})
  void invalidCityName_throwsIllegalArgumentException(String city) {
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    assertThrows(IllegalArgumentException.class, () -> loader.load(city));
  }

  @Test
  void validCityNameCharacters_areAccepted() {
    ParquetFixtures.writeTemps(
        cityFile("st_petersburg-2"), List.of(new Row(LocalDate.of(2024, 1, 1), 10.0, 2.0)));
    assertEquals(1, new ParquetTemperatureLoader(processedDir).load("st_petersburg-2").size());
  }

  @Test
  void nullProcessedDir_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> new ParquetTemperatureLoader(null));
  }
}
