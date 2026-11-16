"""Validate a processed file and copy it to the folder the pricer reads (spec 002)."""

from pathlib import Path


def validate(path: Path) -> None:
    """Check a processed file against the spec 001/002 contract.

    Checks: columns ``date`` (DATE), ``tmax_c``, ``tmin_c`` (DOUBLE); Snappy compression;
    all required metadata keys; dates sorted, unique and without gaps; row count equal to
    the days in the metadata ``start..end``; no NaN; ``tmin_c <= tmax_c``.

    Raises:
        PublishError: Naming the first problem found and the path.
    """
    raise NotImplementedError


def publish(city: str, processed_dir: Path, pricer_dir: Path) -> Path:
    """Validate ``<processed_dir>/<city>.parquet`` and copy it to ``<pricer_dir>``.

    Copies to a temporary name, then renames, so the pricer never sees a partial file. On
    failure the pricer folder is unchanged.

    Returns:
        The published path.

    Raises:
        PublishError: If the processed file is missing or invalid.
    """
    raise NotImplementedError
