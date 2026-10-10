"""Synthetic audit-history reference, not production auth/SQL/cursor evidence.

The token codec models binding and expiry with a public synthetic key. Its bytes
are not the server cursor format; public cursors are opaque.
"""
import base64
import hashlib
import hmac
import re
import sys
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import canonical_bytes, loads

GROUPS = loads((ROOT / 'fixtures/audit-history/event-kinds.json').read_text())
EVENT_KINDS = {event: kind for kind, events in GROUPS.items() for event in events}
STATES = loads((ROOT / 'state-machines.json').read_text())['run_states']
UUID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
TIME = re.compile(r'^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z$')
TOKEN = re.compile(r'^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}$')
CHECKER = FormatChecker()


@CHECKER.checks('date-time', raises=(ValueError, TypeError, OverflowError))
def valid_time(value):
    if not isinstance(value, str):
        return True
    return bool(TIME.fullmatch(value)) and bool(datetime.fromisoformat(value))


def instant(value):
    if not isinstance(value, str) or not valid_time(value):
        raise ValueError('Invalid synthetic timestamp')
    return datetime.fromisoformat(value)


def utc(value):
    return value.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')


@lru_cache(maxsize=None)
def validator(name):
    schema = loads((ROOT / 'schemas' / f'audit-history-{name}.schema.json').read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=CHECKER)


def event_key(item):
    return instant(item['happened_at']), item['id']


def valid(name, value, requested_limit=None):
    try:
        canonical_bytes(value)
        if not validator(name).is_valid(value):
            return False
        if name == 'item':
            if value['run'] is not None and value['run']['id'] != value['resource_id']:
                return False
        if name == 'response':
            items = value['items']
            if len({item['id'] for item in items}) != len(items):
                return False
            if not all(valid('item', item) for item in items):
                return False
            if items != sorted(items, key=event_key, reverse=True):
                return False
            if value['next_cursor'] is not None and not items:
                return False
            if requested_limit is not None:
                if type(requested_limit) is not int or not 1 <= requested_limit <= 100:
                    return False
                if len(items) > requested_limit:
                    return False
                if value['next_cursor'] is not None and len(items) != requested_limit:
                    return False
        return True
    except (ValueError, TypeError, KeyError, OverflowError, UnicodeError):
        return False


def parse_query(pairs, body=b''):
    """Preserve duplicate query fields until rejection, unlike a lossy dict."""
    if body:
        raise ValueError('INVALID_REQUEST')
    query = {}
    for key, value in pairs:
        if key in query:
            raise ValueError('INVALID_REQUEST')
        query[key] = value
    has_cursor = 'cursor' in query
    cursor = query.pop('cursor', None)
    if not valid('query', query):
        raise ValueError('INVALID_REQUEST')
    if has_cursor:
        if not isinstance(cursor, str) or not 1 <= len(cursor) <= 1024:
            raise ValueError('INVALID_REQUEST')
        if not TOKEN.fullmatch(cursor):
            raise ValueError('INVALID_CURSOR')
    return query.get('scope', 'ALL'), int(query.get('limit', '20')), cursor


def project(row, owner, runs):
    """Project already owner-filtered audit rows without reading content."""
    if row['owner_id'] != owner:
        raise ValueError('Wrong synthetic owner')
    kind = EVENT_KINDS.get(row['event_type'], 'OTHER')
    result = {'id': row['id'], 'event_type': row['event_type'] if kind != 'OTHER' else 'OTHER',
              'happened_at': row['happened_at'], 'resource_kind': kind,
              'resource_id': row['resource_id'] if kind != 'OTHER' else None, 'run': None}
    if kind == 'RUN':
        owned = [run for run in runs if run['owner_id'] == owner and run['id'] == row['resource_id']]
        if len(owned) > 1:
            raise ValueError('Duplicate synthetic run identity')
        if owned:
            result['run'] = {'id': owned[0]['id'], 'current_status': owned[0]['status']}
    if not valid('item', result):
        raise ValueError('Invalid synthetic record')
    return result


class SyntheticCursor:
    """Executable boundary examples, never use as an application credential."""
    KEY = b'public synthetic audit contract fixture key'

    @staticmethod
    def encode(raw):
        return base64.urlsafe_b64encode(raw).rstrip(b'=').decode('ascii')

    @classmethod
    def decode(cls, text):
        if not re.fullmatch(r'[A-Za-z0-9_-]+', text):
            raise ValueError('INVALID_REQUEST')
        raw = base64.urlsafe_b64decode(text + '=' * (-len(text) % 4))
        if cls.encode(raw) != text:
            raise ValueError('INVALID_REQUEST')
        return raw

    @classmethod
    def tag(cls, session):
        return hmac.new(cls.KEY, ('session:' + session).encode(), hashlib.sha256).hexdigest()

    @classmethod
    def issue(cls, owner, scope, session, epoch, now, last):
        payload = {'endpoint': 'audit-v1', 'owner': owner, 'scope': scope,
                   'session_tag': cls.tag(session), 'epoch': epoch,
                   'issued_at': now, 'expires_at': utc(instant(now) + timedelta(minutes=15)),
                   'before_time': last['happened_at'], 'before_id': last['id']}
        body = cls.encode(canonical_bytes(payload))
        signature = cls.encode(hmac.new(cls.KEY, body.encode(), hashlib.sha256).digest())
        token = body + '.' + signature
        if len(token) > 1024:
            raise ValueError('Synthetic cursor too large')
        return token

    @classmethod
    def boundary(cls, token, owner, scope, session, epoch, now):
        try:
            if not isinstance(token, str) or len(token) > 1024 or not TOKEN.fullmatch(token):
                raise ValueError('INVALID_CURSOR')
            body, signature = token.split('.')
            expected = hmac.new(cls.KEY, body.encode(), hashlib.sha256).digest()
            if not hmac.compare_digest(expected, cls.decode(signature)):
                raise ValueError('INVALID_CURSOR')
            payload = loads(cls.decode(body))
            if set(payload) != {'endpoint', 'owner', 'scope', 'session_tag', 'epoch', 'issued_at', 'expires_at', 'before_time', 'before_id'}:
                raise ValueError('INVALID_CURSOR')
            if (payload['endpoint'], payload['owner'], payload['scope'], payload['session_tag'], payload['epoch']) != ('audit-v1', owner, scope, cls.tag(session), epoch):
                raise ValueError('INVALID_CURSOR')
            issued, expiry = instant(payload['issued_at']), instant(payload['expires_at'])
            if instant(now) < issued or expiry - issued != timedelta(minutes=15):
                raise ValueError('INVALID_CURSOR')
            if instant(now) >= expiry:
                raise ValueError('AUDIT_CURSOR_EXPIRED')
            if not UUID.fullmatch(payload['before_id']):
                raise ValueError('INVALID_CURSOR')
            return instant(payload['before_time']), payload['before_id'], payload['issued_at']
        except (TypeError, KeyError, ValueError, UnicodeError, OverflowError) as error:
            if str(error) == 'AUDIT_CURSOR_EXPIRED':
                raise
            raise ValueError('INVALID_CURSOR') from error


def page(events, runs, owner, now, scope='ALL', limit=20, cursor=None,
         session='synthetic-session-a', epoch=1):
    if scope not in ('ALL', 'RUNS') or type(limit) is not int or not 1 <= limit <= 100:
        raise ValueError('INVALID_REQUEST')
    instant(now)
    boundary = SyntheticCursor.boundary(cursor, owner, scope, session, epoch, now) if cursor is not None else None
    selected = [row for row in events if row['owner_id'] == owner
                and (scope == 'ALL' or EVENT_KINDS.get(row['event_type']) == 'RUN')
                and (boundary is None or event_key(row) < boundary[:2])]
    selected.sort(key=event_key, reverse=True)
    rows = selected[:limit]
    result = {'checked_at': now, 'scope': scope, 'items': [project(row, owner, runs) for row in rows],
              'next_cursor': SyntheticCursor.issue(owner, scope, session, epoch, now if boundary is None else boundary[2], rows[-1]) if len(selected) > limit else None}
    if not valid('response', result, limit):
        raise ValueError('Invalid synthetic page')
    return result
