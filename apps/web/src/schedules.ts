import { ApiError } from './api';
export type Recurrence = 'ONCE' | 'DAILY' | 'WEEKDAYS' | 'WEEKLY' | 'CUSTOM_DAYS';
export interface ScheduleDefinition { title: string; text: string; project_id: string | null; recurrence: Recurrence; start_date: string; local_time: string; timezone: string; weekdays: number[]; authorization_expires_at: string; max_deliveries: number }
export interface OccurrencePreview { planned_fire_at_utc: string; local_datetime: string; offset_seconds: number; dst_adjustment: 'NONE' | 'GAP_FORWARD' | 'FOLD_FIRST' }
export interface SchedulePreparation { id: string; version: 1; status: 'PREPARED'; operation: 'CREATE' | 'REPLACE'; target_id: string; expected_version: number | null; definition: ScheduleDefinition; destination: 'OWN_INBOX'; next_occurrence: OccurrencePreview; planned_delivery_count: number; max_lateness_seconds: 3600; issued_at: string; expires_at: string; payload_hash: string }
export interface Schedule { id: string; version: number; status: 'ENABLED' | 'DISABLED' | 'EXPIRED' | 'EXHAUSTED' | 'RECONFIRM_REQUIRED'; definition: ScheduleDefinition; destination: 'OWN_INBOX'; next_occurrence: OccurrencePreview | null; delivered_count: number; created_at: string; updated_at: string }
export interface ScheduleReceipt { status: 'COMMITTED' | 'NOT_OBSERVED'; schedule_id: string | null; version: number | null; operation: 'CREATE' | 'REPLACE' | 'DISABLE' | null }
export interface ScheduleOccurrence { id: string; schedule_id: string; schedule_version: number; planned_fire_at_utc: string; status: 'PLANNED' | 'DELIVERED' | 'SKIPPED_MISSED' | 'BLOCKED' | 'CANCELLED'; reason_code: 'PAUSED' | 'EPOCH_CHANGED' | 'DISABLED' | 'REPLACED' | 'AUTHORIZATION_EXPIRED' | 'QUOTA_EXHAUSTED' | 'OPERATIONAL_FAILURE' | 'SUPERSEDED_MISSED' | null; notification_id: string | null; lateness_seconds: number | null; settled_at: string | null }
export interface ReminderPage<T> { items: T[]; next_cursor: string | null }
export const reminder_fail = (): never => { throw new ApiError('INVALID_REMINDER_RESPONSE'); };
export const reminder_uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$(?![\s\S])/.test(v);
export const reminder_integer = (v: unknown, min = 1, max = Number.MAX_SAFE_INTEGER): v is number => Number.isSafeInteger(v) && (v as number) >= min && (v as number) <= max;
export function reminder_object(v: unknown, keys: string[]): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).length !== keys.length || !keys.every(k => Object.hasOwn(v, k))) return reminder_fail(); return v as Record<string, unknown>; }
export function reminder_time(v: unknown): v is string { return typeof v === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?Z$(?![\s\S])/.test(v) && !v.startsWith('0000') && Number.isFinite(Date.parse(v)) && new Date(v).toISOString().slice(0, 19) === v.slice(0, 19); }
/** Exact UTC microseconds for response consistency only; never a browser authorization clock. */
export function reminder_instant_us(value: string): bigint { if (!reminder_time(value)) return reminder_fail(); const fraction = value.match(/\.(\d{1,6})Z$/)?.[1] ?? ''; return BigInt(Date.parse(`${value.slice(0, 19)}Z`)) * 1000n + BigInt(fraction.padEnd(6, '0')); }
export function valid_reminder_text(v: unknown, points: number): v is string { return typeof v === 'string' && !/[\uD800-\uDFFF]/u.test(v) && !/[\x00-\x1f\x7f-\x9f]/u.test(v) && /[^\p{White_Space}\uFEFF]/u.test(v) && [...v].length <= points && new TextEncoder().encode(v).length <= points * 4; }
export function valid_reminder_body(v: unknown): boolean { try { return new TextEncoder().encode(JSON.stringify(v)).length <= 16384; } catch { return false; } }
export function valid_schedule_date(v: unknown): v is string { return typeof v === 'string' && /^\d{4}-\d\d-\d\d$(?![\s\S])/.test(v) && reminder_time(`${v}T00:00:00Z`); }
export function schedule_weekday(date: string): number { return (new Date(`${date}T00:00:00Z`).getUTCDay() + 6) % 7 + 1; }
export function read_schedule_definition(v: unknown, timezones?: string[]): ScheduleDefinition {
  const d = reminder_object(v, ['title', 'text', 'project_id', 'recurrence', 'start_date', 'local_time', 'timezone', 'weekdays', 'authorization_expires_at', 'max_deliveries']);
  if (!valid_reminder_text(d.title, 200) || !valid_reminder_text(d.text, 2000) || !(d.project_id === null || reminder_uuid(d.project_id)) || !['ONCE', 'DAILY', 'WEEKDAYS', 'WEEKLY', 'CUSTOM_DAYS'].includes(d.recurrence as string) || !valid_schedule_date(d.start_date) || typeof d.local_time !== 'string' || !/^(?:[01]\d|2[0-3]):[0-5]\d$(?![\s\S])/.test(d.local_time) || typeof d.timezone !== 'string' || d.timezone.length > 80 || !(d.timezone === 'UTC' || /^(?!SystemV\/)[A-Za-z0-9._+-]+(?:\/[A-Za-z0-9._+-]+)+$(?![\s\S])/.test(d.timezone)) || timezones && !timezones.includes(d.timezone) || !Array.isArray(d.weekdays) || d.weekdays.length > 7 || !d.weekdays.every(n => reminder_integer(n, 1, 7)) || d.weekdays.some((n, i, a) => i > 0 && a[i - 1] >= n) || !reminder_time(d.authorization_expires_at) || !reminder_integer(d.max_deliveries, 1, 31) || !valid_reminder_body(d)) return reminder_fail();
  if (d.recurrence === 'WEEKLY' ? d.weekdays.length !== 1 || d.weekdays[0] !== schedule_weekday(d.start_date) : d.recurrence === 'CUSTOM_DAYS' ? d.weekdays.length < 1 : d.weekdays.length !== 0) return reminder_fail();
  if (d.recurrence === 'ONCE' && d.max_deliveries !== 1) return reminder_fail();
  return { ...d, weekdays: [...d.weekdays] } as unknown as ScheduleDefinition;
}
export function valid_schedule_definition(v: unknown, timezones?: string[]): v is ScheduleDefinition { try { read_schedule_definition(v, timezones); return true; } catch { return false; } }
export function same_schedule_definition(a: ScheduleDefinition, b: ScheduleDefinition): boolean { return (['title', 'text', 'project_id', 'recurrence', 'start_date', 'local_time', 'timezone', 'authorization_expires_at', 'max_deliveries'] as const).every(k => a[k] === b[k]) && a.weekdays.length === b.weekdays.length && a.weekdays.every((day, i) => day === b.weekdays[i]); }
export function read_occurrence_preview(v: unknown): OccurrencePreview {
  const d = reminder_object(v, ['planned_fire_at_utc', 'local_datetime', 'offset_seconds', 'dst_adjustment']);
  if (!reminder_time(d.planned_fire_at_utc) || typeof d.local_datetime !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d$(?![\s\S])/.test(d.local_datetime) || !reminder_time(`${d.local_datetime}Z`) || !reminder_integer(d.offset_seconds, -64800, 64800) || !['NONE', 'GAP_FORWARD', 'FOLD_FIRST'].includes(d.dst_adjustment as string)) return reminder_fail();
  if (reminder_instant_us(d.planned_fire_at_utc) + BigInt(d.offset_seconds) * 1000000n !== reminder_instant_us(`${d.local_datetime}Z`)) return reminder_fail();
  return d as unknown as OccurrencePreview;
}
export function read_schedule_preparation(v: unknown, timezones?: string[]): SchedulePreparation {
  const d = reminder_object(v, ['id', 'version', 'status', 'operation', 'target_id', 'expected_version', 'definition', 'destination', 'next_occurrence', 'planned_delivery_count', 'max_lateness_seconds', 'issued_at', 'expires_at', 'payload_hash']);
  const definition = read_schedule_definition(d.definition, timezones), next_occurrence = read_occurrence_preview(d.next_occurrence);
  if (!reminder_uuid(d.id) || d.version !== 1 || d.status !== 'PREPARED' || !['CREATE', 'REPLACE'].includes(d.operation as string) || !reminder_uuid(d.target_id) || (d.operation === 'CREATE' ? d.expected_version !== null : !reminder_integer(d.expected_version)) || d.destination !== 'OWN_INBOX' || !reminder_integer(d.planned_delivery_count, 1, definition.max_deliveries) || d.max_lateness_seconds !== 3600 || !reminder_time(d.issued_at) || !reminder_time(d.expires_at) || typeof d.payload_hash !== 'string' || !/^[0-9a-f]{64}$(?![\s\S])/.test(d.payload_hash)) return reminder_fail();
  const issued = reminder_instant_us(d.issued_at), end = reminder_instant_us(definition.authorization_expires_at), fire = reminder_instant_us(next_occurrence.planned_fire_at_utc);
  if (reminder_instant_us(d.expires_at) - issued !== 300000000n || end <= issued || end - issued > 2592000000000n || fire <= issued || fire >= end) return reminder_fail();
  return { ...d, definition, next_occurrence } as unknown as SchedulePreparation;
}
export function read_schedule(v: unknown): Schedule {
  const d = reminder_object(v, ['id', 'version', 'status', 'definition', 'destination', 'next_occurrence', 'delivered_count', 'created_at', 'updated_at']);
  const definition = read_schedule_definition(d.definition), next_occurrence = d.next_occurrence === null ? null : read_occurrence_preview(d.next_occurrence);
  if (!reminder_uuid(d.id) || !reminder_integer(d.version) || !['ENABLED', 'DISABLED', 'EXPIRED', 'EXHAUSTED', 'RECONFIRM_REQUIRED'].includes(d.status as string) || d.destination !== 'OWN_INBOX' || !reminder_integer(d.delivered_count, 0, definition.max_deliveries) || !reminder_time(d.created_at) || !reminder_time(d.updated_at) || reminder_instant_us(d.updated_at) < reminder_instant_us(d.created_at) || d.status !== 'ENABLED' && next_occurrence !== null) return reminder_fail();
  return { ...d, definition, next_occurrence } as unknown as Schedule;
}
export function read_schedule_receipt(v: unknown): ScheduleReceipt { const d = reminder_object(v, ['status', 'schedule_id', 'version', 'operation']); if (d.status === 'NOT_OBSERVED') { if (d.schedule_id !== null || d.version !== null || d.operation !== null) return reminder_fail(); } else if (d.status !== 'COMMITTED' || !reminder_uuid(d.schedule_id) || !reminder_integer(d.version) || !['CREATE', 'REPLACE', 'DISABLE'].includes(d.operation as string)) return reminder_fail(); return d as unknown as ScheduleReceipt; }
export function read_schedule_occurrence(v: unknown): ScheduleOccurrence {
  const d = reminder_object(v, ['id', 'schedule_id', 'schedule_version', 'planned_fire_at_utc', 'status', 'reason_code', 'notification_id', 'lateness_seconds', 'settled_at']);
  if (!reminder_uuid(d.id) || !reminder_uuid(d.schedule_id) || !reminder_integer(d.schedule_version) || !reminder_time(d.planned_fire_at_utc) || !['PLANNED', 'DELIVERED', 'SKIPPED_MISSED', 'BLOCKED', 'CANCELLED'].includes(d.status as string)) return reminder_fail();
  if (d.status === 'PLANNED') { if ([d.reason_code, d.notification_id, d.lateness_seconds, d.settled_at].some(n => n !== null)) return reminder_fail(); }
  else {
    if (!reminder_time(d.settled_at) || !reminder_integer(d.lateness_seconds, 0)) return reminder_fail();
    if (d.status === 'DELIVERED') { if (d.reason_code !== null || !reminder_uuid(d.notification_id) || d.lateness_seconds > 3600) return reminder_fail(); }
    else if (d.status === 'SKIPPED_MISSED') { if (d.reason_code !== null || d.notification_id !== null || d.lateness_seconds < 3601) return reminder_fail(); }
    else if (!['PAUSED', 'EPOCH_CHANGED', 'DISABLED', 'REPLACED', 'AUTHORIZATION_EXPIRED', 'QUOTA_EXHAUSTED', 'OPERATIONAL_FAILURE', 'SUPERSEDED_MISSED'].includes(d.reason_code as string) || d.reason_code === 'SUPERSEDED_MISSED' && d.status !== 'CANCELLED' || d.notification_id !== null) return reminder_fail();
  }
  if (d.status !== 'PLANNED') {
    const elapsed = reminder_instant_us(d.settled_at as string) - reminder_instant_us(d.planned_fire_at_utc as string), nonnegative = elapsed < 0n ? 0n : elapsed;
    const display = d.status === 'SKIPPED_MISSED' ? (nonnegative + 999999n) / 1000000n : nonnegative / 1000000n;
    if (display !== BigInt(d.lateness_seconds as number) || d.status === 'DELIVERED' && (elapsed < 0n || elapsed > 3600000000n) || d.status === 'SKIPPED_MISSED' && elapsed <= 3600000000n) return reminder_fail();
  }
  return d as unknown as ScheduleOccurrence;
}
export function read_reminder_page<T extends { id: string }>(v: unknown, read: (v: unknown) => T): ReminderPage<T> { const d = reminder_object(v, ['items', 'next_cursor']); if (!Array.isArray(d.items) || d.items.length > 50 || !(d.next_cursor === null || typeof d.next_cursor === 'string' && /^[A-Za-z0-9_.-]{1,1024}$(?![\s\S])/.test(d.next_cursor))) return reminder_fail(); const items = d.items.map(read); if (new Set(items.map(item => item.id)).size !== items.length) return reminder_fail(); return { items, next_cursor: d.next_cursor as string | null }; }
