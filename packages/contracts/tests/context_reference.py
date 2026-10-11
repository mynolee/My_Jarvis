"""Synthetic context-v1 wire oracle; not an authentication/runtime implementation."""
import json
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker
from canonical import canonical_bytes, loads

ROOT = Path(__file__).resolve().parents[1]
LIMITS = json.loads((ROOT / 'fixtures/context-v1/implementation-limits.json').read_text())
VALIDATOR = Draft202012Validator(
    json.loads((ROOT / 'schemas/turn-request.schema.json').read_text()),
    format_checker=FormatChecker())


def validate_request(item):
    raw = canonical_bytes(item)
    if len(raw) > LIMITS['max_request_canonical_bytes']:
        raise ValueError('request byte limit')
    VALIDATOR.validate(item)
    if 'context' not in item:
        return
    bundle = {key: item[key] for key in ('context', 'project_context', 'memories', 'history')}
    for value, limit in ((bundle, 'max_context_canonical_bytes'),
                         (item['memories'], 'max_memories_canonical_bytes'),
                         (item['history'], 'max_history_canonical_bytes')):
        if len(canonical_bytes(value)) > LIMITS[limit]:
            raise ValueError(limit)
    source_ids, memory_ids, message_ids, runs = set(), set(), set(), set()
    def unique(value, seen):
        if value in seen:
            raise ValueError('duplicate source binding')
        seen.add(value)
    for memory in item['memories']:
        unique(memory['source_id'], source_ids)
        unique(memory['id'], memory_ids)
        if len(memory['value'].encode('utf-8')) > LIMITS['max_memory_value_utf8_bytes']:
            raise ValueError('memory text byte limit')
        if memory['scope'] == 'PROJECT':
            if item['project_context'] is None or memory['project_id'] != item['project_context']['id']:
                raise ValueError('project binding')
    history = item['history']
    for index in range(0, len(history), 2):
        user, assistant = history[index:index + 2]
        if user['run_id'] != assistant['run_id'] or user['run_id'] == item['run_id']:
            raise ValueError('history run binding')
        unique(user['run_id'], runs)
        for message in (user, assistant):
            unique(message['source_id'], source_ids)
            unique(message['id'], message_ids)
            if len(message['text'].encode('utf-8')) > LIMITS['max_history_text_utf8_bytes']:
                raise ValueError('history text byte limit')


def validate_raw(raw):
    # Reject excess transport bytes before parsing, even when canonicalization shrinks it.
    if len(raw.encode('utf-8') if isinstance(raw, str) else raw) > LIMITS['max_request_canonical_bytes']:
        raise ValueError('transport byte limit')
    value = loads(raw)
    validate_request(value)
    return value
