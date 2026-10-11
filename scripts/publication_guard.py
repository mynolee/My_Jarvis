#!/usr/bin/env python3
"""Fail-closed publication inventory. This is a local guard, not a secret scanner."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys

EXCLUDE = {'.git', '.gradle', '.venv', 'node_modules', 'build', 'dist', '__pycache__', '.pytest_cache', 'artifacts', '.ruff_cache', 'test-results', 'playwright-report'}
FORBIDDEN_PARTS = {'.agents', '.codex', '.aws', '.private', 'attachments', 'inputs', 'prompts', 'skills', 'agent_notes', 'user_notes', 'aiprompts', 'private-originals', 'originalfiles', 'jarvis-local', '.claude', '.cursor', '.windsurf', '.aider', '.continue', '.gemini', '.copilot'}
FORBIDDEN_NAMES = {'agents.md', 'skill.md', 'claude.md', 'gemini.md', 'copilot-instructions.md', '.cursorrules', '.windsurfrules', '.aider.conf.yml', '.mcp.json', 'mcp.json', '02_dots_start_prompt.md', '02_dots_start_prompt_v4.0.md', '01_jarvis_secure_spec_v3.0.md', '01_jarvis_secure_spec_v4.0.md', 'model.lock.json', 'model-manifest.json', 'model.manifest.json', 'requirements.mac.lock.txt', 'requirements.mac.lock', 'modelpath', 'local-config.json', '.env'}
SUFFIXES = {'.pem', '.key', '.p12', '.sqlite', '.sqlite3', '.db', '.zip', '.pdf', '.docx', '.safetensors', '.gguf', '.ckpt', '.pt', '.pth'}
PATTERNS = [re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
            re.compile(rb'AKIA[0-9A-Z]{16}'),
            re.compile(rb'gh[pousr]_[A-Za-z0-9]{30,}'),
            re.compile(rb'sk-(?:proj-)?[A-Za-z0-9_-]{40,}')]

def candidates(root):
    # Git candidates capture ignored-but-tracked files too. New checkouts without
    # Git metadata use a deterministic filesystem inventory.
    if (root / '.git').exists():
        raw = subprocess.check_output(['git','ls-files','-z','--cached','--others','--exclude-standard'], cwd=root)
        return sorted({pathlib.PurePosixPath(x.decode()) for x in raw.split(b'\0') if x})
    return sorted(path.relative_to(root) for path in root.rglob('*')
                  if not any(part in EXCLUDE for part in path.relative_to(root).parts)
                  and (path.is_file() or path.is_symlink()))

MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_CORPUS_BYTES = 64 * 1024 * 1024
MAX_CORPUS_FILES = 10000
BODY_WINDOW = 256


def private_index(root, publication_root):
    """Private, optional local-only corpus; no corpus data enters the manifest.

    Exact copies and substantial contiguous text excerpts (whitespace normalized)
    are detected, not semantic rewrites. References must remain outside the repo.
    """
    root = pathlib.Path(root).resolve()
    publication_root = publication_root.resolve()
    if not root.is_dir() or root == publication_root or publication_root in root.parents:
        raise ValueError('private corpus must exist outside publication root')
    hashes, excerpts = set(), set()
    total = count = 0
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError('private corpus contains a symlink')
        if not path.is_file():
            continue
        count += 1
        size = path.stat().st_size
        total += size
        if count > MAX_CORPUS_FILES or size > MAX_FILE_BYTES or total > MAX_CORPUS_BYTES:
            raise ValueError('private corpus exceeds scan bounds')
        data = path.read_bytes()
        hashes.add(hashlib.sha256(data).digest())
        try:
            normalized = ' '.join(data.decode('utf-8').split()).encode('utf-8')
        except UnicodeError:
            continue
        for offset in range(0, len(normalized) - BODY_WINDOW + 1, BODY_WINDOW // 2):
            excerpts.add(normalized[offset:offset + BODY_WINDOW])
    if not count:
        raise ValueError('private corpus contains no files')
    return hashes, excerpts


def inspect(root, private_corpus=None):
    errors, manifest = [], []
    hashes, excerpts = set(), set()
    if private_corpus is not None:
        try:
            hashes, excerpts = private_index(private_corpus, root)
        except (OSError, ValueError):
            return ['private corpus unavailable or invalid; publication blocked'], []
    for relative in candidates(root):
        path = root / relative
        parts = {part.casefold() for part in relative.parts}
        if parts & FORBIDDEN_PARTS or path.name.casefold() in FORBIDDEN_NAMES or path.suffix.lower() in SUFFIXES or (path.name.casefold().startswith('.env.') or path.name.casefold().endswith('.env')):
            errors.append(f'forbidden publication path: {relative}')
            continue
        if path.is_symlink() or not path.is_file():
            errors.append(f'non-regular publication file: {relative}')
            continue
        if path.stat().st_size > MAX_FILE_BYTES:
            errors.append(f'publication file exceeds scan bound: {relative}')
            continue
        content = path.read_bytes()
        if private_corpus is not None:
            try:
                normalized = ' '.join(content.decode('utf-8').split()).encode('utf-8')
            except UnicodeError:
                normalized = b''
            if hashlib.sha256(content).digest() in hashes or any(chunk in normalized for chunk in excerpts):
                errors.append(f'private reference content detected: {relative}')
        # An exact empty-string delimiter removal in a PEM parser contains no
        # key material. Do not ignore the file or adjacent encoded key content.
        credential_content = re.sub(rb'\.replace\("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",""\)', b'', content)
        if any(pattern.search(credential_content) for pattern in PATTERNS):
            errors.append(f'potential credential: {relative}')
        if path.suffix == '.jar' and str(relative) != 'gradle/wrapper/gradle-wrapper.jar':
            errors.append(f'unapproved binary: {relative}')
        manifest.append({'path':str(relative), 'size':len(content), 'sha256':hashlib.sha256(content).hexdigest()})
    return errors, manifest

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--root',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parents[1])
    parser.add_argument('--manifest',type=pathlib.Path)
    parser.add_argument('--private-corpus', type=pathlib.Path, help='local pre-publication private reference directory; required when checking renamed private originals')
    args=parser.parse_args()
    errors,manifest=inspect(args.root.resolve(), args.private_corpus)
    if errors:
        print('\n'.join(errors),file=sys.stderr)
        return 1
    if args.manifest:
        args.manifest.parent.mkdir(parents=True,exist_ok=True)
        args.manifest.write_text(json.dumps({'format':1,'files':manifest},indent=2)+'\n')
    print(f'Publication inventory accepted: {len(manifest)} files. Private reference scan: {"enabled" if args.private_corpus else "NOT_RUN"}. Dedicated secret/SAST/dependency scans remain separate gates.')
    return 0

if __name__=='__main__':
    sys.exit(main())
