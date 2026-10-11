import { describe, expect, it } from 'vitest';
import { read_today, today_overdue, today_time } from './today';
import { readFileSync } from 'node:fs';
const task = (n = 1, due_at: string | null = '2026-10-10T08:00:00Z') => ({ id: `${String(n).padStart(8, '0')}-1111-4111-8111-111111111111`, project_id: null, title: 'Read-only task', priority: 'HIGH', status: 'TODO', due_at, version: 1 });
function snapshot() { return { checked_at: '2026-10-10T12:00:00Z', timezone: 'UTC', local_date: '2026-10-10', day_start_at: '2026-10-10T00:00:00Z', next_day_start_at: '2026-10-11T00:00:00Z', deadline_window_end_at: '2026-10-11T00:00:00Z', tasks: { status: 'AVAILABLE', top_tasks: [task()], deadlines: [task()], deadline_total: 1, deadlines_truncated: false }, calendar: { status: 'UNAVAILABLE', reason: 'NOT_IMPLEMENTED' }, briefing: { status: 'UNAVAILABLE', reason: 'NOT_IMPLEMENTED' }, device: { status: 'UNAVAILABLE', reason: 'NOT_IMPLEMENTED' } }; }
describe('strict Today snapshots', () => {
  it('reads shared empty fixture and populated snapshot', () => { const v = JSON.parse(readFileSync('../../packages/contracts/fixtures/today/empty.json', 'utf8')); expect(read_today(v.data ?? v).tasks.top_tasks).toEqual([]); expect(read_today(snapshot()).tasks.deadline_total).toBe(1); });
  it.each([
    (v: any) => { v.secret = 'extra'; }, (v: any) => { v.calendar.events = []; }, (v: any) => { v.tasks.top_tasks[0].description = 'private'; },
    (v: any) => { v.checked_at = '2026-02-30T12:00:00Z'; }, (v: any) => { v.checked_at += '\n'; },
    (v: any) => { v.timezone = 'Not/AZone'; }, (v: any) => { v.tasks.deadline_total = Number.MAX_SAFE_INTEGER + 1; },
    (v: any) => { v.tasks.deadlines_truncated = true; }, (v: any) => { v.tasks.deadline_total = 2; },
    (v: any) => { v.tasks.top_tasks[0].status = 'DONE'; }, (v: any) => { v.tasks.top_tasks[0].title = '\uD800'; },
    (v: any) => { v.tasks.top_tasks[0].title = '\u0085'; },
    (v: any) => { v.tasks.top_tasks[0].title = ' '; }, (v: any) => { v.tasks.top_tasks[0].title = 'a\0b'; },
    (v: any) => { v.tasks.top_tasks[0].title = 'a'.repeat(201); }, (v: any) => { v.tasks.deadlines[0].due_at = null; },
    (v: any) => { v.tasks.deadlines[0].due_at = v.next_day_start_at; }, (v: any) => { v.tasks.top_tasks[0].title = 'conflicting copy'; },
    (v: any) => { v.day_start_at = v.next_day_start_at; }, (v: any) => { v.deadline_window_end_at = v.checked_at; },
    (v: any) => { v.tasks.deadlines.push(v.tasks.deadlines[0]); v.tasks.deadline_total = 2; },
  ])('rejects malformed payload %#', mutate => { const v = snapshot(); mutate(v); expect(() => read_today(v)).toThrow('INVALID_TODAY_RESPONSE'); });
  it('validates server ranking without sorting a partial list', () => { const v = snapshot(); v.tasks.top_tasks = [task(2), task(1)]; expect(() => read_today(v)).toThrow(); v.tasks.top_tasks.reverse(); expect(read_today(v).tasks.top_tasks[0].id).toBe(task(1).id); });
  it('accepts precise totals and reports truncation only over the bound', () => { const v = snapshot(); v.tasks.deadlines = Array.from({ length: 20 }, (_, i) => task(i + 1)); v.tasks.deadline_total = 25; v.tasks.deadlines_truncated = true; expect(read_today(v).tasks.deadline_total).toBe(25); });
  it('keeps microsecond overdue boundaries exact', () => { const v = snapshot(); v.checked_at = '2026-10-10T12:00:00.000001Z'; v.tasks.top_tasks = [task(1, '2026-10-10T12:00:00.000000Z')]; v.tasks.deadlines = [...v.tasks.top_tasks]; const d = read_today(v); expect(today_overdue(d.tasks.top_tasks[0], d.checked_at)).toBe(true); expect(today_overdue(d.tasks.top_tasks[0], '2026-10-10T12:00:00Z')).toBe(false); });
  it.each([['2026-03-08', '2026-03-08T05:00:00Z', '2026-03-09T04:00:00Z'], ['2026-11-01', '2026-11-01T04:00:00Z', '2026-11-02T05:00:00Z']])('uses server DST day window for %s', (date, start, end) => { const v = snapshot(); Object.assign(v, { timezone: 'America/New_York', local_date: date, day_start_at: start, next_day_start_at: end, deadline_window_end_at: end, checked_at: start }); v.tasks.top_tasks = []; v.tasks.deadlines = []; v.tasks.deadline_total = 0; expect(read_today(v).next_day_start_at).toBe(end); expect(today_time(start, v.timezone)).toContain('00:00:00'); });
  it('formats checked time in owner timezone', () => { expect(today_time('2026-10-10T18:00:00Z', 'Asia/Seoul')).toContain('11 Oct 2026'); expect(today_time('2026-10-10T18:00:00Z', 'Asia/Seoul')).toContain('03:00:00'); });
});
