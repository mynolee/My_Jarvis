"""Synthetic wire validation only; it does not establish database or owner authority."""
import json
import sys
from datetime import datetime
from functools import lru_cache
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import canonical_bytes, loads


@lru_cache(maxsize=None)
def validator(name):
    schema = loads((ROOT / 'schemas' / f'durable-study-{name}.schema.json').read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


def valid(name, value):
    try:
        canonical_bytes(value)
        return validator(name).is_valid(value)
    except (TypeError, ValueError, UnicodeError):
        return False


def instant(value):
    return datetime.fromisoformat(value)


def parse_request(operation, raw, key):
    body = loads(raw)
    if not valid('headers', {'Idempotency-Key': key}) or not valid(operation.lower() + '-request', body):
        raise ValueError('INVALID_REQUEST')
    if len(canonical_bytes(body)) > 65536:
        raise ValueError('BODY_TOO_LARGE')
    if operation == 'ANSWER' and len(body['text'].encode('utf-8')) > 32000:
        raise ValueError('INVALID_REQUEST')
    return body


def receipt_ok(value):
    if not valid('receipt', value):
        return False
    if value['resulting_conversation_version'] != value['prior_conversation_version'] + 1:
        return False
    return value['operation'] == 'START' or value['resulting_version'] == value['prior_version'] + 1


def outcome_ok(value, pending=None):
    if not valid('outcome', value):
        return False
    if value['status'] == 'NOT_OBSERVED':
        return True  # observation is not permission to resend
    receipt = value['receipt']
    if not receipt_ok(receipt) or instant(receipt['committed_at']) > instant(value['checked_at']):
        return False
    if pending:
        for field in ('operation', 'mutation_id', 'conversation_id', 'study_session_id', 'lesson_run_id', 'prior_version', 'prior_conversation_version', 'question_id'):
            if field in pending and pending[field] != receipt.get(field):
                return False
    return True


def view_ok(value):
    if not valid('view', value):
        return False
    if not instant(value['created_at']) <= instant(value['updated_at']) <= instant(value['checked_at']):
        return False
    questions = [q['id'] for q in value['lesson']['questions']]
    answers = value['attempts']
    if len(set(questions)) != 2 or any(a['question_id'] not in questions for a in answers):
        return False
    if any(len({a[field] for a in answers}) != len(answers) for field in ('question_id', 'attempt_id', 'input_id')):
        return False
    closed = value['lifecycle'] == 'CLOSED_BY_OWNER'
    stage = 'CLOSED_BY_OWNER' if closed else 'FEEDBACK_PENDING' if len(answers) == 2 else 'WAITING_ANSWERS'
    return value['stage'] == stage and value['version'] == 1 + len(answers) + int(closed)


def index_ok(value):
    if not valid('index', value):
        return False
    runs = set()
    for item in value['items']:
        optional = [item[field] is None for field in ('study_session_id', 'study_version', 'lifecycle')]
        if len(set(optional)) != 1 or item['lesson_run_id'] in runs or instant(item['created_at']) > instant(value['checked_at']):
            return False
        if item['lifecycle'] == 'CLOSED_BY_OWNER' and item['study_version'] < 2:
            return False
        runs.add(item['lesson_run_id'])
    return True


def envelope_ok(kind, data):
    limit = 262144 if kind == 'view' else 65536
    return len(canonical_bytes({'data': data, 'request_id': 'synthetic-request'})) <= limit
