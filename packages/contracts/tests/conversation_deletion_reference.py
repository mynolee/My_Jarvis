"""Sequential synthetic contract oracle; not production SQL/auth/HTTP/runtime proof.

Only in-memory fixture dictionaries are modified. HMAC keys and session labels are
synthetic. Caller serialization, supplied times and snapshots model requirements;
they do not prove actual locking, encryption, dispatch provenance or atomicity.
"""
import copy
import hashlib
import hmac
import re
import sys
from datetime import datetime, timedelta
from functools import lru_cache
from pathlib import Path
from zoneinfo import ZoneInfo

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import MAX_INTEGER, canonical_bytes, loads

FIXTURES = ROOT / 'fixtures/conversation-deletion'
LIMITS = loads((FIXTURES / 'implementation-limits.json').read_text())
DOMAINS = loads((FIXTURES / 'quiescence-domains.json').read_text())
CHECKER = FormatChecker()
TIME = re.compile(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z')
ACTIVE = {'ACCEPTED', 'PLANNING', 'WAITING_APPROVAL', 'QUEUED', 'RUNNING',
          'CANCEL_REQUESTED', 'EXECUTING', 'STARTED', 'CAPTURED', 'PREPARED', 'DISPATCHED'}
READ_BARRIERS = ('messages', 'run', 'result', 'approval', 'events', 'history')
WRITE_BARRIERS = ('mock_admit', 'local_admit', 'client_message_replay', 'context_capture',
                  'context_prepare', 'context_dispatch', 'worker_settle', 'reconcile', 'reply_append')


def fixture(name):
    return loads((FIXTURES / f'{name}.json').read_text())


@CHECKER.checks('date-time', raises=(ValueError, TypeError, OverflowError))
def utc_instant(value):
    return not isinstance(value, str) or bool(TIME.fullmatch(value)) and bool(datetime.fromisoformat(value))


def instant(value):
    if not isinstance(value, str) or not utc_instant(value):
        raise ValueError('INVALID_TIMESTAMP')
    return datetime.fromisoformat(value)


def stamp(value):
    return value.isoformat().replace('+00:00', 'Z')


def kst(value):
    return instant(value).astimezone(ZoneInfo('Asia/Seoul')).strftime('%Y-%m-%d %H:%M:%S KST')


@lru_cache(maxsize=None)
def validator(name):
    schema = loads((ROOT / 'schemas' / f'conversation-deletion-{name}.schema.json').read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=CHECKER)


def valid(name, value):
    try:
        canonical_bytes(value)
        return validator(name).is_valid(value)
    except (ValueError, TypeError, UnicodeError):
        return False


def parse_read(path_kind, identifier, query=(), body=b''):
    if query or body or not valid(f'{path_kind}-path', {dict(conversation='conversation_id', preparation='preparation_id', request='idempotency_key')[path_kind]: identifier}):
        raise ValueError('INVALID_REQUEST')


def parse_mutation(kind, raw, key, *, query=(), csrf=True, same_origin=True, json_type=True):
    # Flags only model the HTTP gate; actual CSRF/origin verification needs HTTP tests.
    if query or not csrf or not same_origin or not json_type:
        raise ValueError('INVALID_REQUEST')
    try:
        encoded = raw.encode('utf-8') if isinstance(raw, str) else raw
        body = loads(encoded.decode('utf-8'))
        if not valid(f'{kind}-request', body) or not valid('headers', {'Idempotency-Key': key}):
            raise ValueError('INVALID_REQUEST')
    except (ValueError, TypeError, UnicodeError, AttributeError) as error:
        raise ValueError('INVALID_REQUEST') from error
    return body


def validate_state(value, target=None):
    if not valid('state', value) or target is not None and value['conversation_id'] != target:
        return False
    if value['status'] == 'DELETED':
        return instant(value['deleted_at']) <= instant(value['checked_at'])
    blockers = value['eligibility']['blockers']
    return blockers == sorted(set(blockers))


def validate_preparation(value, state=None):
    if not valid('preparation', value):
        return False
    prepared, expires = instant(value['prepared_at']), instant(value['expires_at'])
    counts = value['source_counts']
    try:
        if expires != prepared + timedelta(seconds=300) or instant(value['conversation']['created_at']) > prepared:
            return False
    except OverflowError:
        return False
    if (counts['run_results'] > counts['runs'] or counts['inference_contexts'] > counts['runs']
            or counts['study_answers'] > 2 * counts['study_sessions']):
        return False
    return state is None or (validate_state(state) and state['status'] == 'ACTIVE'
                            and state['eligibility']['status'] == 'ELIGIBLE'
                            and value['conversation']['id'] == state['conversation_id']
                            and value['expected_version'] == state['content_version']
                            and value['expected_digest'] == state['content_digest']
                            and instant(state['checked_at']) <= prepared)


def validate_pending(value):
    # Local-only, content-free metadata. No browser persistence or bearer authority.
    if not isinstance(value, dict) or set(value) != {'stage', 'key', 'conversation_id',
            'preparation_id', 'expected_version', 'prepared_at', 'expires_at'}:
        return False
    try:
        canonical_bytes(value)
        return (value['stage'] == 'CONFIRM'
                and valid('headers', {'Idempotency-Key': value['key']})
                and valid('conversation-path', {'conversation_id': value['conversation_id']})
                and valid('preparation-path', {'preparation_id': value['preparation_id']})
                and type(value['expected_version']) is int and 1 <= value['expected_version'] < MAX_INTEGER
                and instant(value['expires_at']) - instant(value['prepared_at']) == timedelta(seconds=300))
    except (ValueError, TypeError, OverflowError, UnicodeError):
        return False


def validate_outcome(value, pending=None, preparation=None):
    if not valid('outcome', value) or pending is not None and not validate_pending(pending):
        return False
    if value['status'] == 'NOT_OBSERVED':
        return True
    receipt = value['receipt']
    if receipt['tombstone_version'] != receipt['deleted_version'] + 1:
        return False
    if instant(receipt['deleted_at']) > instant(value['checked_at']):
        return False
    if pending is not None and any(receipt[r] != pending[p] for r, p in (
            ('conversation_id', 'conversation_id'), ('preparation_id', 'preparation_id'),
            ('deleted_version', 'expected_version'))):
        return False
    if pending is not None and not instant(pending['prepared_at']) <= instant(receipt['deleted_at']) < instant(pending['expires_at']):
        return False
    return preparation is None or (validate_preparation(preparation)
            and receipt['preparation_id'] == preparation['id']
            and receipt['conversation_id'] == preparation['conversation']['id']
            and receipt['deleted_version'] == preparation['expected_version']
            and instant(preparation['prepared_at']) <= instant(receipt['deleted_at']) < instant(preparation['expires_at']))


def keyed(owner, domain, value):
    key = hmac.new(b'synthetic-contract-key-not-a-credential', owner.encode(), hashlib.sha256).digest()
    return hmac.new(key, domain.encode() + b'\0' + canonical_bytes(value), hashlib.sha256).hexdigest()


def source_counts(target):
    return {'messages': len(target['content']['messages']), 'runs': len(target['graph']['runs']),
            'run_results': len(target['content']['run_results']),
            'action_payloads': len(target['content']['action_payloads']),
            'inference_contexts': len(target['graph']['contexts']),
            'study_sessions': len(target['graph'].get('study_sessions', [])),
            'study_answers': len(target['graph'].get('study_answers', []))}


def check_limits(target, elapsed_ms=0):
    counts = source_counts(target)
    source_bytes = len(target['title'].encode('utf-8')) + sum(
        len(x.encode('utf-8')) for copies in target['content'].values() for x in copies)
    for kind, fields in (('study_sessions', ('lesson_binding_ref',)),
                         ('study_answers', ('text_ref', 'source_binding_ref')),
                         ('study_mutations', ('request_digest',))):
        source_bytes += sum(len(row.get(field, '').encode('utf-8')) for row in target['graph'].get(kind, [])
                            for field in fields if row.get(field) is not None)
    if (counts['messages'] > LIMITS['messages'] or counts['runs'] > LIMITS['runs']
            or len(target['graph']['actions']) > LIMITS['actions']
            or counts['study_sessions'] > LIMITS['study_sessions']
            or counts['study_answers'] > LIMITS['study_answers']
            or (sum(len(rows) for rows in target['graph'].values())
                + len(target['content']['messages']) + len(target['authorizations'])
                + len(target['source_bindings'])) > LIMITS['dependent_rows']
            or source_bytes > LIMITS['ciphertext_and_title_utf8_bytes']):
        raise ValueError('CONVERSATION_DELETION_SCOPE_LIMIT')
    if elapsed_ms >= LIMITS['transaction_budget_ms']:
        raise ValueError('CONVERSATION_DELETION_TIME_LIMIT')


def eligibility(target):
    blockers = set()
    graph = target['graph']
    title = target.get('title')
    if not isinstance(title, str) or not 1 <= len(title) <= 200 or '\0' in title:
        blockers.add('INTEGRITY_UNVERIFIED')
    if (not set(DOMAINS) <= set(graph) or not set(graph) <= set(DOMAINS) | {'study_sessions', 'study_answers', 'study_mutations'}
            or not target['integrity_verified']):
        blockers.add('INTEGRITY_UNVERIFIED')
    runs = {row['id']: row for row in graph.get('runs', [])}
    contexts = {row['id']: row for row in graph.get('contexts', [])}
    for kind, rows in graph.items():
        ids = [row['id'] for row in rows]
        if len(ids) != len(set(ids)):
            blockers.add('INTEGRITY_UNVERIFIED')
        for row in rows:
            if row['owner_id'] != target['owner_id'] or row['conversation_id'] != target['id']:
                blockers.add('INTEGRITY_UNVERIFIED')
            if kind != 'runs' and row.get('run_id') not in runs:
                blockers.add('INTEGRITY_UNVERIFIED')
            if kind in {'study_sessions', 'study_answers', 'study_mutations'}:
                studies = {s['id']: s for s in graph.get('study_sessions', [])}
                if kind == 'study_sessions':
                    if row.get('lifecycle') not in {'OPEN', 'CLOSED_BY_OWNER'} or not row.get('lesson_binding_ref'):
                        blockers.add('INTEGRITY_UNVERIFIED')
                else:
                    study = studies.get(row.get('study_session_id'))
                    if not study or study['run_id'] != row.get('run_id'):
                        blockers.add('INTEGRITY_UNVERIFIED')
                    if kind == 'study_answers' and (not row.get('text_ref') or not row.get('source_binding_ref')):
                        blockers.add('INTEGRITY_UNVERIFIED')
                    if kind == 'study_mutations' and (row.get('operation') not in {'START', 'ANSWER', 'CLOSE'} or len(row.get('request_digest', '')) != 64):
                        blockers.add('INTEGRITY_UNVERIFIED')
                continue
            status = row['status']
            if status == 'UNKNOWN':
                blockers.add('UNKNOWN_WORK')
            elif kind == 'approvals' and status == 'PENDING':
                blockers.add('PENDING_APPROVAL')
            elif status in ACTIVE:
                blockers.add('ACTIVE_WORK')
            elif status not in DOMAINS.get(kind, []):
                blockers.add('INTEGRITY_UNVERIFIED')
            if kind == 'contexts':
                if (sum(r['run_id'] == row['run_id'] for r in graph.get('reservations', [])) != 1
                        or sum(c['run_id'] == row['run_id'] for c in graph.get('contexts', [])) != 1):
                    blockers.add('INTEGRITY_UNVERIFIED')
            if kind == 'reservations':
                run = runs.get(row.get('run_id'))
                matching = [c for c in graph.get('contexts', []) if c['run_id'] == row['run_id']]
                if (row.get('accounting_version') != 1 or not row.get('started_at')
                        or row.get('dispatch_cache_policy') != LIMITS['dispatch_cache_policy']
                        or row.get('dispatch_context_id') is None):
                    blockers.add('UNSUPPORTED_LEGACY_INFERENCE')
                if not run or run.get('context_history_eligible') is not True or len(matching) != 1:
                    blockers.add('INTEGRITY_UNVERIFIED')
                # request_digest is a synthetic dispatch-binding projection, not a DB context column.
                elif (matching[0]['owner_id'] != row['owner_id']
                        or matching[0]['conversation_id'] != target['id']
                        or row.get('dispatch_context_id') is not None and row['dispatch_context_id'] != matching[0]['id']
                        or matching[0].get('request_digest') != row.get('body_hash')):
                    blockers.add('INTEGRITY_UNVERIFIED')
    return {'status': 'BLOCKED' if blockers else 'ELIGIBLE', 'blockers': sorted(blockers)}


def dispatch_transition(before, after, context):
    """Synthetic assertion only: DB/runtime tests must establish actual provenance."""
    protected = ('id', 'owner_id', 'run_id', 'conversation_id')
    if any(before[k] != after[k] for k in protected):
        return False
    if (before.get('dispatch_context_id') is None) != (before.get('dispatch_cache_policy') is None):
        return False
    if (after.get('dispatch_context_id') is None) != (after.get('dispatch_cache_policy') is None):
        return False
    if before.get('dispatch_context_id') is not None or before.get('started_at') is not None:
        return all(before.get(k) == after.get(k) for k in (
            'dispatch_context_id', 'dispatch_cache_policy', 'body_hash', 'started_at'))
    if after.get('dispatch_context_id') is None and after.get('dispatch_cache_policy') is None:
        return True
    return (before['status'] == 'QUEUED' and after['status'] == 'STARTED'
            and after.get('started_at') is not None and after['accounting_version'] == 1
            and after['dispatch_cache_policy'] == LIMITS['dispatch_cache_policy']
            and context['status'] == 'DISPATCHED' and after['dispatch_context_id'] == context['id']
            and all(after[k] == context[k] for k in ('owner_id', 'run_id', 'conversation_id'))
            and after['body_hash'] == context['request_digest'])


class SyntheticDeletions:
    """In-memory, caller-serialized oracle. Not a database or storage eraser."""
    def __init__(self):
        self.targets, self.sessions, self.epochs = {}, {}, {}
        self.preparations, self.receipts, self.ledger = {}, {}, {}
        self.paused, self.audit, self.preparation_audit = set(), [], []
        self.memories, self.usage = [], []
        self.next_id = 1

    def add_session(self, label, owner, now):
        self.epochs.setdefault(owner, 1)
        self.sessions[label] = {'owner': owner, 'epoch': self.epochs[owner], 'live': True, 'reauth_at': now}

    def auth(self, label, now, recent=False):
        session = self.sessions.get(label)
        if session is None:
            raise ValueError('AUTHENTICATION_REQUIRED')
        if not session['live'] or session['epoch'] != self.epochs[session['owner']]:
            raise ValueError('SESSION_REVOKED')
        age = (instant(now) - instant(session['reauth_at'])).total_seconds()
        if recent and not 0 <= age <= 300:
            raise ValueError('REAUTHENTICATION_REQUIRED')
        return session

    def target(self, owner, target_id, live=True):
        target = self.targets.get(target_id)
        if target is None or target['owner_id'] != owner:
            raise ValueError('NOT_FOUND')
        if live and target_id in self.ledger:
            raise ValueError('CONVERSATION_DELETED')
        return target

    def binding(self, label, entry, now):
        session = self.auth(label, now)
        if session['owner'] != entry['owner']:
            raise ValueError('NOT_FOUND')
        if label != entry['session'] or session['epoch'] != entry['epoch']:
            raise ValueError('AUTHORITY_NOT_VALID')
        return session

    def digest(self, target):
        manifest = copy.deepcopy(target)
        for rows in manifest['graph'].values():
            rows.sort(key=lambda row: row['id'])
        manifest['authorizations'].sort(key=lambda row: row['id'])
        manifest['source_bindings'].sort()
        # Source-copy arrays already have semantic message/copy ordinal ordering.
        return keyed(target['owner_id'], 'conversation-manifest-v1', manifest)

    def state(self, label, target_id, now):
        parse_read('conversation', target_id)
        session = self.auth(label, now)
        target = self.target(session['owner'], target_id, live=False)
        if target_id in self.ledger:
            receipt = self.ledger[target_id]
            if instant(now) < instant(receipt['deleted_at']):
                raise ValueError('AUTHORITY_NOT_VALID')
            return {'status': 'DELETED', 'conversation_id': target_id,
                    'deletion_id': receipt['deletion_id'], 'deleted_at': receipt['deleted_at'], 'checked_at': now}
        check_limits(target)
        eligible = eligibility(target)
        return {'status': 'ACTIVE', 'conversation_id': target_id, 'content_version': target['content_version'],
                'content_digest': self.digest(target) if eligible['status'] == 'ELIGIBLE' else None,
                'eligibility': eligible, 'checked_at': now}

    def check_snapshot(self, target, request, elapsed_ms=0):
        check_limits(target, elapsed_ms)
        blockers = eligibility(target)['blockers']
        if 'INTEGRITY_UNVERIFIED' in blockers:
            raise ValueError('CONVERSATION_GRAPH_UNVERIFIED')
        if any(code in blockers for code in ('ACTIVE_WORK', 'UNKNOWN_WORK', 'PENDING_APPROVAL')):
            raise ValueError('CONVERSATION_NOT_QUIESCENT')
        if blockers:
            raise ValueError('CONVERSATION_DELETION_UNSUPPORTED')
        if target['content_version'] != request['expected_version']:
            raise ValueError('PRECONDITION_CHANGED')
        if not hmac.compare_digest(self.digest(target), request['expected_digest']):
            raise ValueError('PRECONDITION_CHANGED')
        if target['content_version'] >= MAX_INTEGER:
            raise ValueError('VERSION_EXHAUSTED')

    def preview(self, entry, target):
        return {'id': entry['id'], 'status': 'PREPARED', 'scope_policy': LIMITS['scope_policy'],
                'conversation': {k: target[k] for k in ('id', 'title', 'project_id', 'created_at')},
                **copy.deepcopy(entry['request']), 'source_counts': source_counts(target),
                'payload_hash': entry['payload_hash'], 'prepared_at': entry['prepared_at'], 'expires_at': entry['expires_at']}

    def check_preparation(self, entry, now):
        if entry['status'] == 'CONSUMED':
            raise ValueError('APPROVAL_CONSUMED')
        # Competing target preparations deliberately resolve as deleted, not reusable authority.
        if entry['conversation_id'] in self.ledger:
            raise ValueError('CONVERSATION_DELETED')
        if entry['status'] != 'UNCONSUMED':
            raise ValueError('AUTHORITY_NOT_VALID')
        if instant(now) < instant(entry['prepared_at']):
            raise ValueError('AUTHORITY_NOT_VALID')
        if instant(now) >= instant(entry['expires_at']):
            raise ValueError('APPROVAL_EXPIRED')

    def prepare(self, label, target_id, raw, key, now, elapsed_ms=0):
        request = parse_mutation('prepare', raw, key)
        parse_read('conversation', target_id)
        session = self.auth(label, now, recent=True)
        if session['owner'] in self.paused:
            raise ValueError('EXECUTION_PAUSED')
        target = self.target(session['owner'], target_id, live=False)
        request_digest = keyed(session['owner'], 'conversation-prepare-v1', {'target': target_id, 'body': request})
        prior = next((p for p in self.preparations.values() if p['owner'] == session['owner'] and p['key'] == key), None)
        if prior is not None:
            self.binding(label, prior, now)
            if request_digest != prior['request_digest']:
                raise ValueError('IDEMPOTENCY_CONFLICT')
            self.check_preparation(prior, now)
            self.check_snapshot(target, request, elapsed_ms)
            return self.preview(prior, target)
        self.target(session['owner'], target_id)
        self.check_snapshot(target, request, elapsed_ms)
        cutoff = instant(now) - timedelta(seconds=86400)
        retained = {pid: p for pid, p in self.preparations.items() if not (
            p['owner'] == session['owner'] and p['status'] != 'CONSUMED' and instant(p['prepared_at']) < cutoff)}
        if sum(p['owner'] == session['owner'] and p['status'] != 'CONSUMED' for p in retained.values()) >= 100:
            raise ValueError('CONVERSATION_DELETION_PREPARATION_LIMIT')
        pid = f'82000000-0000-4000-8000-{self.next_id:012d}'
        entry = {'id': pid, 'owner': session['owner'], 'session': label, 'epoch': session['epoch'],
                 'key': key, 'request_digest': request_digest, 'conversation_id': target_id,
                 'request': request, 'status': 'UNCONSUMED', 'prepared_at': now,
                 'expires_at': stamp(instant(now) + timedelta(seconds=300))}
        binding = {'owner_id': session['owner'], 'session_hash': keyed(session['owner'], 'session', label),
                   'security_epoch': session['epoch'], 'preparation_id': pid, 'conversation_id': target_id,
                   'tool_id': 'conversation.source.delete', 'tool_version': '1', 'policy_version': LIMITS['policy_version'],
                   'scope_policy': LIMITS['scope_policy'], **request, 'manifest_revision': 1,
                   'prepared_at': now, 'expires_at': entry['expires_at'], 'max_executions': 1}
        entry['payload_hash'] = keyed(session['owner'], 'conversation-confirmation-v1', binding)
        result = self.preview(entry, target)
        if not validate_preparation(result):
            raise ValueError('INTERNAL_ERROR')
        retained[pid] = entry
        self.preparations, self.next_id = retained, self.next_id + 1
        self.preparation_audit.append({'kind': 'CONVERSATION_DELETE_PREPARED', 'preparation_id': pid})
        return result

    def confirm(self, label, pid, raw, key, now, elapsed_ms=0, fail_after_stage=False):
        request = parse_mutation('confirm', raw, key)
        parse_read('preparation', pid)
        session = self.auth(label, now)
        digest = keyed(session['owner'], 'conversation-confirm-request-v1', {'preparation_id': pid, 'body': request})
        prior = self.receipts.get((session['owner'], key))
        if prior is not None:
            self.binding(label, prior, now)
            if digest != prior['request_digest']:
                raise ValueError('IDEMPOTENCY_CONFLICT')
            if instant(now) < instant(prior['receipt']['deleted_at']):
                raise ValueError('AUTHORITY_NOT_VALID')
            return {'status': 'COMMITTED', 'receipt': copy.deepcopy(prior['receipt']), 'checked_at': now}
        self.auth(label, now, recent=True)
        if session['owner'] in self.paused:
            raise ValueError('EXECUTION_PAUSED')
        entry = self.preparations.get(pid)
        if entry is None:
            raise ValueError('NOT_FOUND')
        self.binding(label, entry, now)
        if key == entry['key']:
            raise ValueError('IDEMPOTENCY_CONFLICT')
        self.check_preparation(entry, now)
        if (request['expected_version'] != entry['request']['expected_version']
                or request['expected_digest'] != entry['request']['expected_digest']
                or not hmac.compare_digest(request['payload_hash'], entry['payload_hash'])):
            raise ValueError('PRECONDITION_CHANGED')
        target = self.target(session['owner'], entry['conversation_id'])
        self.check_snapshot(target, request, elapsed_ms)
        receipt = {'deletion_id': f'83000000-0000-4000-8000-{self.next_id:012d}',
                   'conversation_id': target['id'], 'preparation_id': pid, 'scope_policy': LIMITS['scope_policy'],
                   'deleted_version': target['content_version'], 'tombstone_version': target['content_version'] + 1, 'deleted_at': now}
        staged = copy.deepcopy(target)
        staged['title'], staged['content'], staged['content_version'] = None, {}, receipt['tombstone_version']
        for run in staged['graph']['runs']:
            run['context_history_eligible'] = False
        for context in staged['graph']['contexts']:
            context['status'] = 'INVALIDATED'
        staged['source_bindings'] = []
        for shell in staged['graph'].get('study_sessions', []):
            shell['lesson_binding_ref'], shell['source_deleted_at'] = None, now
        for shell in staged['graph'].get('study_answers', []):
            shell['text_ref'], shell['source_binding_ref'], shell['content_deleted_at'] = None, None, now
        for shell in staged['authorizations']:
            shell['payload_hash'], shell['source_deleted_at'], shell['revoked'] = None, now, True
        if fail_after_stage:
            raise ValueError('SYNTHETIC_ROLLBACK')
        self.targets[target['id']] = staged
        for prep in self.preparations.values():
            if prep['conversation_id'] == target['id']:
                prep['status'] = 'CONSUMED' if prep['id'] == pid else 'REVOKED'
                prep['request'], prep['payload_hash'] = None, None
                # Purpose-separated issuance HMAC is retained solely for replay identity.
        self.ledger[target['id']] = copy.deepcopy(receipt)
        self.receipts[(session['owner'], key)] = {'owner': session['owner'], 'session': label,
                'epoch': session['epoch'], 'request_digest': digest, 'receipt': copy.deepcopy(receipt)}
        self.audit.append({'kind': 'CONVERSATION_SOURCE_DELETED', 'conversation_id': target['id'], 'deleted_at': now})
        self.next_id += 1
        return {'status': 'COMMITTED', 'receipt': receipt, 'checked_at': now}

    def reconcile(self, label, key, now):
        parse_read('request', key)
        session = self.auth(label, now)
        prior = self.receipts.get((session['owner'], key))
        if prior is None:
            return {'status': 'NOT_OBSERVED', 'receipt': None, 'checked_at': now}
        self.binding(label, prior, now)
        if instant(now) < instant(prior['receipt']['deleted_at']):
            raise ValueError('AUTHORITY_NOT_VALID')
        return {'status': 'COMMITTED', 'receipt': copy.deepcopy(prior['receipt']), 'checked_at': now}

    def content_boundary(self, label, target_id, operation, now):
        if operation not in READ_BARRIERS + WRITE_BARRIERS:
            raise ValueError('UNREVIEWED_BOUNDARY')
        session = self.auth(label, now)
        return self.target(session['owner'], target_id)


class SyntheticDeletionClient:
    """Generation/unknown-outcome oracle, not React or browser execution."""
    def __init__(self):
        self.generation, self.post_count = 0, 0
        self.review, self.pending = None, None
        self.private_display, self.state = None, 'IDLE'
        self.consent = False

    @property
    def writes_blocked(self):
        return self.state in ('CONFIRMING', 'OUTCOME_UNKNOWN')

    def accept_review(self, value, state, generation):
        if generation != self.generation or self.writes_blocked or not validate_preparation(value, state):
            return False
        self.review, self.private_display, self.state, self.consent = copy.deepcopy(value), value['conversation']['title'], 'REVIEW', False
        return True

    def invalidate(self):
        if self.writes_blocked:
            return False
        self.generation += 1
        self.review, self.private_display, self.consent, self.state = None, None, False, 'IDLE'
        return True

    def explicit_confirm(self, key, now):
        if self.state != 'REVIEW' or not self.consent or instant(now) >= instant(self.review['expires_at']):
            return None
        p = self.review
        self.pending = {'stage': 'CONFIRM', 'key': key, 'conversation_id': p['conversation']['id'],
                        'preparation_id': p['id'], 'expected_version': p['expected_version'],
                        'prepared_at': p['prepared_at'], 'expires_at': p['expires_at']}
        self.state, self.post_count = 'CONFIRMING', self.post_count + 1
        return {'payload_hash': p['payload_hash'], 'expected_version': p['expected_version'],
                'expected_digest': p['expected_digest'], 'confirm': True}

    def uncertain(self):
        if self.pending is None:
            return
        self.review, self.private_display, self.consent, self.state = None, None, False, 'OUTCOME_UNKNOWN'

    def receive(self, value, generation):
        if generation != self.generation or self.pending is None:
            return False
        if not validate_outcome(value, self.pending) or value['status'] != 'COMMITTED':
            self.uncertain()
            return False
        self.pending, self.review, self.private_display, self.consent = None, None, None, False
        self.state, self.generation = 'COMMITTED', self.generation + 1
        return True

    def replace_session(self):
        self.generation += 1
        self.pending, self.review, self.private_display, self.consent, self.state = None, None, None, False, 'IDLE'
