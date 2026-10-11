#!/usr/bin/env python3
"""POSIX-only offline restore preparation. Never imports or verifies a database."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import stat
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
LIMIT = 8192
PATH_LIMIT = 4096
ENROLLMENT = 'restore-enrollment.json'
FENCE = 'quarantine-fence.json'
LOCK = '.restore-quarantine.lock'
BLOCKERS = (
    'AUTHORITY_INVALIDATION_NOT_PERFORMED',
    'BACKUP_IMPORT_NOT_IMPLEMENTED',
    'CHECKPOINT_TRUST_AND_FRESHNESS_UNAVAILABLE',
    'DELETION_REPLAY_NOT_PERFORMED',
    'EXTERNAL_OUTCOMES_NOT_RECONCILED',
    'STORAGE_VERIFICATION_NOT_PERFORMED',
)


class Blocked(ValueError):
    """Only application-defined, value-free error codes cross the CLI boundary."""


def _uuid(value):
    if not isinstance(value, str) or len(value) != 36:
        raise Blocked('INVALID_IDENTITY')
    try:
        if str(uuid.UUID(value)) != value:
            raise ValueError()
    except ValueError:
        raise Blocked('INVALID_IDENTITY') from None
    return value


def _path(value):
    if not isinstance(value, str) or not value or '\x00' in value:
        raise Blocked('INVALID_PATH')
    try:
        if len(value.encode('utf-8')) > PATH_LIMIT:
            raise Blocked('INVALID_PATH')
    except UnicodeError:
        raise Blocked('INVALID_PATH') from None
    path = Path(value)
    if not path.is_absolute() or path.anchor != '/' or str(path) != value or os.path.normpath(value) != value or value == '/':
        raise Blocked('INVALID_PATH')
    if path == ROOT or ROOT in path.parents:
        raise Blocked('PATH_IN_SOURCE')
    return path


def _same(a, b):
    return (a.st_dev, a.st_ino, stat.S_IFMT(a.st_mode)) == (b.st_dev, b.st_ino, stat.S_IFMT(b.st_mode))


def _private(info, directory=False):
    expected = stat.S_ISDIR if directory else stat.S_ISREG
    if (not expected(info.st_mode) or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != (0o700 if directory else 0o600)
            or (not directory and info.st_nlink != 1)):
        raise Blocked('UNSAFE_DIRECTORY' if directory else 'UNSAFE_METADATA')


def _open_directory(path):
    """Walk from / using descriptors; no symlink component is followed."""
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    fd = os.open('/', flags)
    try:
        for component in path.parts[1:]:
            child = os.open(component, flags, dir_fd=fd)
            try:
                if not _same(os.stat(component, dir_fd=fd, follow_symlinks=False), os.fstat(child)):
                    raise Blocked('PATH_CHANGED')
            except BaseException:
                os.close(child)
                raise
            os.close(fd)
            fd = child
        _private(os.fstat(fd), directory=True)
        return fd
    except BaseException:
        os.close(fd)
        raise


class Directory:
    def __init__(self, path):
        self.path = path
        self.fd = _open_directory(path)
        self.identity = os.fstat(self.fd)

    def close(self):
        os.close(self.fd)

    def check(self):
        _private(os.fstat(self.fd), directory=True)
        current = _open_directory(self.path)
        try:
            if not _same(self.identity, os.fstat(current)):
                raise Blocked('PATH_CHANGED')
        finally:
            os.close(current)

    def names(self):
        self.check()
        # Bounded enumeration; do not open, decode or report unknown contents.
        with os.scandir(self.fd) as entries:
            names = set()
            for entry in entries:
                names.add(entry.name)
                if len(names) > 3:
                    raise Blocked('UNEXPECTED_FILES')
        self.check()
        return names

    def sync(self):
        self.check()
        os.fsync(self.fd)
        self.check()


def _file_check(directory, name, fd):
    directory.check()
    info = os.fstat(fd)
    _private(info)
    named = os.stat(name, dir_fd=directory.fd, follow_symlinks=False)
    _private(named)
    if not _same(named, info):
        raise Blocked('PATH_CHANGED')
    return info


def _open_file(directory, name, flags):
    directory.check()
    fd = os.open(name, flags | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC,
                 0o600, dir_fd=directory.fd)
    try:
        _file_check(directory, name, fd)
        return fd
    except BaseException:
        os.close(fd)
        raise


def _no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Blocked('INVALID_METADATA')
        result[key] = value
    return result


def _read(directory, name):
    fd = _open_file(directory, name, os.O_RDONLY)
    try:
        info = _file_check(directory, name, fd)
        if info.st_size <= 0 or info.st_size > LIMIT:
            raise Blocked('INVALID_METADATA')
        chunks = bytearray()
        while len(chunks) <= LIMIT:
            chunk = os.read(fd, min(4096, LIMIT + 1 - len(chunks)))
            if not chunk:
                break
            chunks.extend(chunk)
        after = _file_check(directory, name, fd)
        if (len(chunks) != info.st_size or after.st_size != info.st_size
                or after.st_mtime_ns != info.st_mtime_ns or after.st_ctime_ns != info.st_ctime_ns):
            raise Blocked('METADATA_CHANGED')
        try:
            value = json.loads(chunks.decode('utf-8'), object_pairs_hook=_no_duplicates,
                               parse_constant=lambda _: (_ for _ in ()).throw(Blocked('INVALID_METADATA')))
        except (UnicodeError, ValueError, RecursionError):
            raise Blocked('INVALID_METADATA') from None
        if not isinstance(value, dict):
            raise Blocked('INVALID_METADATA')
        return value
    finally:
        os.close(fd)


def _matches(value, expected, code='METADATA_CONFLICT'):
    # Equality alone would accept True as revision 1 or 0 as false.
    if set(value) != set(expected) or any(type(value[k]) is not type(v) or value[k] != v for k, v in expected.items()):
        raise Blocked(code)


def _persist_existing(directory, name, expected, conflict='METADATA_CONFLICT'):
    _matches(_read(directory, name), expected, conflict)
    fd = _open_file(directory, name, os.O_RDONLY)
    try:
        os.fsync(fd)
        _file_check(directory, name, fd)
    finally:
        os.close(fd)
    directory.sync()
    _matches(_read(directory, name), expected, conflict)


def _put(directory, name, value, conflict='METADATA_CONFLICT'):
    raw = (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')
    if len(raw) > LIMIT:
        raise Blocked('INVALID_METADATA')
    try:
        fd = _open_file(directory, name, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
    except FileExistsError:
        try:
            _persist_existing(directory, name, value, conflict)
        except (Blocked, OSError):
            if conflict == 'TARGET_ALREADY_ENROLLED':
                # Another control may have reserved the anchor but not written it yet.
                # Reservation alone wins: never wait, overwrite, repair, or unlink it.
                raise Blocked('TARGET_ALREADY_ENROLLED') from None
            raise
        return
    try:
        # A short write is deliberately blocked, never repaired or unlinked.
        if os.write(fd, raw) != len(raw):
            raise Blocked('SHORT_WRITE')
        _file_check(directory, name, fd)
        os.fsync(fd)
        _file_check(directory, name, fd)
    finally:
        os.close(fd)
    directory.sync()
    _matches(_read(directory, name), value, conflict)


@contextmanager
def _locked(directory, create):
    flags = os.O_RDWR
    if create:
        try:
            fd = _open_file(directory, LOCK, flags | os.O_CREAT | os.O_EXCL)
        except FileExistsError:
            fd = _open_file(directory, LOCK, flags)
    else:
        fd = _open_file(directory, LOCK, flags)
    try:
        if os.fstat(fd).st_size != 0:
            raise Blocked('INVALID_LOCK')
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Blocked('LOCK_BUSY') from None
        _file_check(directory, LOCK, fd)
        os.fsync(fd)
        directory.sync()
        yield
        _file_check(directory, LOCK, fd)
    finally:
        os.close(fd)  # Never unlink or replace the lock inode.


def _records(control, staging, identities):
    common = {'revision': 1, **identities}
    return (
        {**common, 'kind': 'RESTORE_ENROLLMENT', 'control_dir': str(control), 'staging_dir': str(staging)},
        {**common, 'kind': 'RESTORE_QUARANTINE_FENCE', 'launch_permitted': False},
        {**common, 'kind': 'RESTORE_STAGING_ANCHOR', 'control_dir': str(control)},
    )


def _bindings(control, staging):
    if control == staging or control in staging.parents or staging in control.parents:
        raise Blocked('DIRECTORY_OVERLAP')


def _receipt(identities):
    return {'revision': 1, **identities, 'status': 'STILL_PAUSED', 'verification': 'BLOCKED',
            'restore_started': False, 'launch_permitted': False, 'target_database': 'NOT_INSPECTED',
            'durable_isolation': 'UNVERIFIED', 'blockers': list(BLOCKERS)}


def _perform(command, control_dir, deployment_id, target_id, preparation_id, staging_dir=None):
    if os.name != 'posix' or not all(hasattr(os, name) for name in ('O_NOFOLLOW', 'O_DIRECTORY', 'O_CLOEXEC')):
        raise Blocked('PLATFORM_UNSUPPORTED')
    identities = {name: _uuid(value) for name, value in (
        ('deployment_id', deployment_id), ('target_id', target_id), ('preparation_id', preparation_id))}
    control = _path(control_dir)
    if command not in ('prepare', 'inspect') or (command == 'inspect' and staging_dir is not None):
        raise Blocked('INVALID_ARGUMENTS')
    staging = _path(staging_dir) if command == 'prepare' else None
    if staging is not None:
        _bindings(control, staging)
    external = Directory(control)
    try:
        with _locked(external, create=command == 'prepare'):
            names = external.names()
            if not names <= {LOCK, ENROLLMENT, FENCE}:
                raise Blocked('UNEXPECTED_FILES')
            if command == 'inspect':
                recorded = _read(external, ENROLLMENT)
                # Verify closed shape and caller identity before using the bounded stored path.
                expected_keys = {'revision', 'kind', *identities, 'control_dir', 'staging_dir'}
                if set(recorded) != expected_keys:
                    raise Blocked('INVALID_METADATA')
                staging = _path(recorded.get('staging_dir'))
                _bindings(control, staging)
                _matches(recorded, _records(control, staging, identities)[0])
            enrollment, fence, anchor = _records(control, staging, identities)
            target = Directory(staging)
            try:
                if _same(external.identity, target.identity):
                    raise Blocked('DIRECTORY_OVERLAP')
                target_names = target.names()
                if not target_names <= {ENROLLMENT}:
                    raise Blocked('TARGET_NOT_EMPTY')
                if FENCE in names and ENROLLMENT not in names:
                    raise Blocked('INCOMPLETE_METADATA')
                if ENROLLMENT in target_names:
                    _matches(_read(target, ENROLLMENT), anchor, 'TARGET_ALREADY_ENROLLED')
                    if ENROLLMENT not in names or FENCE not in names:
                        raise Blocked('INCOMPLETE_METADATA')
                if command == 'prepare':
                    _put(external, ENROLLMENT, enrollment)
                    _put(external, FENCE, fence)
                    _put(target, ENROLLMENT, anchor, 'TARGET_ALREADY_ENROLLED')
                for directory, name, expected in ((external, ENROLLMENT, enrollment),
                                                  (external, FENCE, fence), (target, ENROLLMENT, anchor)):
                    _persist_existing(directory, name, expected)
                if external.names() != {LOCK, ENROLLMENT, FENCE} or target.names() != {ENROLLMENT}:
                    raise Blocked('UNEXPECTED_FILES')
                return _receipt(identities)
            finally:
                target.close()
    finally:
        external.close()


def prepare(control_dir, staging_dir, deployment_id, target_id, preparation_id):
    """Metadata only; callers must supply new synthetic or operator-authorized directories."""
    old_umask = os.umask(0o077)
    try:
        return _perform('prepare', control_dir, deployment_id, target_id, preparation_id, staging_dir)
    finally:
        os.umask(old_umask)


def inspect(control_dir, deployment_id, target_id, preparation_id):
    return _perform('inspect', control_dir, deployment_id, target_id, preparation_id)


class _Parser(argparse.ArgumentParser):
    def error(self, message):
        raise Blocked('INVALID_ARGUMENTS')


def main(argv=None):
    try:
        parser = _Parser(description=__doc__, add_help=False)
        parser.add_argument('command', choices=('prepare', 'inspect'))
        parser.add_argument('--control-dir', required=True)
        parser.add_argument('--staging-dir')
        parser.add_argument('--deployment-id', required=True)
        parser.add_argument('--target-id', required=True)
        parser.add_argument('--preparation-id', required=True)
        args = parser.parse_args(argv)
        operation = vars(args)
        command = operation.pop('command')
        if command == 'prepare':
            result = prepare(**operation)
        else:
            if operation.pop('staging_dir') is not None:
                raise Blocked('INVALID_ARGUMENTS')
            result = inspect(**operation)
        code = 0
    except (Exception, KeyboardInterrupt) as exc:
        result = {'revision': 1, 'status': 'BLOCKED',
                  'code': str(exc) if type(exc) is Blocked else 'PREPARATION_FAILED',
                  'restore_started': False, 'launch_permitted': False,
                  'verification': 'BLOCKED', 'target_database': 'NOT_INSPECTED',
                  'durable_isolation': 'UNVERIFIED'}
        code = 1
    print(json.dumps(result, sort_keys=True))
    return code


if __name__ == '__main__':
    sys.exit(main())
