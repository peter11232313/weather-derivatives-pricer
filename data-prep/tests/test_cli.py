"""Spec 002: CLI wiring for process/publish, exit codes 0 / 1 (DataPrepError) / 2 (bad args)."""

import pyarrow.parquet as pq
import pytest
from synth import (
    KELVIN,
    LONDON_LATS,
    LONDON_LONS,
    hours,
    make_era5,
    make_lsm,
    write_lsm_file,
    write_year_file,
)

from dataprep.cli import main

REGISTRY = """\
[london]
station = "London Heathrow"
wmo_id = "03772"
icao = "EGLL"
lat = 51.479
lon = -0.449
"""


@pytest.fixture
def registry(tmp_path):
    path = tmp_path / "stations.toml"
    path.write_text(REGISTRY, encoding="utf-8")
    return path


@pytest.fixture
def raw_files(raw_dir):
    ds = make_era5(
        hours("2024-01-01T00:00", "2024-01-03T00:00"),
        LONDON_LATS,
        LONDON_LONS,
        mx2t_k=8.0 + KELVIN,
        mn2t_k=2.0 + KELVIN,
        dtype="float64",
        time_name="valid_time",
        lat_descending=True,
    )
    write_year_file(raw_dir, "london", 2024, ds)
    write_lsm_file(raw_dir, "london", make_lsm(LONDON_LATS, LONDON_LONS))
    return raw_dir


def run_exit_code(argv):
    """Return main's exit code whether it returns it or raises SystemExit (argparse)."""
    try:
        return main(argv)
    except SystemExit as exc:
        return exc.code


def process_args(registry, raw_dir, processed_dir, start="2024-01-01", end="2024-01-02"):
    return [
        "process",
        "--city",
        "london",
        "--start",
        start,
        "--end",
        end,
        "--registry",
        str(registry),
        "--raw-dir",
        str(raw_dir),
        "--processed-dir",
        str(processed_dir),
    ]


def test_cli_process_writes_processed_file(registry, raw_files, processed_dir, pricer_dir):
    assert main(process_args(registry, raw_files, processed_dir)) == 0
    out = processed_dir / "london.parquet"
    assert out.is_file()
    assert pq.read_table(out).num_rows == 2
    assert list(pricer_dir.iterdir()) == []  # process never writes the pricer folder


def test_cli_process_then_publish_copies_identical_file(
    registry, raw_files, processed_dir, pricer_dir
):
    assert main(process_args(registry, raw_files, processed_dir)) == 0
    code = main(
        [
            "publish",
            "--city",
            "london",
            "--processed-dir",
            str(processed_dir),
            "--pricer-dir",
            str(pricer_dir),
        ]
    )
    assert code == 0
    assert (pricer_dir / "london.parquet").read_bytes() == (
        processed_dir / "london.parquet"
    ).read_bytes()


def test_cli_publish_missing_file_exits_1_with_message_on_stderr(processed_dir, pricer_dir, capsys):
    code = main(
        [
            "publish",
            "--city",
            "london",
            "--processed-dir",
            str(processed_dir),
            "--pricer-dir",
            str(pricer_dir),
        ]
    )
    assert code == 1
    assert "london" in capsys.readouterr().err
    assert list(pricer_dir.iterdir()) == []


def test_cli_process_missing_raw_file_exits_1(registry, raw_dir, processed_dir, capsys):
    code = main(process_args(registry, raw_dir, processed_dir))
    assert code == 1
    assert "era5_" in capsys.readouterr().err


def test_cli_unknown_city_exits_1(registry, raw_files, processed_dir, capsys):
    args = process_args(registry, raw_files, processed_dir)
    args[args.index("london")] = "atlantis"
    assert main(args) == 1
    assert "atlantis" in capsys.readouterr().err


@pytest.mark.parametrize(
    "argv",
    [
        ["frobnicate"],
        [],
        ["process", "--city", "london", "--start", "not-a-date", "--end", "2024-01-01"],
        ["publish"],
    ],
    ids=["unknown-command", "no-command", "bad-date", "missing-city"],
)
def test_cli_bad_arguments_exit_2(argv, tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    assert run_exit_code(argv) == 2
