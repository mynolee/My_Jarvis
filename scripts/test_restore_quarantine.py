"""Disposable synthetic filesystem fixtures only; no operational restore enrollment."""
import contextlib
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import threading
import unittest
from unittest.mock import patch

import restore_quarantine as q

IDS = {'deployment_id': '11111111-1111-4111-8111-111111111111',
       'target_id': '22222222-2222-4222-8222-222222222222',
       'preparation_id': '33333333-3333-4333-8333-333333333333'}


class PreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='q0-synthetic-')
        self.root = Path(self.temporary.name)
        self.control = self.root / 'control'
        self.staging = self.root / 'staging'
        self.control.mkdir(mode=0o700)
        self.staging.mkdir(mode=0o700)
        self.args = {'control_dir': str(self.control), 'staging_dir': str(self.staging), **IDS}

    def tearDown(self):
        self.temporary.cleanup()

    def prepare(self, **overrides):
        return q.prepare(**{**self.args, **overrides})

    def inspect(self, **overrides):
        return q.inspect(**{k: v for k, v in {**self.args, **overrides}.items() if k != 'staging_dir'})

    def marker(self, directory=None, name=q.ENROLLMENT):
        return (directory or self.control) / name

    def write(self, path, content):
        path.write_bytes(content if isinstance(content, bytes) else content.encode())
        path.chmod(0o600)

    def snapshot(self):
        return {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}

    def test_prepare_inspect_exact_retry_closed_receipt(self):
        first = self.prepare()
        before = self.snapshot()
        self.assertEqual(first, self.prepare())
        self.assertEqual(first, self.inspect())
        self.assertEqual(before, self.snapshot())
        self.assertEqual(set(first), {'revision', *IDS, 'status', 'verification', 'restore_started',
                                     'launch_permitted', 'target_database', 'durable_isolation', 'blockers'})
        self.assertEqual(first['status'], 'STILL_PAUSED')
        self.assertEqual(first['verification'], 'BLOCKED')
        self.assertEqual(first['durable_isolation'], 'UNVERIFIED')
        self.assertEqual(first['target_database'], 'NOT_INSPECTED')
        self.assertFalse(first['launch_permitted'])
        self.assertFalse(first['restore_started'])
        self.assertEqual(first['blockers'], sorted(q.BLOCKERS))
        self.assertNotIn(str(self.root), json.dumps(first))
        self.assertEqual(set(p.name for p in self.staging.iterdir()), {q.ENROLLMENT})
        for path in self.root.rglob('*'):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o700 if path.is_dir() else 0o600)

    def test_each_identity_and_staging_binding_conflict_preserves_bytes(self):
        self.prepare()
        before = self.snapshot()
        for key in IDS:
            with self.subTest(key=key), self.assertRaises(q.Blocked):
                self.prepare(**{key: '44444444-4444-4444-8444-444444444444'})
            with self.assertRaises(q.Blocked):
                self.inspect(**{key: '44444444-4444-4444-8444-444444444444'})
        other = self.root / 'other'
        other.mkdir(mode=0o700)
        with self.assertRaises(q.Blocked):
            self.prepare(staging_dir=str(other))
        self.assertEqual(before, self.snapshot())

    def test_paths_are_absolute_canonical_separate_and_outside_source(self):
        nested = self.control / 'nested'
        nested.mkdir(mode=0o700)
        for path in ('relative', '', str(self.staging) + '/', str(self.staging) + '/../staging',
                     str(q.ROOT), str(q.ROOT / 'nested'), '/' + str(q.ROOT / 'nested'), str(self.control), str(nested), '\x00'):
            with self.subTest(path=path), self.assertRaises(q.Blocked):
                self.prepare(staging_dir=path)
        with self.assertRaises(q.Blocked):
            self.prepare(control_dir=str(self.staging / 'child'))

    def test_uuid_input_is_canonical_and_typed(self):
        for value in ('', 'normal', IDS['target_id'].upper().replace('2222', 'ABCD', 1),
                      '{' + IDS['target_id'] + '}', IDS['target_id'].replace('-', ''), None, 1):
            with self.subTest(value=value), self.assertRaises(q.Blocked):
                self.prepare(target_id=value)
        self.assertEqual(list(self.staging.iterdir()), [])

    def test_nonempty_target_and_unknown_control_entries_refuse(self):
        self.write(self.staging / 'not-a-backup-to-open', 'private-canary')
        with self.assertRaises(q.Blocked):
            self.prepare()
        self.assertFalse(self.marker().exists())
        (self.staging / 'not-a-backup-to-open').unlink()
        self.write(self.control / 'unexpected', 'x')
        with self.assertRaises(q.Blocked):
            self.prepare()

    def test_symlink_components_and_target_links_refuse(self):
        link = self.root / 'link'
        link.symlink_to(self.staging, target_is_directory=True)
        with self.assertRaises((q.Blocked, OSError)):
            self.prepare(staging_dir=str(link))
        self.marker().symlink_to(self.root / 'missing')
        with self.assertRaises((q.Blocked, OSError)):
            self.prepare()
        self.assertFalse((self.root / 'missing').exists())

    def test_directory_and_marker_permissions_refuse(self):
        for mode in (0o755, 0o710, 0o777):
            self.staging.chmod(mode)
            with self.assertRaises(q.Blocked):
                self.prepare()
        self.staging.chmod(0o700)
        self.prepare()
        self.marker().chmod(0o644)
        with self.assertRaises(q.Blocked):
            self.inspect()

    def test_wrong_owner_fstat_is_denied(self):
        original = os.fstat
        def foreign(fd):
            value = list(original(fd))
            value[4] = os.getuid() + 1
            return os.stat_result(value)
        with patch.object(q.os, 'fstat', side_effect=foreign), self.assertRaises(q.Blocked):
            self.prepare()

    def test_hardlinked_and_special_marker_files_refuse_without_hanging(self):
        self.prepare()
        os.link(self.marker(), self.root / 'extra-link')
        with self.assertRaises(q.Blocked):
            self.inspect()
        (self.root / 'extra-link').unlink()
        self.marker().unlink()
        os.mkfifo(self.marker(), 0o600)
        with self.assertRaises(q.Blocked):
            self.inspect()

    def test_duplicate_invalid_encoding_unknown_version_fields_and_size_refuse(self):
        self.prepare()
        original = self.marker().read_bytes()
        obj = json.loads(original)
        bad = [b'\xff', b'{', b'{}', b'[]', b'x' * (q.LIMIT + 1),
               original.replace(b'"revision":1', b'"revision":1,"revision":1'),
               json.dumps({**obj, 'revision': True}), json.dumps({**obj, 'revision': 2}),
               json.dumps({**obj, 'extra': 'unsafe'}), json.dumps({**obj, 'kind': 'VERIFIED'}),
               json.dumps({**obj, 'target_id': obj['target_id'].replace('-', '')}),
               json.dumps({**obj, 'staging_dir': 1}), b'{"revision":NaN}']
        for content in bad:
            with self.subTest(content=str(content)[:30]):
                self.write(self.marker(), content)
                with self.assertRaises(q.Blocked):
                    self.inspect()
                with self.assertRaises(q.Blocked):
                    self.prepare()
        self.write(self.marker(), original)

    def test_missing_fence_inspect_blocks_exact_prepare_completes(self):
        original = q._put
        def interrupt(directory, name, value, *args):
            if name == q.FENCE:
                raise KeyboardInterrupt()
            return original(directory, name, value, *args)
        with patch.object(q, '_put', side_effect=interrupt), self.assertRaises(KeyboardInterrupt):
            self.prepare()
        self.assertTrue(self.marker().exists())
        self.assertFalse(self.marker(name=q.FENCE).exists())
        with self.assertRaises(FileNotFoundError):
            self.inspect()
        self.assertEqual(self.prepare()['verification'], 'BLOCKED')

    def test_interrupt_at_each_durable_phase_keeps_denying_state_and_exact_retry(self):
        real = q._put
        for phase in range(1, 4):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory(prefix='q0-phase-') as temp:
                control, target = Path(temp) / 'c', Path(temp) / 's'
                control.mkdir(mode=0o700); target.mkdir(mode=0o700)
                args = {**self.args, 'control_dir': str(control), 'staging_dir': str(target)}
                calls = 0
                def fail_after(*args, **kwargs):
                    nonlocal calls
                    result = real(*args, **kwargs)
                    calls += 1
                    if calls == phase:
                        raise KeyboardInterrupt()
                    return result
                with patch.object(q, '_put', side_effect=fail_after), self.assertRaises(KeyboardInterrupt):
                    q.prepare(**args)
                saved = (control / q.ENROLLMENT).read_bytes()
                result = q.prepare(**args)
                self.assertEqual(result['status'], 'STILL_PAUSED')
                self.assertEqual(saved, (control / q.ENROLLMENT).read_bytes())

    def test_short_write_leaves_incomplete_marker_never_repairs(self):
        real = os.write
        with patch.object(q.os, 'write', side_effect=lambda fd, raw: real(fd, raw[:7])), self.assertRaises(q.Blocked):
            self.prepare()
        before = self.snapshot()
        with self.assertRaises(q.Blocked):
            self.prepare()
        self.assertEqual(before, self.snapshot())
        self.assertFalse(self.marker(name=q.FENCE).exists())

    def test_every_fsync_boundary_failure_is_blocked_and_exact_retry_stays_blocked(self):
        real = os.fsync
        calls = 0
        def count(fd):
            nonlocal calls
            calls += 1
            real(fd)
        with patch.object(q.os, 'fsync', side_effect=count):
            self.prepare()
        self.assertGreater(calls, 6)
        for boundary in range(1, calls + 1):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory(prefix='q0-sync-') as temp:
                control, target = Path(temp) / 'c', Path(temp) / 's'
                control.mkdir(mode=0o700); target.mkdir(mode=0o700)
                args = {**self.args, 'control_dir': str(control), 'staging_dir': str(target)}
                n = 0
                def fail(fd):
                    nonlocal n
                    n += 1
                    if n == boundary:
                        raise OSError('synthetic-disk-canary')
                    return real(fd)
                with patch.object(q.os, 'fsync', side_effect=fail), self.assertRaises(OSError):
                    q.prepare(**args)
                before = (control / q.ENROLLMENT).read_bytes() if (control / q.ENROLLMENT).exists() else None
                result = q.prepare(**args)
                self.assertEqual(result['verification'], 'BLOCKED')
                if before is not None:
                    self.assertEqual(before, (control / q.ENROLLMENT).read_bytes())

    def test_read_shortening_or_inode_change_is_denied(self):
        self.prepare()
        real = os.read
        calls = 0
        def short(fd, size):
            nonlocal calls
            calls += 1
            return real(fd, 1) if calls == 1 else b''
        with patch.object(q.os, 'read', side_effect=short), self.assertRaises(q.Blocked):
            self.inspect()
        directory = q.Directory(self.control)
        try:
            moved = self.root / 'moved'
            self.control.rename(moved)
            self.control.mkdir(mode=0o700)
            with self.assertRaises(q.Blocked):
                directory.check()
        finally:
            directory.close()

    def test_lock_conflict_and_lock_replacement_are_denied(self):
        directory = q.Directory(self.control)
        try:
            with q._locked(directory, create=True):
                with self.assertRaisesRegex(q.Blocked, '^LOCK_BUSY$'):
                    self.prepare()
                with self.assertRaisesRegex(q.Blocked, '^LOCK_BUSY$'):
                    self.inspect()
        finally:
            directory.close()
        self.assertTrue((self.control / q.LOCK).exists())
        (self.control / q.LOCK).chmod(0o644)
        with self.assertRaises(q.Blocked):
            self.prepare()

    def test_two_control_directories_compete_for_one_exclusive_target(self):
        other = self.root / 'other-control'
        other.mkdir(mode=0o700)
        barrier = threading.Barrier(2)
        original = q._put
        results = []
        def put(directory, name, value, *args):
            if directory.path == self.staging and name == q.ENROLLMENT:
                barrier.wait(timeout=5)
            return original(directory, name, value, *args)
        def run(control, attempt):
            try:
                results.append(self.prepare(control_dir=str(control), preparation_id=attempt))
            except q.Blocked as error:
                results.append(str(error))
        # Both threads use synthetic fixtures. Restore umask after the concurrent calls.
        old_umask = os.umask(0o077)
        try:
            with patch.object(q, '_put', side_effect=put):
                threads = [threading.Thread(target=run, args=(self.control, IDS['preparation_id'])),
                           threading.Thread(target=run, args=(other, '44444444-4444-4444-8444-444444444444'))]
                for thread in threads: thread.start()
                for thread in threads: thread.join(timeout=10)
                self.assertFalse(any(thread.is_alive() for thread in threads))
        finally:
            os.umask(old_umask)
        self.assertEqual(sum(isinstance(result, dict) for result in results), 1, results)
        self.assertIn('TARGET_ALREADY_ENROLLED', results)
        for directory in (self.control, other):
            self.assertTrue((directory / q.ENROLLMENT).exists())
            self.assertTrue((directory / q.FENCE).exists())
        anchor = json.loads(self.marker(self.staging).read_text())
        self.assertIn(anchor['control_dir'], {str(self.control), str(other)})


    def test_racing_reserved_but_unwritten_anchor_has_closed_loser_code(self):
        real = q._open_file
        reserved = False
        def reserve(directory, name, flags):
            nonlocal reserved
            if directory.path == self.staging and name == q.ENROLLMENT and flags & os.O_EXCL:
                self.write(self.marker(self.staging), b'')
                reserved = True
            return real(directory, name, flags)
        with patch.object(q, '_open_file', side_effect=reserve), \
             self.assertRaisesRegex(q.Blocked, '^TARGET_ALREADY_ENROLLED$'):
            self.prepare()
        self.assertTrue(reserved)
        self.assertEqual(self.marker(self.staging).read_bytes(), b'')
        self.assertTrue(self.marker().exists())
        self.assertTrue(self.marker(name=q.FENCE).exists())
        before = self.snapshot()
        with self.assertRaises(q.Blocked): self.prepare()
        self.assertEqual(before, self.snapshot())

    def test_disk_full_and_permission_change_never_remove_enrollment(self):
        real = os.write
        calls = 0
        def full(fd, raw):
            nonlocal calls
            calls += 1
            if calls == 2: raise OSError('synthetic-disk-full')
            return real(fd, raw)
        with patch.object(q.os, 'write', side_effect=full), self.assertRaises(OSError):
            self.prepare()
        before = self.marker().read_bytes()
        self.assertEqual(self.marker(name=q.FENCE).read_bytes(), b'')
        with self.assertRaises(q.Blocked): self.prepare()
        self.assertEqual(before, self.marker().read_bytes())
        self.control.chmod(0o755)
        with self.assertRaises(q.Blocked): self.inspect()
        self.control.chmod(0o700)

    def test_lock_inode_replacement_is_detected_without_unlinking_replacement(self):
        directory = q.Directory(self.control)
        try:
            with self.assertRaises(q.Blocked):
                with q._locked(directory, create=True):
                    lock = self.control / q.LOCK
                    lock.rename(self.control / 'original-lock')
                    self.write(lock, b'')
            self.assertTrue((self.control / q.LOCK).exists())
            self.assertTrue((self.control / 'original-lock').exists())
        finally:
            directory.close()

    def test_fixed_cli_error_and_receipt_no_arguments_paths_or_exceptions(self):
        args = ['prepare', '--control-dir', str(self.control), '--staging-dir', str(self.staging)]
        for key, value in IDS.items(): args += ['--' + key.replace('_', '-'), value]
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(q.main(args), 0)
        self.assertEqual(json.loads(out.getvalue())['verification'], 'BLOCKED')
        for supplied in (['release', 'private-input-canary'], ['prepare', '--unknown', 'private-input-canary'],
                         ['inspect', '--control-dir', 'private-input-canary']):
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(q.main(supplied), 1)
            result = json.loads(out.getvalue())
            self.assertEqual(result['code'], 'INVALID_ARGUMENTS')
            self.assertNotIn('private-input-canary', out.getvalue())
        with patch.object(q.os, 'fsync', side_effect=OSError(str(self.root) + '/private-input-canary')):
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(q.main(args), 1)
            self.assertNotIn(str(self.root), out.getvalue())
            self.assertNotIn('private-input-canary', out.getvalue())

    def test_offline_source_has_no_database_network_subprocess_or_runtime_imports(self):
        import ast
        source = ast.parse(Path(q.__file__).read_text())
        imports = {node.name.split('.')[0] for part in ast.walk(source) if isinstance(part, ast.Import) for node in part.names}
        imports |= {part.module.split('.')[0] for part in ast.walk(source) if isinstance(part, ast.ImportFrom) and part.module}
        self.assertLessEqual(imports, {'__future__', 'argparse', 'contextlib', 'fcntl', 'json', 'os', 'pathlib', 'stat', 'sys', 'uuid'})
        with patch('socket.socket', side_effect=AssertionError('network attempted')), \
             patch('subprocess.Popen', side_effect=AssertionError('subprocess attempted')):
            self.assertEqual(self.prepare()['target_database'], 'NOT_INSPECTED')
            self.assertEqual(self.inspect()['durable_isolation'], 'UNVERIFIED')


if __name__ == '__main__':
    unittest.main()
