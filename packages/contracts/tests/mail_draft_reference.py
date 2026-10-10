"""Synthetic wire/reference checks only; not server authorization or crypto proof."""
import base64
from datetime import datetime
import hashlib
import hmac
import json
from pathlib import Path
import re
import sys
from jsonschema import Draft202012Validator, FormatChecker
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import canonical_bytes, loads
CHECKER = FormatChecker()
@CHECKER.checks('date-time', raises=(ValueError, TypeError, OverflowError))
def utc(value):
    if not isinstance(value, str): return True
    if not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z', value): return False
    datetime.fromisoformat(value)
    return True

def validator(name):
    schema = loads((ROOT / 'schemas' / f'mail-draft-{name}.schema.json').read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=CHECKER)

def valid(name, value):
    try:
        encoded = canonical_bytes(value)  # Strict integer-only JSON and Unicode scalars.
        if not validator(name).is_valid(value): return False
        if name in ('create-request', 'patch-request', 'record'):
            if name != 'record' and len(encoded) > 65536: return False
            if sum(len(value.get(k, [])) for k in ('to', 'cc', 'bcc')) > 30: return False
            for k, ceiling in [('sender_candidate',1280),('subject',800),('body',64000)]:
                if value.get(k) is not None and len(value[k].encode('utf-8')) > ceiling: return False
            if any(len(s.encode('utf-8'))>1280 for k in ('to','cc','bcc') for s in value.get(k, [])): return False
        if name in ('record','metadata'):
            if datetime.fromisoformat(value['updated_at']) < datetime.fromisoformat(value['created_at']): return False
        if name == 'mutation-result':
            d,m=value['draft'],value['mutation']
            return valid('record',d) and m['status']=='COMMITTED' and m['draft_id']==d['id'] and m['version']<=d['version']
        if name == 'list':
            ids=[x['id'] for x in value['items']]
            if len(ids)!=len(set(ids)) or value['next_cursor'] is not None and not ids: return False
            if not all(valid('metadata',x) for x in value['items']): return False
            order=[(datetime.fromisoformat(x['created_at']),x['id']) for x in value['items']]
            return order == sorted(order,reverse=True)
        return True
    except (ValueError, TypeError, KeyError, UnicodeError): return False

def mac(key, data): return hmac.new(key,data,hashlib.sha256).digest()
def request_digest(raw, owner, method, target, body):
    key=mac(raw,b'jarvis:mail-drafts:request:v1')
    return mac(key,canonical_bytes({'owner_id':owner,'method':method,'target':target,'body':body})).hex()
def b64(data): return base64.urlsafe_b64encode(data).rstrip(b'=').decode('ascii')
def cursor_token(raw, owner, project, created, ident):
    key=mac(raw,b'jarvis:mail-drafts:cursor:v1')
    payload=canonical_bytes({'owner_id':owner,'project_id':project,'created_at':created,'id':ident})
    return b64(payload)+'.'+b64(mac(key,payload))

def valid_merged_patch(editable, patch):
    if not valid('patch-request',patch): return False
    merged={**editable,**{k:v for k,v in patch.items() if k!='expected_version'}}
    return valid('create-request',merged)
