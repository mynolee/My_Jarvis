"""Data-only synthetic fixtures; runtime security needs separate integration tests."""
from copy import deepcopy
from datetime import datetime, timedelta
import hashlib
import hmac
import json
from pathlib import Path
import unittest
from unittest.mock import patch
from jsonschema.exceptions import ValidationError
from reminder_reference import (
    ROOT, UTC, canonical_bytes, loads, validator, schema_validate, strict_request,
    instant, timestamp, validate_definition, resolve_local, occurrences,
    validate_preparation, preparation_binding, keyed_digest, lateness_status,
    decode_query, validate_notification, validate_occurrence, validate_schedule, classify_due_slots,
)

FIXTURES = ROOT / 'fixtures/schedules-inbox'
ZONES = {'UTC', 'Asia/Seoul', 'America/New_York', 'Australia/Lord_Howe', 'Pacific/Apia', 'Etc/UTC'}


class SchedulesInboxContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Invalid wire examples deliberately include unsafe integers.
        cls.cases = json.loads((FIXTURES / 'cases.json').read_text())
        cls.samples = {c['name']: c['instance'] for c in cls.cases}
        cls.schemas = {}
        for prefix in ('schedule-', 'notification-', 'reminder-'):
            for path in (ROOT / 'schemas').glob(prefix + '*.schema.json'):
                cls.schemas[path.stem.removesuffix('.schema')] = validator(path.stem.removesuffix('.schema'))
        cls.vectors = loads((FIXTURES / 'canonical-vectors.json').read_text())
        cls.preparation = cls.samples['preparation-create']
        cls.definition = cls.samples['definition-daily']

    def test_all_schemas_have_fixtures_and_validate(self):
        self.assertEqual(len(self.schemas), 23)
        self.assertEqual(set(self.schemas), {c['schema'] for c in self.cases})
        for case in self.cases:
            with self.subTest(case=case['name']):
                self.assertEqual(self.schemas[case['schema']].is_valid(case['instance']), case['valid'])

    def test_every_object_closed_at_every_depth(self):
        def walk(node):
            if isinstance(node, dict):
                if node.get('type') == 'object':
                    self.assertIs(node.get('additionalProperties'), False)
                for child in node.values():
                    walk(child)
            elif isinstance(node, list):
                for child in node:
                    walk(child)
        for schema in self.schemas.values():
            walk(schema.schema)

    def test_required_fields_cannot_disappear(self):
        for case in self.cases:
            if not case['valid'] or not isinstance(case['instance'], dict) or case['schema'].endswith('query'):
                continue
            for key in case['instance']:
                value = deepcopy(case['instance']);value.pop(key)
                with self.subTest(case=case['name'],missing=key):
                    self.assertFalse(self.schemas[case['schema']].is_valid(value))

    def test_client_authority_and_destination_fields_rejected(self):
        for name, schema in [('prepare-create','schedule-prepare-request'),('confirm','schedule-confirm-request'),('disable','schedule-disable-request'),('open','notification-open-request')]:
            for field in ['owner_id','status','verified','grant_id','tool_id','provider','callback_url','destination','session_hash','security_epoch','approved','risk','notifications','model_id']:
                self.assertFalse(self.schemas[schema].is_valid({**self.samples[name],field:True}), (name,field))
        for field in ['owner_id','grant','destination','url','callback','attendees','allowed_tool_ids','provider_credentials']:
            self.assertFalse(self.schemas['schedule-definition'].is_valid({**self.definition,field:'synthetic'}))

    def test_exact_own_inbox_and_no_provider_delivery_labels(self):
        for target in ['mailto:sample@example.invalid','PUSH','EMAIL','OTHER_OWNER','INTERNAL_INBOX','https://example.invalid/callback',None]:
            self.assertFalse(self.schemas['schedule-preparation'].is_valid({**self.preparation,'destination':target}))
        for label in ['SENT','RECEIVED','PROVIDER_ACCEPTED','DELIVERED',None]:
            self.assertFalse(self.schemas['notification-record'].is_valid({**self.samples['notification-created'],'status':label}))

    def test_strict_integer_json_and_duplicate_keys(self):
        for case in loads((FIXTURES / 'strict-json-cases.json').read_text()):
            with self.subTest(case=case['name']), self.assertRaises((ValueError,UnicodeError)):
                loads(case['raw'])
        for raw in ['{"expected_version":true}','{"expected_version":null}','{"expected_version":0}','{"expected_version":-1}','{"expected_version":"1"}']:
            with self.assertRaises((ValueError,ValidationError)):
                strict_request('schedule-disable-request',raw)
        self.assertEqual(strict_request('schedule-disable-request','{"expected_version":9007199254740991}')['expected_version'],9007199254740991)

    def test_uuid_hash_and_integer_wire_domains(self):
        key = self.samples['headers']['Idempotency-Key']
        for value in [key+'\n',key.upper().replace('0','A',1),'1-1-1-1-1','urn:uuid:'+key,'{'+key+'}',None,True]:
            self.assertFalse(self.schemas['schedule-mutation-headers'].is_valid({'Idempotency-Key':value}))
        for value in ['a'*63,'a'*65,'A'*64,'g'*64,'0'*64+'\n',None,1]:
            self.assertFalse(self.schemas['schedule-confirm-request'].is_valid({**self.samples['confirm'],'payload_hash':value}))
        for value in [True,False,0,-1,9007199254740992,None,'1']:
            self.assertFalse(self.schemas['schedule-disable-request'].is_valid({'expected_version':value}))

    def test_unicode_scalar_control_nonblank_and_byte_limits(self):
        schema = self.schemas['schedule-definition']
        for field,limit in [('title',200),('text',2000)]:
            self.assertTrue(schema.is_valid({**self.definition,field:'😀'*limit}))
            self.assertFalse(schema.is_valid({**self.definition,field:'😀'*(limit+1)}))
            for text in ['', '\u00a0\ufeff ', '\ud800', '\udfff']:
                self.assertFalse(schema.is_valid({**self.definition,field:text}))
            for cp in list(range(32))+list(range(127,160)):
                self.assertFalse(schema.is_valid({**self.definition,field:'x'+chr(cp)}),cp)
        raw=json.dumps({**self.samples['prepare-create'],'definition':{**self.definition,'title':'😀'*200,'text':'😀'*2000}},ensure_ascii=False)
        self.assertLessEqual(len(canonical_bytes(strict_request('schedule-prepare-request',raw))),16384)

    def test_no_trimming_normalization_or_html_interpretation(self):
        for content in ['  exact spaces  ','é','e\u0301','<script>alert(1)</script>','https://example.invalid/?callback=literal']:
            value={**self.definition,'text':content}
            schema_validate('schedule-definition',value)
            self.assertEqual(loads(canonical_bytes(value).decode())['text'],content)
        self.assertNotEqual(canonical_bytes({**self.definition,'text':'é'}),canonical_bytes({**self.definition,'text':'e\u0301'}))

    def test_raw_and_canonical_byte_ceilings_are_distinct(self):
        # Whitespace and escape expansions count toward transport ceiling only.
        with self.assertRaises(ValueError):
            strict_request('schedule-disable-request',' '*262144+'{"expected_version":1}')
        with self.assertRaises(ValueError):
            strict_request('schedule-prepare-request',json.dumps({'padding':'x'*17000}))
        self.assertEqual(strict_request('schedule-disable-request',' '*10000+'{"expected_version":1}'),{'expected_version':1})

    def test_real_dates_minute_time_and_utc_lexical_domain(self):
        for value in ['0000-01-01','2030-02-29','2032-02-30','2030-1-01','2030-01-01\n']:
            self.assertFalse(self.schemas['schedule-definition'].is_valid({**self.definition,'start_date':value}))
        for value in ['9:00','24:00','09:60','09:00:00','09:00Z','09:00\n',None]:
            self.assertFalse(self.schemas['schedule-definition'].is_valid({**self.definition,'local_time':value}))
        for value in ['2030-01-01T00:00:00+00:00','2030-01-01t00:00:00Z','2030-01-01T00:00:00z','2030-01-01T00:00:60Z','2030-01-01T00:00:00.1234567Z','0000-01-01T00:00:00Z','2030-02-29T00:00:00Z','2030-01-01T00:00:00Z\n']:
            self.assertFalse(self.schemas['schedule-definition'].is_valid({**self.definition,'authorization_expires_at':value}),value)
        for value in ['2030-01-01T00:00:00Z','2030-01-01T00:00:00.1Z','2030-01-01T00:00:00.123456Z']:
            self.assertTrue(self.schemas['schedule-definition'].is_valid({**self.definition,'authorization_expires_at':value}))

    def test_timezone_shape_does_not_prove_exact_server_membership(self):
        issued=self.preparation['issued_at']
        for value in ['GMT','EST','Z','+09:00','UTC+09:00','SystemV/EST5','Asia/Seoul\n']:
            self.assertFalse(self.schemas['schedule-definition'].is_valid({**self.definition,'timezone':value}))
        for value in ['NotAReal/Zone','asia/seoul','Asia/Tokyo']:
            with self.assertRaises(ValueError):
                validate_definition({**self.definition,'timezone':value},issued,ZONES)
        self.assertTrue(self.schemas['schedule-definition'].is_valid({**self.definition,'timezone':'NotAReal/Zone'}))

    def test_recurrence_days_sorted_unique_kind_consistent(self):
        for kind,days,valid in [('ONCE',[],True),('ONCE',[1],False),('DAILY',[],True),('WEEKDAYS',[1,2,3,4,5],False),('WEEKLY',[2],True),('WEEKLY',[1],False),('WEEKLY',[2,3],False),('CUSTOM_DAYS',[1,3,7],True),('CUSTOM_DAYS',[7,1],False),('CUSTOM_DAYS',[],False),('CUSTOM_DAYS',[1,1],False)]:
            value={**self.definition,'recurrence':kind,'weekdays':days,'max_deliveries':1 if kind=='ONCE' else 31}
            with self.subTest(kind=kind,days=days):
                if valid:
                    self.assertTrue(validate_definition(value,self.preparation['issued_at'],ZONES))
                else:
                    with self.assertRaises((ValueError,ValidationError)):
                        validate_definition(value,self.preparation['issued_at'],ZONES)
        for value in [0,8,4294967297,True,1.0,'1']:
            with self.assertRaises((ValueError,ValidationError)):
                validate_definition({**self.definition,'recurrence':'CUSTOM_DAYS','weekdays':[value]},self.preparation['issued_at'],ZONES)

    def test_authorization_thirty_days_exclusive_expiry_and_delivery_bounds(self):
        issued=instant(self.preparation['issued_at'])
        for delta in [0,-1,2592000.000001]:
            value={**self.definition,'authorization_expires_at':timestamp(issued+timedelta(seconds=delta))}
            with self.assertRaises(ValueError):
                validate_definition(value,self.preparation['issued_at'],ZONES)
        valid=validate_definition(self.definition,self.preparation['issued_at'],ZONES)
        self.assertEqual(len(valid),30)
        for count in [0,32,True,None]:
            with self.assertRaises((ValueError,ValidationError)):
                validate_definition({**self.definition,'max_deliveries':count},self.preparation['issued_at'],ZONES)
        # The only candidate exactly at expiry is not authorized.
        with self.assertRaises(ValueError):
            validate_definition({**self.definition,'recurrence':'ONCE','max_deliveries':1,'authorization_expires_at':'2030-01-01T00:00:00Z'},self.preparation['issued_at'],ZONES)

    def test_ancient_start_date_enumerates_only_bounded_window(self):
        value={**self.definition,'start_date':'0001-01-01'}
        with patch('reminder_reference.resolve_local',wraps=resolve_local) as resolve:
            slots=validate_definition(value,self.preparation['issued_at'],ZONES)
            self.assertLessEqual(resolve.call_count,34)
            self.assertEqual(len(slots),30)

    def test_frozen_utc_dst_nonhour_and_date_skip_vectors(self):
        for vector in loads((FIXTURES/'recurrence-vectors.json').read_text()):
            with self.subTest(vector=vector['name']):
                self.assertEqual(resolve_local(datetime.fromisoformat(vector['local']),vector['timezone']),vector['expected'])
                schema_validate('schedule-occurrence-preview',vector['expected'])

    def test_daily_gap_returns_to_original_wall_time_next_day(self):
        d={**self.definition,'start_date':'2030-03-09','local_time':'02:30','timezone':'America/New_York','authorization_expires_at':'2030-03-12T00:00:00Z'}
        slots=occurrences(d,instant('2030-03-09T00:00:00Z'))
        self.assertEqual([s['local_datetime'] for s in slots],['2030-03-09T02:30:00','2030-03-10T03:00:00','2030-03-11T02:30:00'])
        self.assertEqual([s['planned_fire_at_utc'] for s in slots],['2030-03-09T07:30:00Z','2030-03-10T07:00:00Z','2030-03-11T06:30:00Z'])

    def test_fold_selects_first_once_and_does_not_add_twenty_four_hours(self):
        d={**self.definition,'start_date':'2030-11-03','local_time':'01:30','timezone':'America/New_York','authorization_expires_at':'2030-11-05T12:00:00Z'}
        slots=occurrences(d,instant('2030-11-03T00:00:00Z'))
        self.assertEqual([s['planned_fire_at_utc'] for s in slots],['2030-11-03T05:30:00Z','2030-11-04T06:30:00Z','2030-11-05T06:30:00Z'])

    def test_date_skip_deduplicates_same_resolved_utc_instant(self):
        d={**self.definition,'start_date':'2011-12-29','local_time':'00:00','timezone':'Pacific/Apia','authorization_expires_at':'2012-01-02T00:00:00Z'}
        slots=occurrences(d,instant('2011-12-29T00:00:00Z'))
        times=[s['planned_fire_at_utc'] for s in slots]
        self.assertEqual(len(times),len(set(times)))
        self.assertEqual(times.count('2011-12-30T10:00:00Z'),1)

    def test_weekdays_custom_days_leap_month_and_year_transitions(self):
        for kind,days,expected in [('WEEKDAYS',[],[1,2,3,4,5]),('CUSTOM_DAYS',[2,7],[2,7])]:
            d={**self.definition,'recurrence':kind,'weekdays':days,'start_date':'2031-12-29','timezone':'UTC','authorization_expires_at':'2032-01-06T00:00:00Z'}
            slots=occurrences(d,instant('2031-12-28T23:00:00Z'))
            self.assertTrue(slots)
            self.assertTrue(all(datetime.fromisoformat(s['local_datetime']).isoweekday() in expected for s in slots))
        d={**self.definition,'start_date':'2032-02-28','timezone':'UTC','authorization_expires_at':'2032-03-02T00:00:00Z'}
        self.assertEqual([s['local_datetime'][:10] for s in occurrences(d,instant('2032-02-28T00:00:00Z'))],['2032-02-28','2032-02-29','2032-03-01'])

    def test_prep_exact_immutable_snapshot_ttl_count_and_first_occurrence(self):
        validate_preparation(self.preparation,ZONES)
        for field,value in [('planned_delivery_count',31),('expires_at','2029-12-31T23:05:00.000001Z'),('next_occurrence',{**self.preparation['next_occurrence'],'offset_seconds':0})]:
            with self.assertRaises(ValueError):
                validate_preparation({**self.preparation,field:value},ZONES)
        with self.assertRaises(ValueError):
            validate_preparation({**self.preparation,'definition':{**self.definition,'timezone':'UTC'}},ZONES)

    def test_canonical_hmac_fixed_vectors_independent_hmac_recomputation(self):
        key=bytes.fromhex(self.vectors['test_vault_key_hex'])
        self.assertEqual(key,bytes(32))
        for v in self.vectors['vectors']:
            with self.subTest(vector=v['name']):
                encoded=canonical_bytes(v['payload'])
                self.assertEqual(encoded.decode(),v['canonical'])
                self.assertEqual(encoded.hex(),v['utf8_hex'])
                derived=hmac.digest(key,b'jarvis:schedules-inbox:v1','sha256')
                expected=hmac.digest(derived,(v['owner_id']+'|'+v['purpose']+'|').encode()+encoded,'sha256').hex()
                self.assertEqual(expected,v['commitment'])
                self.assertEqual(keyed_digest(key,v['owner_id'],v['purpose'],v['payload']),v['commitment'])
                schema_validate('reminder-'+v['purpose']+'-binding',v['payload'])

    def test_hash_binds_owner_session_epoch_destination_definition_and_limits(self):
        vector=self.vectors['vectors'][0];original=vector['payload']
        mutations=[('owner_id',self.samples['notification-created']['id']),('session_hash','a'*64),('security_epoch',2)]
        for key,value in mutations:
            payload=deepcopy(original);payload[key]=value
            self.assertNotEqual(keyed_digest(bytes(32),vector['owner_id'],'preparation',payload),vector['commitment'])
        for field,value in [('target_id',self.samples['notification-created']['id']),('version',2),('operation','REPLACE'),('expected_version',1),('destination','EMAIL'),('planned_delivery_count',1),('max_lateness_seconds',3601),('expires_at','2029-12-31T23:04:00Z')]:
            payload=deepcopy(original);payload['preparation'][field]=value
            self.assertNotEqual(keyed_digest(bytes(32),vector['owner_id'],'preparation',payload),vector['commitment'])
        for field,value in [('title','Another'),('text','PRIVATE_SENTINEL'),('project_id',self.samples['notification-created']['id']),('timezone','UTC'),('recurrence','WEEKDAYS'),('weekdays',[1]),('authorization_expires_at','2030-01-30T00:00:00Z'),('max_deliveries',1),('start_date','2030-01-02'),('local_time','09:01')]:
            payload=deepcopy(original);payload['preparation']['definition'][field]=value
            self.assertNotEqual(keyed_digest(bytes(32),vector['owner_id'],'preparation',payload),vector['commitment'])

    def test_hash_domain_route_target_and_canonical_order_binding(self):
        vector=self.vectors['vectors'][2];payload=vector['payload']
        reordered=dict(reversed(list(payload.items())))
        self.assertEqual(keyed_digest(bytes(32),vector['owner_id'],'mutation',reordered),vector['commitment'])
        self.assertNotEqual(keyed_digest(bytes(32),vector['owner_id'],'preparation',payload),vector['commitment'])
        self.assertNotEqual(keyed_digest(b'1'*32,vector['owner_id'],'mutation',payload),vector['commitment'])
        for patch_value in [{'operation':'REPLACE'},{'target_id':self.samples['notification-created']['id']},{'body':{**payload['body'],'confirm':False}}]:
            self.assertNotEqual(keyed_digest(bytes(32),vector['owner_id'],'mutation',{**payload,**patch_value}),vector['commitment'])
        self.assertNotEqual(hashlib.sha256(canonical_bytes(payload)).hexdigest(),vector['commitment'])

    def test_metadata_receipts_cannot_echo_plaintext_or_hash(self):
        for name in ['receipt-committed','receipt-not-observed']:
            receipt=self.samples[name]
            self.assertEqual(set(receipt),{'status','schedule_id','version','operation'})
            for field in ['definition','text','title','project_id','payload_hash','request_digest','session_hash','body','arguments','error','previous_content']:
                self.assertFalse(self.schemas['schedule-mutation-receipt'].is_valid({**receipt,field:'PRIVATE_SENTINEL'}))
            self.assertNotIn('PRIVATE_SENTINEL',canonical_bytes(receipt).decode())
        self.assertFalse(self.schemas['schedule-mutation-receipt'].is_valid({**self.samples['receipt-not-observed'],'schedule_id':self.samples['receipt-committed']['schedule_id']}))

    def test_history_cannot_expose_reminder_content(self):
        for name in ['occurrence-planned','occurrence-delivered','occurrence-skipped','occurrence-blocked','occurrence-cancelled']:
            validate_occurrence(self.samples[name])
            for field in ['title','text','definition','payload_hash','session_hash','project_id']:
                self.assertFalse(self.schemas['schedule-occurrence'].is_valid({**self.samples[name],field:'PRIVATE_SENTINEL'}))

    def test_precise_lateness_3599_3600_3601_and_fraction_boundary(self):
        planned=instant('2030-01-01T00:00:00Z');expiry=planned+timedelta(days=1)
        for elapsed,status,display in [(-1,'NOT_DUE',None),(0,'DELIVERED',0),(3599,'DELIVERED',3599),(3600,'DELIVERED',3600),(3600.000001,'SKIPPED_MISSED',3601),(3601,'SKIPPED_MISSED',3601)]:
            self.assertEqual(lateness_status(planned,planned+timedelta(seconds=elapsed),expiry),(status,display))
        self.assertEqual(lateness_status(planned,planned+timedelta(seconds=1),planned+timedelta(seconds=1)),('BLOCKED',1))

    def test_notification_open_is_explicit_and_timestamp_consistent(self):
        validate_notification(self.samples['notification-created'])
        validate_notification(self.samples['notification-opened'])
        schema_validate('notification-open-request',{})
        for value in [None,[],{'status':'OPENED'},{'opened_at':'2030-01-01T00:03:00Z'},{'owner_id':'synthetic'}]:
            self.assertFalse(self.schemas['notification-open-request'].is_valid(value))
        for patch_value in [{'opened_at':'2029-12-31T23:59:59Z','status':'OPENED'},{'lateness_seconds':0},{'created_at':'2030-01-01T01:00:00.000001Z','lateness_seconds':3600}]:
            with self.assertRaises(ValueError):
                validate_notification({**self.samples['notification-created'],**patch_value})

    def test_terminal_history_uses_exact_elapsed_duration(self):
        delivered=self.samples['occurrence-delivered']
        for patch_value in [{'settled_at':'2029-12-31T23:59:59Z','lateness_seconds':0},{'settled_at':'2030-01-01T01:00:00.000001Z','lateness_seconds':3600}]:
            with self.assertRaises(ValueError):
                validate_occurrence({**delivered,**patch_value})
        skipped={**self.samples['occurrence-skipped'],'settled_at':'2030-01-01T01:00:00.000001Z'}
        validate_occurrence(skipped)
        with self.assertRaises(ValueError):
            validate_occurrence({**skipped,'settled_at':'2030-01-01T01:00:00Z'})

    def test_schedule_count_and_timestamps_consistent(self):
        validate_schedule(self.samples['schedule-enabled'])
        validate_schedule(self.samples['schedule-disabled'])
        for patch_value in [{'definition':{**self.definition,'max_deliveries':1},'delivered_count':2},{'updated_at':'2029-12-30T00:00:00Z'}]:
            with self.assertRaises(ValueError):
                validate_schedule({**self.samples['schedule-enabled'],**patch_value})

    def test_cursor_query_bounds_unknown_duplicate_and_filter_types(self):
        self.assertEqual(decode_query([]),{})
        self.assertEqual(decode_query([('limit','50'),('cursor','abc.def')]),{'limit':50,'cursor':'abc.def'})
        for pairs in [[('limit','0')],[('limit','51')],[('limit','01')],[('limit','1.0')],[('limit','+1')],[('limit','١')],[('limit','1'),('limit','2')],[('cursor','')],[('cursor','x'*1025)],[('cursor','a\nb')],[('cursor','a b')],[('project_id','null')],[('owner_id','a')],[('sort','created_at')]]:
            with self.assertRaises((ValueError,ValidationError)):
                decode_query(pairs)
        with self.assertRaises(ValueError):
            decode_query([('project_id',self.samples['schedule-enabled']['id'])],occurrences_only=True)
        self.assertTrue(self.schemas['schedule-list'].is_valid({'items':[],'next_cursor':None}))
        self.assertFalse(self.schemas['schedule-list'].is_valid({'items':[self.samples['schedule-enabled']]*51,'next_cursor':None}))

    def test_latest_only_catchup_retains_superseded_and_old_slot_metadata(self):
        for vector in loads((FIXTURES/'catch-up-vectors.json').read_text()):
            slots=occurrences(vector['definition'],instant(vector['issued_at']))
            result=classify_due_slots(slots,instant(vector['now']),instant(vector['definition']['authorization_expires_at']))
            self.assertEqual(result,vector['expected'])
            self.assertEqual(sum(row['status']=='DELIVERED' for row in result),1)
            # Repeated input slots do not become extra effects.
            self.assertEqual(classify_due_slots(slots+slots,instant(vector['now']),instant(vector['definition']['authorization_expires_at'])),result)
        validate_occurrence(self.samples['occurrence-superseded'])

    def test_limits_and_boundary_flags_are_explicit(self):
        limits=loads((FIXTURES/'implementation-limits.json').read_text())
        self.assertEqual((limits['authorization_seconds'],limits['max_deliveries'],limits['max_enabled_schedules']),(2592000,31,100))
        self.assertEqual((limits['max_lateness_seconds'],limits['preparation_ttl_seconds'],limits['poll_seconds']),(3600,300,5))
        self.assertTrue(all(limits[k] for k in ['no_external_delivery','no_model_execution','metadata_only_receipts','pause_requires_reconfirmation']))


if __name__ == '__main__':
    unittest.main()
