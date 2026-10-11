// Task IDs use the backend's canonical lowercase wire representation.
const TASK_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const NONBLANK_TITLE = /[^\u0009-\u000D\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]/u;
export const task_statuses = ['TODO', 'IN_PROGRESS', 'DONE', 'CANCELLED'] as const;
export const task_priorities = ['LOW', 'NORMAL', 'HIGH'] as const;
export type TaskStatus = typeof task_statuses[number];
export type TaskFields = { title: string; description: string; project_id: string | null; due_at: string | null; priority: typeof task_priorities[number]; estimated_minutes: number | null };
export type Task = TaskFields & { id: string; status: TaskStatus; version: number; created_at: string; updated_at: string };
export type TaskArgs = TaskFields | (Partial<TaskFields> & { expected_version: number; status?: TaskStatus });
export type TaskIntent = { id: string; tool_id: 'task.create' | 'task.update'; target_id: string; arguments: TaskArgs; payload_hash: string; expires_at: string; status: 'ISSUED' };
export const empty_task = (): TaskFields & { status: TaskStatus } => ({ title: '', description: '', project_id: null, due_at: null, priority: 'NORMAL', estimated_minutes: null, status: 'TODO' });
export function valid_date(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const match = /^(\d{4})-(\d{2})-(\d{2})[Tt]([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(?:\.\d{1,6})?([Zz]|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.exec(value);
  if (!match || !Number.isFinite(Date.parse(value))) return false;
  const year = +match[1], month = +match[2], day = +match[3];
  const days = [31, year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  const utcYear = new Date(value).getUTCFullYear();
  return year >= 1 && utcYear >= 1 && utcYear <= 9999 && month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1];
}
export function valid_fields(v: TaskFields): boolean {
  return typeof v.title === 'string' && !v.title.includes('\u0000') && NONBLANK_TITLE.test(v.title) && [...v.title].length <= 200 && typeof v.description === 'string' && [...v.description].length <= 8000 && (v.project_id === null || (typeof v.project_id === 'string' && TASK_UUID.test(v.project_id))) && (v.due_at === null || valid_date(v.due_at)) && task_priorities.includes(v.priority) && (v.estimated_minutes === null || Number.isSafeInteger(v.estimated_minutes) && v.estimated_minutes >= 1 && v.estimated_minutes <= 525600);
}
export function read_task(v: unknown): Task {
  const t = v as Task;
  if (!t || !TASK_UUID.test(t.id) || !valid_fields(t) || !task_statuses.includes(t.status) || !Number.isSafeInteger(t.version) || t.version < 1 || !valid_date(t.created_at) || !valid_date(t.updated_at)) throw new Error('INVALID_TASK');
  return t;
}
export function same_args(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object' || Array.isArray(a) || Array.isArray(b)) return false;
  const x = a as Record<string, unknown>, y = b as Record<string, unknown>;
  return Object.keys(x).length === Object.keys(y).length && Object.keys(x).every(k => Object.hasOwn(y, k) && x[k] === y[k]);
}
export function read_intent(v: unknown, tool: TaskIntent['tool_id'], target: string | null, args: TaskArgs): TaskIntent {
  const t = v as TaskIntent;
  if (!t || !TASK_UUID.test(t.id) || !TASK_UUID.test(t.target_id) || (target !== null && t.target_id !== target) || t.tool_id !== tool || t.status !== 'ISSUED' || !/^[a-f0-9]{64}$/.test(t.payload_hash) || !valid_date(t.expires_at) || Date.parse(t.expires_at) <= Date.now() || !same_args(t.arguments, args)) throw new Error('INVALID_TASK_INTENT');
  return t;
}

/** Preserve microseconds when ordering validated RFC3339 timestamps. */
export function task_time_micros(value: string): bigint {
  if (!valid_date(value)) throw new Error('INVALID_TASK_TIME');
  const fraction = /\.(\d{1,6})/.exec(value)?.[1] ?? '';
  return BigInt(Date.parse(value)) * 1000n + BigInt(fraction.padEnd(6, '0').slice(3));
}
