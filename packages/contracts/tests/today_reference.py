"""Synthetic Today contract/reference checks, not runtime authorization evidence."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
import sys
import re
from jsonschema import Draft202012Validator, FormatChecker
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import canonical_bytes, loads
PRIORITY = {'HIGH': 0, 'NORMAL': 1, 'LOW': 2}
FIELDS = ('id', 'project_id', 'title', 'priority', 'status', 'due_at', 'version')
CHECKER = FormatChecker()
@CHECKER.checks('date-time', raises=(ValueError, TypeError, OverflowError))
def valid_time(s):
    if not isinstance(s, str): return True
    if not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z', s): return False
    datetime.fromisoformat(s)
    return True
def instant(s): return datetime.fromisoformat(s)
def utc(d): return d.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')
def validator(name):
    schema = loads((ROOT/'schemas'/f'today-{name}.schema.json').read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=CHECKER)
def bounds(checked_at, zone):
    local = instant(checked_at).astimezone(ZoneInfo(zone))
    start = datetime.combine(local.date(), datetime.min.time(), local.tzinfo)
    end = datetime.combine(local.date()+timedelta(days=1), datetime.min.time(), local.tzinfo)
    return local.date().isoformat(), utc(start), utc(end)
def top_key(t, now):
    due = instant(t['due_at']) if t['due_at'] else datetime.max.replace(tzinfo=timezone.utc)
    return (not (t['due_at'] and due < now), PRIORITY[t['priority']], due, t['id'])
def deadline_key(t): return (instant(t['due_at']), PRIORITY[t['priority']], t['id'])
def summarize(tasks, checked_at, zone):
    date, start, end = bounds(checked_at, zone)
    open_tasks = [{k:t[k] for k in FIELDS} for t in tasks if t['status'] in ('TODO','IN_PROGRESS')]
    deadlines = sorted((t for t in open_tasks if t['due_at'] and instant(t['due_at']) < instant(end)), key=deadline_key)
    return {'checked_at':checked_at,'timezone':zone,'local_date':date,'day_start_at':start,'next_day_start_at':end,'deadline_window_end_at':end,
        'tasks':{'status':'AVAILABLE','top_tasks':sorted(open_tasks,key=lambda t:top_key(t,instant(checked_at)))[:3],'deadlines':deadlines[:20],'deadline_total':len(deadlines),'deadlines_truncated':len(deadlines)>20},
        **{k:{'status':'UNAVAILABLE','reason':'NOT_IMPLEMENTED'} for k in ('calendar','briefing','device')}}
def valid(name, value):
    try:
        canonical_bytes(value)
        if not validator(name).is_valid(value): return False
        if name == 'task-summary':
            return bool(value['title'].strip('\t\n\v\f\r \x85\u00a0\u1680'+''.join(map(chr,range(0x2000,0x200b)))+'\u2028\u2029\u202f\u205f\u3000\ufeff'))
        if name == 'response':
            date,start,end = bounds(value['checked_at'],value['timezone'])
            if (value['local_date'],instant(value['day_start_at']),instant(value['next_day_start_at']),instant(value['deadline_window_end_at'])) != (date,instant(start),instant(end),instant(end)): return False
            now = instant(value['checked_at'])
            if not instant(start) <= now < instant(end): return False
            t = value['tasks']; top=t['top_tasks']; deadlines=t['deadlines']; n=t['deadline_total']
            if len(deadlines) != min(n,20) or t['deadlines_truncated'] != (n>20): return False
            seen = {}
            for items in (top,deadlines):
                if len({x['id'] for x in items}) != len(items): return False
                for item in items:
                    if not valid('task-summary',item): return False
                    if item['id'] in seen and seen[item['id']] != item: return False
                    seen[item['id']] = item
            if top != sorted(top,key=lambda x:top_key(x,now)): return False
            if deadlines != sorted(deadlines,key=deadline_key): return False
            if any(x['due_at'] is None or instant(x['due_at']) >= instant(end) for x in deadlines): return False
        return True
    except (ValueError,TypeError,KeyError,OverflowError,ZoneInfoNotFoundError): return False
