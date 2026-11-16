"""Default data folders, resolved from the repository root rather than the cwd (spec 002)."""

from dataclasses import dataclass
from pathlib import Path

from dataprep.errors import DataPrepError


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
    """Return the repository root: the parent of the ``data-prep`` folder.

    Requires running from a checkout (``pip install -e``), not a copied install.

    Raises:
        DataPrepError: If the package is not inside ``<repo>/data-prep/src``.
    """
    # src/dataprep/paths.py -> src/dataprep -> src -> data-prep -> <repo>
    root = Path(__file__).resolve().parents[3]
    if not (root / "data-prep" / "pyproject.toml").is_file():
        raise DataPrepError(
            f"cannot find the repository from {__file__}; install with "
            "`pip install -e .` from data-prep/, or pass the folders explicitly"
        )
    return root


def default_dirs() -> DataDirs:
    """Return ``<repo>/data/raw``, ``<repo>/data/processed`` and ``<repo>/data/pricer``."""
    data = repo_root() / "data"
    return DataDirs(raw=data / "raw", processed=data / "processed", pricer=data / "pricer")
