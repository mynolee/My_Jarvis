import { ApiError } from './api';
import { task_time_micros, valid_date } from './tasks';

export const AUDIT_PAGE_LIMIT = 20;
export const AUDIT_WINDOW_LIMIT = 100;
export type AuditScope = 'ALL' | 'RUNS';
export const event_kinds = {
  OWNER_CREATED: 'OWNER',
  OWNER_PROFILE_UPDATED: 'OWNER',
  EXECUTION_PAUSED: 'OWNER',
  EXECUTION_RESUMED: 'OWNER',
  PROJECT_CREATED: 'PROJECT',
  TASK_CREATED: 'TASK',
  TASK_UPDATED: 'TASK',
  TASK_INTENT_ISSUED: 'TASK_INTENT',
  TASK_INTENT_CONSUMED: 'TASK_INTENT',
  MEMORY_CREATED: 'MEMORY',
  MEMORY_REVISED: 'MEMORY',
  MEMORY_DELETED: 'MEMORY',
  MEMORY_INTENT_ISSUED: 'MEMORY_INTENT',
  MEMORY_DELETE_PREPARED: 'MEMORY_DELETE_PREPARATION',
  MAIL_DRAFT_CREATED: 'MAIL_DRAFT',
  MAIL_DRAFT_UPDATED: 'MAIL_DRAFT',
  SCHEDULE_CREATE_CONFIRMED: 'SCHEDULE',
  SCHEDULE_REPLACE_CONFIRMED: 'SCHEDULE',
  SCHEDULE_DISABLED: 'SCHEDULE',
  SCHEDULE_PREPARED: 'SCHEDULE_PREPARATION',
  REMINDER_PLANNED: 'SCHEDULE_OCCURRENCE',
  REMINDER_BLOCKED: 'SCHEDULE_OCCURRENCE',
  REMINDER_SKIPPED_MISSED: 'SCHEDULE_OCCURRENCE',
  REMINDER_CANCELLED: 'SCHEDULE_OCCURRENCE',
  REMINDER_INBOX_CREATED: 'NOTIFICATION',
  NOTIFICATION_OPENED: 'NOTIFICATION',
  RUN_ACCEPTED: 'RUN',
  RUN_CANCELLED: 'RUN',
  RUN_WAITING_INPUT: 'RUN',
  RUN_SUCCEEDED: 'RUN',
  RUN_WAITING_APPROVAL: 'RUN',
  RUN_BLOCKED: 'RUN',
  RUN_UNKNOWN: 'RUN',
  WORKER_UNCERTAIN_OR_FAILED: 'RUN',
  MOCK_EXECUTION_STARTED: 'RUN',
  LEASE_EXPIRED_UNKNOWN: 'RUN',
  MOCK_RECONCILED: 'RUN',
  APPROVAL_REJECTED: 'RUN',
  APPROVAL_CONSUMED: 'RUN',
  CANCEL_REQUESTED: 'RUN',
  LOCAL_INFERENCE_RESERVED: 'RUN',
  LOCAL_INFERENCE_STARTED: 'RUN',
  LOCAL_INFERENCE_UNKNOWN: 'RUN',
  LOCAL_INFERENCE_SUCCEEDED: 'RUN',
  LOCAL_INFERENCE_WAITING_INPUT: 'RUN',
  LOCAL_INFERENCE_FAILED: 'RUN',
  LOCAL_CONTEXT_BLOCKED: 'RUN',
  OTHER: 'OTHER',
} as const;
export const resource_labels = {
  OWNER: 'Owner', PROJECT: 'Project', TASK: 'Task', TASK_INTENT: 'Task intent',
  MEMORY: 'Memory', MEMORY_INTENT: 'Memory intent', MEMORY_DELETE_PREPARATION: 'Memory deletion preparation',
  MAIL_DRAFT: 'Internal mail draft', SCHEDULE: 'Reminder schedule', SCHEDULE_PREPARATION: 'Reminder preparation',
  SCHEDULE_OCCURRENCE: 'Reminder occurrence', NOTIFICATION: 'Internal inbox item', RUN: 'Run', OTHER: 'Other',
} as const;
export const audit_run_statuses = [
  'ACCEPTED', 'PLANNING', 'WAITING_INPUT', 'WAITING_APPROVAL', 'QUEUED', 'RUNNING',
  'SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED', 'UNKNOWN', 'EXPIRED', 'BLOCKED',
] as const;
export interface AuditEvent {
  id: string; event_type: keyof typeof event_kinds; happened_at: string;
  resource_kind: keyof typeof resource_labels; resource_id: string | null;
  run: { id: string; current_status: typeof audit_run_statuses[number] } | null;
}
export interface AuditPage { checked_at: string; scope: AuditScope; items: AuditEvent[]; next_cursor: string | null }
export interface AuditRow { event: AuditEvent; checked_at: string }
const fail = (): never => { throw new ApiError('INVALID_AUDIT_RESPONSE'); };
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$(?![\s\S])/.test(v);
const instant = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$(?![\s\S])/.test(v) && valid_date(v);
function object(v: unknown, keys: string[]): Record<string, unknown> {
  if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).length !== keys.length || !keys.every(k => Object.hasOwn(v, k))) return fail();
  return v as Record<string, unknown>;
}
function limit(v: number): number { return Number.isSafeInteger(v) && v >= 1 && v <= 100 ? v : fail(); }
function scope(v: unknown): AuditScope { return v === 'ALL' || v === 'RUNS' ? v : fail(); }
export function read_audit_cursor(v: unknown): string | null {
  return v === null ? null : typeof v === 'string' && v.length <= 1024 && /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}$(?![\s\S])/.test(v) ? v : fail();
}
function event(v: unknown): AuditEvent {
  const e = object(v, ['id', 'event_type', 'happened_at', 'resource_kind', 'resource_id', 'run']);
  if (!uuid(e.id) || !instant(e.happened_at) || typeof e.event_type !== 'string' || !Object.hasOwn(event_kinds, e.event_type)) return fail();
  const event_type = e.event_type as AuditEvent['event_type'];
  const resource_kind = event_kinds[event_type];
  if (e.resource_kind !== resource_kind) return fail();
  if (resource_kind === 'OTHER') {
    if (e.resource_id !== null || e.run !== null) return fail();
  } else if (!uuid(e.resource_id)) return fail();
  let run: AuditEvent['run'] = null;
  if (e.run !== null) {
    const r = object(e.run, ['id', 'current_status']);
    if (resource_kind !== 'RUN' || !uuid(r.id) || r.id !== e.resource_id || !(audit_run_statuses as readonly unknown[]).includes(r.current_status)) return fail();
    run = { id: r.id, current_status: r.current_status as NonNullable<AuditEvent['run']>['current_status'] };
  }
  return { id: e.id, event_type, happened_at: e.happened_at, resource_kind, resource_id: e.resource_id as string | null, run };
}
/** Descending database microseconds, then canonical UUID byte/text order. */
export function audit_order(a: AuditEvent, b: AuditEvent): number {
  const x = task_time_micros(a.happened_at), y = task_time_micros(b.happened_at);
  return x > y ? -1 : x < y ? 1 : a.id > b.id ? -1 : a.id < b.id ? 1 : 0;
}
export function read_audit_history(v: unknown, expectedScope: AuditScope, requestedLimit = AUDIT_PAGE_LIMIT): AuditPage {
  limit(requestedLimit); scope(expectedScope);
  const p = object(v, ['checked_at', 'scope', 'items', 'next_cursor']);
  if (!instant(p.checked_at) || scope(p.scope) !== expectedScope || !Array.isArray(p.items) || p.items.length > requestedLimit) return fail();
  const items = p.items.map(event), next_cursor = read_audit_cursor(p.next_cursor);
  if (new Set(items.map(e => e.id)).size !== items.length || items.some((e, i) => expectedScope === 'RUNS' && e.resource_kind !== 'RUN' || i > 0 && audit_order(items[i - 1], e) >= 0)) return fail();
  if (next_cursor !== null && items.length !== requestedLimit) return fail();
  return { checked_at: p.checked_at, scope: expectedScope, items, next_cursor };
}
export function audit_history_path(selected: AuditScope, cursor: string | null = null, pageLimit = AUDIT_PAGE_LIMIT): string {
  const query = new URLSearchParams({ scope: scope(selected), limit: String(limit(pageLimit)) });
  const checkedCursor = read_audit_cursor(cursor);
  if (checkedCursor !== null) query.set('cursor', checkedCursor);
  return `/audit?${query}`;
}
/** Strict keyset pages cannot overlap; reject repeats instead of duplicating or replacing rows. */
export function merge_audit_history(previous: AuditRow[], page: AuditPage): { rows: AuditRow[]; trimmed: boolean } {
  const rows = new Map(previous.map(row => [row.event.id, row]));
  const boundary = previous.at(-1)?.event;
  for (const incoming of page.items) {
    const existing = rows.get(incoming.id)?.event;
    if (existing) return fail();
    if (!existing && boundary && audit_order(boundary, incoming) >= 0) return fail();
    rows.set(incoming.id, { event: incoming, checked_at: page.checked_at });
  }
  const result = [...rows.values()].sort((a, b) => audit_order(a.event, b.event));
  return { rows: result.slice(-AUDIT_WINDOW_LIMIT), trimmed: result.length > AUDIT_WINDOW_LIMIT };
}
export function audit_time(value: string): string {
  if (!instant(value)) return fail();
  return `${new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'medium', hour12: false }).format(new Date(value))} KST`;
}
