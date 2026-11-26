"""Spec 002 AC: default folders resolve from the repo root, regardless of the cwd."""

from pathlib import Path

import pytest

import dataprep.paths
from dataprep.errors import DataPrepError
from dataprep.paths import DataDirs, default_dirs, repo_root

# tests/ -> data-prep/ -> <repo>
EXPECTED_REPO = Path(__file__).resolve().parents[2]


def test_repo_root_is_parent_of_data_prep():
    root = repo_root()
    assert (root / "data-prep" / "pyproject.toml").is_file()
    assert root.resolve() == EXPECTED_REPO


def test_default_dirs_resolve_to_repo_data_folders_regardless_of_cwd(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    dirs = default_dirs()
    assert isinstance(dirs, DataDirs)
    assert dirs.raw.resolve() == EXPECTED_REPO / "data" / "raw"
    assert dirs.processed.resolve() == EXPECTED_REPO / "data" / "processed"
    assert dirs.pricer.resolve() == EXPECTED_REPO / "data" / "pricer"
    # Absolute paths, so a later chdir cannot change their meaning.
    assert dirs.raw.is_absolute() and dirs.processed.is_absolute() and dirs.pricer.is_absolute()


def test_default_dirs_are_three_separate_folders():
    dirs = default_dirs()
    assert len({dirs.raw, dirs.processed, dirs.pricer}) == 3


# --- repo root outside a checkout


def test_repo_root_outside_a_checkout_is_an_error(tmp_path, monkeypatch):
    fake = tmp_path / "a" / "b" / "c" / "paths.py"
    monkeypatch.setattr(dataprep.paths, "__file__", str(fake))
    with pytest.raises(DataPrepError) as err:
        dataprep.paths.repo_root()
    assert "cannot find the repository" in str(err.value)
