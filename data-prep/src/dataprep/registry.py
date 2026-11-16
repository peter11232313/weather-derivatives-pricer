"""Settlement-station registry (``stations.toml``), spec 002 and ADR 0003."""

from dataclasses import dataclass
from pathlib import Path


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
    raise NotImplementedError


def registry_version(path: Path) -> str:
    """Return the first 12 hex characters of the SHA-256 of the registry file's bytes."""
    raise NotImplementedError
