import { ApiError } from './api';
import { task_time_micros, valid_date } from './tasks';

export const usage_count_keys = ['measured', 'pending', 'in_progress', 'unstarted', 'uncertain', 'legacy_unavailable'] as const;
export type UsageCounts = Record<typeof usage_count_keys[number], number>;
export interface UsageMeasured {
  request_count: number; input_tokens: number; output_tokens: number; compute_ms: number;
  peak_memory_bytes: number | null; external_cost_usd_micro: null;
}
export interface UsageReserved { request_count: number; max_compute_ms: number; max_output_tokens: number }
export interface UsagePeriod {
  local_key: string; start_at: string; end_at: string; request_count: number;
  counts: UsageCounts; measured: UsageMeasured; conservative_reserved: UsageReserved;
}
export interface UsageSnapshot {
  checked_at: string; timezone: string; attribution: 'RESERVATION_CREATED_AT'; quota_timezone: 'UTC';
  day: UsagePeriod; month: UsagePeriod;
}

const fail = (): never => { throw new ApiError('INVALID_USAGE_RESPONSE'); };
function object(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== keys.length || !keys.every(key => Object.hasOwn(value, key))) return fail();
  return value as Record<string, unknown>;
}
function counter(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : fail();
}
function instant(value: unknown): string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$(?![\s\S])/.test(value) && valid_date(value) ? value : fail();
}
function measured(value: unknown, count: number): UsageMeasured {
  const m = object(value, ['request_count', 'input_tokens', 'output_tokens', 'compute_ms', 'peak_memory_bytes', 'external_cost_usd_micro']);
  const request_count = counter(m.request_count), input_tokens = counter(m.input_tokens), output_tokens = counter(m.output_tokens), compute_ms = counter(m.compute_ms);
  const peak_memory_bytes = m.peak_memory_bytes === null ? null : counter(m.peak_memory_bytes);
  if (request_count !== count || m.external_cost_usd_micro !== null || (count === 0 ? peak_memory_bytes !== null : peak_memory_bytes === null)) return fail();
  // Multiply with BigInt: every wire counter is safe, but a product/sum may not be.
  if (BigInt(input_tokens) > BigInt(count) * 3584n || BigInt(output_tokens) > BigInt(count) * 512n || BigInt(compute_ms) > BigInt(count) * 60000n) return fail();
  return { request_count, input_tokens, output_tokens, compute_ms, peak_memory_bytes, external_cost_usd_micro: null };
}
function reserved(value: unknown, total: number): UsageReserved {
  const r = object(value, ['request_count', 'max_compute_ms', 'max_output_tokens']);
  const request_count = counter(r.request_count), max_compute_ms = counter(r.max_compute_ms), max_output_tokens = counter(r.max_output_tokens);
  const count = BigInt(request_count);
  if (request_count > total || BigInt(max_compute_ms) < count || BigInt(max_compute_ms) > count * 60000n || BigInt(max_output_tokens) < count || BigInt(max_output_tokens) > count * 512n) return fail();
  return { request_count, max_compute_ms, max_output_tokens };
}
function period(value: unknown, kind: 'day' | 'month', checked: bigint): UsagePeriod {
  const p = object(value, ['local_key', 'start_at', 'end_at', 'request_count', 'counts', 'measured', 'conservative_reserved']);
  const pattern = kind === 'day' ? /^\d{4}-\d{2}-\d{2}$(?![\s\S])/ : /^\d{4}-\d{2}$(?![\s\S])/;
  if (typeof p.local_key !== 'string' || !pattern.test(p.local_key) || !valid_date(`${p.local_key}${kind === 'month' ? '-01' : ''}T00:00:00Z`)) return fail();
  const start_at = instant(p.start_at), end_at = instant(p.end_at), request_count = counter(p.request_count);
  if (task_time_micros(start_at) > checked || checked >= task_time_micros(end_at)) return fail();
  const c = object(p.counts, usage_count_keys), counts = {} as UsageCounts;
  let sum = 0n;
  for (const key of usage_count_keys) { counts[key] = counter(c[key]); sum += BigInt(counts[key]); }
  if (sum !== BigInt(request_count)) return fail();
  return { local_key: p.local_key, start_at, end_at, request_count, counts, measured: measured(p.measured, counts.measured), conservative_reserved: reserved(p.conservative_reserved, request_count) };
}

function calendar_period(p: UsagePeriod, kind: 'day' | 'month', timezone: string, checked_at: string) {
  const nominal_start = new Date(`${p.local_key}${kind === 'month' ? '-01' : ''}T00:00:00Z`);
  const nominal_end = new Date(nominal_start.getTime());
  if (kind === 'day') nominal_end.setUTCDate(nominal_end.getUTCDate() + 1);
  else nominal_end.setUTCMonth(nominal_end.getUTCMonth() + 1);
  const start = task_time_micros(p.start_at), end = task_time_micros(p.end_at);
  const civil_start = BigInt(nominal_start.getTime()) * 1000n, civil_end = BigInt(nominal_end.getTime()) * 1000n;
  // Zone offsets and TZDB transition boundaries have whole-second precision.
  if (start % 1000000n !== 0n || end % 1000000n !== 0n) return fail();
  if (timezone === 'UTC') {
    if (p.local_key !== checked_at.slice(0, kind === 'day' ? 10 : 7) || start !== civil_start || end !== civil_end) return fail();
    return;
  }
  // Necessary bounds only, independent of the browser's timezone database.
  // Java ZoneOffset is within +/-18h. Even when midnight is skipped, its
  // transition lies between midnight projected using the pre/post offsets.
  // These bounds allow long/date-line days: day <=60h, month nominal +/-36h.
  const maximum_offset = 18n * 60n * 60n * 1000000n;
  if (start < civil_start - maximum_offset || start > civil_start + maximum_offset || end < civil_end - maximum_offset || end > civil_end + maximum_offset) return fail();
}

/** UTC is exact here; non-UTC TZDB correctness remains server-owned after sanity checks. */
export function read_usage(value: unknown): UsageSnapshot {
  const u = object(value, ['checked_at', 'timezone', 'attribution', 'quota_timezone', 'day', 'month']);
  const checked_at = instant(u.checked_at);
  if (typeof u.timezone !== 'string' || u.timezone.length > 80 || !/^(?:UTC|(?!SystemV\/)[A-Za-z0-9._+-]+(?:\/[A-Za-z0-9._+-]+)+)$(?![\s\S])/.test(u.timezone) || u.attribution !== 'RESERVATION_CREATED_AT' || u.quota_timezone !== 'UTC') return fail();
  const checked = task_time_micros(checked_at), day = period(u.day, 'day', checked), month = period(u.month, 'month', checked);
  calendar_period(day, 'day', u.timezone, checked_at); calendar_period(month, 'month', u.timezone, checked_at);
  if (!day.local_key.startsWith(`${month.local_key}-`) || task_time_micros(day.start_at) < task_time_micros(month.start_at) || task_time_micros(day.end_at) > task_time_micros(month.end_at) || day.request_count > month.request_count) return fail();
  for (const key of usage_count_keys) if (day.counts[key] > month.counts[key]) return fail();
  for (const key of ['request_count', 'input_tokens', 'output_tokens', 'compute_ms'] as const) if (day.measured[key] > month.measured[key]) return fail();
  if (day.measured.peak_memory_bytes !== null && (month.measured.peak_memory_bytes === null || day.measured.peak_memory_bytes > month.measured.peak_memory_bytes)) return fail();
  for (const key of ['request_count', 'max_compute_ms', 'max_output_tokens'] as const) if (day.conservative_reserved[key] > month.conservative_reserved[key]) return fail();
  // The records outside the day are also a valid subset of this same snapshot.
  const measured_delta = BigInt(month.measured.request_count) - BigInt(day.measured.request_count);
  for (const [key, cap] of [['input_tokens', 3584n], ['output_tokens', 512n], ['compute_ms', 60000n]] as const) {
    if (BigInt(month.measured[key]) - BigInt(day.measured[key]) > measured_delta * cap) return fail();
  }
  if (measured_delta === 0n && day.measured.peak_memory_bytes !== month.measured.peak_memory_bytes) return fail();
  const reserved_delta = BigInt(month.conservative_reserved.request_count) - BigInt(day.conservative_reserved.request_count);
  for (const [key, cap] of [['max_compute_ms', 60000n], ['max_output_tokens', 512n]] as const) {
    const delta = BigInt(month.conservative_reserved[key]) - BigInt(day.conservative_reserved[key]);
    if (delta < reserved_delta || delta > reserved_delta * cap) return fail();
  }
  return { checked_at, timezone: u.timezone, attribution: 'RESERVATION_CREATED_AT', quota_timezone: 'UTC', day, month };
}

/** Browser TZDB support affects presentation only; it cannot replace the saved owner zone. */
export function usage_timezone_supported(timezone: string): boolean {
  try { new Intl.DateTimeFormat('ko-KR', { timeZone: timezone }).format(0); return true; } catch { return false; }
}
export function usage_checked_time(value: string): string {
  return `${new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date(instant(value)))} KST`;
}
