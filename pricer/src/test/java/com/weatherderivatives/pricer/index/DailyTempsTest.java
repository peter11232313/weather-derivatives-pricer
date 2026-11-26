package com.weatherderivatives.pricer.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract tests for {@link DailyTemps} (spec 001). */
class DailyTempsTest {

  private static void assertMessageContains(Throwable ex, String expected) {
    String msg = ex.getMessage();
    assertTrue(
        msg != null && msg.contains(expected),
        () -> "expected message to contain '" + expected + "' but was: " + msg);
  }

  // --- Criterion: duplicate dates -> exception names the date ---

  @Test
  void duplicateDates_exceptionNamesDate() {
    List<DailyTemp> days =
        List.of(
            new DailyTemp(LocalDate.of(2024, 1, 1), 10.0, 2.0),
            new DailyTemp(LocalDate.of(2024, 1, 2), 12.0, 4.0),
            new DailyTemp(LocalDate.of(2024, 1, 2), 11.0, 3.0));
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> DailyTemps.of(days));
    assertMessageContains(ex, "2024-01-02");
  }

  @Test
  void identicalDuplicateRows_stillRejected() {
    DailyTemp day = new DailyTemp(LocalDate.of(2024, 2, 29), 10.0, 2.0);
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> DailyTemps.of(List.of(day, day)));
    assertMessageContains(ex, "2024-02-29");
  }

  @Test
  void unorderedInput_isKeyedByDate() {
    DailyTemp jan3 = new DailyTemp(LocalDate.of(2024, 1, 3), 20.0, 18.0);
    DailyTemp jan1 = new DailyTemp(LocalDate.of(2024, 1, 1), 10.0, 2.0);
    DailyTemp jan2 = new DailyTemp(LocalDate.of(2024, 1, 2), 12.0, 4.0);
    DailyTemps temps = DailyTemps.of(List.of(jan3, jan1, jan2));
    assertEquals(3, temps.size());
    assertEquals(jan1, temps.get(LocalDate.of(2024, 1, 1)));
    assertEquals(jan2, temps.get(LocalDate.of(2024, 1, 2)));
    assertEquals(jan3, temps.get(LocalDate.of(2024, 1, 3)));
  }

  @Test
  void get_missingDate_exceptionNamesDate() {
    DailyTemps temps = DailyTemps.of(List.of(new DailyTemp(LocalDate.of(2024, 1, 1), 10.0, 2.0)));
    TemperatureDataException ex =
        assertThrows(TemperatureDataException.class, () -> temps.get(LocalDate.of(2024, 1, 2)));
    assertMessageContains(ex, "2024-01-02");
  }

  @Test
  void emptyCollection_hasSizeZero() {
    assertEquals(0, DailyTemps.of(List.of()).size());
  }

  @Test
  void isImmutable_laterChangesToInputListDoNotLeakIn() {
    DailyTemp jan1 = new DailyTemp(LocalDate.of(2024, 1, 1), 10.0, 2.0);
    List<DailyTemp> days = new ArrayList<>(List.of(jan1));
    DailyTemps temps = DailyTemps.of(days);
    days.add(new DailyTemp(LocalDate.of(2024, 1, 2), 12.0, 4.0));
    assertEquals(1, temps.size());
    assertEquals(jan1, temps.get(LocalDate.of(2024, 1, 1)));
  }

  @Test
  void nullCollectionOrElement_throwsNullPointerException() {
    assertMessageContains(
        assertThrows(NullPointerException.class, () -> DailyTemps.of(null)), "days");
    List<DailyTemp> withNull = new ArrayList<>();
    withNull.add(null);
    assertMessageContains(
        assertThrows(NullPointerException.class, () -> DailyTemps.of(withNull)), "day");
  }

  @Test
  void dailyTemp_nullDate_throwsNullPointerException() {
    assertMessageContains(
        assertThrows(NullPointerException.class, () -> new DailyTemp(null, 10.0, 2.0)), "date");
  }
}
