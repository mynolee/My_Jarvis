#!/usr/bin/env python3
"""Private, foreground backend lifecycle. No installation, downloads or training."""
from __future__ import annotations
import argparse
import fcntl
import http.client
import json
import os
from pathlib import Path
import signal
import socket
import ssl
import stat
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
PYTHON_SOURCE = ROOT / 'apps/intelligence/src'
CORE_KEYS = {'JARVIS_DB_URL', 'JARVIS_DB_USER', 'JARVIS_DB_PASSWORD', 'JARVIS_KEY_FILE',
             'JARVIS_ORIGIN', 'JARVIS_LOCAL_URL', 'JARVIS_LOCAL_MANIFEST_HASH',
             'JARVIS_LOCAL_CLIENT_CERT_FILE', 'JARVIS_LOCAL_CLIENT_KEY_FILE',
             'JARVIS_LOCAL_CA_FILE', 'JARVIS_LOCAL_SIGNING_KEY_FILE', 'JARVIS_LOCAL_SIGNING_KEY_ID',
             'SERVER_SSL_CERTIFICATE', 'SERVER_SSL_CERTIFICATE_PRIVATE_KEY'}
LAUNCH_KEYS = {'JARVIS_PYTHON_PATH', 'JARVIS_JAVA_PATH', 'JARVIS_CORE_JAR_PATH',
               'JARVIS_RUNTIME_DIR', 'JARVIS_LOCAL_CONFIG', 'JARVIS_CORE_PUBLIC_KEY_FILE',
               'JARVIS_CACHE_KEY_FILE', 'JARVIS_LEDGER_FILE', 'JARVIS_TLS_CERT_FILE',
               'JARVIS_TLS_KEY_FILE', 'JARVIS_TLS_CA_FILE', 'JARVIS_CORE_CERT_SHA256'}
KEYS = CORE_KEYS | LAUNCH_KEYS
OPTIONAL_CORE_KEYS = {'JARVIS_REMINDER_WORKER_ENABLED'}

class Blocked(ValueError):
    pass


def private_file(path, *, outside=False):
    """Never follow a supplied symlink; private settings are not executable code."""
    path = Path(path)
    if not path.is_absolute():
        raise Blocked('ABSOLUTE_PATH_REQUIRED')
    for item in (path, *path.parents):
        if item.is_symlink():
            raise Blocked('SYMLINK_DENIED')
    if outside and (path == ROOT or ROOT in path.parents):
        raise Blocked('PRIVATE_ARTIFACT_IN_SOURCE')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        os.close(fd)
        raise Blocked('OWNER_ONLY_FILE_REQUIRED')
    return fd


def read_environment(path):
    with os.fdopen(private_file(Path(path).absolute()), 'r') as handle:
        text = handle.read(65537)
    if len(text.encode()) > 65536:
        raise Blocked('ENVIRONMENT_TOO_LARGE')
    values = {}
    for line in text.splitlines():
        if not line or line.startswith('#'):
            continue
        key, separator, value = line.partition('=')
        if not separator or key not in KEYS | OPTIONAL_CORE_KEYS or key in values or not value or '\x00' in value:
            raise Blocked('INVALID_ENVIRONMENT')
        # Intentionally not shell syntax: no quote removal, interpolation or eval.
        values[key] = value
    if not KEYS <= set(values):
        raise Blocked('INCOMPLETE_ENVIRONMENT')
    if any(values[key] not in {'true', 'false'} for key in OPTIONAL_CORE_KEYS if key in values):
        raise Blocked('INVALID_ENVIRONMENT')
    if values['JARVIS_LOCAL_URL'] != 'https://localhost:9443' or values['JARVIS_ORIGIN'] != 'https://localhost:8443':
        raise Blocked('LOOPBACK_TLS_REQUIRED')
    if not values['JARVIS_DB_URL'].startswith('jdbc:postgresql://127.0.0.1:'):
        raise Blocked('LOCAL_DATABASE_REQUIRED')
    for name in ('JARVIS_LOCAL_MANIFEST_HASH', 'JARVIS_CORE_CERT_SHA256'):
        value = values[name]
        if len(value) != 64 or any(c not in '0123456789abcdef' for c in value):
            raise Blocked('INVALID_DIGEST')
    return values


def state_directory(value, create=False):
    path = Path(value)
    if not path.is_absolute() or ROOT == path or ROOT in path.parents:
        raise Blocked('PRIVATE_STATE_DIRECTORY_REQUIRED')
    for item in (path, *path.parents):
        if item.is_symlink():
            raise Blocked('SYMLINK_DENIED')
    if create:
        path.mkdir(mode=0o700, parents=False, exist_ok=True)
    info = path.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise Blocked('OWNER_ONLY_DIRECTORY_REQUIRED')
    if len(os.fsencode(path / 'control.sock')) > 100:
        raise Blocked('STATE_PATH_TOO_LONG')
    return path


def child_environment(values, service):
    env = {key: os.environ[key] for key in ('PATH', 'HOME', 'TMPDIR', 'LANG') if key in os.environ}
    if service == 'core':
        env.update({key: values[key] for key in CORE_KEYS})
        env.update({key: values[key] for key in OPTIONAL_CORE_KEYS if key in values})
        env.update(JARVIS_LOCAL_ENABLED='true', JARVIS_WEB_ENABLED='true', SERVER_ADDRESS='127.0.0.1', SERVER_PORT='8443',
                   SERVER_SSL_ENABLED='true', SERVER_FORWARD_HEADERS_STRATEGY='none')
    else:
        env.update(PYTHONPATH=str(PYTHON_SOURCE), PYTHONNOUSERSITE='1', PYTHONDONTWRITEBYTECODE='1',
                   HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1')
    return env


def commands(values):
    python = [values['JARVIS_PYTHON_PATH'], '-s', '-m', 'jarvis_intelligence',
              '--local-config', values['JARVIS_LOCAL_CONFIG'],
              '--core-public-key', values['JARVIS_CORE_PUBLIC_KEY_FILE'],
              '--core-key-id', values['JARVIS_LOCAL_SIGNING_KEY_ID'],
              '--cache-key-file', values['JARVIS_CACHE_KEY_FILE'],
              '--ledger', values['JARVIS_LEDGER_FILE'],
              '--tls-cert', values['JARVIS_TLS_CERT_FILE'], '--tls-key', values['JARVIS_TLS_KEY_FILE'],
              '--tls-ca', values['JARVIS_TLS_CA_FILE'], '--core-cert-sha256', values['JARVIS_CORE_CERT_SHA256'],
              '--port', '9443']
    core = [values['JARVIS_JAVA_PATH'], '-jar', values['JARVIS_CORE_JAR_PATH']]
    return {'intelligence': python, 'core': core}


def doctor(values):
    for key in KEYS:
        if key.endswith('_FILE') or key in {'JARVIS_LOCAL_CONFIG', 'SERVER_SSL_CERTIFICATE', 'SERVER_SSL_CERTIFICATE_PRIVATE_KEY'}:
            if key == 'JARVIS_LEDGER_FILE':
                continue  # New ledger is created by the service, never here.
            os.close(private_file(values[key], outside=True))
    for key in ('JARVIS_PYTHON_PATH', 'JARVIS_JAVA_PATH', 'JARVIS_CORE_JAR_PATH'):
        p = Path(values[key])
        if not p.is_absolute() or not p.is_file():
            raise Blocked('BUILD_OR_EXECUTABLE_MISSING')
    jar = Path(values['JARVIS_CORE_JAR_PATH']).resolve()
    if ROOT / 'apps/core/build/libs' not in jar.parents or jar.suffix != '.jar':
        raise Blocked('CORE_BUILD_ARTIFACT_REQUIRED')
    state_directory(values['JARVIS_RUNTIME_DIR'], create=True)
    ledger = Path(values['JARVIS_LEDGER_FILE'])
    if ledger.parent != Path(values['JARVIS_RUNTIME_DIR']) or ledger.is_symlink():
        raise Blocked('PRIVATE_LEDGER_REQUIRED')
    result = subprocess.run([values['JARVIS_PYTHON_PATH'], '-s', str(Path(__file__).resolve()),
                             '_doctor', '--config', values['JARVIS_LOCAL_CONFIG']],
                            env=child_environment(values, 'intelligence'), stdin=subprocess.DEVNULL,
                            capture_output=True, timeout=120, check=False)
    if result.returncode or len(result.stdout) > 4096:
        raise Blocked('ARTIFACT_OR_RUNTIME_UNVERIFIED')
    response = json.loads(result.stdout)
    if response != {'manifest_hash': values['JARVIS_LOCAL_MANIFEST_HASH'], 'integrity_verified': True}:
        raise Blocked('MANIFEST_BINDING_MISMATCH')
    version = subprocess.run([values['JARVIS_JAVA_PATH'], '-version'], env=child_environment(values, 'intelligence'), capture_output=True, timeout=10, check=False)
    if version.returncode or b'version "21.' not in version.stderr:
        raise Blocked('JAVA_21_REQUIRED')
    return {'status': 'STATIC_CHECKS_PASSED', 'integrity_verified': True,
            'inference': 'NOT_RUN', 'integration': 'NOT_RUN', 'release_authorized': False}


def readiness(values):
    context = ssl.create_default_context(cafile=values['JARVIS_LOCAL_CA_FILE'])
    context.load_cert_chain(values['JARVIS_LOCAL_CLIENT_CERT_FILE'], values['JARVIS_LOCAL_CLIENT_KEY_FILE'], password='')
    connection = http.client.HTTPSConnection('localhost', 9443, timeout=2, context=context)
    try:
        connection.request('GET', '/health/ready')
        response = connection.getresponse()
        data = response.read(16385)
        if response.status != 200 or len(data) > 16384:
            return False
        value = json.loads(data)
        return (value.get('ready') is True and value.get('runtime_ready') is True
                and value.get('model_id') == 'local-mlx-v1'
                and value.get('model_manifest_hash') == values['JARVIS_LOCAL_MANIFEST_HASH'])
    except (OSError, ValueError, http.client.HTTPException):
        return False
    finally:
        connection.close()


def stop_children(children):
    # Dedicated sessions contain the ASGI child and its inference descendants.
    # Handles/group IDs come only from this supervisor, never from a PID file.
    for process in reversed(children):
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    for process in reversed(children):
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            pass
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=5)
        deadline = time.monotonic() + 5
        while True:
            try:
                os.killpg(process.pid, 0)
            except ProcessLookupError:
                break
            if time.monotonic() >= deadline:
                raise Blocked('PROCESS_GROUP_DEATH_UNCONFIRMED')
            time.sleep(.05)


def supervise(values):
    doctor(values)
    directory = state_directory(values['JARVIS_RUNTIME_DIR'])
    if (directory / 'shutdown-unconfirmed').exists() or (directory / 'shutdown-unconfirmed').is_symlink():
        raise Blocked('PREVIOUS_TERMINATION_UNCONFIRMED')
    lock = os.open(directory / 'supervisor.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    children = []
    server = None
    owned_socket = False
    stopping = False
    def request_stop(*_):
        nonlocal stopping
        stopping = True
    try:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Blocked('ALREADY_RUNNING') from None
        if (directory / 'shutdown-unconfirmed').exists() or (directory / 'shutdown-unconfirmed').is_symlink():
            raise Blocked('PREVIOUS_TERMINATION_UNCONFIRMED')
        sock_path = directory / 'control.sock'
        if sock_path.exists() or sock_path.is_symlink():
            if not stat.S_ISSOCK(sock_path.lstat().st_mode):
                raise Blocked('UNEXPECTED_CONTROL_PATH')
            sock_path.unlink()  # Exclusive lifetime lock proves no other owner.
        server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        server.bind(str(sock_path)); os.chmod(sock_path, 0o600); owned_socket = True
        server.listen(4); server.settimeout(.2)
        signal.signal(signal.SIGTERM, request_stop); signal.signal(signal.SIGINT, request_stop)
        argv = commands(values)
        def launch(name):
            # Never echo raw process output, environment or argv to logs/CI.
            process = subprocess.Popen(argv[name], cwd=ROOT, env=child_environment(values, name),
                                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       shell=False, close_fds=True, start_new_session=True)
            children.append(process)
        launch('intelligence')
        deadline = time.monotonic() + 300
        core_started = False
        while not stopping:
            if any(child.poll() is not None for child in children):
                raise Blocked('SERVICE_EXITED')
            if not core_started:
                if readiness(values):
                    launch('core'); core_started = True
                elif time.monotonic() >= deadline:
                    raise Blocked('MODEL_START_TIMEOUT')
            try:
                connection, _ = server.accept()
            except socket.timeout:
                continue
            with connection:
                connection.settimeout(1)
                try:
                    command = connection.recv(33)
                    if command == b'stop\n':
                        stopping = True
                        result = {'status': 'STOPPING', 'termination_verified': False}
                    elif command == b'status\n':
                        result = {'status': 'RUNNING' if core_started else 'STARTING',
                                  'processes_alive': all(p.poll() is None for p in children),
                                  'inference': 'NOT_RUN', 'integration': 'NOT_RUN',
                                  'release_authorized': False}
                    else:
                        result = {'status': 'REJECTED'}
                    connection.sendall((json.dumps(result) + '\n').encode())
                except (OSError, ValueError):
                    pass
    finally:
        try:
            stop_children(children)
        except BaseException:
            fd = os.open(directory / 'shutdown-unconfirmed', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            os.close(fd)
            raise
        finally:
            if server is not None:
                server.close()
            if owned_socket:
                (directory / 'control.sock').unlink(missing_ok=True)
            os.close(lock)
    return {'status': 'STOPPED', 'termination_verified': True}


def control(values, command):
    directory = state_directory(values['JARVIS_RUNTIME_DIR'])
    path = directory / 'control.sock'
    if path.is_symlink():
        raise Blocked('SYMLINK_DENIED')
    if not path.exists():
        return {'status': 'NOT_RUNNING_OR_UNREACHABLE', 'termination_verified': False}
    if not stat.S_ISSOCK(path.lstat().st_mode):
        raise Blocked('UNEXPECTED_CONTROL_PATH')
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    client.settimeout(3)
    try:
        client.connect(str(path)); client.sendall((command + '\n').encode())
        data = client.recv(4097)
        if len(data) > 4096:
            raise Blocked('CONTROL_RESPONSE_INVALID')
        return json.loads(data)
    except (FileNotFoundError, ConnectionRefusedError):
        # Do not infer stopped from an absent/stale socket: no process owner observed.
        return {'status': 'NOT_RUNNING_OR_UNREACHABLE', 'termination_verified': False}
    finally:
        client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['setup', 'doctor', 'start', 'stop', 'status', '_doctor'])
    parser.add_argument('--env-file', type=Path)
    parser.add_argument('--config', type=Path)
    args = parser.parse_args()
    try:
        if args.command == '_doctor':
            from jarvis_intelligence.local_runtime import LocalConfig, verify_artifacts, verify_dependencies
            config = LocalConfig.from_file(args.config)
            value = {'manifest_hash': verify_artifacts(config), 'integrity_verified': True}
            verify_dependencies(config)
        elif args.command == 'setup':
            value = {'status': 'CONFIGURATION_REQUIRED', 'required_environment_keys': sorted(KEYS), 'optional_environment_keys': sorted(OPTIONAL_CORE_KEYS),
                     'installed': False, 'downloaded': False, 'inference': 'NOT_RUN'}
        else:
            if args.env_file is None:
                raise Blocked('PRIVATE_ENV_FILE_REQUIRED')
            values = read_environment(args.env_file)
            value = doctor(values) if args.command == 'doctor' else supervise(values) if args.command == 'start' else control(values, args.command)
        print(json.dumps(value, sort_keys=True))
        return 0
    except (Exception, KeyboardInterrupt) as exc:
        code = str(exc) if isinstance(exc, Blocked) else 'CHECK_FAILED'
        print(json.dumps({'status': 'BLOCKED', 'code': code, 'inference': 'NOT_RUN', 'release_authorized': False}))
        return 1

if __name__ == '__main__':
    raise SystemExit(main())
