"""Synthetic sequential rules, not production authentication/SQL/browser evidence."""
import copy
import re
import sys
from datetime import datetime
from functools import lru_cache
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import MAX_INTEGER, canonical_bytes, loads

TIME = re.compile(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z')
CHECKER = FormatChecker()


@CHECKER.checks('date-time', raises=(ValueError, TypeError, OverflowError))
def utc_instant(value):
    if not isinstance(value, str):
        return True
    return bool(TIME.fullmatch(value)) and bool(datetime.fromisoformat(value))


@lru_cache(maxsize=None)
def validator(name):
    schema = loads((ROOT / 'schemas' / f'execution-control-{name}.schema.json').read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=CHECKER)


def valid(name, value):
    try:
        canonical_bytes(value)
        return validator(name).is_valid(value)
    except (ValueError, TypeError, UnicodeError):
        return False


def parse_request(raw, query=()):
    if query:
        raise ValueError('INVALID_REQUEST')
    body = loads(raw)
    if not valid('request', body):
        raise ValueError('INVALID_REQUEST')
    return body['expected_version']


def parse_status_request(query=(), body=b''):
    if query or body:
        raise ValueError('INVALID_REQUEST')


def mutation_result_matches(before, target, response):
    if not valid('state', before) or type(target) is not bool or not valid('state', response):
        return False
    version = before['version'] + int(before['execution_paused'] != target)
    return response['execution_paused'] is target and response['version'] == version


class SyntheticBoundary:
    """One owner's abstract state, serialized by the caller; no real locking.

    Session labels and flags are test placeholders, never credentials. Authority
    usability models epoch rejection, not the full checks of any tool or grant.
    """
    def __init__(self, paused=False, version=7, epoch=3):
        self.paused = paused
        self.version = version
        self.epoch = epoch
        self.sessions = {
            'initiator': {'epoch': epoch, 'live': True, 'recent': True},
            'other': {'epoch': epoch, 'live': True, 'recent': True},
        }
        self.work = loads((ROOT / 'fixtures/execution-control/work.json').read_text())
        self.events = []
        self.interrupt_attempts = 0

    def authenticate(self, label, recent=False):
        if label not in self.sessions:
            raise ValueError('AUTHENTICATION_REQUIRED')
        session = self.sessions[label]
        if not session['live'] or session['epoch'] != self.epoch:
            raise ValueError('SESSION_REVOKED')
        if recent and not session['recent']:
            raise ValueError('REAUTHENTICATION_REQUIRED')

    def state(self, label, now):
        self.authenticate(label)
        snapshot = {'execution_paused': self.paused, 'version': self.version, 'checked_at': now}
        if not valid('state', snapshot):
            raise ValueError('INTERNAL_ERROR')
        return snapshot

    def transition(self, label, target, raw, now, query=()):
        expected = parse_request(raw, query)
        if type(target) is not bool:
            raise ValueError('INVALID_REQUEST')
        self.authenticate(label, recent=True)
        # Validate output time before applying this synthetic all-or-nothing step.
        self.state(label, now)
        if expected != self.version:
            raise ValueError('PRECONDITION_CHANGED')
        if target == self.paused:
            return self.state(label, now)
        if self.version >= MAX_INTEGER or self.epoch >= MAX_INTEGER:
            raise ValueError('VERSION_EXHAUSTED')
        self.paused = target
        self.version += 1
        self.epoch += 1
        self.sessions[label]['epoch'] = self.epoch
        if target:
            for job in self.work['jobs']:
                if job['status'] in ('QUEUED', 'RUNNING'):
                    job['status'] = 'UNKNOWN' if job['started_effect'] else 'CANCELLED'
            for approval in self.work['approvals']:
                if approval['status'] == 'PENDING':
                    approval['status'] = 'REVOKED'
            self.work['runs'] = [
                'UNKNOWN' if state == 'RUNNING' else
                'CANCELLED' if state in ('ACCEPTED', 'PLANNING', 'WAITING_APPROVAL', 'QUEUED') else state
                for state in self.work['runs']
            ]
            self.interrupt_attempts += 1
        self.events.append('EXECUTION_PAUSED' if target else 'EXECUTION_RESUMED')
        return self.state(label, now)

    def authority_eligible(self, authority):
        return not self.paused and authority['security_epoch'] == self.epoch


class SyntheticObservation:
    """Client-state invariant sketch, not a browser or transport implementation."""
    def __init__(self):
        self.generation = 0
        self.snapshot = None
        self.fresh = False
        self.request_outcome = None
        self.other_request_outcome = None

    def refresh(self, snapshot, generation):
        if generation != self.generation:
            return False
        if not valid('state', snapshot):
            self.fresh = False
            return False
        self.snapshot = copy.deepcopy(snapshot)
        self.fresh = True
        # A current-state observation never attributes a lost response.
        return True

    def lose_response(self):
        self.request_outcome = 'UNKNOWN'
        self.fresh = False

    def session_changed(self):
        self.generation += 1
        self.snapshot = None
        self.fresh = False
        # Retain ambiguity even though private session-dependent state is cleared.

    def can_pause(self, live_session=True, csrf_known=True, reauth_recent=True):
        return bool(self.snapshot is not None and self.fresh and live_session and csrf_known and reauth_recent)

    def can_new_ordinary_work(self, *, explicit_owner_action=False,
                              live_session=True, csrf_known=True):
        return bool(explicit_owner_action and self.fresh and self.snapshot is not None
                    and not self.snapshot['execution_paused'] and live_session and csrf_known
                    and self.other_request_outcome not in ('PENDING', 'UNKNOWN'))
