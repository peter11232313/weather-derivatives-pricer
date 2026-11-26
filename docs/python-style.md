# Python style

Applies to `data-prep/`. AGENTS.md rules come first. Ruff formats and lints (line length 100,
rules E F W I UP B D, Google docstrings; `ruff check --fix && ruff format`). Python ≥ 3.12.

## Structure
- Package in `src/dataprep/`, one module per pipeline stage (`download`, `daily`, `interpolate`,
  `write`, `publish`), with `pipeline` joining them and `cli` as the only entry point.
- Only the entry points (`cli.py`, and `__main__.py` for `sys.exit`) print, read `sys.argv` or
  exit. Library functions return values or raise; no `print`, no `sys.exit`.
- Module-private helpers start with `_`. Constants are `UPPER_CASE` at module top, with a comment
  naming the spec or ADR when they encode a rule (e.g. `MAX_WINDOW_START_H`, ADR 0005).

## Types and values
- Type hints on every function signature (public and private, including return types) and every
  dataclass field; use built-in generics and `X | None` (ruff `UP` enforces the modern forms).
- Value types are `@dataclass(frozen=True)`. Validate when loading (e.g. `load_registry`), not
  later at the point of use.
- Units in snake_case names: `tmax_c`, `lat_deg`, `tmax_window_start_utc_h`.
- Dates are `datetime.date`; datetimes are timezone-aware UTC (`datetime.now(UTC)`), never naive.
- External services sit behind a `Protocol` (`CdsClient`) so tests pass a fake; no
  `unittest.mock` for our own seams.
- `Any` only at untyped third-party boundaries (the CDS client), never in our own data types.

## Errors
- Raise a subclass of `DataPrepError` (`RegistryError`, `DataError`, `DownloadError`,
  `PublishError`). The message names the entry, date, timestamp or path.
- Never return NaN-padded or partial data in place of an error. Check completeness (hours, days,
  metadata) before writing.
- `cli.main` maps `DataPrepError` to exit code 1 with `error: ...` on stderr; bad arguments are
  exit 2 (argparse). Any other exception is a bug and should surface with its traceback.

## Files and data
- `pathlib.Path` everywhere; no string paths, no `os.path`.
- Write atomically: write to `*.tmp` next to the target, then `os.replace`. A crash never leaves
  a half-written file under its final name.
- Folders: `data/raw` → `data/processed` → (validated `publish`) → `data/pricer`. Only
  `publish` writes to `data/pricer`.
- Parquet carries its metadata (station, windows, registry version). Java checks it, so a key
  rename is a cross-language change: update the spec and the Java loader together.
- Units, physics and time handling for ERA5: follow the `era5-data` skill and ADRs 0002, 0003
  and 0005.

## Docs
- Google-style docstrings on every public module, class and function (ruff `D`): `Args`,
  `Returns`, `Raises` and `Attributes` when they apply. Tests are exempt.
- Docstrings state units and conventions, e.g. "London is 9 (D 09:00..D+1 09:00)".

## Tests
- pytest only. `tmp_path` for files; shared fixtures in `conftest.py`; synthetic ERA5 data and
  fakes in `tests/synth.py`. Tests never download and never read `data/`.
- Names describe behaviour: `test_<unit>_<condition>_<result>`, e.g.
  `test_cli_publish_missing_file_exits_1_with_message_on_stderr`.
- `pytest.mark.parametrize` for input tables; `pytest.raises(..., match=...)` checks the message
  names the thing at fault.
- Floats: `pytest.approx` or `numpy.testing.assert_allclose` with an explicit tolerance;
  bit-for-bit (`==`) only where the spec says the value must survive a round trip.
- `monkeypatch` for environment and module seams; prefer passing a fake through a `Protocol`.
- Name test files by behaviour, not by origin: no new `test_review_fixes.py`-style files.

## Dependencies
- Declared in `pyproject.toml` with a minimum version and a one-line comment saying why. A new
  one needs a reason in the PR (AGENTS.md).
- One Copernicus request at a time; never put credentials in code or tests (`~/.cdsapirc` only).

## Deliberately not used
- mypy/pyright: not set up yet; type hints are documentation plus ruff `UP`. Adding a checker
  is a separate decision.
- Logging framework: the CLI's stdout/stderr is enough for one-shot runs.
- `unittest`-style test classes: plain functions and fixtures.
