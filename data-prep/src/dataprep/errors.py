"""Exceptions raised by data-prep. Messages always name the entry, date, timestamp or path."""


class DataPrepError(Exception):
    """Base class for all data-prep errors."""


class RegistryError(DataPrepError):
    """Invalid station registry; the message names the entry."""


class DataError(DataPrepError):
    """Invalid or incomplete ERA5 input; the message names the date, timestamp or city."""


class DownloadError(DataPrepError):
    """A download failed or returned an unusable file; the message names the target path."""


class PublishError(DataPrepError):
    """A processed file failed validation; the message names the problem and the path."""
