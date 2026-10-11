"""Synthetic usage semantics. This is not backend, authentication or native evidence."""
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from pathlib import Path
import re
import sys
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import MAX_INTEGER, canonical_bytes, loads

COUNTS = ('measured', 'pending', 'in_progress', 'unstarted', 'uncertain', 'legacy_unavailable')
METRICS = ('input_tokens', 'output_tokens', 'compute_ms', 'peak_memory_bytes')
IDENTITY = ('owner_id', 'run_id', 'reservation_id', 'inference_request_id',
            'model_id', 'model_manifest_hash', 'budget_revision')
CHECKER = FormatChecker()


@CHECKER.checks('date-time', raises=(ValueError, TypeError, OverflowError))
def valid_time(value):
    if not isinstance(value, str):
        return True
    if not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z', value):
        return False
    datetime.fromisoformat(value)
    return True


def instant(value):
    if not isinstance(value, str) or not valid_time(value):
        raise ValueError('INVALID_TIMESTAMP')
    return datetime.fromisoformat(value)


def utc(value):
    value = value.astimezone(timezone.utc)
    # strftime is not year-zero-padded on all hosts for years below 1000.
    prefix = f'{value.year:04d}-{value.month:02d}-{value.day:02d}T{value.hour:02d}:{value.minute:02d}:{value.second:02d}'
    return prefix + (f'.{value.microsecond:06d}' if value.microsecond else '') + 'Z'


def validator(name):
    schema = loads((ROOT / 'schemas' / f'usage-{name}.schema.json').read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=CHECKER)


def parse_read_request(query=(), body=b''):
    if query or body:
        raise ValueError('INVALID_REQUEST')


def checked_sum(values):
    total = 0
    for value in values:
        if type(value) is not int or value < 0 or value > MAX_INTEGER - total:
            raise ValueError('USAGE_AGGREGATE_OVERFLOW')
        total += value
    return total


def bounds(checked_at, zone):
    local = instant(checked_at).astimezone(ZoneInfo(zone))
    day = local.date()
    day_start = datetime.combine(day, datetime.min.time(), local.tzinfo)
    next_day = datetime.combine(day + timedelta(days=1), datetime.min.time(), local.tzinfo)
    month_start = day_start.replace(day=1)
    next_month = month_start.replace(year=month_start.year + 1, month=1) if month_start.month == 12 else month_start.replace(month=month_start.month + 1)
    return {
        'day': (day.isoformat(), utc(day_start), utc(next_day)),
        'month': (f'{local.year:04d}-{local.month:02d}', utc(month_start), utc(next_month)),
    }


def classify(row, measured, errors):
    key = row['inference_request_id']
    if key in measured:
        return 'measured'
    if key in errors:
        return 'uncertain'
    if row['accounting_version'] == 0:
        return 'legacy_unavailable'
    if row['status'] == 'CANCELLED' and row['started_at'] is None:
        return 'unstarted'
    if row['status'] == 'QUEUED' and row['started_at'] is None:
        return 'pending'
    if row['status'] == 'STARTED' and row['started_at'] is not None:
        return 'in_progress'
    return 'uncertain'


def summarize(rows, measurements, errors, checked_at, zone):
    periods = bounds(checked_at, zone)
    measurements = {key: event for key, event in measurements.items() if instant(event['recorded_at']) <= instant(checked_at)}
    errors = {key: event for key, event in errors.items() if instant(event['recorded_at']) <= instant(checked_at)}
    response = {'checked_at': checked_at, 'timezone': zone,
                'attribution': 'RESERVATION_CREATED_AT', 'quota_timezone': 'UTC'}
    for name, (key, start, end) in periods.items():
        included = [row for row in rows if instant(start) <= instant(row['created_at']) < instant(end)
                    and instant(row['created_at']) <= instant(checked_at)]
        counts = {category: 0 for category in COUNTS}
        actuals = []
        for row in included:
            category = classify(row, measurements, errors)
            counts[category] = checked_sum((counts[category], 1))
            if category == 'measured':
                actuals.append(measurements[row['inference_request_id']])
        retained = [row for row in included if row['status'] != 'CANCELLED']
        measured = {'request_count': len(actuals),
                    **{field: checked_sum(event[field] for event in actuals) for field in METRICS[:-1]},
                    'peak_memory_bytes': max((event['peak_memory_bytes'] for event in actuals), default=None),
                    'external_cost_usd_micro': None}
        response[name] = {'local_key': key, 'start_at': start, 'end_at': end,
                          'request_count': checked_sum(counts.values()), 'counts': counts,
                          'measured': measured,
                          'conservative_reserved': {
                              'request_count': len(retained),
                              'max_compute_ms': checked_sum(row['max_compute_ms'] for row in retained),
                              'max_output_tokens': checked_sum(row['max_output_tokens'] for row in retained)}}
    return response


def valid(name, value):
    try:
        canonical_bytes(value)
        if not validator(name).is_valid(value):
            return False
        if name != 'response':
            return True
        expected = bounds(value['checked_at'], value['timezone'])
        for name in ('day', 'month'):
            period = value[name]
            key, start, end = expected[name]
            if period['local_key'] != key or instant(period['start_at']) != instant(start) or instant(period['end_at']) != instant(end):
                return False
            if not instant(start) <= instant(value['checked_at']) < instant(end):
                return False
            counts = period['counts']
            if checked_sum(counts.values()) != period['request_count']:
                return False
            actual = period['measured']
            count = counts['measured']
            if actual['request_count'] != count:
                return False
            for field, ceiling in (('input_tokens', 3584), ('output_tokens', 512), ('compute_ms', 60000)):
                if actual[field] > count * ceiling:
                    return False
            reserved = period['conservative_reserved']
            count = reserved['request_count']
            if count > period['request_count']:
                return False
            if not count <= reserved['max_compute_ms'] <= count * 60000:
                return False
            if not count <= reserved['max_output_tokens'] <= count * 512:
                return False
        day, month = value['day'], value['month']
        if day['request_count'] > month['request_count']:
            return False
        for field in COUNTS:
            if day['counts'][field] > month['counts'][field]:
                return False
        for group, fields in (('measured', ('request_count', *METRICS[:-1])),
                              ('conservative_reserved', ('request_count', 'max_compute_ms', 'max_output_tokens'))):
            if any(day[group][field] > month[group][field] for field in fields):
                return False
        peak = day['measured']['peak_memory_bytes']
        if peak is not None and (month['measured']['peak_memory_bytes'] is None or peak > month['measured']['peak_memory_bytes']):
            return False
        measured_delta = month['measured']['request_count'] - day['measured']['request_count']
        for field, ceiling in (('input_tokens', 3584), ('output_tokens', 512), ('compute_ms', 60000)):
            if month['measured'][field] - day['measured'][field] > measured_delta * ceiling:
                return False
        if measured_delta == 0 and peak != month['measured']['peak_memory_bytes']:
            return False
        reserved_delta = month['conservative_reserved']['request_count'] - day['conservative_reserved']['request_count']
        for field, ceiling in (('max_compute_ms', 60000), ('max_output_tokens', 512)):
            difference = month['conservative_reserved'][field] - day['conservative_reserved'][field]
            if not reserved_delta <= difference <= reserved_delta * ceiling:
                return False
        return True
    except (ValueError, TypeError, KeyError, OverflowError, ZoneInfoNotFoundError):
        return False


class SyntheticLedger:
    """Trusted service observations are separate from any Run/output authority."""
    def __init__(self, rows=()):
        self.rows = deepcopy(list(rows))
        self.measurements = {}
        self.errors = {}
        self.run_state = {'status': 'RUNNING', 'output': None, 'authority': False}

    def observe(self, request_id, identity, response, recorded_at, *, authenticated=True):
        if authenticated is not True:
            raise ValueError('UNAUTHENTICATED_OBSERVATION')
        matching = [row for row in self.rows if row['inference_request_id'] == request_id]
        if len(matching) != 1:
            raise ValueError('UNKNOWN_RESERVATION')
        row = matching[0]
        canonical_bytes(identity)
        canonical_bytes(response)
        if set(identity) != set(IDENTITY) or any(type(identity[field]) is not type(row[field]) or identity[field] != row[field] for field in IDENTITY):
            raise ValueError('MEASUREMENT_BINDING_MISMATCH')
        instant(recorded_at)
        if row['started_at'] is None or instant(recorded_at) < instant(row['started_at']):
            raise ValueError('MEASUREMENT_BEFORE_DISPATCH')
        response_schema = loads((ROOT / 'schemas' / 'turn-response.schema.json').read_text())
        if not Draft202012Validator(response_schema, format_checker=CHECKER).is_valid(response):
            raise ValueError('INVALID_RESPONSE')
        kind = response.get('kind')
        if kind not in ('ANSWER', 'NEED_INPUT', 'ERROR'):
            raise ValueError('INVALID_RESPONSE')
        usage = response.get('usage')
        if not isinstance(usage, dict) or set(usage) != {'inference_request_id', 'model_manifest_hash', 'reservation_id', *METRICS}:
            raise ValueError('INVALID_USAGE')
        for field in ('inference_request_id', 'model_manifest_hash', 'reservation_id'):
            if usage[field] != row[field]:
                raise ValueError('MEASUREMENT_BINDING_MISMATCH')
        for field in METRICS:
            if type(usage[field]) is not int or not 0 <= usage[field] <= MAX_INTEGER:
                raise ValueError('INVALID_USAGE')
        for field, limit in (('input_tokens', row['max_input_tokens']),
                             ('output_tokens', row['max_output_tokens']),
                             ('compute_ms', row['max_compute_ms'])):
            if usage[field] > limit:
                raise ValueError('USAGE_LIMIT_EXCEEDED')
        if kind == 'ERROR':
            if response['retryable'] is not False:
                raise ValueError('INVALID_RESPONSE')
            receipt = {'identity': deepcopy(identity), 'usage': deepcopy(usage), 'kind': kind}
            prior = self.errors.get(request_id)
            if prior is not None and {key: value for key, value in prior.items() if key != 'recorded_at'} != receipt:
                raise ValueError('USAGE_OBSERVATION_CONFLICT')
            self.errors.setdefault(request_id, {**receipt, 'recorded_at': recorded_at})
            return 'UNMEASURED'
        event = {**deepcopy(identity), **{field: usage[field] for field in METRICS},
                 'external_cost_usd_micro': None}
        prior = self.measurements.get(request_id)
        if prior is not None:
            if {key: value for key, value in prior.items() if key != 'recorded_at'} != event:
                raise ValueError('USAGE_MEASUREMENT_CONFLICT')
            return 'REPLAY'
        self.measurements[request_id] = {**event, 'recorded_at': recorded_at}
        return 'RECORDED'

    def read(self, owner, checked_at, zone, *, live=True, recent=False, query=(), body=b''):
        if owner is None:
            raise ValueError('AUTHENTICATION_REQUIRED')
        if not live:
            raise ValueError('SESSION_REVOKED')
        parse_read_request(query, body)
        rows = [row for row in self.rows if row['owner_id'] == owner]
        return summarize(rows, self.measurements, self.errors, checked_at, zone)
