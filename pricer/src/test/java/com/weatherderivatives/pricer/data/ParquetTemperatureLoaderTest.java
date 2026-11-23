package com.weatherderivatives.pricer.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weatherderivatives.pricer.data.ParquetFixtures.Row;
import com.weatherderivatives.pricer.index.DailyTemp;
import com.weatherderivatives.pricer.index.DailyTemps;
import com.weatherderivatives.pricer.index.TemperatureDataException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    assertMessageContains(ex, "non-finite");
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
    assertMessageContains(ex, "non-finite");
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

  // --- Spec 006 decision 4: a missing file is a CityFileNotFoundException (404 in the API) ---

  @Test
  void spec006_missingCityFile_throwsCityFileNotFoundExceptionNamingPath() {
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    // the subclass keeps existing TemperatureDataException callers working (checked by the type)
    CityFileNotFoundException ex =
        assertThrows(CityFileNotFoundException.class, () -> loader.load("paris"));
    assertMessageContains(ex, cityFile("paris").toString());
  }

  @Test
  void spec006_badDataInExistingFile_isNotCityFileNotFoundException() {
    // only a missing file is "not found"; a present but invalid file stays a plain data error
    ParquetFixtures.writeTemps(
        cityFile("hot"), List.of(new Row(LocalDate.of(2024, 1, 1), 70.0, 2.0)));
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> loader.load("hot"));
    assertFalse(ex instanceof CityFileNotFoundException, () -> "was: " + ex);
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

  // ---------------------------------------------------------------------------------------------
  // Spec 001 Amendment A: physical range check, -90.0..60.0 degC inclusive, in the loader only.
  // ---------------------------------------------------------------------------------------------

  private static final String RANGE_ERROR = "outside physical range";

  private static void assertMessageLacks(Throwable ex, String unexpected) {
    String msg = ex.getMessage();
    assertFalse(
        msg != null && msg.contains(unexpected),
        () -> "expected message NOT to contain '" + unexpected + "' but was: " + msg);
  }

  private TemperatureDataException loadExpectingFailure(String city) {
    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    return assertThrows(TemperatureDataException.class, () -> loader.load(city));
  }

  @Test
  void amendmentA_ac1_valuesExactlyAtLimits_load() {
    // Limits are inclusive: tmax 60.0 and tmin -90.0 are both accepted, values unchanged.
    LocalDate d = LocalDate.of(2024, 1, 1);
    ParquetFixtures.writeTemps(cityFile("limits"), List.of(new Row(d, 60.0, -90.0)));

    DailyTemps temps = new ParquetTemperatureLoader(processedDir).load("limits");

    assertEquals(1, temps.size());
    assertEquals(60.0, temps.get(d).tmaxC());
    assertEquals(-90.0, temps.get(d).tminC());
  }

  @Test
  void amendmentA_ac1_limitsInTheOtherColumn_load() {
    // Each limit applies to both columns: Tmax at -90.0 and Tmin at 60.0 are also accepted.
    LocalDate cold = LocalDate.of(2024, 1, 1);
    LocalDate hot = LocalDate.of(2024, 1, 2);
    ParquetFixtures.writeTemps(
        cityFile("swapped"), List.of(new Row(cold, -90.0, -90.0), new Row(hot, 60.0, 60.0)));

    DailyTemps temps = new ParquetTemperatureLoader(processedDir).load("swapped");

    assertEquals(-90.0, temps.get(cold).tmaxC());
    assertEquals(60.0, temps.get(hot).tminC());
  }

  @Test
  void amendmentA_ac2_tmaxJustAboveUpperLimit_exceptionNamesDateColumnValueAndFile() {
    ParquetFixtures.writeTemps(
        cityFile("seville"),
        List.of(
            new Row(LocalDate.of(2024, 1, 1), 10.0, 2.0),
            new Row(LocalDate.of(2024, 1, 2), 60.000001, 2.0)));

    TemperatureDataException ex = loadExpectingFailure("seville");

    assertMessageContains(ex, RANGE_ERROR);
    assertMessageContains(ex, "2024-01-02");
    assertMessageContains(ex, "tmax_c");
    assertMessageContains(ex, "60.000001"); // Double.toString(60.000001)
    assertMessageContains(ex, cityFile("seville").toString());
  }

  @Test
  void amendmentA_ac3_tminBelowLowerLimit_exceptionNamesTminColumn() {
    ParquetFixtures.writeTemps(
        cityFile("vostok"), List.of(new Row(LocalDate.of(2024, 1, 1), 5.0, -90.5)));

    TemperatureDataException ex = loadExpectingFailure("vostok");

    assertMessageContains(ex, RANGE_ERROR);
    assertMessageContains(ex, "tmin_c");
    assertMessageContains(ex, "-90.5");
    assertMessageContains(ex, "2024-01-01");
    assertMessageContains(ex, cityFile("vostok").toString());
  }

  @Test
  void amendmentA_ac4_threeDayFileStillInKelvin_isRejected() {
    // Without the check: mean 279.15, daily HDD max(0, 18 - 279.15) = 0, a silent index of 0.0.
    ParquetFixtures.writeTemps(
        cityFile("kelvin"),
        List.of(
            new Row(LocalDate.of(2024, 1, 1), 283.15, 275.15),
            new Row(LocalDate.of(2024, 1, 2), 283.15, 275.15),
            new Row(LocalDate.of(2024, 1, 3), 283.15, 275.15)));

    TemperatureDataException ex = loadExpectingFailure("kelvin");

    assertMessageContains(ex, RANGE_ERROR);
    assertMessageContains(ex, "283.15"); // Tmax is checked first
    assertMessageContains(ex, "tmax_c");
    assertMessageContains(ex, "2024-01-01"); // the first bad row is reported
  }

  @Test
  void amendmentA_ac5_float32AtUpperLimit_loadsAfterWidening() {
    ParquetFixtures.writeQuery(
        cityFile("f32ok"),
        "SELECT CAST('2024-01-01' AS DATE) AS date, CAST(60.0 AS FLOAT) AS tmax_c,"
            + " CAST(0.0 AS FLOAT) AS tmin_c");
    assertEquals("FLOAT", ParquetFixtures.schemaOf(cityFile("f32ok")).get("tmax_c"));

    DailyTemp day =
        new ParquetTemperatureLoader(processedDir).load("f32ok").get(LocalDate.of(2024, 1, 1));

    assertEquals(60.0, day.tmaxC()); // 60.0f widens exactly to 60.0
    assertEquals(0.0, day.tminC());
  }

  @Test
  void amendmentA_ac5_float32AboveUpperLimit_failsNamingTmaxColumnAndValue() {
    ParquetFixtures.writeQuery(
        cityFile("f32bad"),
        "SELECT CAST('2024-01-01' AS DATE) AS date, CAST(60.5 AS FLOAT) AS tmax_c,"
            + " CAST(0.0 AS FLOAT) AS tmin_c");
    assertEquals("FLOAT", ParquetFixtures.schemaOf(cityFile("f32bad")).get("tmax_c"));

    TemperatureDataException ex = loadExpectingFailure("f32bad");

    assertMessageContains(ex, RANGE_ERROR);
    assertMessageContains(ex, "tmax_c");
    assertMessageContains(ex, "60.5"); // 60.5f is exact in binary, widens to 60.5
    // Note: 60.000001 stored as float32 rounds to exactly 60.0f and therefore loads; the check
    // applies to the widened stored value, not to what the writer intended.
  }

  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({
    "tmax_c, NaN",
    "tmax_c, Infinity",
    "tmax_c, -Infinity",
    "tmin_c, NaN",
    "tmin_c, Infinity",
    "tmin_c, -Infinity"
  })
  void amendmentA_ac6_nonFiniteValue_raisesNonFiniteErrorNotRangeError(
      String column, double value) {
    // The other value is a normal, in-range temperature.
    Row row =
        column.equals("tmax_c")
            ? new Row(LocalDate.of(2024, 1, 2), value, 2.0)
            : new Row(LocalDate.of(2024, 1, 2), 10.0, value);
    ParquetFixtures.writeTemps(cityFile("nonfinite"), List.of(row));

    TemperatureDataException ex = loadExpectingFailure("nonfinite");

    assertMessageContains(ex, "non-finite");
    assertMessageLacks(ex, RANGE_ERROR);
  }

  @Test
  void amendmentA_ac7_kelvinTminWithCelsiusTmax_reportsRangeErrorNotOrderError() {
    // 275.15 > 10.0 would also trip tminC > tmaxC; the range check must run first.
    ParquetFixtures.writeTemps(
        cityFile("ordera"), List.of(new Row(LocalDate.of(2024, 1, 1), 10.0, 275.15)));

    TemperatureDataException ex = loadExpectingFailure("ordera");

    assertMessageContains(ex, RANGE_ERROR);
    assertMessageContains(ex, "tmin_c");
    assertMessageContains(ex, "275.15");
    assertMessageLacks(ex, "tminC > tmaxC");
  }

  @Test
  void amendmentA_ac7_kelvinTmaxWithCelsiusTmin_reportsRangeErrorNamingTmax() {
    ParquetFixtures.writeTemps(
        cityFile("orderb"), List.of(new Row(LocalDate.of(2024, 1, 1), 283.15, 5.0)));

    TemperatureDataException ex = loadExpectingFailure("orderb");

    assertMessageContains(ex, RANGE_ERROR);
    assertMessageContains(ex, "tmax_c");
    assertMessageContains(ex, "283.15");
  }

  @Test
  void amendmentA_ac8_infiniteTmaxWithOutOfRangeTmin_nonFiniteErrorWins() {
    ParquetFixtures.writeTemps(
        cityFile("mixeda"),
        List.of(new Row(LocalDate.of(2024, 1, 1), Double.POSITIVE_INFINITY, 500.0)));

    TemperatureDataException ex = loadExpectingFailure("mixeda");

    assertMessageContains(ex, "non-finite");
    assertMessageLacks(ex, RANGE_ERROR);
  }

  @Test
  void amendmentA_ac8_outOfRangeTmaxWithNanTmin_nonFiniteErrorWins() {
    ParquetFixtures.writeTemps(
        cityFile("mixedb"), List.of(new Row(LocalDate.of(2024, 1, 1), 500.0, Double.NaN)));

    TemperatureDataException ex = loadExpectingFailure("mixedb");

    assertMessageContains(ex, "non-finite");
    assertMessageLacks(ex, RANGE_ERROR);
  }

  @Test
  void amendmentA_ac8_bothOutOfRange_tmaxIsCheckedFirst() {
    ParquetFixtures.writeTemps(
        cityFile("bothbad"), List.of(new Row(LocalDate.of(2024, 1, 1), 70.0, -95.0)));

    TemperatureDataException ex = loadExpectingFailure("bothbad");

    assertMessageContains(ex, RANGE_ERROR);
    assertMessageContains(ex, "tmax_c");
    assertMessageContains(ex, "70.0");
    // starts-with rather than "no tmin_c anywhere": the temp-dir path could contain that text.
    assertTrue(
        ex.getMessage().startsWith("tmax_c="),
        () -> "expected the Tmax error first but was: " + ex.getMessage());
  }

  // ---------------------------------------------------------------------------------------------
  // Spec 001 Amendment B: observation-window metadata check (form only, same rules as publish).
  // Keys tmax_window_start_utc_h / tmin_window_start_utc_h: canonical integer 0|-?[1-9][0-9]?,
  // each in -23..23, |tmax - tmin| <= 24. Order: tmax (missing, form, range), tmin, then gap.
  // ---------------------------------------------------------------------------------------------

  private static final String TMAX_KEY = ParquetFixtures.TMAX_WINDOW_KEY;
  private static final String TMIN_KEY = ParquetFixtures.TMIN_WINDOW_KEY;

  private static final List<Row> WINDOW_ROWS =
      List.of(
          new Row(LocalDate.of(2024, 2, 29), 12.345678901234567, -3.1),
          new Row(LocalDate.of(2023, 12, 31), 0.1, -0.30000000000000004),
          new Row(LocalDate.of(2024, 1, 1), -1.0E-12, -40.25));

  private static Map<String, String> windows(String tmaxH, String tminH) {
    return Map.of(TMAX_KEY, tmaxH, TMIN_KEY, tminH);
  }

  private void writeWindows(String city, Map<String, String> kvMetadata) {
    ParquetFixtures.writeTemps(cityFile(city), WINDOW_ROWS, kvMetadata);
  }

  /** The spec quotes the value; either quote style is accepted (the spec does not fix one). */
  private static void assertMessageQuotesValue(Throwable ex, String value) {
    String msg = ex.getMessage();
    assertTrue(
        msg != null && (msg.contains("'" + value + "'") || msg.contains("\"" + value + "\"")),
        () -> "expected message to quote the value [" + value + "] but was: " + msg);
  }

  private static void assertLoadsWindowRows(DailyTemps temps) {
    assertEquals(WINDOW_ROWS.size(), temps.size());
    for (Row r : WINDOW_ROWS) {
      assertEquals(new DailyTemp(r.date(), r.tmaxC(), r.tminC()), temps.get(r.date()));
    }
  }

  @Test
  void amendmentB_london9AndMinus15_loadsSameRowsAsDefaultZeroZero() {
    // London: Tmax window D 09:00..D+1 09:00 (9), Tmin window D-1 09:00..D 09:00 (-15).
    writeWindows("london", windows("9", "-15"));
    ParquetFixtures.writeTemps(cityFile("default"), WINDOW_ROWS); // fixture default 0 / 0

    ParquetTemperatureLoader loader = new ParquetTemperatureLoader(processedDir);
    DailyTemps london = loader.load("london");
    DailyTemps dflt = loader.load("default");

    assertLoadsWindowRows(london);
    assertLoadsWindowRows(dflt);
    for (Row r : WINDOW_ROWS) {
      assertEquals(dflt.get(r.date()), london.get(r.date()));
    }
  }

  @Test
  void amendmentB_zeroZero_loads() {
    writeWindows("utcday", windows("0", "0"));
    assertLoadsWindowRows(new ParquetTemperatureLoader(processedDir).load("utcday"));
  }

  @Test
  void amendmentB_noKeyValueMetadataAtAll_rejectedNamingTmaxKeyAndFile() {
    writeWindows("oldfile", Map.of());
    assertTrue(ParquetFixtures.kvMetadataOf(cityFile("oldfile")).isEmpty());

    TemperatureDataException ex = loadExpectingFailure("oldfile");

    assertMessageContains(ex, TMAX_KEY); // tmax is checked first
    assertMessageContains(ex, "missing");
    assertMessageContains(ex, cityFile("oldfile").toString());
    assertFalse(ex instanceof CityFileNotFoundException, () -> "was: " + ex);
  }

  @Test
  void amendmentB_onlyTmaxKeyPresent_rejectedNamingTminKey() {
    writeWindows("halfmeta", Map.of(TMAX_KEY, "9"));

    TemperatureDataException ex = loadExpectingFailure("halfmeta");

    assertMessageContains(ex, TMIN_KEY);
    assertMessageContains(ex, "missing");
    assertMessageContains(ex, cityFile("halfmeta").toString());
  }

  @ParameterizedTest(name = "[{index}] tmax=[{0}]")
  @ValueSource(strings = {"+9", "09", "9.0", " 9", "-0", "abc", "", "9 "})
  void amendmentB_nonCanonicalTmaxValue_rejectedNamingKeyAndValue(String bad) {
    // tmin 0 is valid, so the tmax form is the only fault.
    writeWindows("badtmax", windows(bad, "0"));

    TemperatureDataException ex = loadExpectingFailure("badtmax");

    assertMessageContains(ex, TMAX_KEY);
    assertMessageQuotesValue(ex, bad);
    assertMessageContains(ex, cityFile("badtmax").toString());
  }

  @ParameterizedTest(name = "[{index}] tmin=[{0}]")
  @ValueSource(strings = {"-015", "+9", "-0", " -15", ""})
  void amendmentB_nonCanonicalTminValue_rejectedNamingKeyAndValue(String bad) {
    writeWindows("badtmin", windows("0", bad));

    TemperatureDataException ex = loadExpectingFailure("badtmin");

    assertMessageContains(ex, TMIN_KEY);
    assertMessageQuotesValue(ex, bad);
  }

  @ParameterizedTest(name = "[{index}] tmax={0} tmin={1}")
  @CsvSource({"23, 0", "-23, 0", "0, 23", "0, -23"})
  void amendmentB_valuesAtPlusMinus23_load(String tmaxH, String tminH) {
    // Limits are inclusive; the other key is 0 so the gap is 23 h, within 24.
    writeWindows("edge", windows(tmaxH, tminH));
    assertLoadsWindowRows(new ParquetTemperatureLoader(processedDir).load("edge"));
  }

  @ParameterizedTest(name = "[{index}] {0}={1}")
  @CsvSource({
    "tmax_window_start_utc_h, 24",
    "tmax_window_start_utc_h, -24",
    "tmin_window_start_utc_h, 24",
    "tmin_window_start_utc_h, -24"
  })
  void amendmentB_valuesAtPlusMinus24_rejectedNamingKeyAndValue(String key, String value) {
    // The other key is 0, so the gap is exactly 24 h (allowed): only the range is at fault.
    Map<String, String> kv = new HashMap<>(windows("0", "0"));
    kv.put(key, value);
    writeWindows("outofrange", kv);

    TemperatureDataException ex = loadExpectingFailure("outofrange");

    assertMessageContains(ex, key);
    assertMessageQuotesValue(ex, value);
  }

  @ParameterizedTest(name = "[{index}] tmax={0} tmin={1}")
  @CsvSource({"9, -15", "-15, 9", "23, -1", "12, -12"})
  void amendmentB_windowsExactly24hApart_load(String tmaxH, String tminH) {
    // |9 - (-15)| = 24, |23 - (-1)| = 24, |12 - (-12)| = 24: touching windows are allowed.
    writeWindows("touch", windows(tmaxH, tminH));
    assertLoadsWindowRows(new ParquetTemperatureLoader(processedDir).load("touch"));
  }

  @ParameterizedTest(name = "[{index}] tmax={0} tmin={1}")
  @CsvSource({"10, -15", "-15, 10", "23, -2"})
  void amendmentB_windows25hApart_rejectedNamingBothKeys(String tmaxH, String tminH) {
    // |10 - (-15)| = 25 > 24; each value alone is in range and canonical.
    writeWindows("gap", windows(tmaxH, tminH));

    TemperatureDataException ex = loadExpectingFailure("gap");

    assertMessageContains(ex, TMAX_KEY);
    assertMessageContains(ex, TMIN_KEY);
    assertMessageContains(ex, cityFile("gap").toString());
  }

  @Test
  void amendmentB_wrongColumnTypeAndNoMetadata_columnErrorReportedFirst() {
    ParquetFixtures.writeQuery(
        cityFile("intnometa"),
        "SELECT CAST('2024-01-01' AS DATE) AS date, CAST(10 AS INTEGER) AS tmax_c,"
            + " CAST(2.0 AS DOUBLE) AS tmin_c",
        Map.of());

    TemperatureDataException ex = loadExpectingFailure("intnometa");

    assertMessageContains(ex, "tmax_c");
    assertMessageLacks(ex, TMAX_KEY);
    assertMessageLacks(ex, TMIN_KEY);
  }

  @Test
  void amendmentB_longJunkValue_quotedAndCutTo40Characters() {
    // 100 chars "abcdefghij" x 10; the message may show at most the first 40 of them.
    String junk = "abcdefghij".repeat(10);
    String first40 = junk.substring(0, 40);
    String first41 = junk.substring(0, 41);
    writeWindows("junk", windows(junk, "0"));

    TemperatureDataException ex = loadExpectingFailure("junk");

    assertMessageContains(ex, TMAX_KEY);
    assertMessageContains(ex, first40);
    assertTrue(
        ex.getMessage().contains("'" + first40) || ex.getMessage().contains("\"" + first40),
        () -> "expected the cut value to be quoted but was: " + ex.getMessage());
    assertMessageLacks(ex, junk);
    assertMessageLacks(ex, first41);
  }

  @Test
  void amendmentB_missingTmaxKeyAndBadTminValue_tmaxErrorReported() {
    writeWindows("order1", Map.of(TMIN_KEY, "abc"));

    TemperatureDataException ex = loadExpectingFailure("order1");

    assertMessageContains(ex, TMAX_KEY);
    assertMessageContains(ex, "missing");
    assertMessageLacks(ex, TMIN_KEY);
  }

  @Test
  void amendmentB_tmaxOutOfRangeAndTminMissing_tmaxErrorReported() {
    writeWindows("order2", Map.of(TMAX_KEY, "24"));

    TemperatureDataException ex = loadExpectingFailure("order2");

    assertMessageContains(ex, TMAX_KEY);
    assertMessageQuotesValue(ex, "24");
    assertMessageLacks(ex, TMIN_KEY);
  }
}
