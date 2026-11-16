"""Command line: ``python -m dataprep {download,process,publish,build}`` (spec 002).

Options: ``--city``, ``--start``, ``--end`` (ISO dates, inclusive), ``--registry``,
``--raw-dir``, ``--processed-dir``, ``--pricer-dir`` (folders default to ``<repo>/data/...``).
"""


def main(argv: list[str] | None = None) -> int:
    """Run the CLI.

    Args:
        argv: Arguments without the program name; ``None`` reads ``sys.argv``.

    Returns:
        Process exit code: 0 on success, 1 on a data-prep error (message on stderr), 2 on
        bad arguments.
    """
    raise NotImplementedError
