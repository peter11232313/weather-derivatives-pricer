"""Settlement-station registry (``stations.toml``), spec 002 and ADR 0003."""

import hashlib
import re
import tomllib
from dataclasses import dataclass
from pathlib import Path

from dataprep.errors import RegistryError

_CITY_KEY = re.compile(r"[a-z0-9_-]+")
_TEXT_FIELDS = ("station", "wmo_id", "icao")
_COORD_FIELDS = ("lat", "lon")


@dataclass(frozen=True)
class Station:
    """One settlement station.

    Attributes:
        city: Registry key, matching ``[a-z0-9_-]+``; used in file names.
        station: Human-readable station name, e.g. ``London Heathrow``.
        wmo_id: WMO station identifier.
        icao: ICAO airport code.
        lat_deg: Latitude in decimal degrees (WGS84), in [-90, 90].
        lon_deg: Longitude in decimal degrees (WGS84), in [-180, 180].
    """

    city: str
    station: str
    wmo_id: str
    icao: str
    lat_deg: float
    lon_deg: float


def load_registry(path: Path) -> dict[str, Station]:
    """Load and validate the registry.

    Args:
        path: Path to ``stations.toml``.

    Returns:
        Stations keyed by city.

    Raises:
        RegistryError: If an entry has an invalid key, a missing field, out-of-range
            coordinates, or a duplicate city; the message names the entry.
    """
    try:
        with path.open("rb") as f:
            entries = tomllib.load(f)
    except tomllib.TOMLDecodeError as e:
        # tomllib reports a duplicate table as "Cannot declare ('london',) twice ...".
        raise RegistryError(f"invalid station registry {path}: {e}") from e
    return {city: _station(city, fields, path) for city, fields in entries.items()}


def registry_version(path: Path) -> str:
    """Return the first 12 hex characters of the SHA-256 of the registry file's bytes."""
    return hashlib.sha256(path.read_bytes()).hexdigest()[:12]


def _station(city: str, fields: object, path: Path) -> Station:
    """Validate one registry entry; every error names the entry."""
    where = f"station registry {path}, entry [{city}]"
    if not _CITY_KEY.fullmatch(city):
        raise RegistryError(f"{where}: city key must match [a-z0-9_-]+")
    if not isinstance(fields, dict):
        raise RegistryError(f"{where}: must be a table")
    missing = [name for name in _TEXT_FIELDS + _COORD_FIELDS if name not in fields]
    if missing:
        raise RegistryError(f"{where}: missing field(s) {', '.join(missing)}")
    for name in _TEXT_FIELDS:
        if not isinstance(fields[name], str) or not fields[name]:
            raise RegistryError(f"{where}: {name} must be a non-empty string")
    for name in _COORD_FIELDS:
        value = fields[name]
        if isinstance(value, bool) or not isinstance(value, int | float):
            raise RegistryError(f"{where}: {name} must be a number")
    lat, lon = float(fields["lat"]), float(fields["lon"])
    if not -90.0 <= lat <= 90.0:
        raise RegistryError(f"{where}: lat {lat} outside [-90, 90]")
    if not -180.0 <= lon <= 180.0:
        raise RegistryError(f"{where}: lon {lon} outside [-180, 180]")
    return Station(
        city=city,
        station=fields["station"],
        wmo_id=fields["wmo_id"],
        icao=fields["icao"],
        lat_deg=lat,
        lon_deg=lon,
    )
