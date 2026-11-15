package com.weatherderivatives.pricer.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class ParquetFixtures {

  /** One fixture row in the spec 001 schema. */
  record Row(LocalDate date, double tmaxC, double tminC) {}

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection("jdbc:duckdb:");
  }

  private static String sqlPath(Path file) {
    return file.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
  }

  /**
   * Writes {@code rows} to {@code file} with columns {@code date DATE, tmax_c DOUBLE, tmin_c
   * DOUBLE}, in the given order. Doubles are bound as parameters so values round-trip bit-exactly.
   */
  static Path writeTemps(Path file, List<Row> rows) {
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
              + "' (FORMAT PARQUET)");
      return file;
    } catch (SQLException e) {
      throw new IllegalStateException("fixture write failed: " + file, e);
    }
  }

  /**
   * Writes the result of an arbitrary DuckDB {@code SELECT} to {@code file}, for malformed-schema
   * fixtures. Cast every column explicitly in {@code selectSql}.
   */
  static Path writeQuery(Path file, String selectSql) {
    return writeQuery(file, selectSql, "");
  }

  /**
   * Like {@link #writeQuery(Path, String)} with extra DuckDB {@code COPY} options, e.g. {@code
   * "COMPRESSION zstd"}.
   */
  static Path writeQuery(Path file, String selectSql, String copyOptions) {
    String options = copyOptions.isEmpty() ? "" : ", " + copyOptions;
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
}
