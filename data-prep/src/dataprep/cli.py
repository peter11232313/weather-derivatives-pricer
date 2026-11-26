"""Command line: ``python -m dataprep {download,process,publish,build}`` (spec 002).

Options: ``--city``, ``--start``, ``--end`` (ISO dates, inclusive), ``--registry``,
``--raw-dir``, ``--processed-dir``, ``--pricer-dir`` (folders default to ``<repo>/data/...``).
"""

import argparse
import sys
from collections.abc import Callable
from datetime import UTC, date, datetime
from pathlib import Path

from dataprep.download import DEFAULT_YEARS_PER_REQUEST, MAX_YEARS_PER_REQUEST, CdsClient
from dataprep.errors import DataPrepError, DownloadError, RegistryError
from dataprep.paths import default_dirs, repo_root
from dataprep.registry import Station, load_registry, registry_version


def main(argv: list[str] | None = None) -> int:
    """Run the CLI.

    Args:
        argv: Arguments without the program name; ``None`` reads ``sys.argv``.

    Returns:
        Process exit code: 0 on success, 1 on a data-prep error (message on stderr), 2 on
        bad arguments.
    """
    try:
        parser = _parser()  # resolves default folders; fails outside a checkout
    except DataPrepError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    args = parser.parse_args(argv)  # exits with code 2 on bad arguments
    try:
        args.run(args)
    except DataPrepError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


def _download(args: argparse.Namespace) -> None:
    from dataprep.download import download_range

    station = _station(args)
    paths = download_range(
        _cds_client(),
        station,
        args.start,
        args.end,
        args.raw_dir,
        years_per_request=args.years_per_request,
    )
    print(f"raw files ready: {len(paths)} in {args.raw_dir}")


def _process(args: argparse.Namespace) -> None:
    from dataprep.pipeline import process

    station = _station(args)
    out = process(
        station,
        args.start,
        args.end,
        args.raw_dir,
        args.processed_dir,
        registry_version(args.registry),
        datetime.now(UTC),
    )
    print(f"processed: {out}")


def _publish(args: argparse.Namespace) -> None:
    from dataprep.publish import publish

    print(f"published: {publish(args.city, args.processed_dir, args.pricer_dir)}")


def _build(args: argparse.Namespace) -> None:
    _download(args)
    _process(args)
    _publish(args)


def _station(args: argparse.Namespace) -> Station:
    stations = load_registry(args.registry)
    if args.city not in stations:
        raise RegistryError(f"city {args.city!r} not in station registry {args.registry}")
    return stations[args.city]


def _cds_client() -> CdsClient:
    """Create the CDS client; a missing or bad ``~/.cdsapirc`` becomes a DownloadError."""
    import cdsapi

    try:
        return cdsapi.Client()
    except Exception as e:  # cdsapi raises plain Exception for config problems
        raise DownloadError(
            f"cannot create the Copernicus CDS client (check ~/.cdsapirc, see README): {e}"
        ) from e


def _iso_date(text: str) -> date:
    try:
        return date.fromisoformat(text)
    except ValueError as e:
        raise argparse.ArgumentTypeError(f"not an ISO date (YYYY-MM-DD): {text!r}") from e


def _years_per_request(text: str) -> int:
    """Parse ``--years-per-request`` (1..MAX_YEARS_PER_REQUEST)."""
    message = f"must be an integer from 1 to {MAX_YEARS_PER_REQUEST}, got {text!r}"
    try:
        value = int(text)
    except ValueError as e:
        raise argparse.ArgumentTypeError(message) from e
    if not 1 <= value <= MAX_YEARS_PER_REQUEST:
        raise argparse.ArgumentTypeError(message)
    return value


def _parser() -> argparse.ArgumentParser:
    dirs = default_dirs()
    parser = argparse.ArgumentParser(
        prog="dataprep", description="ERA5 -> daily Tmax/Tmin per settlement station."
    )
    commands = parser.add_subparsers(dest="command", required=True)

    def command(
        name: str,
        run: Callable[[argparse.Namespace], None],
        *,
        period: bool,
        raw: bool,
        processed: bool,
        pricer: bool,
        chunked: bool = False,
    ) -> None:
        sub = commands.add_parser(name)
        sub.set_defaults(run=run)
        sub.add_argument("--city", required=True, help="registry key, e.g. london")
        if period:
            sub.add_argument("--start", required=True, type=_iso_date, help="first UTC day")
            sub.add_argument("--end", required=True, type=_iso_date, help="last UTC day")
            sub.add_argument(
                "--registry",
                type=Path,
                default=repo_root() / "data-prep" / "stations.toml",
                help="station registry (default: data-prep/stations.toml)",
            )
        if raw:
            sub.add_argument("--raw-dir", type=Path, default=dirs.raw)
        if chunked:
            sub.add_argument(
                "--years-per-request",
                type=_years_per_request,
                default=DEFAULT_YEARS_PER_REQUEST,
                help=f"years per Copernicus request, 1..{MAX_YEARS_PER_REQUEST} "
                f"(default {DEFAULT_YEARS_PER_REQUEST})",
            )
        if processed:
            sub.add_argument("--processed-dir", type=Path, default=dirs.processed)
        if pricer:
            sub.add_argument("--pricer-dir", type=Path, default=dirs.pricer)

    command(
        "download", _download, period=True, raw=True, processed=False, pricer=False, chunked=True
    )
    command("process", _process, period=True, raw=True, processed=True, pricer=False)
    command("publish", _publish, period=False, raw=False, processed=True, pricer=True)
    command("build", _build, period=True, raw=True, processed=True, pricer=True, chunked=True)
    return parser
