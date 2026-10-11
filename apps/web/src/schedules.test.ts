import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { read_occurrence_preview, read_reminder_page, read_schedule, read_schedule_definition, read_schedule_occurrence, read_schedule_preparation, read_schedule_receipt, reminder_time, same_schedule_definition, valid_reminder_body, valid_reminder_text, valid_schedule_definition } from './schedules';
import { read_notification, same_notification_content } from './notifications';
const cases = JSON.parse(readFileSync('../../packages/contracts/fixtures/schedules-inbox/cases.json', 'utf8')) as Array<{ name: string; schema: string; instance: unknown; valid: boolean }>;
const sample = (name: string): any => structuredClone(cases.find(c => c.name === name)!.instance);
const readers: Record<string, (v: unknown) => unknown> = { 'schedule-definition': read_schedule_definition, 'schedule-preparation': read_schedule_preparation, 'schedule-record': read_schedule, 'schedule-occurrence-preview': read_occurrence_preview, 'schedule-occurrence': read_schedule_occurrence, 'schedule-mutation-receipt': read_schedule_receipt, 'notification-record': read_notification, 'schedule-list': v => read_reminder_page(v, read_schedule), 'schedule-occurrences': v => read_reminder_page(v, read_schedule_occurrence), 'notification-list': v => read_reminder_page(v, read_notification) };
describe('frozen reminder/inbox contract fixtures in TypeScript', () => {
  for (const fixture of cases.filter(c => readers[c.schema])) it(`${fixture.valid ? 'accepts' : 'rejects'} ${fixture.name}`, () => { const parse = () => readers[fixture.schema](fixture.instance); if (fixture.valid) expect(parse).not.toThrow(); else expect(parse).toThrow(); });
});
describe('strict reminder boundary validation', () => {
  it('bounds codepoints, bytes, controls, scalar validity and canonical body size', () => {
    expect(valid_reminder_text('😀'.repeat(200), 200)).toBe(true);
    for (const value of [' ', '\uFEFF', '\u0000', 'line\nbreak', 'x\t', '\u0085', '\uD800', '\uDC00', '😀'.repeat(201)]) expect(valid_reminder_text(value, 200)).toBe(false);
    expect(valid_reminder_body({ text: 'x'.repeat(16384) })).toBe(false);
  });
  it('rejects impossible/offset timestamps, excessive precision and disguised IDs', () => {
    for (const value of ['2030-02-30T00:00:00Z', '2030-01-01T00:00:00+00:00', '2030-01-01t00:00:00z', '2030-01-01T00:00:60Z', '0000-01-01T00:00:00Z', '2030-01-01T00:00:00.1234567Z', '2030-01-01T00:00:00Z\n']) expect(reminder_time(value)).toBe(false);
    expect(reminder_time('0001-01-01T00:00:00.123456Z')).toBe(true);
    expect(() => read_schedule({ ...sample('schedule-enabled'), id: `${sample('schedule-enabled').id}\n` })).toThrow();
  });
  it('checks supported explicit zones, weekday ordering and once quota', () => {
    const d = sample('definition-daily'); expect(valid_schedule_definition(d, ['UTC'])).toBe(false); expect(valid_schedule_definition(d, ['Asia/Seoul', 'UTC'])).toBe(true);
    for (const timezone of ['+09:00', 'KST', 'SystemV/EST5', 'Asia/Seoul\n']) expect(valid_schedule_definition({ ...d, timezone })).toBe(false);
    for (const weekdays of [[0], [1, 1], [7, 1], [true], [1.5]]) expect(valid_schedule_definition({ ...d, recurrence: 'CUSTOM_DAYS', weekdays })).toBe(false);
    expect(valid_schedule_definition({ ...d, recurrence: 'WEEKLY', weekdays: [1] })).toBe(false);
    expect(valid_schedule_definition({ ...d, recurrence: 'WEEKLY', weekdays: [2] })).toBe(true);
    expect(same_schedule_definition(d, { ...d, text: `${d.text} ` })).toBe(false);
  });
  it('rejects altered preview authority, inconsistent local/UTC, TTL and bounded grant', () => {
    const p = sample('preparation-create');
    for (const patch of [{ destination: 'EMAIL' }, { version: 2 }, { expected_version: 1 }, { planned_delivery_count: 32 }, { max_lateness_seconds: 3601 }, { payload_hash: 'a'.repeat(64) + '\n' }, { expires_at: p.issued_at }, { definition: { ...p.definition, authorization_expires_at: '2031-01-01T00:00:00Z' } }, { next_occurrence: { ...p.next_occurrence, offset_seconds: 0 } }, { owner_id: p.id }]) expect(() => read_schedule_preparation({ ...p, ...patch })).toThrow();
  });
  it('keeps microsecond precision for immutable preparation TTL and authorization bounds', () => {
    const p = sample('preparation-create');
    expect(() => read_schedule_preparation({ ...p, expires_at: p.expires_at.replace('Z', '.000001Z') })).toThrow();
    const issued = new Date(p.issued_at).getTime(), exactEnd = new Date(issued + 2592000000).toISOString().replace('.000Z', 'Z');
    expect(() => read_schedule_preparation({ ...p, definition: { ...p.definition, authorization_expires_at: exactEnd.replace('Z', '.000001Z') } })).toThrow();
    expect(() => read_schedule_preparation({ ...p, definition: { ...p.definition, authorization_expires_at: p.next_occurrence.planned_fire_at_utc.replace('Z', '.000001Z') } })).not.toThrow();
  });
  it('rejects subsecond UTC drift against a second-precision local preview', () => {
    const p = sample('preview');
    expect(() => read_occurrence_preview({ ...p, planned_fire_at_utc: p.planned_fire_at_utc.replace('Z', '.000001Z') })).toThrow();
    expect(() => read_occurrence_preview({ ...p, planned_fire_at_utc: p.planned_fire_at_utc.replace('Z', '.000000Z') })).not.toThrow();
  });
  it('enforces full-precision delivery grace and exact reported lateness', () => {
    const n = sample('notification-created');
    expect(() => read_notification({ ...n, created_at: '2030-01-01T01:00:00.000001Z', lateness_seconds: 3600 })).toThrow();
    expect(() => read_notification({ ...n, lateness_seconds: n.lateness_seconds - 1 })).toThrow();
    const o = sample('occurrence-skipped');
    expect(() => read_schedule_occurrence({ ...o, settled_at: '2030-01-01T01:00:00.000001Z', lateness_seconds: 3601 })).not.toThrow();
    expect(() => read_schedule_occurrence({ ...o, settled_at: '2030-01-01T01:00:00Z', lateness_seconds: 3601 })).toThrow();
  });
  it('requires closed content-free receipts, safe versions and null absent fields', () => {
    const r = sample('receipt-committed');
    for (const patch of [{ text: 'secret' }, { version: true }, { version: 9007199254740992 }, { status: 'NOT_OBSERVED' }, { operation: 'ENABLE' }]) expect(() => read_schedule_receipt({ ...r, ...patch })).toThrow();
  });
  it('rejects duplicate IDs, malformed cursors and unbounded pages', () => {
    const s = sample('schedule-enabled');
    for (const page of [{ items: [s, s], next_cursor: null }, { items: [], next_cursor: '' }, { items: [], next_cursor: 'abc\n' }, { items: [], next_cursor: 'a'.repeat(1025) }, { items: Array(51).fill(s), next_cursor: null }]) expect(() => read_reminder_page(page, read_schedule)).toThrow();
  });
  it('keeps missed, blocked, cancelled and delivered evidence distinct', () => {
    const o = sample('occurrence-skipped'); expect(() => read_schedule_occurrence({ ...o, lateness_seconds: 3600 })).toThrow();
    const c = sample('occurrence-cancelled'); expect(read_schedule_occurrence({ ...c, reason_code: 'SUPERSEDED_MISSED' }).status).toBe('CANCELLED'); expect(() => read_schedule_occurrence({ ...c, status: 'BLOCKED', reason_code: 'SUPERSEDED_MISSED' })).toThrow();
  });
  it('validates literal notification content and original open timestamp consistency', () => {
    const n = sample('notification-created'), opened = { ...n, status: 'OPENED', opened_at: n.created_at };
    expect(read_notification({ ...n, text: '<img src="https://example.invalid/" onerror="alert(1)">' }).text).toContain('<img');
    expect(same_notification_content(n, opened)).toBe(true); expect(same_notification_content(n, { ...opened, title: 'Changed' })).toBe(false);
    for (const patch of [{ opened_at: n.created_at }, { status: 'RECEIVED' }, { lateness_seconds: 3601 }, { status: 'OPENED', opened_at: '2000-01-01T00:00:00Z' }, { url: 'https://example.invalid/' }]) expect(() => read_notification({ ...n, ...patch })).toThrow();
  });
});
