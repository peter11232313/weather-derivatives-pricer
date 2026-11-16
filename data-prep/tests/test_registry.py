"""Spec 002 AC: registry validation errors name the entry; registry_version is a SHA-256 prefix."""

import hashlib
from pathlib import Path

import pytest

from dataprep.errors import RegistryError
from dataprep.registry import Station, load_registry, registry_version

REPO_REGISTRY = Path(__file__).resolve().parents[1] / "stations.toml"

LONDON_TOML = """\
[london]
station = "London Heathrow"
wmo_id = "03772"
icao = "EGLL"
lat = 51.479
lon = -0.449
"""


def entry(key: str, lat: float = 51.479, lon: float = -0.449, drop: str | None = None) -> str:
    fields = {
        "station": '"Somewhere"',
        "wmo_id": '"00000"',
        "icao": '"ZZZZ"',
        "lat": repr(lat),
        "lon": repr(lon),
    }
    if drop:
        del fields[drop]
    body = "\n".join(f"{k} = {v}" for k, v in fields.items())
    return f"[{key}]\n{body}\n"


def write(tmp_path: Path, text: str) -> Path:
    path = tmp_path / "stations.toml"
    path.write_text(text, encoding="utf-8")
    return path


def test_valid_registry_loads_london_station(tmp_path):
    stations = load_registry(write(tmp_path, LONDON_TOML))
    assert stations == {
        "london": Station(
            city="london",
            station="London Heathrow",
            wmo_id="03772",
            icao="EGLL",
            lat_deg=51.479,
            lon_deg=-0.449,
        )
    }


def test_repo_registry_has_london_heathrow():
    stations = load_registry(REPO_REGISTRY)
    assert stations["london"].lat_deg == 51.479
    assert stations["london"].lon_deg == -0.449
    assert stations["london"].wmo_id == "03772"


def test_coordinate_bounds_are_inclusive(tmp_path):
    text = entry("north", lat=90.0, lon=180.0) + entry("south", lat=-90.0, lon=-180.0)
    stations = load_registry(write(tmp_path, text))
    assert set(stations) == {"north", "south"}


@pytest.mark.parametrize("key", ['"London"', '"new york"', '"paris!"', '"zürich"'])
def test_invalid_key_is_rejected_naming_the_entry(tmp_path, key):
    path = write(tmp_path, LONDON_TOML + entry(key))
    with pytest.raises(RegistryError) as err:
        load_registry(path)
    assert key.strip('"') in str(err.value)


@pytest.mark.parametrize(
    ("lat", "lon"),
    [(90.001, 0.0), (-90.5, 0.0), (0.0, 180.01), (0.0, -181.0)],
)
def test_out_of_range_coordinates_are_rejected_naming_the_entry(tmp_path, lat, lon):
    path = write(tmp_path, LONDON_TOML + entry("badplace", lat=lat, lon=lon))
    with pytest.raises(RegistryError) as err:
        load_registry(path)
    assert "badplace" in str(err.value)


@pytest.mark.parametrize("field", ["station", "wmo_id", "icao", "lat", "lon"])
def test_missing_field_is_rejected_naming_the_entry(tmp_path, field):
    path = write(tmp_path, LONDON_TOML + entry("partial", drop=field))
    with pytest.raises(RegistryError) as err:
        load_registry(path)
    assert "partial" in str(err.value)


def test_duplicate_city_is_rejected_naming_the_entry(tmp_path):
    # A duplicate TOML table is a parse error in tomllib; it must surface as RegistryError.
    path = write(tmp_path, LONDON_TOML + "\n" + LONDON_TOML)
    with pytest.raises(RegistryError) as err:
        load_registry(path)
    assert "london" in str(err.value)


def test_registry_version_is_sha256_prefix_of_file_bytes(tmp_path):
    path = write(tmp_path, LONDON_TOML)
    expected = hashlib.sha256(path.read_bytes()).hexdigest()[:12]
    assert registry_version(path) == expected
    assert len(registry_version(path)) == 12


def test_registry_version_changes_when_file_changes(tmp_path):
    path = write(tmp_path, LONDON_TOML)
    before = registry_version(path)
    path.write_text(LONDON_TOML.replace("51.479", "51.4775"), encoding="utf-8")
    assert registry_version(path) != before
