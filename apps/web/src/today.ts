import { ApiError } from './api';
import { task_time_micros, valid_date } from './tasks';

export interface TodayTask { id: string; project_id: string | null; title: string; priority: 'HIGH' | 'NORMAL' | 'LOW'; status: 'TODO' | 'IN_PROGRESS'; due_at: string | null; version: number }
export interface TodaySnapshot {
  checked_at: string; timezone: string; local_date: string; day_start_at: string; next_day_start_at: string; deadline_window_end_at: string;
  tasks: { status: 'AVAILABLE'; top_tasks: TodayTask[]; deadlines: TodayTask[]; deadline_total: number; deadlines_truncated: boolean };
  calendar: Unavailable; briefing: Unavailable; device: Unavailable;
}
interface Unavailable { status: 'UNAVAILABLE'; reason: 'NOT_IMPLEMENTED' }
const fail = (): never => { throw new ApiError('INVALID_TODAY_RESPONSE'); };
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$(?![\s\S])/.test(v);
function object(v: unknown, keys: string[]): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).length !== keys.length || !keys.every(k => Object.hasOwn(v, k))) return fail(); return v as Record<string, unknown>; }
function instant(v: unknown): v is string { return typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$(?![\s\S])/.test(v) && valid_date(v); }
const micros = task_time_micros;
const compare = (a: bigint, b: bigint) => a < b ? -1 : a > b ? 1 : 0;
const priority = { HIGH: 0, NORMAL: 1, LOW: 2 };
const idOrder = (a: TodayTask, b: TodayTask) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
const dueOrder = (a: TodayTask, b: TodayTask) => a.due_at === null ? (b.due_at === null ? 0 : 1) : b.due_at === null ? -1 : compare(micros(a.due_at), micros(b.due_at));
export function today_overdue(task: TodayTask, checked: string): boolean { return task.due_at !== null && micros(task.due_at) < micros(checked); }
function task(v: unknown): TodayTask {
  const t = object(v, ['id', 'project_id', 'title', 'priority', 'status', 'due_at', 'version']);
  if (!uuid(t.id) || !(t.project_id === null || uuid(t.project_id)) || typeof t.title !== 'string' || !/[^\u0009-\u000D\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]/u.test(t.title) || [...t.title].length > 200 || /[\u0000\uD800-\uDFFF]/u.test(t.title) || !['HIGH', 'NORMAL', 'LOW'].includes(t.priority as string) || !['TODO', 'IN_PROGRESS'].includes(t.status as string) || !(t.due_at === null || instant(t.due_at)) || !Number.isSafeInteger(t.version) || (t.version as number) < 1) return fail();
  return { ...t } as unknown as TodayTask;
}
function list(v: unknown, max: number, order: (a: TodayTask, b: TodayTask) => number): TodayTask[] {
  if (!Array.isArray(v) || v.length > max) return fail();
  const items = v.map(task);
  if (new Set(items.map(t => t.id)).size !== items.length || items.some((t, i) => i > 0 && order(items[i - 1], t) > 0)) return fail();
  return items;
}
function unavailable(v: unknown): Unavailable { const d = object(v, ['status', 'reason']); if (d.status !== 'UNAVAILABLE' || d.reason !== 'NOT_IMPLEMENTED') return fail(); return { status: 'UNAVAILABLE', reason: 'NOT_IMPLEMENTED' }; }
export function read_today(v: unknown): TodaySnapshot {
  const d = object(v, ['checked_at', 'timezone', 'local_date', 'day_start_at', 'next_day_start_at', 'deadline_window_end_at', 'tasks', 'calendar', 'briefing', 'device']);
  if (!instant(d.checked_at) || !instant(d.day_start_at) || !instant(d.next_day_start_at) || !instant(d.deadline_window_end_at) || d.deadline_window_end_at !== d.next_day_start_at || typeof d.local_date !== 'string' || !/^\d{4}-\d{2}-\d{2}$(?![\s\S])/.test(d.local_date) || !valid_date(`${d.local_date}T00:00:00Z`) || typeof d.timezone !== 'string' || d.timezone.length < 1 || d.timezone.length > 80 || !/^[A-Za-z0-9_+\-/]+$(?![\s\S])/.test(d.timezone)) return fail();
  try { new Intl.DateTimeFormat('en', { timeZone: d.timezone }).format(new Date(d.checked_at)); } catch { return fail(); }
  // The server's timezone database owns the window. Never replace it with browser midnight + 24h.
  if (micros(d.day_start_at) > micros(d.checked_at) || micros(d.checked_at) >= micros(d.next_day_start_at)) return fail();
  const checked = d.checked_at;
  const t = object(d.tasks, ['status', 'top_tasks', 'deadlines', 'deadline_total', 'deadlines_truncated']);
  if (t.status !== 'AVAILABLE' || !Number.isSafeInteger(t.deadline_total) || (t.deadline_total as number) < 0 || typeof t.deadlines_truncated !== 'boolean') return fail();
  const top_tasks = list(t.top_tasks, 3, (a, b) => Number(today_overdue(b, checked)) - Number(today_overdue(a, checked)) || priority[a.priority] - priority[b.priority] || dueOrder(a, b) || idOrder(a, b));
  const deadlines = list(t.deadlines, 20, (a, b) => dueOrder(a, b) || priority[a.priority] - priority[b.priority] || idOrder(a, b));
  const total = t.deadline_total as number;
  if (deadlines.length !== Math.min(total, 20) || t.deadlines_truncated !== (total > 20) || deadlines.some(item => item.due_at === null || micros(item.due_at) >= micros(d.next_day_start_at as string))) return fail();
  for (const item of top_tasks) { const match = deadlines.find(other => other.id === item.id); if (match && Object.keys(item).some(k => item[k as keyof TodayTask] !== match[k as keyof TodayTask])) return fail(); }
  if (total > 0 && top_tasks.length === 0) return fail();
  return { checked_at: checked, timezone: d.timezone, local_date: d.local_date, day_start_at: d.day_start_at, next_day_start_at: d.next_day_start_at, deadline_window_end_at: d.deadline_window_end_at, tasks: { status: 'AVAILABLE', top_tasks, deadlines, deadline_total: total, deadlines_truncated: t.deadlines_truncated }, calendar: unavailable(d.calendar), briefing: unavailable(d.briefing), device: unavailable(d.device) };
}
export function today_time(value: string, timezone: string): string { return new Intl.DateTimeFormat('en-GB', { timeZone: timezone, dateStyle: 'medium', timeStyle: 'long' }).format(new Date(value)); }
