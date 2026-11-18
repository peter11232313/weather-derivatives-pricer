"""Spec 002 amendment A: multi-year download requests (criteria A1..A9, Decisions 1-5).

Each acceptance criterion has one test (or one parametrised test) named after it; extra
tests pin the amendment's decisions and the ``year_chunks`` helper. All offline: the CDS
client is ``synth.FakeCdsClient``, which serves every hour of every requested year.
"""

import re
from datetime import UTC, date, datetime
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow.parquet as pq
import pytest
import xarray as xr
from synth import (
    FakeCdsClient,
    as_list,
    hours,
    is_edge_day_request,
    is_lsm_request,
    is_single_step_request,
)

from dataprep.download import (
    DEFAULT_YEARS_PER_REQUEST,
    LSM,
    MAX_YEARS_PER_REQUEST,
    MN2T,
    MX2T,
    download_range,
    download_year,
    download_years,
    year_chunks,
    year_file,
)
from dataprep.errors import DownloadError
from dataprep.pipeline import process

NOW = datetime(2026, 11, 18, 12, 0, tzinfo=UTC)
LONDON_AREA = [52.0, -1.0, 50.75, 0.25]
MONTHS = [f"{m:02d}" for m in range(1, 13)]
DAYS = [f"{d:02d}" for d in range(1, 32)]
TIMES = [f"{h:02d}:00" for h in range(24)]


def single_year_request(year: int) -> dict:
    """Known answer: today's single-year request (download_year), keys in this order."""
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


def year_hours(year: int) -> pd.DatetimeIndex:
    """[Y-01-01 00:00, Y+1-01-01 00:00): 8,760 hours, or 8,784 in a leap year."""
    # Nanoseconds, as xarray decodes netCDF times (pandas 3 ranges default to microseconds).
    return hours(f"{year}-01-01T00:00", f"{year}-12-31T23:00").as_unit("ns")


def leftovers(raw_dir: Path) -> list[Path]:
    """Temporary files or folders (``*.tmp``, ``*.parts.tmp``, ...) left anywhere."""
    return [p for p in raw_dir.rglob("*") if ".tmp" in p.name]


def names(raw_dir: Path) -> set[str]:
    return {p.name for p in raw_dir.iterdir()}


def files_for(raw_dir: Path, years) -> set[str]:
    return {f"era5_london_{y}.nc" for y in years}


def missing_hour_pattern(stamp: str) -> str:
    """Accept '1994-07-01 12:00' or '1994-07-01T12:00' (seconds optional)."""
    day, hour = stamp.split("T")
    return rf"{re.escape(day)}[ T]{re.escape(hour)}"


def open_loaded(path: Path, **kwargs) -> xr.Dataset:
    with xr.open_dataset(path, **kwargs) as ds:
        return ds.load()


# --------------------------------------------------------------------------- constants


def test_chunk_size_default_and_maximum_are_three():
    # Decision 1: 3 x 17,568 items fits the published ~60,000-item cap.
    assert DEFAULT_YEARS_PER_REQUEST == 3
    assert MAX_YEARS_PER_REQUEST == 3


# --------------------------------------------------------------------------- year_chunks


@pytest.mark.parametrize(
    ("years", "n", "expected"),
    [
        (range(1993, 2002), 3, [[1993, 1994, 1995], [1996, 1997, 1998], [1999, 2000, 2001]]),
        ([1995, 1993, 1994, 1993, 1996], 3, [[1993, 1994, 1995], [1996]]),  # unsorted, dupes
        ([1993, 1995, 1996], 3, [[1993], [1995, 1996]]),  # gap splits runs
        ([1993, 1994, 1995], 1, [[1993], [1994], [1995]]),
        (range(1993, 1998), 2, [[1993, 1994], [1995, 1996], [1997]]),
        (
            [2000, 2001, 2003, 2004, 2005, 2006, 2007],
            3,
            [[2000, 2001], [2003, 2004, 2005], [2006, 2007]],
        ),
        ([2024], 3, [[2024]]),
        ([], 3, []),
    ],
    ids=["nine-years", "unsorted-duplicates", "gap", "n1", "n2", "two-runs", "one-year", "empty"],
)
def test_year_chunks_groups_consecutive_years_into_chunks_of_at_most_n(years, n, expected):
    assert year_chunks(list(years), n) == expected


def test_year_chunks_spec_example_1993_to_2023_starts_at_first_missing_year():
    # Scope: missing 1993..2023 -> 1993-95, 1996-98, ..., 2023 (31 years = 10 x 3 + 1).
    chunks = year_chunks(list(range(1993, 2024)), 3)
    assert len(chunks) == 11
    assert chunks[0] == [1993, 1994, 1995]
    assert chunks[1] == [1996, 1997, 1998]
    assert chunks[-1] == [2023]
    assert [y for c in chunks for y in c] == list(range(1993, 2024))


@pytest.mark.parametrize("n", [0, -1, 4, 10])
def test_year_chunks_rejects_years_per_request_outside_1_to_3(n):
    with pytest.raises(ValueError):
        year_chunks([1993, 1994], n)


# --------------------------------------------------------------------------- A1


def test_a1_nine_missing_years_make_three_requests_whose_other_keys_equal_single_year(
    tmp_path, london
):
    raw = tmp_path / "chunked"
    client = FakeCdsClient()
    download_years(client, london, range(1993, 2002), raw, years_per_request=3)

    assert [as_list(r["year"]) for r in client.requests] == [
        ["1993", "1994", "1995"],
        ["1996", "1997", "1998"],
        ["1999", "2000", "2001"],
    ]
    for request in client.requests:
        first = int(request["year"][0])
        # Reference 1: the request today's code records for a single-year download.
        single = FakeCdsClient()
        download_year(single, london, first, tmp_path / f"single{first}")
        (recorded,) = single.requests
        for reference in (recorded, single_year_request(first)):
            assert list(request) == list(reference)  # same keys, same order
            for key in reference:
                if key != "year":
                    assert request[key] == reference[key], key
        assert all(isinstance(y, str) for y in request["year"])


# --------------------------------------------------------------------------- A2


@pytest.mark.parametrize(
    ("first", "expected_steps"),
    [(1996, [8784, 8760, 8760]), (1999, [8760, 8784, 8760])],  # 1996 and 2000 are leap years
    ids=["1996-98", "1999-2001-century-leap"],
)
@pytest.mark.parametrize("mode", ["netcdf", "zip_split"])
def test_a2_three_year_response_is_split_into_complete_year_files_with_served_values(
    raw_dir, london, first, expected_steps, mode
):
    years = [first, first + 1, first + 2]
    client = FakeCdsClient(mode=mode)
    paths = download_years(client, london, years, raw_dir, years_per_request=3)

    assert paths == [year_file(raw_dir, "london", y) for y in years]
    assert names(raw_dir) == files_for(raw_dir, years)
    (served,) = client.served
    for year, steps in zip(years, expected_steps, strict=True):
        ds = open_loaded(year_file(raw_dir, "london", year))
        assert ds.sizes["valid_time"] == steps
        pd.testing.assert_index_equal(
            pd.DatetimeIndex(ds["valid_time"].values), year_hours(year), check_names=False
        )
        own = served.sel(valid_time=slice(f"{year}-01-01T00:00", f"{year}-12-31T23:00"))
        for var in ("mx2t", "mn2t"):
            xr.testing.assert_identical(ds[var], own[var])
    assert leftovers(raw_dir) == []


def test_a2_year_from_a_chunk_is_identical_to_the_same_year_downloaded_alone(tmp_path, london):
    # Decision 5: equal values, coordinates, attributes and stored dtype.
    chunked, alone = tmp_path / "chunked", tmp_path / "alone"
    download_years(FakeCdsClient(), london, [1996, 1997, 1998], chunked, years_per_request=3)
    download_years(FakeCdsClient(), london, [1997], alone, years_per_request=1)

    a_path, b_path = year_file(chunked, "london", 1997), year_file(alone, "london", 1997)
    xr.testing.assert_identical(open_loaded(a_path), open_loaded(b_path))
    # Stored (on-disk) dtypes, without decoding: no float32 -> float64 / int16 repacking.
    raw_a = open_loaded(a_path, mask_and_scale=False, decode_times=False)
    raw_b = open_loaded(b_path, mask_and_scale=False, decode_times=False)
    assert set(raw_a.variables) == set(raw_b.variables)
    for name in raw_a.variables:
        assert raw_a[name].dtype == raw_b[name].dtype, name
    assert raw_a["mx2t"].dtype == np.float32  # as served
    assert raw_a["mn2t"].dtype == np.float32


def test_a2_processing_chunk_downloaded_files_gives_the_same_table_as_single_year_files(
    tmp_path, london
):
    start, end = date(1996, 1, 1), date(1998, 12, 31)
    tables = []
    for n in (3, 1):
        raw, out = tmp_path / f"raw{n}", tmp_path / f"processed{n}"
        client = FakeCdsClient(grid_from_area=True)
        download_range(client, london, start, end, raw, years_per_request=n)
        expected = [["1996", "1997", "1998"]] if n == 3 else [["1996"], ["1997"], ["1998"]]
        assert client.temperature_years() == expected  # the chunked path really ran
        path = process(london, start, end, raw, out, "abcdef012345", NOW)
        tables.append(pq.read_table(path))
    chunked, single = tables
    assert chunked.num_rows == 366 + 365 + 365
    assert chunked.equals(single)
    assert chunked.schema.metadata == single.schema.metadata


# --------------------------------------------------------------------------- A3


def test_a3_only_consecutive_missing_years_are_combined_and_existing_files_untouched(
    raw_dir, london
):
    present = year_file(raw_dir, "london", 1994)
    present.write_bytes(b"1994 already downloaded")
    before = present.stat().st_mtime_ns
    client = FakeCdsClient()

    paths = download_years(client, london, [1993, 1994, 1995, 1996], raw_dir, years_per_request=3)

    assert [as_list(r["year"]) for r in client.requests] == [["1993"], ["1995", "1996"]]
    assert paths == [year_file(raw_dir, "london", y) for y in (1993, 1994, 1995, 1996)]
    assert present.read_bytes() == b"1994 already downloaded"
    assert present.stat().st_mtime_ns == before


def test_all_years_present_makes_no_requests(raw_dir, london):
    for y in (1993, 1994, 1995):
        year_file(raw_dir, "london", y).write_bytes(b"present")
    client = FakeCdsClient()
    paths = download_years(client, london, [1993, 1994, 1995], raw_dir, years_per_request=3)
    assert client.calls == []
    assert paths == [year_file(raw_dir, "london", y) for y in (1993, 1994, 1995)]


# --------------------------------------------------------------------------- A4


def test_a4_years_per_request_one_sends_exactly_todays_single_year_requests(tmp_path, london):
    chunked = FakeCdsClient()
    download_years(chunked, london, [1993, 1994, 1995], tmp_path / "n1", years_per_request=1)
    today = FakeCdsClient()
    for y in (1993, 1994, 1995):
        download_year(today, london, y, tmp_path / "today")

    assert chunked.requests == [single_year_request(y) for y in (1993, 1994, 1995)]
    assert chunked.requests == today.requests
    assert [list(r) for r in chunked.requests] == [list(r) for r in today.requests]


# --------------------------------------------------------------------------- A5


@pytest.mark.parametrize("missing", ["1994-01-01T00:00", "1994-07-01T12:00", "1994-12-31T23:00"])
def test_a5_incomplete_year_is_not_written_and_named_and_rerun_requests_only_it(
    raw_dir, london, missing
):
    client = FakeCdsClient(missing_hours=[missing])
    with pytest.raises(DownloadError) as err:
        download_years(client, london, [1993, 1994, 1995], raw_dir, years_per_request=3)

    message = str(err.value)
    assert "1994" in message
    assert re.search(missing_hour_pattern(missing), message), message
    assert names(raw_dir) == files_for(raw_dir, [1993, 1995])
    assert leftovers(raw_dir) == []
    kept = {y: year_file(raw_dir, "london", y).read_bytes() for y in (1993, 1995)}
    for y in (1993, 1995):
        assert open_loaded(year_file(raw_dir, "london", y)).sizes["valid_time"] == 8760

    rerun = FakeCdsClient()
    download_years(rerun, london, [1993, 1994, 1995], raw_dir, years_per_request=3)
    assert [as_list(r["year"]) for r in rerun.requests] == [["1994"]]
    assert open_loaded(year_file(raw_dir, "london", 1994)).sizes["valid_time"] == 8760
    for y in (1993, 1995):
        assert year_file(raw_dir, "london", y).read_bytes() == kept[y]


def test_a5_error_names_the_first_missing_hour_of_the_incomplete_year(raw_dir, london):
    client = FakeCdsClient(missing_hours=["1994-07-01T12:00", "1994-03-05T07:00"])
    with pytest.raises(DownloadError) as err:
        download_years(client, london, [1993, 1994, 1995], raw_dir, years_per_request=3)
    assert re.search(missing_hour_pattern("1994-03-05T07:00"), str(err.value))


def test_a5_missing_leap_day_hour_makes_the_leap_year_incomplete(raw_dir, london):
    # 1996-02-29 exists; a year file without it has 8,783 steps and must not be kept.
    client = FakeCdsClient(missing_hours=["1996-02-29T12:00"])
    with pytest.raises(DownloadError) as err:
        download_years(client, london, [1996, 1997, 1998], raw_dir, years_per_request=3)
    assert re.search(missing_hour_pattern("1996-02-29T12:00"), str(err.value))
    assert names(raw_dir) == files_for(raw_dir, [1997, 1998])


def test_a5_incomplete_year_carries_on_with_later_chunks_and_raises_once_at_the_end(
    raw_dir, london
):
    # Decision 4: chunk 1993-95 has 1994 incomplete; chunk 1996-98 is still requested.
    client = FakeCdsClient(missing_hours=["1994-07-01T12:00"])
    with pytest.raises(DownloadError) as err:
        download_years(client, london, range(1993, 1999), raw_dir, years_per_request=3)

    assert [as_list(r["year"]) for r in client.requests] == [
        ["1993", "1994", "1995"],
        ["1996", "1997", "1998"],
    ]
    assert "1994" in str(err.value)
    assert names(raw_dir) == files_for(raw_dir, [1993, 1995, 1996, 1997, 1998])
    assert leftovers(raw_dir) == []


def test_a5_error_names_every_incomplete_year_across_chunks(raw_dir, london):
    client = FakeCdsClient(missing_hours=["1994-07-01T12:00", "1997-10-26T01:00"])
    with pytest.raises(DownloadError) as err:
        download_years(client, london, range(1993, 1999), raw_dir, years_per_request=3)
    assert "1994" in str(err.value)
    assert "1997" in str(err.value)
    assert len(client.requests) == 2
    assert names(raw_dir) == files_for(raw_dir, [1993, 1995, 1996, 1998])


@pytest.mark.parametrize("n", [1, 3])
def test_decision3_unfinished_single_year_fails_naming_the_first_missing_hour(raw_dir, london, n):
    # Decision 3: e.g. the current year, served only up to 2026-11-17 23:00.
    unserved = hours("2026-11-18T00:00", "2026-12-31T23:00")
    client = FakeCdsClient(missing_hours=unserved)
    with pytest.raises(DownloadError) as err:
        download_years(client, london, [2026], raw_dir, years_per_request=n)
    assert re.search(missing_hour_pattern("2026-11-18T00:00"), str(err.value))
    assert list(raw_dir.iterdir()) == []


# --------------------------------------------------------------------------- A6


def test_a6_hours_outside_the_requested_years_are_ignored(raw_dir, london):
    neighbour = year_file(raw_dir, "london", 1996)
    neighbour.write_bytes(b"1996 already downloaded")
    # Served: 1992-12-31 00:00..23:00, all of 1993..1995, 1996-01-01 00:00..23:00.
    client = FakeCdsClient(pad_hours=24)
    download_years(client, london, [1993, 1994, 1995], raw_dir, years_per_request=3)

    (served,) = client.served
    assert served.sizes["valid_time"] == 3 * 8760 + 48  # the padding really was served
    assert names(raw_dir) == files_for(raw_dir, [1993, 1994, 1995, 1996])
    assert not year_file(raw_dir, "london", 1992).exists()
    assert neighbour.read_bytes() == b"1996 already downloaded"
    assert not any(n.startswith("era5_london_1996") and n != neighbour.name for n in names(raw_dir))
    assert not any("0101T00" in n for n in names(raw_dir))
    for year in (1993, 1994, 1995):
        ds = open_loaded(year_file(raw_dir, "london", year))
        pd.testing.assert_index_equal(
            pd.DatetimeIndex(ds["valid_time"].values), year_hours(year), check_names=False
        )


# --------------------------------------------------------------------------- A7


def test_a7_failed_chunk_stops_at_once_keeps_earlier_chunks_and_leaves_no_temp_files(
    raw_dir, london
):
    client = FakeCdsClient(fail_on_calls=[2])  # 2nd temperature request raises
    with pytest.raises(DownloadError):
        download_years(client, london, range(1993, 2002), raw_dir, years_per_request=3)

    assert len(client.calls) == 2  # chunk 1999-2001 never requested
    assert names(raw_dir) == files_for(raw_dir, [1993, 1994, 1995])
    for y in (1993, 1994, 1995):
        assert open_loaded(year_file(raw_dir, "london", y)).sizes["valid_time"] == 8760
    assert leftovers(raw_dir) == []
    assert not any(p.is_dir() for p in raw_dir.iterdir())


@pytest.mark.parametrize("mode", ["raise", "partial_then_raise", "garbage", "zip_corrupt"])
def test_a7_failed_first_chunk_leaves_no_files(raw_dir, london, mode):
    with pytest.raises(DownloadError):
        download_years(FakeCdsClient(mode=mode), london, [1993, 1994, 1995], raw_dir)
    assert list(raw_dir.iterdir()) == []


# --------------------------------------------------------------------------- A9


def test_a9_land_sea_mask_unchanged_and_next_year_step_is_now_an_edge_day(tmp_path, london):
    start, end = date(1993, 1, 1), date(1995, 12, 31)
    clients = {}
    for n in (1, 3):
        clients[n] = FakeCdsClient()
        download_range(clients[n], london, start, end, tmp_path / f"n{n}", years_per_request=n)

    assert clients[3].temperature_years() == [["1993", "1994", "1995"]]  # N=3 path is active
    expected_lsm = {
        "product_type": ["reanalysis"],
        "variable": [LSM],
        "year": ["2024"],
        "month": ["01"],
        "day": ["01"],
        "time": ["00:00"],
        "area": LONDON_AREA,
        "data_format": "netcdf",
        "download_format": "zip",
    }
    # Amendment B: the 1996-01-01 00:00 step became the whole edge day 1996-01-01.
    expected_edge = {
        **expected_lsm,
        "variable": [MX2T, MN2T],
        "year": ["1996"],
        "month": ["01"],
        "day": ["01"],
        "time": TIMES,
    }
    for n, client in clients.items():
        lsm = [r for r in client.requests if is_lsm_request(r)]
        edges = [r for r in client.requests if is_edge_day_request(r)]
        assert lsm == [expected_lsm]
        assert list(lsm[0]) == list(expected_lsm)
        assert edges == [expected_edge]
        assert list(edges[0]) == list(single_year_request(1996))  # a year request's keys
        assert client.requests[-1] == expected_edge  # still requested last
        assert not any(is_single_step_request(r) for r in client.requests)
        assert not any("0101T00" in target for _, _, target in client.calls)
        assert not any("0101T00" in p.name for p in (tmp_path / f"n{n}").iterdir())


def test_a9_no_step_request_when_next_year_file_exists(raw_dir, london):
    year_file(raw_dir, "london", 1996).write_bytes(b"next year present")
    client = FakeCdsClient()
    paths = download_range(
        client, london, date(1993, 1, 1), date(1995, 12, 31), raw_dir, years_per_request=3
    )
    assert client.temperature_years() == [["1993", "1994", "1995"]]
    assert not any(is_single_step_request(r) for r in client.requests)
    assert not any(is_edge_day_request(r) for r in client.requests)
    assert year_file(raw_dir, "london", 1996) in paths
