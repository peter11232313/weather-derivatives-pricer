"""Spec 002 ACs: download area, skip-if-present, atomic writes, zip rejection, request plan."""

import zipfile
from datetime import date

import pytest
import xarray as xr
from synth import (
    LSM_NAMES,
    MN2T_NAMES,
    MX2T_NAMES,
    FakeCdsClient,
    as_list,
    edge_day_of,
    is_edge_day_request,
    is_lsm_request,
    is_single_step_request,
    make_station,
    request_variables,
)

from dataprep.download import (
    DATASET,
    area_for,
    download_edge_day,
    download_lsm,
    download_range,
    download_year,
    edge_day_file,
    lsm_file,
    year_file,
)
from dataprep.errors import DataPrepError, DownloadError

# --------------------------------------------------------------------------- area & names


def test_london_download_area_is_grid_aligned():
    # 51.479 +/- 0.5 -> 51.979 / 50.979 -> outward to 0.25: N 52.0, S 50.75
    # -0.449 +/- 0.5 -> -0.949 / 0.051  -> outward to 0.25: W -1.0, E 0.25
    assert area_for(make_station()) == (52.0, -1.0, 50.75, 0.25)


def test_area_for_station_on_grid_node_is_exactly_half_degree_box():
    assert area_for(make_station(50.0, 0.0, city="node")) == (50.5, -0.5, 49.5, 0.5)


def test_area_for_southern_and_western_hemisphere():
    # -33.87 +/- 0.5 -> -33.37 / -34.37 -> N -33.25, S -34.5
    # -70.66 +/- 0.5 -> -71.16 / -70.16 -> W -71.25, E -70.0
    assert area_for(make_station(-33.87, -70.66, city="santiago")) == (
        -33.25,
        -71.25,
        -34.5,
        -70.0,
    )


def test_raw_file_names(raw_dir):
    assert year_file(raw_dir, "london", 2024) == raw_dir / "era5_london_2024.nc"
    assert edge_day_file(raw_dir, "london", date(2025, 1, 1)) == raw_dir / "era5_london_20250101.nc"
    assert lsm_file(raw_dir, "london") == raw_dir / "era5_lsm_london.nc"


# --------------------------------------------------------------------------- download_year


def test_download_year_requests_mx2t_mn2t_for_every_hour_of_the_year(raw_dir, london):
    client = FakeCdsClient()
    out = download_year(client, london, 2024, raw_dir)
    assert out == raw_dir / "era5_london_2024.nc"
    assert out.is_file()
    with xr.open_dataset(out) as ds:
        assert "mx2t" in ds.data_vars
    assert len(client.calls) == 1
    name, request, target = client.calls[0]
    assert name == DATASET
    variables = request_variables(request)
    assert variables & MX2T_NAMES and variables & MN2T_NAMES
    assert not variables & LSM_NAMES
    assert as_list(request["year"]) == ["2024"]
    assert len(as_list(request["month"])) == 12
    assert len(as_list(request["day"])) == 31
    assert len(as_list(request["time"])) == 24
    assert [float(x) for x in request["area"]] == [52.0, -1.0, 50.75, 0.25]
    # Written to a temporary name, then renamed into place.
    assert target != str(out)


def test_existing_target_is_not_downloaded_again(raw_dir, london):
    existing = raw_dir / "era5_london_2024.nc"
    existing.write_bytes(b"already here")
    client = FakeCdsClient()
    assert download_year(client, london, 2024, raw_dir) == existing
    assert client.calls == []
    assert existing.read_bytes() == b"already here"


@pytest.mark.parametrize("mode", ["raise", "partial_then_raise"])
def test_failed_download_raises_and_leaves_no_file(raw_dir, london, mode):
    client = FakeCdsClient(mode=mode)
    with pytest.raises(DownloadError) as err:
        download_year(client, london, 2024, raw_dir)
    assert "era5_london_2024" in str(err.value)
    assert list(raw_dir.iterdir()) == []


def test_zip_response_is_an_error_and_leaves_no_file(raw_dir, london):
    client = FakeCdsClient(mode="zip")
    with pytest.raises(DownloadError) as err:
        download_year(client, london, 2024, raw_dir)
    assert "era5_london_2024" in str(err.value)
    assert list(raw_dir.iterdir()) == []


# --------------------------------------------------------------------------- edge day & lsm


def test_edge_day_requests_every_hour_of_one_day(raw_dir, london):
    client = FakeCdsClient()
    out = download_edge_day(client, london, date(2025, 1, 1), raw_dir)
    assert out == raw_dir / "era5_london_20250101.nc"
    assert out.is_file()
    (request,) = client.requests
    assert as_list(request["year"]) == ["2025"]
    assert is_edge_day_request(request)
    assert edge_day_of(request) == date(2025, 1, 1)
    assert not is_single_step_request(request)
    assert request_variables(request) & MX2T_NAMES and request_variables(request) & MN2T_NAMES


def test_edge_day_failure_leaves_no_file(raw_dir, london):
    with pytest.raises(DownloadError) as err:
        download_edge_day(
            FakeCdsClient(mode="partial_then_raise"), london, date(2025, 1, 1), raw_dir
        )
    assert "era5_london_20250101" in str(err.value)
    assert list(raw_dir.iterdir()) == []


def test_lsm_is_its_own_request(raw_dir, london):
    client = FakeCdsClient()
    out = download_lsm(client, london, raw_dir)
    assert out == raw_dir / "era5_lsm_london.nc"
    assert out.is_file()
    (request,) = client.requests
    variables = request_variables(request)
    assert variables & LSM_NAMES
    assert not variables & (MX2T_NAMES | MN2T_NAMES)
    assert len(as_list(request["time"])) == 1


def test_lsm_zip_response_is_an_error(raw_dir, london):
    with pytest.raises(DownloadError) as err:
        download_lsm(FakeCdsClient(mode="zip"), london, raw_dir)
    assert "era5_lsm_london" in str(err.value)
    assert list(raw_dir.iterdir()) == []


def test_existing_lsm_is_not_downloaded_again(raw_dir, london):
    (raw_dir / "era5_lsm_london.nc").write_bytes(b"x")
    client = FakeCdsClient()
    download_lsm(client, london, raw_dir)
    assert client.calls == []


# --------------------------------------------------------------------------- download_range


def classify(client: FakeCdsClient) -> tuple[list[str], list[date], int]:
    """Return (full-year years, edge days, lsm request count).

    The old one-step request (amendment B removed it) is counted as a full-year request so
    that it can never pass unnoticed.
    """
    full, edges, lsm = [], [], 0
    for request in client.requests:
        if is_lsm_request(request):
            lsm += 1
        elif is_edge_day_request(request):
            edges.append(edge_day_of(request))
        else:
            full.extend(as_list(request["year"]))
    return sorted(full), sorted(edges), lsm


def test_range_downloads_years_lsm_and_final_edge_day_not_next_full_year(raw_dir, london):
    client = FakeCdsClient()
    paths = download_range(client, london, date(2023, 1, 1), date(2024, 12, 31), raw_dir)
    full, edges, lsm = classify(client)
    assert full == ["2023", "2024"]
    assert edges == [date(2025, 1, 1)]
    assert lsm == 1
    assert all(name == DATASET for name, _, _ in client.calls)
    assert set(paths) == {
        raw_dir / "era5_lsm_london.nc",
        raw_dir / "era5_london_2023.nc",
        raw_dir / "era5_london_2024.nc",
        raw_dir / "era5_london_20250101.nc",
    }
    assert all(p.is_file() for p in paths)
    assert not (raw_dir / "era5_london_20250101T00.nc").exists()


def test_range_uses_existing_next_year_file_instead_of_edge_day(raw_dir, london):
    (raw_dir / "era5_london_2025.nc").write_bytes(b"next year already downloaded")
    client = FakeCdsClient()
    paths = download_range(client, london, date(2023, 1, 1), date(2024, 12, 31), raw_dir)
    full, edges, _ = classify(client)
    assert full == ["2023", "2024"]
    assert edges == []
    assert raw_dir / "era5_london_2025.nc" in paths
    assert not (raw_dir / "era5_london_20250101.nc").exists()
    assert not (raw_dir / "era5_london_20250101T00.nc").exists()


def test_range_starting_1_january_does_not_request_previous_year(raw_dir, london):
    # 0 / 0 windows: day 2024-01-01 uses 01:00..2024-01-02 00:00; 2024-01-01 00:00
    # belongs to 2023-12-31, so no 2023 year file and no 2023-12-31 edge day.
    client = FakeCdsClient()
    download_range(client, london, date(2024, 1, 1), date(2024, 12, 31), raw_dir)
    full, edges, _ = classify(client)
    assert full == ["2024"]
    assert edges == [date(2025, 1, 1)]


def test_range_ending_mid_year_needs_no_edge_day(raw_dir, london):
    client = FakeCdsClient()
    download_range(client, london, date(2024, 3, 1), date(2024, 6, 30), raw_dir)
    full, edges, lsm = classify(client)
    assert full == ["2024"]
    assert edges == []
    assert lsm == 1


def test_range_with_everything_present_makes_no_requests(raw_dir, london):
    for name in ["era5_lsm_london.nc", "era5_london_2024.nc", "era5_london_20250101.nc"]:
        (raw_dir / name).write_bytes(b"present")
    client = FakeCdsClient()
    paths = download_range(client, london, date(2024, 1, 1), date(2024, 12, 31), raw_dir)
    assert client.calls == []
    assert len(paths) == 3


# --- zip responses, download checks and area limits


def test_zip_of_split_netcdfs_is_merged_into_one_year_file(raw_dir, london):
    client = FakeCdsClient(mode="zip_split")
    out = download_year(client, london, 2024, raw_dir)
    assert out == raw_dir / "era5_london_2024.nc"
    assert not zipfile.is_zipfile(out)  # stored as merged netCDF, not the zip
    with xr.open_dataset(out) as ds:
        assert {"mx2t", "mn2t"} <= set(ds.data_vars)
    assert [p.name for p in raw_dir.iterdir()] == ["era5_london_2024.nc"]


def test_zipped_land_sea_mask_is_unpacked(raw_dir, london):
    out = download_lsm(FakeCdsClient(mode="zip_split"), london, raw_dir)
    with xr.open_dataset(out) as ds:
        assert "lsm" in ds.data_vars
    assert [p.name for p in raw_dir.iterdir()] == ["era5_lsm_london.nc"]


def test_requests_ask_for_a_zip(raw_dir, london):
    client = FakeCdsClient(mode="zip_split")
    download_year(client, london, 2024, raw_dir)
    download_lsm(client, london, raw_dir)
    assert all(as_list(r["download_format"]) == ["zip"] for r in client.requests)


def test_non_netcdf_download_is_rejected_and_not_kept(raw_dir, london):
    # An HTML error page must not be renamed into place (it would never be re-fetched).
    with pytest.raises(DownloadError) as err:
        download_year(FakeCdsClient(mode="garbage"), london, 2024, raw_dir)
    assert "era5_london_2024" in str(err.value)
    assert list(raw_dir.iterdir()) == []


def test_download_missing_a_variable_is_rejected_naming_it(raw_dir, london):
    with pytest.raises(DownloadError) as err:
        download_year(FakeCdsClient(mode="netcdf_missing_var"), london, 2024, raw_dir)
    assert "mn2t" in str(err.value)
    assert list(raw_dir.iterdir()) == []


def test_locked_target_on_rename_is_a_download_error(raw_dir, london, monkeypatch):
    def locked(src, dst):
        raise PermissionError(32, "The process cannot access the file", str(dst))

    monkeypatch.setattr("dataprep.download.os.replace", locked)
    with pytest.raises(DownloadError) as err:
        download_year(FakeCdsClient(), london, 2024, raw_dir)
    assert "era5_london_2024" in str(err.value)


@pytest.mark.parametrize(
    ("lat", "lon"), [(89.8, 10.0), (-89.9, 10.0), (50.0, 179.8), (50.0, -179.9)]
)
def test_area_near_pole_or_antimeridian_is_rejected(lat, lon):
    with pytest.raises(DataPrepError) as err:
        area_for(make_station(lat, lon, city="edge"))
    assert "station edge" in str(err.value)


def test_damaged_zip_is_a_download_error_and_not_kept(raw_dir, london):
    with pytest.raises(DownloadError) as err:
        download_year(FakeCdsClient(mode="zip_corrupt"), london, 2024, raw_dir)
    assert "era5_london_2024" in str(err.value)
    assert list(raw_dir.iterdir()) == []


def test_zip_parts_with_different_time_axes_are_rejected(raw_dir, london):
    # Merging must not pad mismatched parts with NaN (join="exact").
    with pytest.raises(DownloadError) as err:
        download_year(FakeCdsClient(mode="zip_misaligned"), london, 2024, raw_dir)
    assert "era5_london_2024" in str(err.value)
    assert list(raw_dir.iterdir()) == []
