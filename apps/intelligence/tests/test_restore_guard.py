"""Pure-standard-library launch-boundary tests; no model/key/ledger/runtime is opened."""
import ast
import builtins
import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from jarvis_intelligence import __main__ as launcher
from jarvis_intelligence import restore_guard as guard


class RestoreGuardTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='q0-guard-')
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def argv(self):
        return ['jarvis_intelligence', '--core-public-key', str(self.root / 'never-read-public-key'),
                '--core-key-id', 'synthetic', '--cache-key-file', str(self.root / 'never-read-cache-key'),
                '--ledger', str(self.root / 'never-open-ledger'), '--allow-loopback-mock-without-mtls']

    def test_all_present_control_values_deny_without_opening_any_path(self):
        for value in ('', 'normal', 'false', '/nonexistent', 'not-a-path', None):
            with self.subTest(value=value), patch.object(guard.os, 'open', side_effect=AssertionError('file open')):
                with self.assertRaisesRegex(guard.RestoreBlocked, '^RESTORE_LAUNCH_BLOCKED$'):
                    guard.check_restore_launch({guard.CONTROL: value})

    def test_ordinary_unenrolled_no_setting_empty_and_missing_directory_allow(self):
        guard.check_restore_launch({})
        guard.check_restore_launch({guard.RUNTIME: str(self.root)})
        guard.check_restore_launch({guard.RUNTIME: str(self.root / 'absent')})
        guard.check_restore_launch({}, ledger=self.root / 'ledger')

    def test_union_of_environment_sources_and_anchors_is_fail_closed(self):
        for sources in (({guard.CONTROL: ''}, {}), ({}, {guard.CONTROL: 'normal'})):
            with self.assertRaises(guard.RestoreBlocked): guard.check_restore_launch(*sources)
        (self.root / guard.ANCHOR).write_text('{malformed')
        with self.assertRaises(guard.RestoreBlocked):
            guard.check_restore_launch({guard.RUNTIME: str(self.root)}, {guard.RUNTIME: str(self.root / 'absent')})

    def test_anchor_presence_including_link_directory_fifo_denies_without_read(self):
        anchor = self.root / guard.ANCHOR
        operations = (lambda: anchor.write_text('foreign or corrupt'), lambda: anchor.mkdir(),
                      lambda: anchor.symlink_to(self.root / 'absent'), lambda: os.mkfifo(anchor))
        for create in operations:
            create()
            with patch.object(guard.os, 'read', side_effect=AssertionError('metadata content read')):
                with self.assertRaises(guard.RestoreBlocked):
                    guard.check_restore_launch({guard.RUNTIME: str(self.root)})
                with self.assertRaises(guard.RestoreBlocked):
                    guard.check_restore_launch({}, ledger=self.root / 'ledger')
            if anchor.is_dir(): anchor.rmdir()
            else: anchor.unlink()

    def test_symlink_component_relative_and_invalid_runtime_refuse(self):
        directory = self.root / 'actual'
        directory.mkdir()
        link = self.root / 'linked'
        link.symlink_to(directory, target_is_directory=True)
        for path in (str(link), str(link / 'missing'), 'relative', '', '\x00', str(self.root) + '/../other'):
            with self.subTest(path=path), self.assertRaises(guard.RestoreBlocked):
                guard.check_restore_launch({guard.RUNTIME: path})

    def test_directory_swap_during_probe_refuses(self):
        directory = self.root / 'runtime'
        directory.mkdir()
        real = guard.os.stat
        def swap(path, *args, **kwargs):
            if path == guard.ANCHOR:
                directory.rename(self.root / 'original')
                directory.mkdir()
            return real(path, *args, **kwargs)
        with patch.object(guard.os, 'stat', side_effect=swap), self.assertRaises(guard.RestoreBlocked):
            guard.check_restore_launch({guard.RUNTIME: str(directory)})

    def test_ambient_guard_precedes_argument_parsing_and_all_heavy_imports(self):
        with patch.dict(os.environ, {guard.CONTROL: ''}, clear=True), \
             patch.object(launcher.argparse, 'ArgumentParser', side_effect=AssertionError('parser invoked')), \
             patch.object(launcher.os, 'open', side_effect=AssertionError('key or ledger opened')):
            with self.assertRaisesRegex(SystemExit, '^RESTORE_LAUNCH_BLOCKED$'):
                launcher.main()

    def test_ledger_anchor_guard_precedes_heavy_imports_keys_or_factory(self):
        (self.root / guard.ANCHOR).write_text('not even parsed')
        real_import = builtins.__import__
        attempted = []
        def guarded_import(name, *args, **kwargs):
            if name.split('.')[0] in {'uvicorn', 'cryptography'} or name in {'app', 'local_runtime', 'tls'}:
                attempted.append(name)
                raise AssertionError('runtime import attempted')
            return real_import(name, *args, **kwargs)
        with patch.dict(os.environ, {}, clear=True), patch('sys.argv', self.argv()), \
             patch('builtins.__import__', side_effect=guarded_import):
            with self.assertRaisesRegex(SystemExit, '^RESTORE_LAUNCH_BLOCKED$'):
                launcher.main()
        self.assertEqual(attempted, [])

    def test_ordinary_launch_reaches_existing_runtime_boundary_without_starting_it(self):
        real_import = builtins.__import__
        class Admitted(Exception): pass
        def stop_at_runtime(name, *args, **kwargs):
            if name == 'uvicorn': raise Admitted()
            return real_import(name, *args, **kwargs)
        with patch.dict(os.environ, {}, clear=True), patch('sys.argv', self.argv()), \
             patch('builtins.__import__', side_effect=stop_at_runtime):
            with self.assertRaises(Admitted): launcher.main()


    def test_ledger_alias_cannot_check_clean_parent_then_resolve_to_enrolled_target(self):
        enrolled = self.root / 'enrolled'
        enrolled.mkdir()
        (enrolled / guard.ANCHOR).write_text('metadata')
        link = self.root / 'never-open-ledger'
        link.symlink_to(enrolled / 'ledger')
        with self.assertRaises(guard.RestoreBlocked):
            guard.check_restore_launch({}, ledger=link)
        real_import = builtins.__import__
        def deny_runtime(name, *args, **kwargs):
            if name.split('.')[0] in {'uvicorn', 'cryptography'} or name in {'app', 'local_runtime', 'tls'}:
                raise AssertionError('heavy import attempted')
            return real_import(name, *args, **kwargs)
        with patch.dict(os.environ, {}, clear=True), patch('sys.argv', self.argv()), \
             patch('builtins.__import__', side_effect=deny_runtime):
            with self.assertRaisesRegex(SystemExit, '^RESTORE_LAUNCH_BLOCKED$'):
                launcher.main()

    def test_ledger_directory_alias_and_hardlinked_file_are_refused(self):
        actual = self.root / 'actual'
        actual.mkdir()
        link = self.root / 'alias'
        link.symlink_to(actual, target_is_directory=True)
        with self.assertRaises(guard.RestoreBlocked):
            guard.check_restore_launch({}, ledger=link / 'ledger')
        original = actual / 'ledger'
        original.write_text('synthetic ledger not opened')
        os.link(original, self.root / 'linked-ledger')
        with self.assertRaises(guard.RestoreBlocked):
            guard.check_restore_launch({}, ledger=self.root / 'linked-ledger')

    def test_import_graph_is_stdlib_until_main_and_guard_is_pure(self):
        allowed = {'argparse', 'os', 'stat', 'ssl', 'pathlib', 'restore_guard'}
        module = ast.parse(Path(launcher.__file__).read_text())
        for node in module.body:
            if isinstance(node, ast.Import): self.assertLessEqual({n.name for n in node.names}, allowed)
            if isinstance(node, ast.ImportFrom): self.assertIn(node.module, allowed)
        module = ast.parse(Path(guard.__file__).read_text())
        for node in ast.walk(module):
            if isinstance(node, ast.Import): self.assertLessEqual({n.name for n in node.names}, {'os', 'pathlib', 'stat'})
            if isinstance(node, ast.ImportFrom): self.assertIn(node.module, {'os', 'pathlib', 'stat'})


if __name__ == '__main__':
    unittest.main()
