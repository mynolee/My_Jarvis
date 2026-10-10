"""Synthetic wire/recurrence oracle, not production authority or storage code.

Uses host Python TZDB for declared fixture zones, never as Java's acceptance list.
All clocks are explicit parameters; no wall-clock/network/model calls are made.
"""
from copy import deepcopy
from datetime import date, datetime, time, timedelta, timezone
import hashlib
import hmac
from pathlib import Path
import re
import sys
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import canonical_bytes, loads

UTC = timezone.utc
MAX_BODY = 16384
MAX_GRANT_SECONDS = 2592000
CHECKER = FormatChecker()


@CHECKER.checks('date-time', raises=(ValueError, TypeError, OverflowError))
def utc_format(value):
    if not isinstance(value, str):
        return True
    if not re.fullmatch(r'(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-5][0-9](?:\.[0-9]{1,6})?Z', value):
        return False
    return datetime.fromisoformat(value).tzinfo == UTC


@CHECKER.checks('date', raises=(ValueError, TypeError, OverflowError))
def date_format(value):
    if not isinstance(value, str):
        return True
    return bool(re.fullmatch(r'(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}', value)) and bool(date.fromisoformat(value))


def instant(value):
    if not utc_format(value):
        raise ValueError('invalid UTC timestamp')
    return datetime.fromisoformat(value)


def timestamp(value):
    # Explicit format preserves four-digit years on every supported platform.
    result = f'{value.year:04d}-{value.month:02d}-{value.day:02d}T{value.hour:02d}:{value.minute:02d}:{value.second:02d}'
    if value.microsecond:
        result += f'.{value.microsecond:06d}'.rstrip('0')
    return result + 'Z'


def local_timestamp(value):
    return f'{value.year:04d}-{value.month:02d}-{value.day:02d}T{value.hour:02d}:{value.minute:02d}:{value.second:02d}'


def validator(name):
    schema = loads((ROOT / 'schemas' / f'{name}.schema.json').read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=CHECKER)


def schema_validate(name, value):
    # This also rejects floats, unsafe ints and invalid scalar sequences.
    canonical_bytes(value)
    validator(name).validate(value)


def strict_request(name, raw):
    if len(raw.encode('utf-8')) > 262144:
        raise ValueError('raw request too large')
    value = loads(raw)
    encoded = canonical_bytes(value)
    if len(encoded) > MAX_BODY:
        raise ValueError('canonical request too large')
    schema_validate(name, value)
    return value


def validate_definition(value, issued_at, supported_zones):
    schema_validate('schedule-definition', value)
    issued = instant(issued_at)
    expiry = instant(value['authorization_expires_at'])
    if not timedelta() < expiry - issued <= timedelta(seconds=MAX_GRANT_SECONDS):
        raise ValueError('authorization duration')
    if value['timezone'] not in supported_zones:
        raise ValueError('unsupported exact timezone')
    try:
        ZoneInfo(value['timezone'])
    except ZoneInfoNotFoundError as exc:
        raise ValueError('unsupported fixture zone') from exc
    days = value['weekdays']
    if days != sorted(days):
        raise ValueError('unsorted weekdays')
    if value['recurrence'] == 'WEEKLY' and days != [date.fromisoformat(value['start_date']).isoweekday()]:
        raise ValueError('weekly start day mismatch')
    if len(canonical_bytes(value)) > MAX_BODY:
        raise ValueError('definition too large')
    slots = occurrences(value, issued)
    if not slots:
        raise ValueError('empty recurrence')
    return slots


def _candidates(local, zone):
    """Round-trip detects gaps; duplicate fold values are one actual instant."""
    result = set()
    for fold in (0, 1):
        aware = local.replace(tzinfo=zone, fold=fold)
        utc = aware.astimezone(UTC)
        if utc.astimezone(zone).replace(tzinfo=None) == local:
            result.add(utc)
    return sorted(result)


def resolve_local(local, timezone_id):
    if local.tzinfo is not None or local.microsecond:
        raise ValueError('local second precision required')
    zone = ZoneInfo(timezone_id)
    candidates = _candidates(local, zone)
    adjustment = 'NONE'
    if len(candidates) > 1:
        adjustment = 'FOLD_FIRST'
    elif not candidates:
        # Fixture oracle supports gaps <=48h; find the first valid local second,
        # not local + gap length. Java runtime uses ZoneRules transitions instead.
        lo, hi = 0, 48 * 3600
        if not _candidates(local + timedelta(seconds=hi), zone):
            raise ValueError('unsupported time gap')
        while lo + 1 < hi:
            mid = (lo + hi) // 2
            if _candidates(local + timedelta(seconds=mid), zone):
                hi = mid
            else:
                lo = mid
        local += timedelta(seconds=hi)
        candidates = _candidates(local, zone)
        adjustment = 'GAP_FORWARD'
    utc = candidates[0]
    resolved = utc.astimezone(zone)
    return {'planned_fire_at_utc': timestamp(utc),
            'local_datetime': local_timestamp(resolved),
            'offset_seconds': int(resolved.utcoffset().total_seconds()),
            'dst_adjustment': adjustment}


def occurrences(definition, after):
    """Enumerate <=34 local dates regardless of an ancient nominal start date."""
    if after.tzinfo != UTC:
        raise ValueError('explicit UTC clock required')
    zone = ZoneInfo(definition['timezone'])
    start = date.fromisoformat(definition['start_date'])
    expires = instant(definition['authorization_expires_at'])
    if not timedelta() < expires - after <= timedelta(seconds=MAX_GRANT_SECONDS):
        raise ValueError('bounded enumeration requires live <=30-day grant')
    kind = definition['recurrence']
    lower = start if kind == 'ONCE' else max(start, after.astimezone(zone).date())
    end = expires.astimezone(zone).date()
    if kind == 'ONCE':
        dates = [lower]
    else:
        count = (end - lower).days + 1
        if count > 34:
            raise ValueError('local-date enumeration overflow')
        dates = [lower + timedelta(days=i) for i in range(max(0, count))]
    result, seen = [], set()
    for day in dates:
        if kind == 'WEEKDAYS' and day.isoweekday() > 5:
            continue
        if kind in ('WEEKLY', 'CUSTOM_DAYS') and day.isoweekday() not in definition['weekdays']:
            continue
        local = datetime.combine(day, time.fromisoformat(definition['local_time']))
        slot = resolve_local(local, definition['timezone'])
        when = instant(slot['planned_fire_at_utc'])
        if after < when < expires and when not in seen:
            result.append(slot)
            seen.add(when)
            if len(result) == definition['max_deliveries']:
                break
    return result


def validate_preparation(value, supported_zones):
    schema_validate('schedule-preparation', value)
    slots = validate_definition(value['definition'], value['issued_at'], supported_zones)
    if instant(value['expires_at']) - instant(value['issued_at']) != timedelta(seconds=300):
        raise ValueError('preparation TTL')
    if value['next_occurrence'] != slots[0] or value['planned_delivery_count'] != len(slots):
        raise ValueError('immutable preview mismatch')
    return value


def preparation_binding(preparation, owner_id, session_hash, security_epoch):
    snapshot = deepcopy(preparation)
    snapshot.pop('payload_hash')
    payload = {'revision': 'schedules-inbox-v1', 'owner_id': owner_id,
               'session_hash': session_hash, 'security_epoch': security_epoch,
               'preparation': snapshot}
    schema_validate('reminder-preparation-binding', payload)
    return payload


def keyed_digest(vault_key, owner_id, purpose, value):
    if len(vault_key) != 32 or purpose not in ('preparation', 'mutation'):
        raise ValueError('invalid key/domain')
    derived = hmac.new(vault_key, b'jarvis:schedules-inbox:v1', hashlib.sha256).digest()
    data = owner_id.encode() + b'|' + purpose.encode() + b'|' + canonical_bytes(value)
    return hmac.new(derived, data, hashlib.sha256).hexdigest()


def lateness_status(planned, now, expires):
    """Display integer and precise grace decision are deliberately separate."""
    if now < planned:
        return ('NOT_DUE', None)
    elapsed = now - planned
    micros = (elapsed.days * 86400 + elapsed.seconds) * 1000000 + elapsed.microseconds
    if now >= expires:
        return ('BLOCKED', micros // 1000000)
    if micros > 3600 * 1000000:
        return ('SKIPPED_MISSED', (micros + 999999) // 1000000)
    return ('DELIVERED', micros // 1000000)


def decode_query(pairs, *, occurrences_only=False):
    """Pairs retain duplicates that an ordinary object parser would erase."""
    result = {}
    allowed = {'limit', 'cursor'} if occurrences_only else {'limit', 'cursor', 'project_id'}
    for key, value in pairs:
        if key not in allowed or key in result or not isinstance(value, str) or not value:
            raise ValueError('invalid query parameter')
        if key == 'limit':
            if not re.fullmatch(r'[1-9][0-9]?', value):
                raise ValueError('invalid limit lexical form')
            value = int(value)
        result[key] = value
    schema_validate('schedule-occurrences-query' if occurrences_only else 'schedule-list-query', result)
    return result


def validate_notification(value):
    schema_validate('notification-record', value)
    planned, created = instant(value['planned_fire_at_utc']), instant(value['created_at'])
    elapsed = created - planned
    if not timedelta() <= elapsed <= timedelta(seconds=3600):
        raise ValueError('notification outside grace')
    if value['lateness_seconds'] != elapsed // timedelta(seconds=1):
        raise ValueError('notification lateness mismatch')
    if value['opened_at'] is not None and instant(value['opened_at']) < created:
        raise ValueError('opened before created')
    return value


def validate_occurrence(value):
    schema_validate('schedule-occurrence', value)
    if value['status'] != 'PLANNED':
        actual_elapsed = instant(value['settled_at']) - instant(value['planned_fire_at_utc'])
        elapsed = max(timedelta(), actual_elapsed)
        micros = elapsed // timedelta(microseconds=1)
        displayed = ((micros + 999999) // 1000000 if value['status'] == 'SKIPPED_MISSED'
                     else micros // 1000000)
        if displayed != value['lateness_seconds']:
            raise ValueError('occurrence lateness mismatch')
        if value['status'] == 'DELIVERED' and not timedelta() <= actual_elapsed <= timedelta(seconds=3600):
            raise ValueError('delivered outside grace')
        if value['status'] == 'SKIPPED_MISSED' and elapsed <= timedelta(seconds=3600):
            raise ValueError('skip inside grace')
    return value


def validate_schedule(value):
    schema_validate('schedule-record', value)
    if value['delivered_count'] > value['definition']['max_deliveries']:
        raise ValueError('count exceeds quota')
    if instant(value['updated_at']) < instant(value['created_at']):
        raise ValueError('updated before created')
    return value


def classify_due_slots(slots, now, expires):
    """Synthetic latest-only catch-up classification, not queue/lease execution."""
    unique = {slot['planned_fire_at_utc']: slot for slot in slots}
    due = sorted(instant(key) for key in unique if instant(key) <= now)
    result = []
    latest_eligible = next((when for when in reversed(due)
                            if lateness_status(when, now, expires)[0] == 'DELIVERED'), None)
    for when in due:
        status, lateness = lateness_status(when, now, expires)
        reason = 'AUTHORIZATION_EXPIRED' if status == 'BLOCKED' else None
        if status == 'DELIVERED' and when != latest_eligible:
            status, reason = 'CANCELLED', 'SUPERSEDED_MISSED'
        result.append({'planned_fire_at_utc': timestamp(when), 'status': status,
                       'reason_code': reason, 'lateness_seconds': lateness})
    return result
