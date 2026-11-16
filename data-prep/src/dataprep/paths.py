"""Default data folders, resolved from the repository root rather than the cwd (spec 002)."""

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class DataDirs:
    """The three separate data folders.

    Attributes:
        raw: ERA5 netCDF downloads (written by ``download``).
        processed: Python output, staging (written by ``process``).
        pricer: validated files the Java pricer reads (written only by ``publish``).
    """

    raw: Path
    processed: Path
    pricer: Path


def repo_root() -> Path:
    """Return the repository root: the parent of the ``data-prep`` folder."""
    raise NotImplementedError


def default_dirs() -> DataDirs:
    """Return ``<repo>/data/raw``, ``<repo>/data/processed`` and ``<repo>/data/pricer``."""
    raise NotImplementedError
