import { ApiError } from './api';
import { valid_memory_text } from './memories';
import { task_time_micros, valid_date } from './tasks';

export const EXPORT_MAX_BYTES = 8 * 1024 * 1024;
export const EXPORT_MAX_ITEMS = 1000;
export const EXPORT_FILENAME = 'my-jarvis-memories.json';
export interface ExportScope { scope: 'GLOBAL' | 'PROJECT'; project_id: string | null }
export interface ExportRequest extends ExportScope { kind: 'EXPLICIT_MEMORIES'; format: 'JSON_UTF8' }
export interface ExportJob extends ExportRequest {
  id: string; semantics: 'CURRENT_AT_DOWNLOAD'; status: 'AVAILABLE' | 'EXPIRED';
  created_at: string; expires_at: string; checked_at: string;
}
export type ExportReceipt = { status: 'NOT_OBSERVED'; job: null } | { status: 'COMMITTED'; job: ExportJob };
export interface ExportItem {
  id: string; key: string; kind: 'PROFILE' | 'PREFERENCE'; value: string; change_reason: string; version: number;
  confirmed_at: string; created_at: string; updated_at: string;
  source: { type: 'EXPLICIT_OWNER_FORM'; submitted_at: string };
}
export interface ExportDocument extends ExportScope {
  schema_version: 1; kind: 'EXPLICIT_MEMORIES'; export_job_id: string; exported_at: string;
  semantics: 'CURRENT_AT_DOWNLOAD'; items: ExportItem[];
}
const fail = (): never => { throw new ApiError('INVALID_EXPORT_RESPONSE'); };
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$(?![\s\S])/.test(value);
function object(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== keys.length || !keys.every(key => Object.hasOwn(value, key))) return fail();
  return value as Record<string, unknown>;
}
function instant(value: unknown): string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$(?![\s\S])/.test(value) && valid_date(value) ? value : fail();
}
export function valid_export_scope(value: ExportScope): boolean {
  return value.scope === 'GLOBAL' && value.project_id === null || value.scope === 'PROJECT' && uuid(value.project_id);
}
function scope(value: Record<string, unknown>, expected?: ExportScope): ExportScope {
  const s = { scope: value.scope, project_id: value.project_id } as ExportScope;
  if (!valid_export_scope(s) || expected && (s.scope !== expected.scope || s.project_id !== expected.project_id)) return fail();
  return s;
}
export function export_request(value: ExportScope): ExportRequest {
  if (!valid_export_scope(value)) return fail();
  return { kind: 'EXPLICIT_MEMORIES', scope: value.scope, project_id: value.project_id, format: 'JSON_UTF8' };
}
export function export_job_path(id: string): string { if (!uuid(id)) return fail(); return `/exports/${id}`; }
export function export_receipt_path(key: string): string { if (!uuid(key)) return fail(); return `/exports/requests/${key}`; }
export function read_export_job(value: unknown, expected: ExportScope, previous?: ExportJob | null): ExportJob {
  const j = object(value, ['id', 'kind', 'scope', 'project_id', 'format', 'semantics', 'status', 'created_at', 'expires_at', 'checked_at']);
  const binding = scope(j, expected), created_at = instant(j.created_at), expires_at = instant(j.expires_at), checked_at = instant(j.checked_at);
  if (!uuid(j.id) || j.kind !== 'EXPLICIT_MEMORIES' || j.format !== 'JSON_UTF8' || j.semantics !== 'CURRENT_AT_DOWNLOAD' || j.status !== 'AVAILABLE' && j.status !== 'EXPIRED') return fail();
  const created = task_time_micros(created_at), expires = task_time_micros(expires_at), checked = task_time_micros(checked_at);
  if (created > checked || expires - created !== 900000000n || (j.status === 'AVAILABLE' ? checked >= expires : checked < expires)) return fail();
  if (previous && (j.id !== previous.id || created_at !== previous.created_at || expires_at !== previous.expires_at || checked < task_time_micros(previous.checked_at) || previous.status === 'EXPIRED' && j.status !== 'EXPIRED')) return fail();
  return { ...binding, id: j.id, kind: 'EXPLICIT_MEMORIES', format: 'JSON_UTF8', semantics: 'CURRENT_AT_DOWNLOAD', status: j.status, created_at, expires_at, checked_at };
}
export function read_export_receipt(value: unknown, expected: ExportScope): ExportReceipt {
  const r = object(value, ['status', 'job']);
  if (r.status === 'NOT_OBSERVED' && r.job === null) return { status: 'NOT_OBSERVED', job: null };
  if (r.status !== 'COMMITTED') return fail();
  return { status: 'COMMITTED', job: read_export_job(r.job, expected) };
}
function item(value: unknown, exported: bigint): ExportItem {
  const m = object(value, ['id', 'key', 'kind', 'value', 'change_reason', 'version', 'confirmed_at', 'created_at', 'updated_at', 'source']);
  const s = object(m.source, ['type', 'submitted_at']);
  if (!uuid(m.id) || typeof m.key !== 'string' || !/^[a-z][a-z0-9_.-]{0,95}$(?![\s\S])/.test(m.key) || m.kind !== 'PROFILE' && m.kind !== 'PREFERENCE' || !valid_memory_text(m.value, 2000, 8000) || !valid_memory_text(m.change_reason, 300, 1200) || !Number.isSafeInteger(m.version) || (m.version as number) < 1 || s.type !== 'EXPLICIT_OWNER_FORM') return fail();
  const confirmed_at = instant(m.confirmed_at), created_at = instant(m.created_at), updated_at = instant(m.updated_at), submitted_at = instant(s.submitted_at);
  const created = task_time_micros(created_at), updated = task_time_micros(updated_at), confirmed = task_time_micros(confirmed_at), submitted = task_time_micros(submitted_at);
  if (created > updated || updated > exported || confirmed !== updated || submitted !== confirmed) return fail();
  return { id: m.id, key: m.key, kind: m.kind, value: m.value, change_reason: m.change_reason, version: m.version as number, confirmed_at, created_at, updated_at, source: { type: 'EXPLICIT_OWNER_FORM', submitted_at } };
}
export function read_export_document(value: unknown, expected: ExportJob): ExportDocument {
  const d = object(value, ['schema_version', 'kind', 'scope', 'project_id', 'export_job_id', 'exported_at', 'semantics', 'items']);
  const binding = scope(d, expected), exported_at = instant(d.exported_at), exported = task_time_micros(exported_at);
  if (d.schema_version !== 1 || d.kind !== 'EXPLICIT_MEMORIES' || d.semantics !== 'CURRENT_AT_DOWNLOAD' || d.export_job_id !== expected.id || expected.status !== 'AVAILABLE' || !Array.isArray(d.items) || d.items.length > EXPORT_MAX_ITEMS || exported < task_time_micros(expected.created_at) || exported >= task_time_micros(expected.expires_at)) return fail();
  const items = d.items.map(value => item(value, exported));
  if (items.some((m, index) => index > 0 && items[index - 1].id >= m.id) || new Set(items.map(m => m.key)).size !== items.length) return fail();
  return { ...binding, schema_version: 1, kind: 'EXPLICIT_MEMORIES', export_job_id: expected.id, exported_at, semantics: 'CURRENT_AT_DOWNLOAD', items };
}
/** Reject duplicate JSON object keys, including escaped aliases, before retaining raw bytes in a Blob. */
function unique_json_keys(text: string): void {
  let i = 0;
  const whitespace = () => { while (i < text.length && /[\t\n\r ]/.test(text[i])) i++; };
  function string(): string {
    const start = i++;
    while (i < text.length) { const c = text[i++]; if (c === '\\') i++; else if (c === '"') return JSON.parse(text.slice(start, i)) as string; }
    return fail();
  }
  function value(depth: number): void {
    if (depth > 12) return fail(); whitespace();
    const first = text[i];
    if (first === '"') { string(); return; }
    if (first === '{') {
      i++; whitespace(); const keys = new Set<string>(); if (text[i] === '}') { i++; return; }
      for (;;) { whitespace(); if (text[i] !== '"') return fail(); const key = string(); if (keys.has(key)) return fail(); keys.add(key); whitespace(); if (text[i++] !== ':') return fail(); value(depth + 1); whitespace(); const separator = text[i++]; if (separator === '}') return; if (separator !== ',') return fail(); }
    }
    if (first === '[') {
      i++; whitespace(); if (text[i] === ']') { i++; return; }
      for (;;) { value(depth + 1); whitespace(); const separator = text[i++]; if (separator === ']') return; if (separator !== ',') return fail(); }
    }
    const start = i; while (i < text.length && !/[\t\n\r ,}\]]/.test(text[i])) i++; if (i === start) return fail();
    const token = text.slice(start, i);
    if (token !== 'null' && token !== 'true' && token !== 'false' && (!/^(?:0|[1-9][0-9]*)$(?![\s\S])/.test(token) || !Number.isSafeInteger(Number(token)))) return fail();
  }
  value(0); whitespace(); if (i !== text.length) return fail();
}
/** Private document exists only during this explicit download; callers must not persist or display it. */
export function parse_export_bytes(bytes: Uint8Array, expected: ExportJob): ExportDocument {
  if ((!ArrayBuffer.isView(bytes) || Object.prototype.toString.call(bytes) !== '[object Uint8Array]') || bytes.byteLength === 0 || bytes.byteLength > EXPORT_MAX_BYTES) return fail();
  try {
    const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
    const value: unknown = JSON.parse(text); unique_json_keys(text);
    return read_export_document(value, expected);
  } catch { return fail(); }
}
export function export_time(value: string): string {
  return `${new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date(instant(value)))} KST`;
}
