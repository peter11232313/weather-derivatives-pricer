# Python style

General best practice for modern Python (3.12+), followed by the rules specific to this project.
AGENTS.md comes first; where "This project" differs from the general guidance, "This project"
wins.

## Formatting and naming
- Let the tools decide layout and import order (Ruff format and lint here); never hand-format.
- PEP 8 names: `snake_case` functions and variables, `PascalCase` classes, `UPPER_SNAKE_CASE`
  module constants, a leading `_` for module-private names.
- Clear names over comments; no single-letter names except loop indices and maths with a
  documented convention. Put units in names (`timeout_s`, `tmax_c`, `lat_deg`).

## Design
- Small functions that do one thing; prefer pure functions that take inputs and return outputs
  over functions that mutate shared state.
- No module-level mutable state and no work at import time (no I/O, no network).
- Keep I/O at the edges: parse and validate input, run pure logic, then write output.
- Depend on protocols (`typing.Protocol`) at boundaries (network clients, clocks) so tests can
  pass a fake.
- Prefer composition over inheritance; avoid deep class hierarchies.
- Only the entry point (CLI, `__main__`) prints, reads `sys.argv` or calls `sys.exit`. Library
  code returns values or raises.

## Types and values
- Type-hint every function signature in library code, including return types, and every class
  attribute (test functions are exempt; fixtures and helpers should still be typed). Use
  built-in generics (`list[int]`), `X | None`, and `collections.abc` for parameters
  (`Sequence`, `Mapping`).
- Run a type checker (mypy or pyright) where the project has one; avoid `Any` except at untyped
  third-party boundaries.
- Use `@dataclass(frozen=True)` (or `NamedTuple`) for values; validate on construction or at load
  time, then trust the type.
- Use `enum.Enum` or `Literal` instead of magic strings.
- Never use a mutable default argument; use `None` and create the value inside.
- Dates are `datetime.date`; datetimes are timezone-aware (`datetime.now(UTC)`), never naive.
- Use `decimal.Decimal` where decimal exactness matters (money, specified rounding).

## Errors
- Raise specific exceptions; define a small project hierarchy under one base class for expected
  failures (bad data, failed download).
- A caller passing an invalid argument raises `ValueError` / `TypeError`.
- Messages name the input and the bad value (file, date, field).
- Never use a bare `except:` or swallow exceptions. Catch the narrowest type you can handle; use
  `raise ... from e` to keep the cause.
- Don't return sentinel values (`None`, NaN, partial results) in place of an error.
- Document raised exceptions in the docstring.

## Files, resources and data
- `pathlib.Path` for all paths; no string joining, no `os.path`.
- Use context managers (`with`) for files, locks and connections.
- Write files atomically: write to a temporary file in the same folder, then `os.replace`.
- Specify encodings explicitly (`encoding="utf-8"`).
- NumPy/pandas/xarray: vectorise instead of looping over rows; set dtypes explicitly; avoid
  chained assignment; check for NaN and missing timestamps rather than assuming completeness;
  keep time indexes timezone-aware or explicitly UTC.

## Documentation
- Docstrings (Google style here) on every public module, class and function: summary line, then
  `Args`, `Returns`, `Raises` and `Attributes` as needed. State units and conventions.
- Comments explain why, not what. Link the spec or ADR a non-obvious rule comes from.

## Logging and output
- Libraries use `logging` (`logger = logging.getLogger(__name__)`), never `print`; configure
  handlers only in the entry point.
- Use lazy formatting (`logger.info("loaded %s", path)`); never log secrets.

## Testing (pytest)
- Plain test functions and fixtures; no `unittest.TestCase` classes. Shared fixtures in
  `conftest.py`, test data builders in a helper module.
- Names describe behaviour: `test_<unit>_<condition>_<result>`. Name test files after the module
  or behaviour they test, not after where the test came from (no `test_review_fixes.py`).
- `pytest.mark.parametrize` for input tables; pass `ids=` when the generated ids would not say
  which case failed.
- Error tests check the message names the thing at fault, with `pytest.raises(..., match=...)` or
  by asserting on `err.value`.
- Floats: `pytest.approx` or `numpy.testing.assert_allclose` with an explicit tolerance; exact
  equality only where the value is exact by construction.
- Tests are deterministic and isolated: `tmp_path` for files, `monkeypatch` for environment, fixed
  random seeds, no network, no dependence on test order.
- Prefer fakes passed through a `Protocol` over `unittest.mock.patch` on your own code.

## Dependencies and security
- Declare dependencies in `pyproject.toml` with minimum versions and a comment on why each is
  needed; keep dev tools in an optional `dev` group. Work in a virtual environment.
- Add a dependency only when it earns its place; prefer the standard library.
- Never put credentials in code, tests or logs; read them from the environment or the tool's
  config file.
- Don't use `eval`/`exec`, `pickle` on untrusted data, or `subprocess` with `shell=True`.

## This project
These rules are specific to `data-prep/` and override the general guidance above where they
differ.

- **Layout.** Package in `src/dataprep/`, one module per pipeline stage (`download`, `daily`,
  `interpolate`, `write`, `publish`), `pipeline` joining them, `cli` as the only entry point.
- **Errors.** Expected failures raise a subclass of `DataPrepError` (`RegistryError`,
  `DataError`, `DownloadError`, `PublishError`). `cli.main` maps them to exit code 1 with
  `error: ...` on stderr; bad arguments exit 2.
- **Output.** The CLI prints to stdout/stderr; no `logging` setup yet (one-shot runs).
- **Type checking.** No mypy/pyright yet; hints plus Ruff `UP`. Adding a checker is a separate
  decision.
- **Data flow.** `data/raw` → `data/processed` → (validated `publish`) → `data/pricer`. Only
  `publish` writes to `data/pricer`. Data files are never committed.
- **Parquet metadata** (station, observation windows, registry version) is checked by Java, so a
  key rename is a cross-language change: update the spec and the Java loader together.
- **ERA5.** Units, physics and time handling follow the `era5-data` skill and ADRs 0002, 0003
  and 0005. Observation windows are whole UTC hours relative to D 00:00.
- **Copernicus.** One request at a time; credentials only in `~/.cdsapirc`.
- **Tests.** Synthetic ERA5 data and `FakeCdsClient` live in `tests/synth.py`; tests never
  download and never read `data/`.
- **Constants** that encode a rule carry a comment naming the spec or ADR (e.g.
  `MAX_WINDOW_START_H`, ADR 0005).
