"""Spec 002 amendment B: edge-day downloads (criteria B7, B8; ADR 0005).

An edge day is a day just outside ``start..end`` holding a needed hourly stamp (from
``needed_span`` with the station's window offsets) whose year file is absent. It is
fetched in its own request (every hour 00:00..23:00 of that one day) into
``era5_<city>_<YYYYMMDD>.nc``. The old one-step ``…0101T00`` request is gone.
"""

from datetime import UTC, date, datetime
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow.parquet as pq
import pytest
import xarray as xr
from synth import (
    LONDON_LATS,
    LONDON_LONS,
    FakeCdsClient,
    cds_era5,
    edge_day_of,
    hours,
    is_edge_day_request,
    is_lsm_request,
    is_single_step_request,
    make_station,
)

from dataprep.download import MN2T, MX2T, download_edge_day, download_range, edge_day_file
from dataprep.errors import DownloadError
from dataprep.pipeline import process
from dataprep.registry import Station

NOW = datetime(2026, 11, 19, 12, 0, tzinfo=UTC)
LONDON_AREA = [52.0, -1.0, 50.75, 0.25]
MONTHS = [f"{m:02d}" for m in range(1, 13)]
DAYS = [f"{d:02d}" for d in range(1, 32)]
TIMES = [f"{h:02d}:00" for h in range(24)]


def year_request(year: int) -> dict:
    """Known answer: a single-year temperature request, keys in this order."""
    return {
        "product_type": ["reanalysis"],
        "variable": [MX2T, MN2T],
        "year": [str(year)],
        "month": MONTHS,
        "day": DAYS,
        "time": TIMES,
        "area": LONDON_AREA,
        "data_format": "netcdf",
        "download_format": "zip",
    }


def edge_request(day: date) -> dict:
    """Known answer: same keys and order as a year request; one day, all 24 times."""
    return year_request(day.year) | {"month": [f"{day.month:02d}"], "day": [f"{day.day:02d}"]}


def present_years(raw_dir: Path, years) -> None:
    for year in years:
        (raw_dir / f"era5_london_{year}.nc").write_bytes(b"year file present")


def temperature_requests(client: FakeCdsClient) -> list[dict]:
    return [r for r in client.requests if not is_lsm_request(r)]


def no_old_step_request(client: FakeCdsClient, raw_dir: Path) -> bool:
    return (
        not any(is_single_step_request(r) for r in client.requests)
        and not any("0101T00" in target for _, _, target in client.calls)
        and not any("0101T00" in p.name for p in raw_dir.iterdir())
    )


def names_day(message: str, day: date) -> bool:
    return day.isoformat() in message or f"{day:%Y%m%d}" in message


@pytest.fixture
def london_9_15() -> Station:
    return make_station(tmax_window_start_utc_h=9, tmin_window_start_utc_h=-15)


# --------------------------------------------------------------------------- names


@pytest.mark.parametrize(
    ("day", "name"),
    [
        (date(1990, 12, 31), "era5_london_19901231.nc"),
        (date(2025, 1, 1), "era5_london_20250101.nc"),
        (date(2024, 2, 29), "era5_london_20240229.nc"),
    ],
)
def test_edge_day_file_name(raw_dir, day, name):
    assert edge_day_file(raw_dir, "london", day) == raw_dir / name


# --------------------------------------------------------------------------- download_edge_day


@pytest.mark.parametrize("day", [date(1990, 12, 31), date(2025, 1, 1), date(2024, 2, 29)])
def test_edge_day_request_is_a_year_request_for_one_day_with_all_24_times(raw_dir, london, day):
    client = FakeCdsClient()
    out = download_edge_day(client, london, day, raw_dir)
    assert out == raw_dir / f"era5_london_{day:%Y%m%d}.nc"
    assert out.is_file()
    ((_, request, target),) = client.calls
    assert request == edge_request(day)
    assert list(request) == list(year_request(day.year))
    assert target != str(out)  # temporary name, then renamed into place


def test_edge_day_file_holds_exactly_the_24_hours_of_that_day(raw_dir, london):
    out = download_edge_day(FakeCdsClient(), london, date(1990, 12, 31), raw_dir)
    with xr.open_dataset(out) as ds:
        name = "valid_time" if "valid_time" in ds.coords else "time"
        got = pd.DatetimeIndex(ds[name].values)
    assert list(got) == list(hours("1990-12-31T00:00", "1990-12-31T23:00"))
    assert [p.name for p in raw_dir.iterdir()] == ["era5_london_19901231.nc"]


def test_existing_edge_day_file_is_not_downloaded_again(raw_dir, london):
    existing = raw_dir / "era5_london_20250101.nc"
    existing.write_bytes(b"already here")
    client = FakeCdsClient()
    assert download_edge_day(client, london, date(2025, 1, 1), raw_dir) == existing
    assert client.calls == []
    assert existing.read_bytes() == b"already here"


# --------------------------------------------------------------------------- B7


def test_b7_london_1991_2024_with_all_year_files_requests_exactly_the_two_edge_days(
    raw_dir, london_9_15
):
    present_years(raw_dir, range(1991, 2025))
    client = FakeCdsClient()
    paths = download_range(client, london_9_15, date(1991, 1, 1), date(2024, 12, 31), raw_dir)
    requests = temperature_requests(client)
    assert len(requests) == 2
    assert sorted(requests, key=edge_day_of) == [
        edge_request(date(1990, 12, 31)),
        edge_request(date(2025, 1, 1)),
    ]
    for request in requests:
        assert list(request) == list(year_request(1991))
    for name in ("era5_london_19901231.nc", "era5_london_20250101.nc"):
        assert raw_dir / name in paths
        assert (raw_dir / name).is_file()
    assert no_old_step_request(client, raw_dir)


@pytest.mark.parametrize(
    ("tmax_h", "tmin_h", "expected"),
    [
        (9, -15, [date(1990, 12, 31), date(2025, 1, 1)]),
        (0, 0, [date(2025, 1, 1)]),  # first needed stamp 1991-01-01 01:00: no previous day
        (6, -6, [date(1990, 12, 31), date(2025, 1, 1)]),
        (-1, -1, []),  # needed 1991-01-01 00:00 .. 2024-12-31 23:00
        (-2, -2, [date(1990, 12, 31)]),  # needed 1990-12-31 23:00 .. 2024-12-31 22:00
        (23, 23, [date(2025, 1, 1)]),  # needed 1991-01-02 00:00 .. 2025-01-01 23:00
        (-23, 1, [date(1990, 12, 31), date(2025, 1, 1)]),
    ],
)
def test_b7_edge_days_follow_the_needed_span_of_the_station_windows(
    raw_dir, tmax_h, tmin_h, expected
):
    present_years(raw_dir, range(1991, 2025))
    station = make_station(tmax_window_start_utc_h=tmax_h, tmin_window_start_utc_h=tmin_h)
    client = FakeCdsClient()
    download_range(client, station, date(1991, 1, 1), date(2024, 12, 31), raw_dir)
    assert sorted(edge_day_of(r) for r in temperature_requests(client)) == expected
    assert all(is_edge_day_request(r) for r in temperature_requests(client))
    assert no_old_step_request(client, raw_dir)


def test_b7_utc_day_station_sends_no_previous_day_request(raw_dir, london):
    present_years(raw_dir, range(1991, 2025))
    client = FakeCdsClient()
    download_range(client, london, date(1991, 1, 1), date(2024, 12, 31), raw_dir)
    assert temperature_requests(client) == [edge_request(date(2025, 1, 1))]
    assert not (raw_dir / "era5_london_19901231.nc").exists()


def test_b7_no_edge_day_is_requested_when_its_year_file_exists(raw_dir, london_9_15):
    present_years(raw_dir, range(1990, 2026))
    client = FakeCdsClient()
    paths = download_range(client, london_9_15, date(1991, 1, 1), date(2024, 12, 31), raw_dir)
    assert temperature_requests(client) == []
    assert raw_dir / "era5_london_1990.nc" in paths
    assert raw_dir / "era5_london_2025.nc" in paths
    assert not (raw_dir / "era5_london_19901231.nc").exists()
    assert not (raw_dir / "era5_london_20250101.nc").exists()
    assert no_old_step_request(client, raw_dir)


def test_b7_only_the_edge_day_whose_year_file_is_absent_is_requested(raw_dir, london_9_15):
    present_years(raw_dir, range(1990, 2025))  # 1990 present, 2025 absent
    client = FakeCdsClient()
    download_range(client, london_9_15, date(1991, 1, 1), date(2024, 12, 31), raw_dir)
    assert temperature_requests(client) == [edge_request(date(2025, 1, 1))]


def test_b7_present_edge_day_files_are_not_requested_again(raw_dir, london_9_15):
    present_years(raw_dir, range(1991, 2025))
    for name in ("era5_london_19901231.nc", "era5_london_20250101.nc"):
        (raw_dir / name).write_bytes(b"edge day present")
    client = FakeCdsClient()
    paths = download_range(client, london_9_15, date(1991, 1, 1), date(2024, 12, 31), raw_dir)
    assert temperature_requests(client) == []
    for name in ("era5_london_19901231.nc", "era5_london_20250101.nc"):
        assert raw_dir / name in paths
        assert (raw_dir / name).read_bytes() == b"edge day present"


def test_b7_rerun_after_a_full_download_makes_no_requests(raw_dir, london_9_15):
    present_years(raw_dir, range(1991, 2025))
    download_range(FakeCdsClient(), london_9_15, date(1991, 1, 1), date(2024, 12, 31), raw_dir)
    client = FakeCdsClient()
    download_range(client, london_9_15, date(1991, 1, 1), date(2024, 12, 31), raw_dir)
    assert client.calls == []


def test_b7_mid_year_range_needs_no_edge_day(raw_dir, london_9_15):
    # 1991-03-01..1991-03-31: needed 02-28 10:00..04-01 09:00, all in the 1991 year file.
    present_years(raw_dir, [1991])
    client = FakeCdsClient()
    download_range(client, london_9_15, date(1991, 3, 1), date(1991, 3, 31), raw_dir)
    assert temperature_requests(client) == []


def test_b7_downloaded_edge_day_is_processed_with_the_station_windows(tmp_path, london_9_15):
    # Download (fake CDS on the station grid) and process London 9 / -15, 2024-01-01..02:
    # the 2024 year file plus the edge day 2023-12-31 (2024-01-03 09:00 is in the 2024 file).
    # The fake field is linear in lat/lon, so the bilinear station value is exact:
    #   mx2t(h) = 270 + (h % 997) * 0.03 + (h % 24) * 0.25 + 4 (lat - 51) + 2 lon   [K]
    #   mn2t(h) = mx2t(h) - 3 - (h % 7) * 0.5, h = whole hours since 1970-01-01 00:00 UTC.
    # Expected: Tmax(D) = max mx2t over D 10:00..D+1 09:00, Tmin(D) = min mn2t over
    # D-1 10:00..D 09:00 (float32 storage: tolerance 1e-4).
    raw, processed = tmp_path / "raw", tmp_path / "processed"
    client = FakeCdsClient(grid_from_area=True)
    download_range(client, london_9_15, date(2024, 1, 1), date(2024, 1, 2), raw)
    assert client.edge_days() == [date(2023, 12, 31)]
    out = process(london_9_15, date(2024, 1, 1), date(2024, 1, 2), raw, processed, "x", NOW)
    tmax = pq.read_table(out).column("tmax_c").to_pylist()
    tmin = pq.read_table(out).column("tmin_c").to_pylist()

    def mx2t_c(stamps: pd.DatetimeIndex) -> np.ndarray:
        h = np.asarray((stamps - pd.Timestamp("1970-01-01")) // pd.Timedelta(hours=1))
        return (
            270.0 + (h % 997) * 0.03 + (h % 24) * 0.25 + 4.0 * (51.479 - 51.0) + 2.0 * -0.449
        ) - 273.15

    def mn2t_c(stamps: pd.DatetimeIndex) -> np.ndarray:
        h = np.asarray((stamps - pd.Timestamp("1970-01-01")) // pd.Timedelta(hours=1))
        return mx2t_c(stamps) - 3.0 - (h % 7) * 0.5

    expected_tmax, expected_tmin = [], []
    for day in (date(2024, 1, 1), date(2024, 1, 2)):
        d = pd.Timestamp(day)
        tmax_window = pd.date_range(d + pd.Timedelta(hours=10), periods=24, freq="h")
        tmin_window = pd.date_range(d - pd.Timedelta(hours=14), periods=24, freq="h")
        expected_tmax.append(mx2t_c(tmax_window).max())
        expected_tmin.append(mn2t_c(tmin_window).min())
    np.testing.assert_allclose(tmax, expected_tmax, atol=1e-4)
    np.testing.assert_allclose(tmin, expected_tmin, atol=1e-4)


# --------------------------------------------------------------------------- B8


@pytest.mark.parametrize("missing", ["2025-01-01T00:00", "2025-01-01T13:00", "2025-01-01T23:00"])
def test_b8_edge_day_response_missing_an_hour_names_the_day_and_leaves_no_file(
    raw_dir, london, missing
):
    client = FakeCdsClient(missing_hours=[missing])
    with pytest.raises(DownloadError) as err:
        download_edge_day(client, london, date(2025, 1, 1), raw_dir)
    assert names_day(str(err.value), date(2025, 1, 1))
    assert list(raw_dir.iterdir()) == []  # no edge file, no temporary files


def test_b8_edge_day_response_for_another_day_is_rejected(raw_dir, london):
    wrong = cds_era5(
        hours("2025-01-02T00:00", "2025-01-02T23:00"), LONDON_LATS[:2], LONDON_LONS[:2]
    )
    with pytest.raises(DownloadError) as err:
        download_edge_day(FakeCdsClient(dataset=wrong), london, date(2025, 1, 1), raw_dir)
    assert names_day(str(err.value), date(2025, 1, 1))
    assert list(raw_dir.iterdir()) == []


def test_b8_edge_day_response_with_a_repeated_hour_is_rejected(raw_dir, london):
    stamps = hours("2025-01-01T00:00", "2025-01-01T23:00")
    repeated = stamps.append(pd.DatetimeIndex([stamps[5]]))
    served = cds_era5(repeated, LONDON_LATS[:2], LONDON_LONS[:2])
    with pytest.raises(DownloadError) as err:
        download_edge_day(FakeCdsClient(dataset=served), london, date(2025, 1, 1), raw_dir)
    assert names_day(str(err.value), date(2025, 1, 1))
    assert list(raw_dir.iterdir()) == []


@pytest.mark.parametrize("mode", ["raise", "partial_then_raise", "garbage", "zip_corrupt"])
def test_b8_failed_edge_day_download_leaves_no_file(raw_dir, london, mode):
    with pytest.raises(DownloadError) as err:
        download_edge_day(FakeCdsClient(mode=mode), london, date(2025, 1, 1), raw_dir)
    assert "era5_london_20250101" in str(err.value)
    assert list(raw_dir.iterdir()) == []


def test_b8_incomplete_edge_day_in_download_range_raises_and_keeps_year_files(raw_dir, london_9_15):
    present_years(raw_dir, range(1991, 2025))
    client = FakeCdsClient(missing_hours=["2025-01-01T04:00"])
    with pytest.raises(DownloadError) as err:
        download_range(client, london_9_15, date(1991, 1, 1), date(2024, 12, 31), raw_dir)
    assert names_day(str(err.value), date(2025, 1, 1))
    assert not (raw_dir / "era5_london_20250101.nc").exists()
    assert all((raw_dir / f"era5_london_{y}.nc").is_file() for y in range(1991, 2025))
    assert not any(p.name.endswith(".tmp") or p.is_dir() for p in raw_dir.iterdir())


def test_b7_one_day_range_with_both_ends_on_the_same_edge_day_fetches_it_once(raw_dir):
    # Code review: offsets 23/23, start == end == 2020-12-31 -> the needed span is
    # 2021-01-01 00:00..23:00, so both ends fall on the same edge day.
    station = make_station(tmax_window_start_utc_h=23, tmin_window_start_utc_h=23)
    present_years(raw_dir, [2020])
    client = FakeCdsClient()
    paths = download_range(client, station, date(2020, 12, 31), date(2020, 12, 31), raw_dir)
    assert temperature_requests(client) == [edge_request(date(2021, 1, 1))]
    assert paths.count(raw_dir / "era5_london_20210101.nc") == 1
    assert len(paths) == len(set(paths))


@pytest.mark.parametrize("value", ["09", "-09", "-0", "+9"])
def test_publish_rejects_non_canonical_window_metadata(tmp_path, value):
    from test_publish import FULL_METADATA, write_file

    from dataprep.errors import PublishError
    from dataprep.publish import validate

    meta = dict(FULL_METADATA, tmax_window_start_utc_h=value)
    path = write_file(tmp_path / "london.parquet", metadata=meta)
    with pytest.raises(PublishError, match="tmax_window_start_utc_h"):
        validate(path)
