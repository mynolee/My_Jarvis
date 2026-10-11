import json
import contextlib
import io
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import local_stack as stack

class LifecycleTest(unittest.TestCase):
    def values(self):
        values = {key: 'synthetic' for key in stack.KEYS}
        values.update(JARVIS_LOCAL_URL='https://localhost:9443', JARVIS_ORIGIN='https://localhost:8443',
                      JARVIS_DB_URL='jdbc:postgresql://127.0.0.1:5432/synthetic',
                      JARVIS_LOCAL_MANIFEST_HASH='a'*64, JARVIS_CORE_CERT_SHA256='b'*64)
        return values

    def write_env(self, root, values=None):
        path = Path(root) / 'local.env'
        path.write_text('\n'.join(k+'='+v for k,v in (values or self.values()).items()))
        path.chmod(0o600)
        return path

    def test_optional_reminder_worker_switch_is_closed_and_core_only(self):
        with tempfile.TemporaryDirectory() as root:
            baseline = stack.read_environment(self.write_env(root))
            self.assertNotIn('JARVIS_REMINDER_WORKER_ENABLED', baseline)
            for value in ('true', 'false'):
                supplied = {**self.values(), 'JARVIS_REMINDER_WORKER_ENABLED': value}
                parsed = stack.read_environment(self.write_env(root, supplied))
                self.assertEqual(stack.child_environment(parsed, 'core')['JARVIS_REMINDER_WORKER_ENABLED'], value)
                self.assertNotIn('JARVIS_REMINDER_WORKER_ENABLED', stack.child_environment(parsed, 'intelligence'))
            for value in ('TRUE', '1', ' false', 'false ', '$(false)', 'true;false'):
                with self.assertRaises(stack.Blocked):
                    stack.read_environment(self.write_env(root, {**self.values(), 'JARVIS_REMINDER_WORKER_ENABLED': value}))

    def test_environment_closed_no_shell_expansion(self):
        with tempfile.TemporaryDirectory() as root:
            values=self.values(); values['JARVIS_DB_PASSWORD']='$(touch /never-execute);"${SECRET}"'
            path=self.write_env(root,values)
            self.assertEqual(stack.read_environment(path)['JARVIS_DB_PASSWORD'],values['JARVIS_DB_PASSWORD'])
            with path.open('a') as handle:handle.write('\nJAVA_TOOL_OPTIONS=-agentlib:evil')
            with self.assertRaises(stack.Blocked):stack.read_environment(path)

    def test_reject_duplicate_missing_public_bind_and_permissions(self):
        with tempfile.TemporaryDirectory() as root:
            path=self.write_env(root)
            with path.open('a') as handle:handle.write('\nJARVIS_DB_USER=duplicate')
            with self.assertRaises(stack.Blocked):stack.read_environment(path)
            for change in ({'JARVIS_LOCAL_URL':'http://localhost:9443'},{'JARVIS_ORIGIN':'https://example.org'},{'JARVIS_DB_URL':'jdbc:postgresql://remote:5432/x'}):
                path=self.write_env(root,{**self.values(),**change})
                with self.assertRaises(stack.Blocked):stack.read_environment(path)
            path=self.write_env(root);path.chmod(0o644)
            with self.assertRaises(stack.Blocked):stack.read_environment(path)

    def test_symlink_private_env_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            path=self.write_env(root);link=Path(root)/'link.env';link.symlink_to(path)
            with self.assertRaises(stack.Blocked):stack.read_environment(link)

    def test_subprocess_env_least_privilege(self):
        values=self.values()
        with patch.dict(os.environ, {'UNRELATED_SECRET':'must-not-inherit', 'JAVA_TOOL_OPTIONS':'-Xanything', 'PYTHONPATH':'/evil'}):
            python=stack.child_environment(values,'intelligence');core=stack.child_environment(values,'core')
        for env in (python,core):
            self.assertNotIn('UNRELATED_SECRET',env);self.assertNotIn('JAVA_TOOL_OPTIONS',env)
        self.assertNotIn('JARVIS_DB_PASSWORD',python)
        self.assertEqual(core['JARVIS_LOCAL_ENABLED'],'true')
        self.assertEqual(python['HF_HUB_OFFLINE'],'1')
        self.assertEqual(python['PYTHONPATH'],str(stack.PYTHON_SOURCE))

    def test_commands_fixed_no_secrets_in_argv(self):
        values=self.values();values['JARVIS_DB_PASSWORD']='sensitive-synthetic-canary'
        commands=stack.commands(values)
        self.assertEqual(set(commands),{'core','intelligence'})
        self.assertNotIn(values['JARVIS_DB_PASSWORD'],str(commands))
        self.assertNotIn('--allow-loopback-mock-without-mtls',commands['intelligence'])
        self.assertIn('--tls-ca',commands['intelligence'])

    def test_missing_socket_never_proves_termination(self):
        with tempfile.TemporaryDirectory() as root:
            values={**self.values(),'JARVIS_RUNTIME_DIR':root}
            result=stack.control(values,'status')
            self.assertFalse(result['termination_verified'])
            self.assertEqual(result['status'],'NOT_RUNNING_OR_UNREACHABLE')

    def test_refuse_stale_non_socket(self):
        with tempfile.TemporaryDirectory() as root:
            (Path(root)/'control.sock').write_text('not socket')
            with self.assertRaises(stack.Blocked):stack.control({**self.values(),'JARVIS_RUNTIME_DIR':root},'stop')

    def test_process_group_termination_real_synthetic_process(self):
        process=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'],start_new_session=True,
                                 stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        try:
            stack.stop_children([process])
            self.assertIsNotNone(process.poll())
            with self.assertRaises(ProcessLookupError):os.killpg(process.pid,0)
        finally:
            if process.poll() is None:process.kill();process.wait()

    def test_synthetic_supervisor_socket_start_status_stop(self):
        try:
            probe=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
        except PermissionError:
            self.skipTest('Unix sockets denied by execution sandbox; lifecycle transport NOT_RUN')
        probe.close()
        with tempfile.TemporaryDirectory(prefix='jvs-') as root:
            helper=Path(root)/'synthetic_supervisor.py'
            helper.write_text(
                'import sys,json\n'
                + 'sys.path.insert(0,'+repr(str(Path(stack.__file__).parent))+')\n'
                + 'import local_stack as s\n'
                + 's.doctor=lambda values: {}\n'
                + 's.readiness=lambda values: True\n'
                + 's.commands=lambda values: {name:[sys.executable,"-c","import time;time.sleep(60)"] for name in ("core","intelligence")}\n'
                + 's.child_environment=lambda values,service: {}\n'
                + 'print(json.dumps(s.supervise({"JARVIS_RUNTIME_DIR":'+repr(root)+'})))\n')
            process=subprocess.Popen([sys.executable,str(helper)],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            values={'JARVIS_RUNTIME_DIR':root}
            try:
                import time
                deadline=time.monotonic()+10
                while not (Path(root)/'control.sock').exists() and time.monotonic()<deadline:
                    if process.poll() is not None:self.fail('synthetic supervisor exited before readiness')
                    time.sleep(.02)
                status=stack.control(values,'status')
                self.assertEqual(status['status'],'RUNNING')
                self.assertEqual(status['inference'],'NOT_RUN')
                self.assertFalse(status['release_authorized'])
                ack=stack.control(values,'stop')
                self.assertEqual(ack['status'],'STOPPING')
                self.assertFalse(ack['termination_verified'])
                out,err=process.communicate(timeout=20)
                self.assertEqual(process.returncode,0,err.decode())
                self.assertEqual(json.loads(out),{'status':'STOPPED','termination_verified':True})
                self.assertFalse((Path(root)/'control.sock').exists())
            finally:
                if process.poll() is None:process.terminate();process.communicate(timeout=20)

    def test_setup_never_installs_and_errors_never_echo_values(self):
        result=subprocess.run([sys.executable,str(Path(stack.__file__)),'setup'],capture_output=True,check=True)
        value=json.loads(result.stdout);self.assertFalse(value['installed']);self.assertEqual(value['inference'],'NOT_RUN')
        with tempfile.TemporaryDirectory() as root:
            p=Path(root)/'secret-value.env';p.write_text('TOKEN=never-log-this');p.chmod(0o600)
            result=subprocess.run([sys.executable,str(Path(stack.__file__)),'doctor','--env-file',str(p)],capture_output=True)
            self.assertEqual(result.returncode,1)
            self.assertNotIn(b'never-log-this',result.stdout+result.stderr)
            self.assertNotIn(str(p).encode(),result.stdout+result.stderr)


    def test_restore_optional_key_and_both_child_environments_preserve_intent(self):
        with tempfile.TemporaryDirectory() as root:
            values = {**self.values(), 'JARVIS_RUNTIME_DIR': root,
                      'JARVIS_RESTORE_CONTROL_DIR': str(Path(root) / 'control')}
            parsed = stack.read_environment(self.write_env(root, values))
            for service in ('core', 'intelligence'):
                child = stack.child_environment(parsed, service)
                self.assertEqual(child['JARVIS_RESTORE_CONTROL_DIR'], values['JARVIS_RESTORE_CONTROL_DIR'])
                self.assertEqual(child['JARVIS_RUNTIME_DIR'], root)
            parsed.pop('JARVIS_RESTORE_CONTROL_DIR')
            with patch.dict(os.environ, {'JARVIS_RESTORE_CONTROL_DIR': ''}, clear=True):
                for service in ('core', 'intelligence'):
                    self.assertEqual(stack.child_environment(parsed, service)['JARVIS_RESTORE_CONTROL_DIR'], '')

    def test_ambient_restore_refuses_every_start_doctor_before_config_or_runtime(self):
        for command in ('start', 'doctor', '_doctor'):
            with self.subTest(command=command), patch.dict(os.environ, {'JARVIS_RESTORE_CONTROL_DIR': 'normal'}, clear=True), \
                 patch('sys.argv', ['local_stack', command]), \
                 patch.object(stack, 'read_environment', side_effect=AssertionError('env file read')), \
                 patch.object(stack, 'doctor', side_effect=AssertionError('doctor invoked')), \
                 patch.object(stack, 'supervise', side_effect=AssertionError('supervisor invoked')):
                output = io.StringIO()
                with contextlib.redirect_stdout(output): self.assertEqual(stack.main(), 1)
                self.assertEqual(json.loads(output.getvalue())['code'], 'RESTORE_LAUNCH_BLOCKED')

    def test_envfile_restore_and_ambient_runtime_anchor_refuse_before_doctor(self):
        with tempfile.TemporaryDirectory() as root:
            values = {**self.values(), 'JARVIS_RUNTIME_DIR': root}
            path = self.write_env(root, {**values, 'JARVIS_RESTORE_CONTROL_DIR': 'false'})
            for command in ('doctor', 'start', '_doctor'):
                with patch.dict(os.environ, {}, clear=True), \
                     patch('sys.argv', ['local_stack', command, '--env-file', str(path)]), \
                     patch.object(stack, 'doctor', side_effect=AssertionError('doctor invoked')), \
                     patch.object(stack, 'supervise', side_effect=AssertionError('supervisor invoked')):
                    output = io.StringIO()
                    with contextlib.redirect_stdout(output): self.assertEqual(stack.main(), 1)
                    self.assertEqual(json.loads(output.getvalue())['code'], 'RESTORE_LAUNCH_BLOCKED')
            (Path(root) / 'restore-enrollment.json').write_text('corrupt')
            path = self.write_env(root, {**values, 'JARVIS_RUNTIME_DIR': str(Path(root) / 'clean')})
            with patch.dict(os.environ, {'JARVIS_RUNTIME_DIR': root}, clear=True), \
                 patch('sys.argv', ['local_stack', 'doctor', '--env-file', str(path)]), \
                 patch.object(stack, 'read_environment', side_effect=AssertionError('env file read')):
                output = io.StringIO()
                with contextlib.redirect_stdout(output): self.assertEqual(stack.main(), 1)
                self.assertEqual(json.loads(output.getvalue())['code'], 'RESTORE_LAUNCH_BLOCKED')

    def test_direct_doctor_and_supervisor_refuse_before_key_or_process_access(self):
        with patch.dict(os.environ, {}, clear=True), \
             patch.object(stack, 'private_file', side_effect=AssertionError('key read')), \
             patch.object(stack.subprocess, 'run', side_effect=AssertionError('process run')), \
             patch.object(stack.subprocess, 'Popen', side_effect=AssertionError('process start')):
            values = {**self.values(), 'JARVIS_RESTORE_CONTROL_DIR': ''}
            with self.assertRaises(stack.RestoreBlocked): stack.doctor(values)
            with self.assertRaises(stack.RestoreBlocked): stack.supervise(values)

    def test_unenrolled_main_admission_and_enrolled_stop_status_remain_usable(self):
        with tempfile.TemporaryDirectory() as root:
            values = {**self.values(), 'JARVIS_RUNTIME_DIR': root}
            path = self.write_env(root, values)
            for command in ('doctor', 'start'):
                with patch.dict(os.environ, {}, clear=True), \
                     patch('sys.argv', ['local_stack', command, '--env-file', str(path)]), \
                     patch.object(stack, 'doctor', return_value={'admitted': True}) as doctor, \
                     patch.object(stack, 'supervise', return_value={'admitted': True}) as supervise:
                    with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(stack.main(), 0)
                    (doctor if command == 'doctor' else supervise).assert_called_once()
            path = self.write_env(root, {**values, 'JARVIS_RESTORE_CONTROL_DIR': ''})
            for command in ('stop', 'status'):
                with patch.dict(os.environ, {'JARVIS_RESTORE_CONTROL_DIR': ''}, clear=True), \
                     patch('sys.argv', ['local_stack', command, '--env-file', str(path)]), \
                     patch.object(stack, 'control', return_value={'status': 'synthetic'}) as control:
                    with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(stack.main(), 0)
                    control.assert_called_once()

if __name__=='__main__':unittest.main()
