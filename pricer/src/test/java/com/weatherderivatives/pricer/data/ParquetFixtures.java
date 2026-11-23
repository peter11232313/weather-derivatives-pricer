package com.weatherderivatives.pricer.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes small Parquet fixtures with DuckDB (test scope only) into a JUnit temp dir, so no data
 * file is ever committed. Also self-tests the fixtures by reading them back with DuckDB, so loader
 * tests can only fail because of the loader.
 */
public class ParquetFixtures {

  /** Parquet key-value metadata key for the Tmax observation-window start (spec 001 amend. B). */
  public static final String TMAX_WINDOW_KEY = "tmax_window_start_utc_h";

  /** Parquet key-value metadata key for the Tmin observation-window start (spec 001 amend. B). */
  public static final String TMIN_WINDOW_KEY = "tmin_window_start_utc_h";

  /**
   * Default window metadata written by the overloads without a metadata argument: 0 / 0, i.e. ADR
   * 0002's 00-24 UTC day (spec 001 amendment B decision), so no existing assertion changes.
   */
  public static final Map<String, String> DEFAULT_WINDOW_METADATA =
      Map.of(TMAX_WINDOW_KEY, "0", TMIN_WINDOW_KEY, "0");

  /** One fixture row in the spec 001 schema. */
  public record Row(LocalDate date, double tmaxC, double tminC) {}

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection("jdbc:duckdb:");
  }

  private static String sqlPath(Path file) {
    return file.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
  }

  private static String sqlString(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  /**
   * DuckDB {@code COPY} option text for {@code kvMetadata}, starting with ", ", or "" for an empty
   * map (no KV_METADATA written at all).
   */
  private static String kvOption(Map<String, String> kvMetadata) {
    if (kvMetadata.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(", KV_METADATA {");
    boolean first = true;
    for (Map.Entry<String, String> e : kvMetadata.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(sqlString(e.getKey())).append(": ").append(sqlString(e.getValue()));
    }
    return sb.append('}').toString();
  }

  /**
   * Writes {@code rows} to {@code file} with columns {@code date DATE, tmax_c DOUBLE, tmin_c
   * DOUBLE}, in the given order, with {@link #DEFAULT_WINDOW_METADATA}. Doubles are bound as
   * parameters so values round-trip bit-exactly.
   */
  public static Path writeTemps(Path file, List<Row> rows) {
    return writeTemps(file, rows, DEFAULT_WINDOW_METADATA);
  }

  /**
   * Like {@link #writeTemps(Path, List)} with the given Parquet key-value metadata instead of the
   * default; an empty map writes no key-value metadata at all.
   */
  public static Path writeTemps(Path file, List<Row> rows, Map<String, String> kvMetadata) {
    try (Connection conn = connect();
        Statement st = conn.createStatement()) {
      st.execute("CREATE TABLE t (seq INTEGER, date DATE, tmax_c DOUBLE, tmin_c DOUBLE)");
      try (PreparedStatement ins =
          conn.prepareStatement("INSERT INTO t VALUES (?, CAST(? AS DATE), ?, ?)")) {
        int seq = 0;
        for (Row r : rows) {
          ins.setInt(1, seq++);
          ins.setString(2, r.date().toString());
          ins.setDouble(3, r.tmaxC());
          ins.setDouble(4, r.tminC());
          ins.executeUpdate();
        }
      }
      st.execute(
          "COPY (SELECT date, tmax_c, tmin_c FROM t ORDER BY seq) TO '"
              + sqlPath(file)
              + "' (FORMAT PARQUET"
              + kvOption(kvMetadata)
              + ")");
      return file;
    } catch (SQLException e) {
      throw new IllegalStateException("fixture write failed: " + file, e);
    }
  }

  /**
   * Writes the result of an arbitrary DuckDB {@code SELECT} to {@code file}, for malformed-schema
   * fixtures, with {@link #DEFAULT_WINDOW_METADATA}. Cast every column explicitly in {@code
   * selectSql}.
   */
  public static Path writeQuery(Path file, String selectSql) {
    return writeQuery(file, selectSql, "", DEFAULT_WINDOW_METADATA);
  }

  /**
   * Like {@link #writeQuery(Path, String)} with the given Parquet key-value metadata instead of the
   * default; an empty map writes no key-value metadata at all.
   */
  public static Path writeQuery(Path file, String selectSql, Map<String, String> kvMetadata) {
    return writeQuery(file, selectSql, "", kvMetadata);
  }

  /**
   * Like {@link #writeQuery(Path, String)} with extra DuckDB {@code COPY} options, e.g. {@code
   * "COMPRESSION zstd"}.
   */
  public static Path writeQuery(Path file, String selectSql, String copyOptions) {
    return writeQuery(file, selectSql, copyOptions, DEFAULT_WINDOW_METADATA);
  }

  /**
   * Like {@link #writeQuery(Path, String, String)} with the given Parquet key-value metadata; an
   * empty map writes no key-value metadata at all.
   */
  public static Path writeQuery(
      Path file, String selectSql, String copyOptions, Map<String, String> kvMetadata) {
    String options = (copyOptions.isEmpty() ? "" : ", " + copyOptions) + kvOption(kvMetadata);
    try (Connection conn = connect();
        Statement st = conn.createStatement()) {
      st.execute(
          "COPY (" + selectSql + ") TO '" + sqlPath(file) + "' (FORMAT PARQUET" + options + ")");
      return file;
    } catch (SQLException e) {
      throw new IllegalStateException("fixture write failed: " + file, e);
    }
  }

  /** Returns column name to DuckDB type as read back from {@code file}. */
  static Map<String, String> schemaOf(Path file) {
    Map<String, String> cols = new LinkedHashMap<>();
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT column_name, column_type FROM"
                    + " (DESCRIBE SELECT * FROM read_parquet('"
                    + sqlPath(file)
                    + "'))")) {
      while (rs.next()) {
        cols.put(rs.getString(1), rs.getString(2));
      }
      return cols;
    } catch (SQLException e) {
      throw new IllegalStateException("fixture read failed: " + file, e);
    }
  }

  /** Returns the Parquet key-value metadata of {@code file} as strings (test-only reader). */
  static Map<String, String> kvMetadataOf(Path file) {
    Map<String, String> kv = new LinkedHashMap<>();
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                // decode() (BLOB -> UTF-8 VARCHAR), not CAST: CAST escapes ' as \x27.
                "SELECT decode(key), decode(value) FROM parquet_kv_metadata('"
                    + sqlPath(file)
                    + "')")) {
      while (rs.next()) {
        kv.put(rs.getString(1), rs.getString(2));
      }
      return kv;
    } catch (SQLException e) {
      throw new IllegalStateException("fixture read failed: " + file, e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Self-tests of the fixture helper (independent of the loader under test).
  // ---------------------------------------------------------------------------------------------

  @Test
  void writeTemps_producesDateAndDoubleColumnsAndExactValues(@TempDir Path dir)
      throws SQLException, IOException {
    List<Row> rows =
        List.of(
            new Row(LocalDate.of(2024, 2, 29), 12.345678901234567, -3.1),
            new Row(LocalDate.of(2023, 12, 31), 0.1, -0.30000000000000004));
    Path file = writeTemps(dir.resolve("fixture.parquet"), rows);

    assertTrue(Files.size(file) > 0, "fixture file is empty");
    Map<String, String> schema = schemaOf(file);
    assertEquals(List.of("date", "tmax_c", "tmin_c"), List.copyOf(schema.keySet()));
    assertEquals("DATE", schema.get("date"));
    assertEquals("DOUBLE", schema.get("tmax_c"));
    assertEquals("DOUBLE", schema.get("tmin_c"));

    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT CAST(date AS VARCHAR), tmax_c, tmin_c FROM read_parquet('"
                    + sqlPath(file)
                    + "')")) {
      for (Row expected : rows) {
        assertTrue(rs.next());
        assertEquals(expected.date().toString(), rs.getString(1));
        assertEquals(expected.tmaxC(), rs.getDouble(2));
        assertEquals(expected.tminC(), rs.getDouble(3));
      }
      assertTrue(!rs.next(), "unexpected extra row");
    }
  }

  @Test
  void writeQuery_honoursExplicitCasts(@TempDir Path dir) {
    Path file =
        writeQuery(
            dir.resolve("ts.parquet"),
            "SELECT CAST('2024-01-01 00:00:00' AS TIMESTAMP) AS date,"
                + " CAST(10.0 AS DOUBLE) AS tmax_c, CAST('2.0' AS VARCHAR) AS tmin_c");
    Map<String, String> schema = schemaOf(file);
    assertEquals("TIMESTAMP", schema.get("date"));
    assertEquals("DOUBLE", schema.get("tmax_c"));
    assertEquals("VARCHAR", schema.get("tmin_c"));
  }

  private static final String ONE_ROW_SELECT =
      "SELECT CAST('2024-01-01' AS DATE) AS date, CAST(1.0 AS DOUBLE) AS tmax_c,"
          + " CAST(0.0 AS DOUBLE) AS tmin_c";

  @Test
  void writeTemps_defaultWindowMetadataIsZeroZero(@TempDir Path dir) {
    Path file =
        writeTemps(dir.resolve("d.parquet"), List.of(new Row(LocalDate.of(2024, 1, 1), 1.0, 0.0)));
    Map<String, String> kv = kvMetadataOf(file);
    assertEquals("0", kv.get(TMAX_WINDOW_KEY));
    assertEquals("0", kv.get(TMIN_WINDOW_KEY));
  }

  @Test
  void writeQuery_defaultWindowMetadataIsZeroZero_alsoWithCopyOptions(@TempDir Path dir) {
    Path plain = writeQuery(dir.resolve("q.parquet"), ONE_ROW_SELECT);
    Path zstd = writeQuery(dir.resolve("z.parquet"), ONE_ROW_SELECT, "COMPRESSION zstd");
    for (Path file : List.of(plain, zstd)) {
      Map<String, String> kv = kvMetadataOf(file);
      assertEquals("0", kv.get(TMAX_WINDOW_KEY), file::toString);
      assertEquals("0", kv.get(TMIN_WINDOW_KEY), file::toString);
    }
  }

  @Test
  void writeTempsAndWriteQuery_customWindowMetadataRoundTripsIncludingQuote(@TempDir Path dir) {
    Path file =
        writeTemps(
            dir.resolve("c.parquet"),
            List.of(),
            Map.of(TMAX_WINDOW_KEY, "9", TMIN_WINDOW_KEY, "-15"));
    Map<String, String> kv = kvMetadataOf(file);
    assertEquals("9", kv.get(TMAX_WINDOW_KEY));
    assertEquals("-15", kv.get(TMIN_WINDOW_KEY));

    Path quoted =
        writeQuery(
            dir.resolve("q.parquet"),
            ONE_ROW_SELECT,
            Map.of(TMAX_WINDOW_KEY, "it's 9", TMIN_WINDOW_KEY, ""));
    Map<String, String> kvq = kvMetadataOf(quoted);
    assertEquals("it's 9", kvq.get(TMAX_WINDOW_KEY));
    assertEquals("", kvq.get(TMIN_WINDOW_KEY));
  }

  @Test
  void writeTempsAndWriteQuery_emptyMetadataMapWritesNoWindowKeys(@TempDir Path dir) {
    Path t = writeTemps(dir.resolve("t.parquet"), List.of(), Map.of());
    Path q = writeQuery(dir.resolve("q.parquet"), ONE_ROW_SELECT, Map.of());
    for (Path file : List.of(t, q)) {
      Map<String, String> kv = kvMetadataOf(file);
      assertFalse(kv.containsKey(TMAX_WINDOW_KEY), () -> file + ": " + kv);
      assertFalse(kv.containsKey(TMIN_WINDOW_KEY), () -> file + ": " + kv);
    }
  }
}
