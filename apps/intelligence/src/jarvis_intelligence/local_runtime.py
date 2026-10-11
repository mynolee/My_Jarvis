"""Single-owner local inference supervisor. No downloads, tools or repair inference.

A private inherited Pipe is the only worker command channel. This module does
not expose a network listener. Actual macOS/Metal validation is a separate gate.
"""
from __future__ import annotations

import asyncio
import hashlib
import importlib.metadata
import json
import multiprocessing
import os
import platform
import re
import stat
import time
from dataclasses import dataclass, field
from pathlib import Path

from .contracts import ContractViolation, canonical, parse_json, validate


class RuntimeFailure(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open('rb') as handle:
        for block in iter(lambda: handle.read(8 * 1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


@dataclass(frozen=True)
class LocalConfig:
    model_path: Path
    manifest_path: Path
    runtime_lock_path: Path
    prompt_path: Path
    lock_path: Path
    runtime_lock_sha256: str
    prompt_sha256: str
    manifest_sha256: str
    study_lesson_prompt_path: Path | None = field(default=None, repr=False)
    study_lesson_prompt_sha256: str | None = field(default=None, repr=False)

    def __post_init__(self):
        path, pin = self.study_lesson_prompt_path, self.study_lesson_prompt_sha256
        if (path is None) != (pin is None):
            raise ValueError('incomplete study prompt configuration')
        if path is not None and (not isinstance(path, Path) or not path.is_absolute() or
                                 not isinstance(pin, str) or not re.fullmatch('[a-f0-9]{64}', pin)):
            raise ValueError('invalid study prompt configuration')

    @classmethod
    def from_file(cls, path: Path):
        if path.is_symlink():
            raise ValueError('configuration cannot be a link')
        info = path.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_uid != os.getuid():
            raise ValueError('configuration must be owner-only')
        value = parse_json(path.read_bytes())
        names = set(cls.__dataclass_fields__)
        optional = {'study_lesson_prompt_path', 'study_lesson_prompt_sha256'}
        if type(value) is not dict or set(value) not in (names, names - optional):
            raise ValueError('invalid local configuration')
        for name in value:
            if not isinstance(value[name], str) or not value[name]:
                raise ValueError('invalid local configuration')
            if name.endswith('_path'):
                item = Path(value[name])
                if not item.is_absolute():
                    raise ValueError('absolute private paths required')
                value[name] = item
            elif not re.fullmatch('[a-f0-9]{64}', value[name]):
                raise ValueError('invalid digest')
        return cls(**value)


def load_study_lesson_prompt(config: LocalConfig) -> str | None:
    """Read only the exact privately pinned bytes; never disclose path or text."""
    path, pin = config.study_lesson_prompt_path, config.study_lesson_prompt_sha256
    if path is None and pin is None:
        return None
    if (not isinstance(path, Path) or not path.is_absolute() or
            not isinstance(pin, str) or not re.fullmatch('[a-f0-9]{64}', pin)):
        raise ValueError('invalid study prompt configuration')
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
                raise ValueError('invalid study prompt file')
            # Use one bounded descriptor read, not a hash-then-reread path race.
            with os.fdopen(fd, 'rb', closefd=False) as handle:
                raw = handle.read(65537)
            if not raw or len(raw) > 65536 or hashlib.sha256(raw).hexdigest() != pin:
                raise ValueError('invalid study prompt bytes')
            return raw.decode('utf-8', errors='strict')
        finally:
            os.close(fd)
    except (OSError, ValueError):
        raise ValueError('study prompt unavailable') from None


def verify_artifacts(config: LocalConfig) -> str:
    """Validate every loadable byte, with no publishing of private path data."""
    root = config.model_path
    if not root.is_dir() or root.is_symlink():
        raise ValueError('model directory unavailable')
    if config.manifest_path.is_symlink() or digest(config.manifest_path) != config.manifest_sha256:
        raise ValueError('manifest pin mismatch')
    manifest = parse_json(config.manifest_path.read_bytes(), limit=4 * 1024 * 1024)
    validate('model-artifact-manifest', manifest)
    if manifest.get('licenses_reviewed') is not True or not manifest.get('upstream_repo') or not re.fullmatch('[a-f0-9]{40}', manifest.get('upstream_revision', '')):
        raise ValueError('unreviewed model provenance')
    for key in ('upstream_license_sha256', 'conversion_license_sha256'):
        if not re.fullmatch('[a-f0-9]{64}', manifest.get(key, '')):
            raise ValueError('license evidence missing')
    if manifest.get('runtime_lock_sha256') != config.runtime_lock_sha256 or manifest.get('prompt_sha256') != config.prompt_sha256:
        raise ValueError('manifest runtime binding mismatch')
    if manifest.get('study_lesson_prompt_sha256') != config.study_lesson_prompt_sha256:
        raise ValueError('manifest study prompt binding mismatch')
    load_study_lesson_prompt(config)
    if manifest.get('complete') is not True or not re.fullmatch('[a-f0-9]{40}', manifest.get('revision', '')):
        raise ValueError('model revision incomplete')
    if not isinstance(manifest.get('repo'), str) or not manifest['repo']:
        raise ValueError('model provenance missing')
    records = manifest.get('files')
    if not isinstance(records, list) or not records:
        raise ValueError('model file inventory missing')
    expected = set()
    for record in records:
        if type(record) is not dict or set(record) != {'path', 'size', 'sha256'}:
            raise ValueError('invalid model inventory')
        relative = Path(record['path'])
        if relative.is_absolute() or '..' in relative.parts or not relative.parts or relative.as_posix() in expected:
            raise ValueError('unsafe or duplicate model path')
        path = root / relative
        if any((root / Path(*relative.parts[:i])).is_symlink() for i in range(1, len(relative.parts) + 1)):
            raise ValueError('model symlink denied')
        allowed = path.suffix in {'.safetensors', '.json', '.txt', '.jinja', '.jinja2', '.model', '.tiktoken'} or path.name.startswith('LICENSE') or path.name == 'README.md'
        if not allowed or not path.is_file() or type(record['size']) is not int or record['size'] < 0:
            raise ValueError('unapproved model file')
        if path.stat().st_size != record['size'] or digest(path) != record['sha256']:
            raise ValueError('model integrity mismatch')
        expected.add(relative.as_posix())
    actual = set()
    for path in root.rglob('*'):
        relative = path.relative_to(root)
        if '.cache' in relative.parts:
            continue
        if path.is_symlink():
            raise ValueError('model symlink denied')
        if path.is_file():
            actual.add(relative.as_posix())
    if actual != expected or not {'config.json', 'tokenizer_config.json', 'model.safetensors.index.json'} <= expected:
        raise ValueError('model inventory mismatch')
    for key in ('template_files', 'tokenizer_files', 'processor_files'):
        names = manifest.get(key)
        if not isinstance(names, list) or not names or any(not isinstance(name, str) or name not in expected for name in names):
            raise ValueError('model input artifact missing')
    index = parse_json((root / 'model.safetensors.index.json').read_bytes(), limit=8 * 1024 * 1024)
    shards = index.get('weight_map', {})
    if not isinstance(shards, dict) or not shards:
        raise ValueError('weight index missing')
    if any(not isinstance(v, str) or not v.endswith('.safetensors') or v not in expected for v in shards.values()):
        raise ValueError('weight index mismatch')
    for path, wanted in ((config.runtime_lock_path, config.runtime_lock_sha256), (config.prompt_path, config.prompt_sha256)):
        if path.is_symlink() or digest(path) != wanted:
            raise ValueError('runtime or prompt integrity mismatch')
    return digest(config.manifest_path)


def verify_dependencies(config):
    """Only an exact, installed freeze lock is accepted; never install anything."""
    for line in config.runtime_lock_path.read_text().splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        match = re.fullmatch(r'([A-Za-z0-9_.-]+)==([^\s;]+)', line)
        if not match or importlib.metadata.version(match[1]) != match[2]:
            raise ValueError('runtime lock mismatch')
    if importlib.metadata.version('mlx-vlm') != '0.7.6':
        raise ValueError('unsupported runtime')


def _detach_fd(handle):
    return handle.detach()


class _InheritedLock:
    def __init__(self, fd):
        self.fd = fd

    def __reduce__(self):
        # Reduction runs DURING spawn: descriptor is inherited directly,
        # without starting multiprocessing's network resource-sharer listener.
        from multiprocessing.reduction import DupFd
        return _detach_fd, (DupFd(self.fd),)


class LocalRuntime:
    """One generation and two waiters. Never release a live orphan's slot."""
    def __init__(self, config: LocalConfig, *, worker_target=None):
        self.config = config
        self.manifest_hash = config.manifest_sha256
        self._target = worker_target or _worker_main
        self._process = None
        self._pipe = None
        self._owner_fd = None
        self._serial = asyncio.Lock()
        self._starting = asyncio.Lock()
        self._waiting = 0
        self._active = None
        self._ready = False
        self._integrity = False
        self._inference_verified = False
        self._poisoned = False
        self._cancel = None
        self._restart_task = None
        self._closing = False

    def health(self):
        alive = self._process is not None and self._process.is_alive()
        ready = bool(self._ready and alive and not self._poisoned)
        return {'ready': ready, 'model_id': 'local-mlx-v1', 'model_manifest_hash': self.manifest_hash,
                'status': 'READY' if ready else 'MODEL_UNAVAILABLE',
                'download_complete': self._integrity, 'integrity_verified': self._integrity,
                'runtime_ready': ready, 'inference_verified': self._inference_verified,
                'integration_verified': False}

    async def start(self):
        async with self._starting:
            if self._closing:
                raise RuntimeFailure("MODEL_UNAVAILABLE")
            cold_deadline = time.monotonic() + 300
            if self.health()['runtime_ready']:
                return
            if self._poisoned or (self._process is not None and self._process.is_alive()):
                raise RuntimeFailure('MODEL_UNAVAILABLE')
            try:
                import fcntl
                if self._owner_fd is None:
                    fd = os.open(self.config.lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
                    try:
                        info = os.fstat(fd)
                        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
                            raise ValueError('worker lock must be owner-only regular file')
                        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BaseException:
                        os.close(fd)
                        raise
                    self._owner_fd = fd
                self._integrity = False
                self._inference_verified = False
                self.manifest_hash = await asyncio.wait_for(asyncio.to_thread(verify_artifacts, self.config), 300)
                self._integrity = True
                context = multiprocessing.get_context('spawn')
                self._pipe, child = context.Pipe()
                self._cancel = context.Event()
                # Child retains this same kernel lock if its parent dies.
                self._process = context.Process(target=self._target, args=(child, self.config, self._cancel, _InheritedLock(self._owner_fd)), daemon=True)
                self._process.start()
                child.close()
                message = await self._receive(cold_deadline)
                if message != {'ready': True}:
                    raise RuntimeFailure('MODEL_UNAVAILABLE')
                self._ready = True
            except BaseException:
                await self._stop_worker()
                raise RuntimeFailure('MODEL_UNAVAILABLE') from None

    async def _receive(self, deadline):
        cancelled_at = None
        while time.monotonic() < deadline:
            if self._cancel is not None and self._cancel.is_set():
                cancelled_at = cancelled_at or time.monotonic()
                if time.monotonic() - cancelled_at > 1:
                    raise RuntimeFailure('CANCELLED')
            if self._pipe.poll():
                return self._pipe.recv()
            if not self._process.is_alive():
                raise RuntimeFailure('MODEL_UNAVAILABLE')
            await asyncio.sleep(.01)
        raise RuntimeFailure('GENERATION_TIMEOUT')

    async def _stop_worker(self):
        self._ready = False
        process = self._process
        if process is not None:
            try:
                if getattr(process, 'pid', 1) is not None:
                    if process.is_alive():
                        process.terminate()
                    await asyncio.to_thread(process.join, 2)
                    if process.is_alive():
                        process.kill()
                        await asyncio.to_thread(process.join, 2)
                    if process.is_alive() or process.exitcode is None:
                        raise RuntimeFailure('WORKER_DEATH_UNCONFIRMED')
                self._process = None
            except BaseException:
                self._poisoned = True
                raise
        if self._pipe is not None:
            self._pipe.close()
            self._pipe = None

    async def _recover(self):
        try:
            await self.start()
        except (RuntimeFailure, asyncio.CancelledError):
            pass  # Readiness remains unavailable; never retry a generation.

    async def close(self):
        self._closing = True
        if self._restart_task is not None and not self._restart_task.done():
            self._restart_task.cancel()
            try:
                await self._restart_task
            except (RuntimeFailure, asyncio.CancelledError):
                pass
        await self._stop_worker()
        if self._owner_fd is not None:
            os.close(self._owner_fd)
            self._owner_fd = None

    async def cancel(self, request_id):
        if self._active == request_id and self._cancel is not None:
            self._cancel.set()

    async def generate(self, turn, deadline_epoch):
        study = getattr(turn, 'purpose', 'CONVERSATION') == 'STUDY_LESSON'
        if study and getattr(self.config, 'study_lesson_prompt_path', None) is None:
            raise RuntimeFailure('MODEL_UNAVAILABLE')
        if not self.health()['runtime_ready']:
            raise RuntimeFailure('MODEL_UNAVAILABLE')
        if self._waiting + int(self._serial.locked()) >= 3:
            raise RuntimeFailure('RESOURCE_LIMIT')
        self._waiting += 1
        acquired = False
        try:
            wait = min(30, deadline_epoch - time.time())
            if wait <= 0:
                raise RuntimeFailure('GENERATION_TIMEOUT')
            try:
                await asyncio.wait_for(self._serial.acquire(), wait)
                acquired = True
            except TimeoutError:
                raise RuntimeFailure('RESOURCE_LIMIT') from None
        finally:
            self._waiting -= 1
        acknowledged = False
        try:
            if not self.health()['runtime_ready']:
                raise RuntimeFailure('MODEL_UNAVAILABLE')
            if turn.model_manifest_hash != self.manifest_hash:
                raise RuntimeFailure('MODEL_UNAVAILABLE')
            self._active = turn.request_id
            self._cancel.clear()
            duration = min(60, turn.budget.max_compute_ms / 1000, deadline_epoch - time.time())
            if duration <= 0:
                raise RuntimeFailure('GENERATION_TIMEOUT')
            worker_request = {'request_id': turn.request_id, 'user_input': turn.user_input,
                              'allowed_tool_ids': list(turn.allowed_tool_ids),
                              'current_time': turn.current_time, 'timezone': turn.timezone,
                              'max_input_tokens': turn.budget.max_input_tokens,
                              'max_output_tokens': turn.budget.max_output_tokens,
                              'deadline_monotonic': time.monotonic() + duration}
            if study:
                worker_request['purpose'] = 'STUDY_LESSON'
            if getattr(turn, 'context', None) is not None:
                wire = turn.model_dump(mode='json', exclude_unset=True)
                worker_request.update({key: wire[key] for key in
                                       ('context', 'project_context', 'memories', 'history',
                                        'evidence', 'tool_results')})
            self._pipe.send(worker_request)
            try:
                message = await self._receive(time.monotonic() + max(0, duration))
            except (EOFError, OSError):
                raise RuntimeFailure("INFERENCE_UNKNOWN") from None
            except RuntimeFailure as exc:
                if exc.code == "MODEL_UNAVAILABLE":
                    raise RuntimeFailure("INFERENCE_UNKNOWN") from None
                raise
            if message.get('request_id') != turn.request_id:
                raise RuntimeFailure('INFERENCE_UNKNOWN')
            acknowledged = True
            if 'error' in message:
                raise RuntimeFailure(message['error'])
            self._inference_verified = self._target is _worker_main
            return message
        except (asyncio.CancelledError, RuntimeFailure, EOFError, OSError) as exc:
            # Cancellation acknowledgement isn't inferred from client closure.
            # A completed cooperative response may reuse the worker. Otherwise
            # confirm death, then reload the same pinned artifact, never the turn.
            if not acknowledged:
                await asyncio.shield(self._stop_worker())
                if not self._closing and not self._poisoned:
                    self._restart_task = asyncio.create_task(self._recover())
            raise
        finally:
            self._active = None
            if acquired and not self._poisoned:
                self._serial.release()


def _worker_request_loop(pipe, adapter, cancel):
    """Release completed turn references before waiting for another request."""
    while True:
        request = result = response = None
        try:
            request = pipe.recv()
            try:
                result = adapter.generate(request, cancel)
                response = {'request_id': request['request_id'], **result}
                pipe.send(response)
            except RuntimeFailure as exc:
                response = {'request_id': request['request_id'], 'error': exc.code}
                pipe.send(response)
            except Exception:
                response = {'request_id': request['request_id'], 'error': 'MODEL_OUTPUT_INVALID'}
                pipe.send(response)
        finally:
            # A warm worker may block in recv indefinitely. Drop Python references
            # on every path; this does not promise physical RAM/GPU zeroization.
            request = result = response = None


def _worker_main(pipe, config, cancel, owner_handle):
    """All MLX imports and model ownership live in this child, never in ASGI."""
    owner_fd = owner_handle
    try:
        # Third-party runtime logs must not disclose prompts or private paths.
        import logging
        logging.disable(logging.CRITICAL)
        with open(os.devnull, 'w') as sink:
            os.dup2(sink.fileno(), 1)
            os.dup2(sink.fileno(), 2)
        # Do not inherit service/DB/OAuth secrets. HF switches prevent downloads,
        # but are not represented as evidence of an OS network sandbox.
        keep = {key: os.environ[key] for key in ('PATH', 'HOME', 'TMPDIR', 'LANG') if key in os.environ}
        os.environ.clear()
        os.environ.update(keep, HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1')
        import sys
        if platform.system() != 'Darwin' or platform.machine() != 'arm64' or sys.version_info[:2] != (3, 12):
            raise ValueError('native Apple Silicon required')
        verify_artifacts(config)
        verify_dependencies(config)
        # Defense in depth only: native-code/OS egress still requires device
        # verification. No socket, DNS, or external command is needed for inference.
        import sys
        def deny_network(event, args):
            if event in {'socket.connect', 'socket.bind', 'socket.sendto', 'socket.getaddrinfo', 'socket.gethostbyname', 'socket.gethostbyaddr', 'subprocess.Popen', 'os.system', 'os.posix_spawn'}:
                raise PermissionError('worker external access disabled')
        sys.addaudithook(deny_network)
        from .mlx_adapter import MLXAdapter
        adapter = MLXAdapter(config)
        pipe.send({'ready': True})
        _worker_request_loop(pipe, adapter, cancel)
    except BaseException:
        try:
            pipe.send({'ready': False})
        except (BrokenPipeError, EOFError, OSError):
            pass
    finally:
        pipe.close()
        os.close(owner_fd)
