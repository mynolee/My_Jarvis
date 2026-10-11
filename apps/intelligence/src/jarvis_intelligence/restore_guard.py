"""Standard-library-only, supported-entrypoint restore refusal. No admission token."""
import os
from pathlib import Path
import stat

CONTROL = 'JARVIS_RESTORE_CONTROL_DIR'
RUNTIME = 'JARVIS_RUNTIME_DIR'
ANCHOR = 'restore-enrollment.json'


class RestoreBlocked(ValueError):
    def __init__(self):
        super().__init__('RESTORE_LAUNCH_BLOCKED')


def _open_directory(path):
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    fd = os.open('/', flags)
    try:
        for part in path.parts[1:]:
            child = os.open(part, flags, dir_fd=fd)
            try:
                named = os.stat(part, dir_fd=fd, follow_symlinks=False)
                opened = os.fstat(child)
                if (named.st_dev, named.st_ino) != (opened.st_dev, opened.st_ino) or not stat.S_ISDIR(named.st_mode):
                    raise RestoreBlocked()
            except BaseException:
                os.close(child)
                raise
            os.close(fd)
            fd = child
        return fd
    except BaseException:
        os.close(fd)
        raise


def _check_anchor(value, *, ledger_name=None):
    """Presence of any anchor object denies; no contents or stored path are trusted."""
    try:
        if not isinstance(value, (str, os.PathLike)):
            raise RestoreBlocked()
        raw = os.fspath(value)
        if not isinstance(raw, str) or '\x00' in raw or len(raw.encode('utf-8')) > 4096:
            raise RestoreBlocked()
        path = Path(raw)
        if not path.is_absolute() or path.anchor != '/' or not raw or str(path) != raw or os.path.normpath(raw) != raw:
            raise RestoreBlocked()
        try:
            fd = _open_directory(path)
        except FileNotFoundError:
            return  # An ordinary, not-yet-created runtime directory is unenrolled.
        try:
            before = os.fstat(fd)
            try:
                os.stat(ANCHOR, dir_fd=fd, follow_symlinks=False)
            except FileNotFoundError:
                pass
            else:
                raise RestoreBlocked()
            if ledger_name is not None:
                try:
                    leaf = os.stat(ledger_name, dir_fd=fd, follow_symlinks=False)
                except FileNotFoundError:
                    pass
                else:
                    if not stat.S_ISREG(leaf.st_mode) or leaf.st_nlink != 1:
                        raise RestoreBlocked()
            # Detect path replacement while probing the fixed child.
            other = _open_directory(path)
            try:
                after = os.fstat(other)
                if (before.st_dev, before.st_ino) != (after.st_dev, after.st_ino):
                    raise RestoreBlocked()
            finally:
                os.close(other)
        finally:
            os.close(fd)
    except (OSError, ValueError, TypeError, UnicodeError, AttributeError):
        raise RestoreBlocked() from None


def check_restore_launch(*environments, ledger=None):
    """Union all supplied sources; even a blank restore setting is a denial."""
    sources = environments or (os.environ,)
    if any(CONTROL in source for source in sources):
        raise RestoreBlocked()
    for source in sources:
        if RUNTIME in source:
            _check_anchor(source[RUNTIME])
    if ledger is not None:
        # The launcher historically accepts relative ledgers; anchor its absolute parent.
        path = Path(ledger).absolute()
        _check_anchor(path.parent, ledger_name=path.name)
