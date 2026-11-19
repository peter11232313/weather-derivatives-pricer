"""Spec 002 amendment B: per-station observation windows (ADR 0005).

Each acceptance criterion has one or more tests named after it (``test_bN_...``); the
download criteria (B7, B8) are in ``test_download_edge.py``.

Rule under test: a window starting at D 00:00 + s uses the ``mx2t``/``mn2t`` values stamped
D+s+1h .. D+s+24h. Tmax(D) = max ``mx2t`` over the window with s = tmax_window_start_utc_h;
Tmin(D) = min ``mn2t`` over the window with s = tmin_window_start_utc_h. London is 9 / -15
(Tmax: D 10:00 .. D+1 09:00; Tmin: D-1 10:00 .. D 09:00), Paris 6 / -6.
"""

from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
import pytest
from synth import (
    KELVIN,
    LONDON_LATS,
    LONDON_LONS,
    hours,
    linear_c,
    make_era5,
    make_lsm,
    make_station,
    series_c,
    write_edge_day_file,
    write_lsm_file,
    write_step_file,
    write_year_file,
)

from dataprep.daily import daily_extremes, needed_span
from dataprep.errors import DataError, PublishError, RegistryError
from dataprep.pipeline import load_hourly, process
from dataprep.publish import publish, validate
from dataprep.registry import Station, load_registry
from dataprep.write import REQUIRED_METADATA_KEYS

NOW = datetime(2026, 11, 19, 12, 0, tzinfo=UTC)
REG_VERSION = "0123456789ab"
REPO_REGISTRY = Path(__file__).resolve().parents[1] / "stations.toml"
LAT2, LON2 = np.meshgrid(LONDON_LATS, LONDON_LONS, indexing="ij")

TMAX_KEY = "tmax_window_start_utc_h"
TMIN_KEY = "tmin_window_start_utc_h"
LONDON_W = {TMAX_KEY: 9, TMIN_KEY: -15}
PARIS_W = {TMAX_KEY: 6, TMIN_KEY: -6}
UTC_DAY_W = {TMAX_KEY: 0, TMIN_KEY: 0}

# Spec amendment B worked example (London 9 / -15): stamp -> (mx2t, mn2t), degrees C.
# Every other hour: mx2t 8.0, mn2t 3.0.
WORKED_MX = {"2024-01-15T09:00": 14.0, "2024-01-15T20:00": 8.0, "2024-01-16T09:00": 11.0}
WORKED_MN = {"2024-01-15T09:00": 2.0, "2024-01-15T20:00": -1.0, "2024-01-16T09:00": -4.0}


# --------------------------------------------------------------------------- helpers


def values(times: pd.DatetimeIndex, base: float, at: dict[str, float] | None = None) -> np.ndarray:
    """``base`` at every stamp of ``times`` except the stamps listed in ``at``."""
    out = np.full(len(times), base, dtype=np.float64)
    for stamp, value in (at or {}).items():
        out[times.get_loc(pd.Timestamp(stamp))] = value
    return out


def station_series(first, last, mx=8.0, mn=3.0, mx_at=None, mn_at=None, drop=()):
    """Hourly station series (degrees C) from ``first`` to ``last`` inclusive."""
    times = hours(first, last)
    mx_v, mn_v = values(times, mx, mx_at), values(times, mn, mn_at)
    keep = ~times.isin(pd.DatetimeIndex(list(drop)))
    return series_c(mx_v[keep], times[keep]), series_c(mn_v[keep], times[keep])


def era5(times, mx_c, mn_c, spatial=0.0):
    """Raw-file content on the London grid as Copernicus delivers it (K, float64).

    ``mx_c`` / ``mn_c``: degrees C, scalar or one value per stamp (spatially uniform), plus
    an optional ``spatial`` (lat, lon) field in degrees C.
    """
    times = pd.DatetimeIndex(times)
    mx = np.asarray(mx_c, dtype=float)
    mn = np.asarray(mn_c, dtype=float)
    if mx.ndim == 1:
        mx = mx[:, None, None]
    if mn.ndim == 1:
        mn = mn[:, None, None]
    return make_era5(
        times,
        LONDON_LATS,
        LONDON_LONS,
        mx2t_k=mx + spatial + KELVIN,
        mn2t_k=mn + spatial + KELVIN,
        time_name="valid_time",
        lat_descending=True,
        number=True,
        dtype="float64",
    )


def read(path: Path) -> tuple[list[date], list[float], list[float], dict[str, str]]:
    table = pq.read_table(path)
    kv = {k.decode(): v.decode() for k, v in (pq.ParquetFile(path).metadata.metadata or {}).items()}
    return (
        table.column("date").to_pylist(),
        table.column("tmax_c").to_pylist(),
        table.column("tmin_c").to_pylist(),
        kv,
    )


def run(raw_dir, processed_dir, station, start, end, reg=REG_VERSION):
    if not (raw_dir / f"era5_lsm_{station.city}.nc").exists():
        write_lsm_file(raw_dir, station.city, make_lsm(LONDON_LATS, LONDON_LONS))
    return process(station, start, end, raw_dir, processed_dir, reg, NOW)


def worked_example_year_file(raw_dir, spatial=0.0):
    """2024 file holding 2024-01-13 00:00 .. 2024-01-17 23:00 with the worked example."""
    times = hours("2024-01-13T00:00", "2024-01-17T23:00")
    ds = era5(times, values(times, 8.0, WORKED_MX), values(times, 3.0, WORKED_MN), spatial)
    return write_year_file(raw_dir, "london", 2024, ds)


@pytest.fixture
def london_9_15() -> Station:
    return make_station(**LONDON_W)


# --------------------------------------------------------------------------- needed_span


@pytest.mark.parametrize(
    ("tmax_h", "tmin_h", "start", "end", "first", "last"),
    [
        # Spec: London 1991 needs 1990-12-31 10:00 .. 1992-01-01 09:00.
        (9, -15, date(1991, 1, 1), date(1991, 12, 31), "1990-12-31T10:00", "1992-01-01T09:00"),
        # 0 / 0 reproduces ADR 0002: start 01:00 .. end+1 00:00.
        (0, 0, date(2024, 1, 1), date(2024, 1, 1), "2024-01-01T01:00", "2024-01-02T00:00"),
        (0, 0, date(1991, 1, 1), date(1991, 12, 31), "1991-01-01T01:00", "1992-01-01T00:00"),
        # Paris: D-1 19:00 .. D+1 06:00.
        (6, -6, date(2024, 3, 10), date(2024, 3, 10), "2024-03-09T19:00", "2024-03-11T06:00"),
        # Leap day, London.
        (9, -15, date(2024, 2, 29), date(2024, 2, 29), "2024-02-28T10:00", "2024-03-01T09:00"),
        # Order of the two offsets does not matter for the span: min / max.
        (-15, 9, date(2024, 1, 15), date(2024, 1, 15), "2024-01-14T10:00", "2024-01-16T09:00"),
        # Window ending at D 23:00: neither neighbouring day is needed.
        (-1, -1, date(2024, 1, 1), date(2024, 12, 31), "2024-01-01T00:00", "2024-12-31T23:00"),
        # Extremes of -23..23.
        (23, 23, date(2024, 1, 1), date(2024, 1, 1), "2024-01-02T00:00", "2024-01-02T23:00"),
        (-23, -23, date(2024, 1, 1), date(2024, 1, 1), "2023-12-31T02:00", "2024-01-01T01:00"),
    ],
)
def test_needed_span_is_start_plus_min_offset_plus_1h_to_end_plus_1d_plus_max_offset(
    tmax_h, tmin_h, start, end, first, last
):
    span = needed_span(tmax_h, tmin_h, start, end)
    assert tuple(span) == (pd.Timestamp(first), pd.Timestamp(last))


# --------------------------------------------------------------------------- B1


def test_b1_london_worked_example():
    # Needed for 2024-01-14..2024-01-16: 2024-01-13 10:00 .. 2024-01-17 09:00.
    # 2024-01-14: Tmax 01-14 10:00..01-15 09:00 -> 14.0;  Tmin 01-13 10:00..01-14 09:00 -> 3.0
    # 2024-01-15: Tmax 01-15 10:00..01-16 09:00 -> 11.0;  Tmin 01-14 10:00..01-15 09:00 -> 2.0
    # 2024-01-16: Tmax 01-16 10:00..01-17 09:00 -> 8.0;   Tmin 01-15 10:00..01-16 09:00 -> -4.0
    mx, mn = station_series(
        "2024-01-13T10:00", "2024-01-17T09:00", mx_at=WORKED_MX, mn_at=WORKED_MN
    )
    out = daily_extremes(mx, mn, date(2024, 1, 14), date(2024, 1, 16), **LONDON_W)
    np.testing.assert_array_equal(
        out.dates, np.array(["2024-01-14", "2024-01-15", "2024-01-16"], dtype="datetime64[D]")
    )
    assert out.tmax_c.tolist() == [14.0, 11.0, 8.0]
    assert out.tmin_c.tolist() == [3.0, 2.0, -4.0]


def test_b1_worked_example_under_utc_day_windows_gives_the_old_answer():
    # Spec: "The 00:00..24:00 window would give 14.0 / -1.0" for 2024-01-15
    # (stamps 01-15 01:00..01-16 00:00 contain mx 14.0 at 09:00 and mn -1.0 at 20:00).
    mx, mn = station_series(
        "2024-01-13T00:00", "2024-01-17T23:00", mx_at=WORKED_MX, mn_at=WORKED_MN
    )
    out = daily_extremes(mx, mn, date(2024, 1, 15), date(2024, 1, 15), **UTC_DAY_W)
    assert out.tmax_c.tolist() == [14.0]
    assert out.tmin_c.tolist() == [-1.0]


def test_b1_london_worked_example_end_to_end(raw_dir, processed_dir, london_9_15):
    # Same known answer through process(); spatially uniform field, so the bilinear
    # station value equals the cell value (to the K round-trip tolerance).
    worked_example_year_file(raw_dir)
    out = run(raw_dir, processed_dir, london_9_15, date(2024, 1, 14), date(2024, 1, 16))
    dates, tmax, tmin, _ = read(out)
    assert dates == [date(2024, 1, 14), date(2024, 1, 15), date(2024, 1, 16)]
    np.testing.assert_allclose(tmax, [14.0, 11.0, 8.0], atol=1e-9)
    np.testing.assert_allclose(tmin, [3.0, 2.0, -4.0], atol=1e-9)


# --------------------------------------------------------------------------- B2


@pytest.mark.parametrize(
    ("inside_mx", "inside_mn"),
    [("2024-03-10T07:00", "2024-03-09T19:00"), ("2024-03-11T06:00", "2024-03-10T18:00")],
    ids=["first-stamp-of-each-window", "last-stamp-of-each-window"],
)
def test_b2_paris_windows_are_exactly_d0700_to_d1_0600_and_dm1_1900_to_d1800(inside_mx, inside_mn):
    # Paris 6 / -6, D = 2024-03-10. Base mx 10.0 / mn 0.0.
    # One hour outside each window: mx 50.0 at D 06:00, 60.0 at D+1 07:00;
    #                               mn -50.0 at D-1 18:00, -60.0 at D 19:00.
    # One stamp inside, on the window edge: mx 20.0 at inside_mx, mn -20.0 at inside_mn.
    # => Tmax(D) = 20.0, Tmin(D) = -20.0. (A UTC-day window would see 50.0 and -60.0.)
    mx, mn = station_series(
        "2024-03-09T00:00",
        "2024-03-11T23:00",
        mx=10.0,
        mn=0.0,
        mx_at={"2024-03-10T06:00": 50.0, "2024-03-11T07:00": 60.0, inside_mx: 20.0},
        mn_at={"2024-03-09T18:00": -50.0, "2024-03-10T19:00": -60.0, inside_mn: -20.0},
    )
    out = daily_extremes(mx, mn, date(2024, 3, 10), date(2024, 3, 10), **PARIS_W)
    assert out.tmax_c.tolist() == [20.0]
    assert out.tmin_c.tolist() == [-20.0]


def test_b2_paris_needs_only_dm1_1900_to_d1_0600():
    # Exactly the 36 stamps of the needed span are enough (no stamp outside is required).
    mx, mn = station_series("2024-03-09T19:00", "2024-03-11T06:00")
    out = daily_extremes(mx, mn, date(2024, 3, 10), date(2024, 3, 10), **PARIS_W)
    assert out.tmax_c.tolist() == [8.0]
    assert out.tmin_c.tolist() == [3.0]


# --------------------------------------------------------------------------- B3


def test_b3_utc_day_windows_reproduce_the_hour_alignment_example():
    # Spec 002 hour-alignment example with offsets 0 / 0:
    #   2023-12-31 01:00..23:00 mx 8 / mn 6; 2024-01-01 00:00 mx 9 / mn 4 (-> 2023-12-31);
    #   2024-01-01 01:00..23:00 mx 5 / mn 1; 2024-01-02 00:00 mx 12 / mn -3 (-> 2024-01-01).
    # => 2023-12-31: 9.0 / 4.0; 2024-01-01: 12.0 / -3.0.
    times = hours("2023-12-31T01:00", "2024-01-02T00:00")
    mx = [8.0] * 23 + [9.0] + [5.0] * 23 + [12.0]
    mn = [6.0] * 23 + [4.0] + [1.0] * 23 + [-3.0]
    out = daily_extremes(
        series_c(mx, times), series_c(mn, times), date(2023, 12, 31), date(2024, 1, 1), **UTC_DAY_W
    )
    assert out.tmax_c.tolist() == [9.0, 12.0]
    assert out.tmin_c.tolist() == [4.0, -3.0]


def test_b3_window_offsets_are_required_keyword_arguments():
    # Decision 3: no default (a silent 00:00 default is how the mismatch happened).
    mx, mn = station_series("2024-01-01T01:00", "2024-01-02T00:00")
    day = date(2024, 1, 1)
    with pytest.raises(TypeError):
        daily_extremes(mx, mn, day, day)
    with pytest.raises(TypeError):
        daily_extremes(mx, mn, day, day, tmax_window_start_utc_h=0)
    with pytest.raises(TypeError):
        daily_extremes(mx, mn, day, day, 0, 0)  # keyword-only


def test_b3_utc_day_windows_still_use_year_file_midnight_end_to_end(raw_dir, processed_dir):
    # 0 / 0 through process(): 2023-12-31 takes 2024-01-01 00:00 (12 / -3) from the 2024
    # year file; 2023-12-31 00:00 (40 / -40) belongs to 2023-12-30.
    write_year_file(
        raw_dir,
        "london",
        2023,
        era5(
            hours("2023-12-31T00:00", "2023-12-31T23:00"), [40.0] + [5.0] * 23, [-40.0] + [1.0] * 23
        ),
    )
    write_year_file(
        raw_dir,
        "london",
        2024,
        era5(
            hours("2024-01-01T00:00", "2024-01-01T05:00"), [12.0] + [30.0] * 5, [-3.0] + [-30.0] * 5
        ),
    )
    out = run(
        raw_dir, processed_dir, make_station(**UTC_DAY_W), date(2023, 12, 31), date(2023, 12, 31)
    )
    _, tmax, tmin, _ = read(out)
    np.testing.assert_allclose(tmax, [12.0], atol=1e-9)
    np.testing.assert_allclose(tmin, [-3.0], atol=1e-9)


# --------------------------------------------------------------------------- windows: edges


@pytest.mark.parametrize(
    "day", [date(2024, 3, 31), date(2024, 10, 27)], ids=["bst-starts", "bst-ends"]
)
def test_windows_are_utc_with_no_daylight_saving_shift(day):
    # UK clocks change on these days; windows stay 24 UTC hours. London 9 / -15:
    # Tmax(D): D 10:00 .. D+1 09:00 -> 30.0 at D+1 09:00 counts, 40.0 at D+1 10:00 does not.
    # Tmin(D): D-1 10:00 .. D 09:00 -> -10.0 at D-1 10:00 counts, -20.0 at D-1 09:00 does not.
    d = pd.Timestamp(day)
    mx, mn = station_series(
        (d - pd.Timedelta(days=1)).isoformat(),
        (d + pd.Timedelta(days=2)).isoformat(),
        mx_at={
            (d + pd.Timedelta(hours=33)).isoformat(): 30.0,
            (d + pd.Timedelta(hours=34)).isoformat(): 40.0,
        },
        mn_at={
            (d - pd.Timedelta(hours=14)).isoformat(): -10.0,
            (d - pd.Timedelta(hours=15)).isoformat(): -20.0,
        },
    )
    out = daily_extremes(mx, mn, day, day, **LONDON_W)
    assert out.tmax_c.tolist() == [30.0]
    assert out.tmin_c.tolist() == [-10.0]


def test_london_windows_across_leap_day():
    # 2024-02-28..2024-03-01, London 9 / -15; base 8 / 3.
    # mx 20.0 at 03-01 09:00 ends Tmax(02-29)'s window; mn -5.0 at 02-29 09:00 ends Tmin(02-29)'s.
    # => tmax [8, 20, 8], tmin [3, -5, 3].
    mx, mn = station_series(
        "2024-02-27T10:00",
        "2024-03-02T09:00",
        mx_at={"2024-03-01T09:00": 20.0},
        mn_at={"2024-02-29T09:00": -5.0},
    )
    out = daily_extremes(mx, mn, date(2024, 2, 28), date(2024, 3, 1), **LONDON_W)
    np.testing.assert_array_equal(
        out.dates, np.array(["2024-02-28", "2024-02-29", "2024-03-01"], dtype="datetime64[D]")
    )
    assert out.tmax_c.tolist() == [8.0, 20.0, 8.0]
    assert out.tmin_c.tolist() == [3.0, -5.0, 3.0]


@pytest.mark.parametrize(("year", "n_days"), [(2024, 366), (2023, 365)])
def test_london_full_year_row_count(year, n_days):
    first, last = needed_span(9, -15, date(year, 1, 1), date(year, 12, 31))
    mx, mn = station_series(first.isoformat(), last.isoformat())
    out = daily_extremes(mx, mn, date(year, 1, 1), date(year, 12, 31), **LONDON_W)
    assert len(out.dates) == n_days
    assert out.dates[0] == np.datetime64(f"{year}-01-01")
    assert out.dates[-1] == np.datetime64(f"{year}-12-31")


def test_flat_series_gives_tmin_equal_tmax_under_shifted_windows():
    # Zero variability: mx == mn == 4.0 every hour -> Tmax == Tmin == 4.0 (allowed).
    mx, mn = station_series("2024-01-14T10:00", "2024-01-16T09:00", mx=4.0, mn=4.0)
    out = daily_extremes(mx, mn, date(2024, 1, 15), date(2024, 1, 15), **LONDON_W)
    assert out.tmax_c.tolist() == [4.0] and out.tmin_c.tolist() == [4.0]


# --------------------------------------------------------------------------- NaN messages


def test_nan_in_mn2t_names_variable_stamp_and_the_tmin_day():
    # London 9 / -15, 2024-01-15..2024-01-16. Stamp 2024-01-16 05:00 is in Tmax(01-15)'s
    # window AND Tmin(01-16)'s window; the NaN is only in mn2t, so the message must blame
    # Tmin of day 2024-01-16, not Tmax of 2024-01-15.
    mx, mn = station_series(
        "2024-01-14T10:00", "2024-01-17T09:00", mn_at={"2024-01-16T05:00": np.nan}
    )
    with pytest.raises(DataError) as err:
        daily_extremes(mx, mn, date(2024, 1, 15), date(2024, 1, 16), **LONDON_W)
    message = str(err.value)
    assert "mn2t" in message
    assert "2024-01-16T05:00" in message
    assert "Tmin of day 2024-01-16" in message
    assert "Tmax" not in message


def test_nan_in_mx2t_names_variable_stamp_and_the_tmax_day():
    # 2024-01-16 09:00 is the last stamp of Tmax(2024-01-15)'s window.
    mx, mn = station_series(
        "2024-01-14T10:00", "2024-01-17T09:00", mx_at={"2024-01-16T09:00": np.nan}
    )
    with pytest.raises(DataError) as err:
        daily_extremes(mx, mn, date(2024, 1, 15), date(2024, 1, 16), **LONDON_W)
    message = str(err.value)
    assert "mx2t" in message
    assert "2024-01-16T09:00" in message
    assert "Tmax of day 2024-01-15" in message


@pytest.mark.parametrize(
    ("variable", "stamp", "expected"),
    [
        ("mx2t", "2024-03-11T03:00", "Tmax of day 2024-03-10"),
        ("mn2t", "2024-03-09T20:00", "Tmin of day 2024-03-10"),
    ],
)
def test_nan_message_names_the_paris_settlement_day(variable, stamp, expected):
    nan = {stamp: np.nan}
    mx, mn = station_series(
        "2024-03-09T19:00",
        "2024-03-11T06:00",
        mx_at=nan if variable == "mx2t" else None,
        mn_at=nan if variable == "mn2t" else None,
    )
    with pytest.raises(DataError) as err:
        daily_extremes(mx, mn, date(2024, 3, 10), date(2024, 3, 10), **PARIS_W)
    assert variable in str(err.value)
    assert stamp in str(err.value)
    assert expected in str(err.value)


def test_nan_outside_a_variables_own_windows_is_not_an_error():
    # London 9 / -15, D = 2024-01-15. mx2t is only used over 01-15 10:00..01-16 09:00 and
    # mn2t only over 01-14 10:00..01-15 09:00. NaN mx2t at 01-14 12:00 and NaN mn2t at
    # 01-15 12:00 are inside the needed span but in no window of their own variable.
    mx, mn = station_series(
        "2024-01-14T10:00",
        "2024-01-16T09:00",
        mx_at={"2024-01-14T12:00": np.nan},
        mn_at={"2024-01-15T12:00": np.nan},
    )
    out = daily_extremes(mx, mn, date(2024, 1, 15), date(2024, 1, 15), **LONDON_W)
    assert out.tmax_c.tolist() == [8.0]
    assert out.tmin_c.tolist() == [3.0]


# --------------------------------------------------------------------------- B4


def window_entry(key: str, tmax: str | None, tmin: str | None) -> str:
    """A registry entry with the given TOML literals for the two window fields (None: omit)."""
    lines = [
        f"[{key}]",
        'station = "Somewhere"',
        'wmo_id = "00000"',
        'icao = "ZZZZ"',
        "lat = 51.479",
        "lon = -0.449",
    ]
    if tmax is not None:
        lines.append(f"{TMAX_KEY} = {tmax}")
    if tmin is not None:
        lines.append(f"{TMIN_KEY} = {tmin}")
    return "\n".join(lines) + "\n"


def write_registry(tmp_path: Path, text: str) -> Path:
    path = tmp_path / "stations.toml"
    path.write_text(text, encoding="utf-8")
    return path


@pytest.mark.parametrize(
    ("tmax", "tmin", "field"),
    [
        (None, "-15", TMAX_KEY),
        ("9", None, TMIN_KEY),
        ("true", "-15", TMAX_KEY),
        ("9", "false", TMIN_KEY),
        ("9.0", "-15", TMAX_KEY),
        ("9", "-15.0", TMIN_KEY),
        ('"9"', "-15", TMAX_KEY),
        ("24", "0", TMAX_KEY),
        ("-24", "0", TMAX_KEY),
        ("0", "24", TMIN_KEY),
        ("0", "-24", TMIN_KEY),
        ("12", "-13", None),  # |diff| 25: windows neither overlap nor touch
        ("-13", "12", None),
        ("23", "-23", None),
    ],
    ids=[
        "tmax-missing",
        "tmin-missing",
        "tmax-bool",
        "tmin-bool",
        "tmax-float",
        "tmin-float",
        "tmax-string",
        "tmax-24",
        "tmax-minus-24",
        "tmin-24",
        "tmin-minus-24",
        "diff-25",
        "diff-25-reversed",
        "diff-46",
    ],
)
def test_b4_invalid_window_field_is_rejected_naming_entry_and_field(tmp_path, tmax, tmin, field):
    text = window_entry("london", "9", "-15") + window_entry("badwin", tmax, tmin)
    with pytest.raises(RegistryError) as err:
        load_registry(write_registry(tmp_path, text))
    message = str(err.value)
    assert "[badwin]" in message
    if field is None:
        assert TMAX_KEY in message or TMIN_KEY in message
    else:
        assert field in message


@pytest.mark.parametrize(
    ("tmax", "tmin"),
    [(9, -15), (-15, 9), (0, 0), (6, -6), (12, -12), (23, -1), (-23, 1), (23, 23), (-23, -23)],
)
def test_b4_valid_window_fields_are_loaded_as_ints_including_touching_windows(tmp_path, tmax, tmin):
    # |tmax - tmin| == 24 (windows touch) is allowed, as is the full -23..23 range.
    path = write_registry(tmp_path, window_entry("okwin", str(tmax), str(tmin)))
    station = load_registry(path)["okwin"]
    assert station.tmax_window_start_utc_h == tmax
    assert station.tmin_window_start_utc_h == tmin
    assert type(station.tmax_window_start_utc_h) is int
    assert type(station.tmin_window_start_utc_h) is int


def test_b4_station_window_fields_have_no_default():
    with pytest.raises(TypeError):
        Station(city="x", station="X", wmo_id="00000", icao="ZZZZ", lat_deg=51.0, lon_deg=0.0)


# --------------------------------------------------------------------------- B5


def test_b5_london_1991_reads_edge_day_and_next_year_file_each_hour_once(
    raw_dir, processed_dir, london_9_15
):
    # London 9 / -15, 1991-01-01..1991-12-31. Needed: 1990-12-31 10:00 .. 1992-01-01 09:00
    # (14 + 8760 + 10 = 8784 hours). Marker values (mx / mn, degrees C):
    #   1991 year file: 10 / 0 every hour, plus a stray 1992-01-01 00:00 stamp 77 / -77
    #     (1992-01-01 belongs to the 1992 year file).
    #   edge file era5_london_19901231.nc: 00:00..09:00 50 / -50 (before the span),
    #     10:00 5 / -7, 11:00..23:00 5 / -5, plus a stray 1991-01-01 00:00 stamp 66 / -66
    #     (1991 has a year file; an edge file supplies only its own day).
    #   1992 year file: 1992-01-01 00:00..08:00 15 / 1, 09:00 25 / 1, 10:00..23:00 60 / -60.
    #   Never read: edge file 19920101 (99 / -99; the 1992 year file exists), stray edge
    #     file 19910101 (98 / -98) and old step files 19910101T00 / 19920101T00 (97 / -97).
    # Known answer: Tmin(1991-01-01) = min(-7, -5, 0) = -7.0 (from the edge file);
    #   Tmax(1991-12-31) = max(10, 15, 25) = 25.0 (1992-01-01 00:00..09:00 from 1992 file);
    #   every other day 10.0 / 0.0.
    y1991 = hours("1991-01-01T00:00", "1992-01-01T00:00")
    write_year_file(
        raw_dir,
        "london",
        1991,
        era5(
            y1991,
            values(y1991, 10.0, {"1992-01-01T00:00": 77.0}),
            values(y1991, 0.0, {"1992-01-01T00:00": -77.0}),
        ),
    )
    edge = hours("1990-12-31T00:00", "1991-01-01T00:00")
    edge_mx = [50.0] * 10 + [5.0] * 14 + [66.0]
    edge_mn = [-50.0] * 10 + [-7.0] + [-5.0] * 13 + [-66.0]
    write_edge_day_file(raw_dir, "london", date(1990, 12, 31), era5(edge, edge_mx, edge_mn))
    y1992 = hours("1992-01-01T00:00", "1992-01-01T23:00")
    write_year_file(
        raw_dir,
        "london",
        1992,
        era5(y1992, [15.0] * 9 + [25.0] + [60.0] * 14, [1.0] * 10 + [-60.0] * 14),
    )
    day_1992 = hours("1992-01-01T00:00", "1992-01-01T23:00")
    write_edge_day_file(raw_dir, "london", date(1992, 1, 1), era5(day_1992, 99.0, -99.0))
    day_1991 = hours("1991-01-01T00:00", "1991-01-01T23:00")
    write_edge_day_file(raw_dir, "london", date(1991, 1, 1), era5(day_1991, 98.0, -98.0))
    for year in (1991, 1992):
        step = hours(f"{year}-01-01T00:00", f"{year}-01-01T00:00")
        write_step_file(raw_dir, "london", year, era5(step, 97.0, -97.0))

    hourly = load_hourly(london_9_15, date(1991, 1, 1), date(1991, 12, 31), raw_dir)
    times = pd.DatetimeIndex(hourly["time"].values)
    assert times.is_unique
    assert list(times) == list(hours("1990-12-31T10:00", "1992-01-01T09:00"))
    assert len(times) == 8784
    cell = {"latitude": 51.5, "longitude": -0.5}

    def at(var: str, stamp: str) -> float:
        return float(hourly[var].sel(time=pd.Timestamp(stamp), **cell).values)

    assert at("mn2t", "1990-12-31T10:00") == pytest.approx(-7.0, abs=1e-9)  # edge file
    assert at("mn2t", "1990-12-31T23:00") == pytest.approx(-5.0, abs=1e-9)  # edge file
    assert at("mn2t", "1991-01-01T00:00") == pytest.approx(0.0, abs=1e-9)  # 1991 file
    assert at("mx2t", "1992-01-01T00:00") == pytest.approx(15.0, abs=1e-9)  # 1992 file
    assert at("mx2t", "1992-01-01T09:00") == pytest.approx(25.0, abs=1e-9)  # 1992 file

    out = run(raw_dir, processed_dir, london_9_15, date(1991, 1, 1), date(1991, 12, 31))
    dates, tmax, tmin, _ = read(out)
    assert len(dates) == 365
    assert dates[0] == date(1991, 1, 1) and dates[-1] == date(1991, 12, 31)
    assert tmin[0] == pytest.approx(-7.0, abs=1e-9)
    assert tmax[-1] == pytest.approx(25.0, abs=1e-9)
    np.testing.assert_allclose(tmax[:-1], 10.0, atol=1e-9)
    np.testing.assert_allclose(tmin[1:], 0.0, atol=1e-9)


def test_b5_year_file_is_preferred_over_an_edge_day_file(raw_dir, processed_dir, london_9_15):
    # Both the 1990 year file (mn -3.0 on 1990-12-31) and the edge file (mn -9.0) exist:
    # every hour comes from the year file -> Tmin(1991-01-01) = -3.0.
    write_year_file(
        raw_dir, "london", 1991, era5(hours("1991-01-01T00:00", "1991-01-02T23:00"), 10.0, 0.0)
    )
    day = hours("1990-12-31T00:00", "1990-12-31T23:00")
    write_year_file(raw_dir, "london", 1990, era5(day, 10.0, -3.0))
    write_edge_day_file(raw_dir, "london", date(1990, 12, 31), era5(day, 10.0, -9.0))
    out = run(raw_dir, processed_dir, london_9_15, date(1991, 1, 1), date(1991, 1, 1))
    _, tmax, tmin, _ = read(out)
    np.testing.assert_allclose(tmax, [10.0], atol=1e-9)
    np.testing.assert_allclose(tmin, [-3.0], atol=1e-9)


def test_b5_no_edge_day_needed_away_from_year_boundaries(raw_dir, processed_dir, london_9_15):
    # 1991-03-01..1991-03-31: the needed span 02-28 10:00..04-01 09:00 is all in 1991.
    write_year_file(
        raw_dir, "london", 1991, era5(hours("1991-02-28T00:00", "1991-04-01T23:00"), 10.0, 0.0)
    )
    out = run(raw_dir, processed_dir, london_9_15, date(1991, 3, 1), date(1991, 3, 31))
    dates, _, _, _ = read(out)
    assert len(dates) == 31


# --------------------------------------------------------------------------- B6


def test_b6_missing_hour_in_edge_day_names_the_timestamp(raw_dir, processed_dir, london_9_15):
    write_year_file(
        raw_dir, "london", 1991, era5(hours("1991-01-01T00:00", "1991-01-02T23:00"), 10.0, 0.0)
    )
    edge = hours("1990-12-31T00:00", "1990-12-31T23:00").delete(15)  # drops 15:00
    write_edge_day_file(raw_dir, "london", date(1990, 12, 31), era5(edge, 10.0, 0.0))
    with pytest.raises(DataError) as err:
        run(raw_dir, processed_dir, london_9_15, date(1991, 1, 1), date(1991, 1, 1))
    assert "1990-12-31T15:00" in str(err.value)
    assert list(processed_dir.iterdir()) == []


@pytest.mark.parametrize(
    "dropped",
    ["2024-01-14T10:00", "2024-01-15T03:00", "2024-01-16T09:00"],
    ids=["first-needed", "interior", "last-needed"],
)
def test_b6_missing_step_in_the_needed_span_names_the_timestamp(dropped):
    # London 9 / -15, D = 2024-01-15: needed 2024-01-14 10:00 .. 2024-01-16 09:00.
    mx, mn = station_series("2024-01-14T10:00", "2024-01-16T09:00", drop=[dropped])
    with pytest.raises(DataError) as err:
        daily_extremes(mx, mn, date(2024, 1, 15), date(2024, 1, 15), **LONDON_W)
    assert dropped in str(err.value)


def test_b6_missing_edge_day_and_year_file_names_the_path(raw_dir, london_9_15):
    write_year_file(
        raw_dir, "london", 1991, era5(hours("1991-01-01T00:00", "1991-01-02T23:00"), 10.0, 0.0)
    )
    with pytest.raises(DataError) as err:
        load_hourly(london_9_15, date(1991, 1, 1), date(1991, 1, 1), raw_dir)
    assert "era5_london_1990" in str(err.value)  # the 1990 year file or the 19901231 edge file


# --------------------------------------------------------------------------- B9


def test_b9_processed_metadata_has_the_registry_windows(tmp_path, raw_dir, processed_dir):
    registry = write_registry(tmp_path, window_entry("london", "6", "-6"))
    station = load_registry(registry)["london"]
    write_year_file(
        raw_dir, "london", 2024, era5(hours("2024-01-13T00:00", "2024-01-17T23:00"), 8.0, 3.0)
    )
    out = run(raw_dir, processed_dir, station, date(2024, 1, 14), date(2024, 1, 16))
    _, _, _, kv = read(out)
    assert kv[TMAX_KEY] == "6"
    assert kv[TMIN_KEY] == "-6"


def test_b9_required_metadata_keys_include_the_windows():
    assert TMAX_KEY in REQUIRED_METADATA_KEYS
    assert TMIN_KEY in REQUIRED_METADATA_KEYS


def test_b9_diagnostics_use_the_station_windows(raw_dir, processed_dir, london_9_15):
    # Worked example in time plus the linear field linear_c in space (degrees C).
    # Nearest cell (51.5, -0.5) and the 3x3 mean around it both add linear_c = 14.0.
    # Windows 9 / -15 (B1): Tmax [14, 11, 8], Tmin [3, 2, -4] -> mean (Tmax+Tmin)/2
    #   = (8.5 + 6.5 + 2.0) / 3 = 17/3 -> diagnostics 14 + 17/3 = 19.6667.
    # (UTC-day windows would give [8, 14, 11] / [3, -1, -4] -> 14 + 15.5/3 = 19.1667.)
    # Station (bilinear, 51.479 / -0.449): linear_c = 14.072 -> tmax 28.072, 25.072, 22.072.
    worked_example_year_file(raw_dir, spatial=linear_c(LAT2, LON2))
    out = run(raw_dir, processed_dir, london_9_15, date(2024, 1, 14), date(2024, 1, 16))
    _, tmax, tmin, kv = read(out)
    np.testing.assert_allclose(tmax, [28.072, 25.072, 22.072], atol=1e-9)
    np.testing.assert_allclose(tmin, [17.072, 16.072, 10.072], atol=1e-9)
    assert float(kv["tmean_nearest_c_mean"]) == pytest.approx(14.0 + 17.0 / 3.0, abs=1e-9)
    assert float(kv["tmean_3x3_c_mean"]) == pytest.approx(14.0 + 17.0 / 3.0, abs=1e-9)
    assert kv[TMAX_KEY] == "9"
    assert kv[TMIN_KEY] == "-15"


VALID_META = {
    "source": "ERA5 mx2t/mn2t",
    "method": "bilinear",
    "station": "London Heathrow",
    "lat": "51.479",
    "lon": "-0.449",
    "start": "2024-01-01",
    "end": "2024-01-03",
    "registry_version": "0123456789ab",
    "created_utc": "2026-11-19T12:00:00Z",
    "tmean_nearest_c_mean": "5.0",
    "tmean_3x3_c_mean": "5.0",
    TMAX_KEY: "9",
    TMIN_KEY: "-15",
}


def processed_file(path: Path, metadata: dict[str, str]) -> Path:
    """A 3-day processed file written directly with pyarrow (independent of write_parquet)."""
    table = pa.table(
        {
            "date": pa.array([date(2024, 1, 1) + timedelta(days=i) for i in range(3)], pa.date32()),
            "tmax_c": pa.array([8.0, 9.0, 7.0], pa.float64()),
            "tmin_c": pa.array([1.0, 2.0, 0.0], pa.float64()),
        }
    )
    table = table.replace_schema_metadata({k.encode(): v.encode() for k, v in metadata.items()})
    path.parent.mkdir(parents=True, exist_ok=True)
    pq.write_table(table, path, compression="snappy")
    return path


def _without(key):
    return {k: v for k, v in VALID_META.items() if k != key}


BAD_META = {
    "tmax-missing": (_without(TMAX_KEY), TMAX_KEY),
    "tmin-missing": (_without(TMIN_KEY), TMIN_KEY),
    **{
        f"{name}-{label}": (VALID_META | {key: bad}, key)
        for name, key in (("tmax", TMAX_KEY), ("tmin", TMIN_KEY))
        for label, bad in (
            ("float", "9.0"),
            ("word", "nine"),
            ("empty", ""),
            ("bool", "True"),
            ("24", "24"),
            ("minus-24", "-24"),
        )
    },
    "diff-25": (VALID_META | {TMAX_KEY: "12", TMIN_KEY: "-13"}, None),
    "diff-25-reversed": (VALID_META | {TMAX_KEY: "-13", TMIN_KEY: "12"}, None),
    "diff-46": (VALID_META | {TMAX_KEY: "23", TMIN_KEY: "-23"}, None),
}


@pytest.mark.parametrize(("metadata", "key"), BAD_META.values(), ids=BAD_META.keys())
def test_b9_publish_rejects_missing_or_invalid_window_metadata(
    processed_dir, pricer_dir, metadata, key
):
    path = processed_file(processed_dir / "london.parquet", metadata)
    with pytest.raises(PublishError) as err:
        validate(path)
    message = str(err.value)
    if key is None:
        assert TMAX_KEY in message or TMIN_KEY in message
    else:
        assert key in message
    with pytest.raises(PublishError):
        publish("london", processed_dir, pricer_dir)
    assert list(pricer_dir.iterdir()) == []


@pytest.mark.parametrize(
    ("tmax", "tmin"), [("9", "-15"), ("0", "0"), ("6", "-6"), ("23", "-1"), ("-23", "1")]
)
def test_b9_publish_accepts_valid_window_metadata(tmp_path, tmax, tmin):
    path = processed_file(
        tmp_path / "london.parquet", VALID_META | {TMAX_KEY: tmax, TMIN_KEY: tmin}
    )
    assert validate(path) is None


# --------------------------------------------------------------------------- B10


def test_b10_repo_registry_london_has_windows_9_and_minus_15():
    london = load_registry(REPO_REGISTRY)["london"]
    assert london.tmax_window_start_utc_h == 9
    assert london.tmin_window_start_utc_h == -15
