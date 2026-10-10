import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { ApiError } from './api';
import { AUDIT_WINDOW_LIMIT, audit_history_path, audit_order, audit_run_statuses, audit_time, event_kinds, merge_audit_history, read_audit_cursor, read_audit_history, type AuditEvent, type AuditPage } from './auditHistory';

const id = (n: number) => `${String(n).padStart(8, '0')}-aaaa-4aaa-8aaa-aaaaaaaaaaaa`;
const cursor = (n = 1) => `cursor${n}.${'a'.repeat(43)}`;
const event = (n = 1): AuditEvent => ({ id: id(n), event_type: 'RUN_ACCEPTED', happened_at: '2026-10-10T18:00:00.000001Z', resource_kind: 'RUN', resource_id: id(999), run: { id: id(999), current_status: 'UNKNOWN' } });
const page = (items: AuditEvent[] = [event()]): AuditPage => ({ checked_at: '2026-10-10T18:05:00Z', scope: 'ALL', items, next_cursor: null });
const full = (): AuditPage => ({ ...page(Array.from({ length: 20 }, (_, i) => event(100 - i))), next_cursor: cursor() });

describe('closed metadata-only audit history contract', () => {
  it('accepts known typed metadata and preserves UNKNOWN exactly', () => {
    expect(read_audit_history(page(), 'ALL')).toEqual(page());
    expect(read_audit_history({ ...page(), scope: 'RUNS' }, 'RUNS').items[0].run?.current_status).toBe('UNKNOWN');
  });
  it('pins all event mappings and run states to the shared synthetic contracts', () => {
    const fixture = JSON.parse(readFileSync('../../packages/contracts/fixtures/audit-history/event-kinds.json', 'utf8')) as Record<string, string[]>;
    const mapping = Object.fromEntries(Object.entries(fixture).flatMap(([kind, events]) => events.map(type => [type, kind])));
    expect(event_kinds).toEqual({ ...mapping, OTHER: 'OTHER' });
    const machines = JSON.parse(readFileSync('../../packages/contracts/state-machines.json', 'utf8'));
    expect(audit_run_statuses).toEqual(machines.run_states);
    for (const [type, kind] of Object.entries(event_kinds)) {
      const item = { ...event(), event_type: type, resource_kind: kind, resource_id: kind === 'OTHER' ? null : id(999), run: kind === 'RUN' ? event().run : null };
      expect(read_audit_history({ ...page(), items: [item] }, 'ALL').items[0]).toEqual(item);
    }
  });
  it('allows missing current run while retaining historical resource identity', () => {
    const item = { ...event(), run: null };
    expect(read_audit_history(page([item]), 'ALL').items[0]).toEqual(item);
  });
  it('accepts only sanitized OTHER events', () => {
    const other: AuditEvent = { ...event(), event_type: 'OTHER', resource_kind: 'OTHER', resource_id: null, run: null };
    expect(read_audit_history(page([other]), 'ALL').items[0]).toEqual(other);
    expect(() => read_audit_history(page([{ ...other, resource_id: id(1) }]), 'ALL')).toThrow(ApiError);
  });
  it('rejects unknown fields at every nesting level without exposing their contents', () => {
    for (const value of [
      { ...page(), secret: 'private token' },
      { ...page(), items: [{ ...event(), payload: { prompt: 'private text' } }] },
      { ...page(), items: [{ ...event(), run: { ...event().run, execution_mode: 'MOCK' } }] },
      { ...page(), items: [{ ...event(), run: { ...event().run, version: Number.MAX_SAFE_INTEGER + 1 } }] },
    ]) expect(() => read_audit_history(value, 'ALL')).toThrow('INVALID_AUDIT_RESPONSE');
  });
  it('rejects missing required fields at every level', () => {
    for (const key of Object.keys(page())) { const value: any = page(); delete value[key]; expect(() => read_audit_history(value, 'ALL')).toThrow(ApiError); }
    for (const key of Object.keys(event())) { const value: any = page(); delete value.items[0][key]; expect(() => read_audit_history(value, 'ALL')).toThrow(ApiError); }
    for (const key of ['id', 'current_status']) { const value: any = page(); delete value.items[0].run[key]; expect(() => read_audit_history(value, 'ALL')).toThrow(ApiError); }
  });
  it('rejects nonobjects, arrays and nonarray item sets', () => {
    for (const v of [null, [], 'private', 42, false, { ...page(), items: {} }, { ...page(), items: [null] }, { ...page(), items: [[]] }, { ...page(), items: [{ ...event(), run: [] }] }]) expect(() => read_audit_history(v, 'ALL')).toThrow(ApiError);
  });
  it('rejects noncanonical UUIDs in event/resource/run identifiers', () => {
    for (const bad of [id(1).toUpperCase(), `${id(1)}\n`, 'bad', 1, null]) {
      for (const field of ['id', 'resource_id']) expect(() => read_audit_history({ ...page(), items: [{ ...event(), [field]: bad }] }, 'ALL')).toThrow(ApiError);
      expect(() => read_audit_history({ ...page(), items: [{ ...event(), run: { ...event().run, id: bad } }] }, 'ALL')).toThrow(ApiError);
    }
  });
  it('rejects dates with offsets, impossible calendar dates or unsupported precision', () => {
    const invalid = ['2026-02-29T00:00:00Z', '2026-04-31T00:00:00Z', '0000-01-01T00:00:00Z', '2026-10-10T24:00:00Z', '2026-10-10T18:00:00+00:00', '2026-10-10t18:00:00z', '2026-10-10T18:00:00.0000001Z', '2026-10-10T18:00:00Z\n', '2026-10-10', NaN];
    for (const bad of invalid) {
      expect(() => read_audit_history({ ...page(), checked_at: bad }, 'ALL')).toThrow(ApiError);
      expect(() => read_audit_history({ ...page(), items: [{ ...event(), happened_at: bad }] }, 'ALL')).toThrow(ApiError);
    }
    expect(read_audit_history({ ...page(), checked_at: '2028-02-29T00:00:00.999999Z' }, 'ALL').checked_at).toContain('2028-02-29');
  });
  it('validates event-kind-run correlations and every current state', () => {
    for (const current_status of audit_run_statuses) expect(read_audit_history(page([{ ...event(), run: { id: id(999), current_status } }]), 'ALL').items[0].run?.current_status).toBe(current_status);
    for (const item of [
      { ...event(), event_type: '<script>secret</script>' }, { ...event(), event_type: 'toString' },
      { ...event(), resource_kind: 'TASK' }, { ...event(), resource_id: null },
      { ...event(), run: { id: id(998), current_status: 'UNKNOWN' } },
      { ...event(), run: { id: id(999), current_status: 'VERIFIED' } },
      { ...event(), run: { id: id(999), current_status: 'UNKNOWN\n' } },
      { ...event(), event_type: 'TASK_CREATED', resource_kind: 'TASK' },
    ]) expect(() => read_audit_history({ ...page(), items: [item] }, 'ALL')).toThrow(ApiError);
  });
  it('requires the exact requested scope and prevents nonrun leakage into RUNS', () => {
    expect(() => read_audit_history(page(), 'RUNS')).toThrow(ApiError);
    expect(() => read_audit_history({ ...page(), scope: 'all' }, 'ALL')).toThrow(ApiError);
    expect(() => read_audit_history({ ...page(), scope: 'RUNS', items: [{ ...event(), event_type: 'TASK_CREATED', resource_kind: 'TASK', run: null }] }, 'RUNS')).toThrow(ApiError);
  });
  it('validates page size, unique ids and full-page continuation', () => {
    expect(read_audit_history(full(), 'ALL').items).toHaveLength(20);
    expect(() => read_audit_history({ ...full(), items: [...full().items, event(1)] }, 'ALL')).toThrow(ApiError);
    expect(() => read_audit_history(page([event(), event()]), 'ALL')).toThrow(ApiError);
    expect(() => read_audit_history({ ...page(), next_cursor: cursor() }, 'ALL')).toThrow(ApiError);
    expect(() => read_audit_history({ ...page([]), next_cursor: cursor() }, 'ALL')).toThrow(ApiError);
    expect(read_audit_history(page([]), 'ALL').items).toEqual([]);
  });
  it('checks descending microseconds before UUID tie breaks without losing submillisecond precision', () => {
    const newer = { ...event(1), happened_at: '2026-10-10T18:00:00.000002Z' }, older = event(2);
    expect(audit_order(newer, older)).toBeLessThan(0);
    expect(read_audit_history(page([newer, older]), 'ALL').items).toEqual([newer, older]);
    expect(() => read_audit_history(page([older, newer]), 'ALL')).toThrow(ApiError);
    expect(read_audit_history(page([event(2), event(1)]), 'ALL').items).toHaveLength(2);
    expect(() => read_audit_history(page([event(1), event(2)]), 'ALL')).toThrow(ApiError);
  });
  it('validates opaque signed cursor syntax without decoding or trusting embedded content', () => {
    expect(read_audit_cursor(null)).toBeNull(); expect(read_audit_cursor(cursor())).toBe(cursor());
    const longest = `${'a'.repeat(980)}.${'b'.repeat(43)}`;
    expect(read_audit_cursor(longest)).toBe(longest);
    for (const bad of ['', 'a', 'https://example.invalid', `${cursor()}\n`, `a.${'b'.repeat(42)}`, `a.${'b'.repeat(44)}`, `${'a'.repeat(981)}.${'b'.repeat(43)}`, 'a/a.' + 'b'.repeat(43), 0, undefined]) expect(() => read_audit_cursor(bad)).toThrow(ApiError);
  });
  it('builds only bounded metadata GET paths with canonical safe integer limits', () => {
    expect(audit_history_path('ALL')).toBe('/audit?scope=ALL&limit=20');
    expect(audit_history_path('RUNS', cursor(), 100)).toBe(`/audit?scope=RUNS&limit=100&cursor=${cursor()}`);
    expect(audit_history_path('ALL', null, 1)).toBe('/audit?scope=ALL&limit=1');
    for (const bad of [0, 101, -1, 1.2, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
      expect(() => audit_history_path('ALL', null, bad)).toThrow(ApiError);
      expect(() => read_audit_history(page(), 'ALL', bad)).toThrow(ApiError);
    }
    expect(() => audit_history_path('OWNER' as any)).toThrow(ApiError);
  });
  it('formats the requested KST date rollover without changing server timestamps', () => {
    const value = '2026-10-10T18:00:00.000001Z';
    expect(audit_time(value)).toBe('11 Oct 2026, 03:00:00 KST');
    expect(event().happened_at).toBe(value);
    expect(() => audit_time('invalid')).toThrow(ApiError);
  });
});

describe('bounded audit page append', () => {
  it('appends distinct events for the same run and retains per-row state and check times', () => {
    const first = merge_audit_history([], page([event(3), event(2)])).rows;
    const second = { ...page([{ ...event(1), run: { id: id(999), current_status: 'SUCCEEDED' as const } }]), checked_at: '2026-10-10T19:00:00Z' };
    const result = merge_audit_history(first, second);
    expect(result.rows.map(row => row.event.id)).toEqual([id(3), id(2), id(1)]);
    expect(result.rows[0].checked_at).toBe(page().checked_at);
    expect(result.rows[1].checked_at).toBe(page().checked_at);
    expect(result.rows[2].checked_at).toBe(second.checked_at);
    expect(result.rows[1].event.run?.current_status).toBe('UNKNOWN');
    expect(result.rows[2].event.run?.current_status).toBe('SUCCEEDED');
    expect(result.trimmed).toBe(false);
  });
  it('rejects any overlap and a newer event on an older page', () => {
    const first = merge_audit_history([], page([event(3), event(2)])).rows;
    for (const changed of [event(2), { ...event(2), run: null }, { ...event(2), happened_at: '2026-10-10T18:00:01Z' }, { ...event(2), event_type: 'RUN_UNKNOWN' as const }, { ...event(2), resource_id: id(1000), run: null }, event(4)]) expect(() => merge_audit_history(first, page([changed]))).toThrow(ApiError);
  });
  it('bounds retained rows at 100, evicts newest and can restart from latest', () => {
    const previous = Array.from({ length: 100 }, (_, i) => ({ event: event(200 - i), checked_at: page().checked_at }));
    const result = merge_audit_history(previous, page(Array.from({ length: 20 }, (_, i) => event(100 - i))));
    expect(result.rows).toHaveLength(AUDIT_WINDOW_LIMIT); expect(result.trimmed).toBe(true);
    expect(result.rows[0].event.id).toBe(id(180)); expect(result.rows.at(-1)?.event.id).toBe(id(81));
    expect(merge_audit_history([], full()).rows[0].event.id).toBe(id(100));
  });
});
