"""Shared pytest fixtures for spec 002 tests. Helpers live in ``synth.py``."""

from collections.abc import Callable
from pathlib import Path

import pytest
from synth import FakeCdsClient, make_station

from dataprep.registry import Station


@pytest.fixture
def raw_dir(tmp_path: Path) -> Path:
    d = tmp_path / "raw"
    d.mkdir()
    return d


@pytest.fixture
def processed_dir(tmp_path: Path) -> Path:
    d = tmp_path / "processed"
    d.mkdir()
    return d


@pytest.fixture
def pricer_dir(tmp_path: Path) -> Path:
    d = tmp_path / "pricer"
    d.mkdir()
    return d


@pytest.fixture
def london() -> Station:
    return make_station()


@pytest.fixture
def fake_cds() -> Callable[..., FakeCdsClient]:
    return FakeCdsClient
