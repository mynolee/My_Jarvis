import { describe, expect, it } from 'vitest';
import { read_usage, usage_checked_time, usage_count_keys, usage_timezone_supported, type UsagePeriod, type UsageSnapshot } from './usage';

function period(multiplier = 1): UsagePeriod {
  return {
    local_key: '2026-10-11', start_at: '2026-10-10T15:00:00Z', end_at: '2026-10-11T15:00:00Z', request_count: 6 * multiplier,
    counts: { measured: multiplier, pending: multiplier, in_progress: multiplier, unstarted: multiplier, uncertain: multiplier, legacy_unavailable: multiplier },
    measured: { request_count: multiplier, input_tokens: 80 * multiplier, output_tokens: 40 * multiplier, compute_ms: 1234 * multiplier, peak_memory_bytes: 4096 * multiplier, external_cost_usd_micro: null },
    conservative_reserved: { request_count: 5 * multiplier, max_compute_ms: 300000 * multiplier, max_output_tokens: 2560 * multiplier },
  };
}
function snapshot(): UsageSnapshot {
  return { checked_at: '2026-10-10T18:00:00.000001Z', timezone: 'Asia/Seoul', attribution: 'RESERVATION_CREATED_AT', quota_timezone: 'UTC', day: period(), month: { ...period(2), local_key: '2026-10', start_at: '2026-09-30T15:00:00Z', end_at: '2026-10-31T15:00:00Z' } };
}
function empty(value: UsagePeriod) {
  value.request_count = 0;
  for (const key of usage_count_keys) value.counts[key] = 0;
  value.measured = { request_count: 0, input_tokens: 0, output_tokens: 0, compute_ms: 0, peak_memory_bytes: null, external_cost_usd_micro: null };
  value.conservative_reserved = { request_count: 0, max_compute_ms: 0, max_output_tokens: 0 };
}
function utcSnapshot(checked_at = '2026-10-10T18:00:00.000001Z'): UsageSnapshot {
  const value = snapshot(); value.timezone = 'UTC'; value.checked_at = checked_at;
  for (const kind of ['day', 'month'] as const) {
    const local_key = checked_at.slice(0, kind === 'day' ? 10 : 7), start_at = `${local_key}${kind === 'month' ? '-01' : ''}T00:00:00Z`, end = new Date(start_at);
    if (kind === 'day') end.setUTCDate(end.getUTCDate() + 1); else end.setUTCMonth(end.getUTCMonth() + 1);
    empty(value[kind]); Object.assign(value[kind], { local_key, start_at, end_at: end.toISOString() });
  }
  return value;
}

describe('closed owner usage summary contract', () => {
  it('accepts six disjoint categories, additive actuals, observed MAX and separate retained reservations', () => {
    const value = snapshot(), result = read_usage(value);
    expect(result).toEqual(value);
    expect(result.day.measured.peak_memory_bytes).toBe(4096);
    expect(result.month.measured.peak_memory_bytes).toBe(8192);
    expect(result.day.conservative_reserved.request_count).toBeGreaterThan(result.day.counts.pending + result.day.counts.in_progress);
    expect(result).not.toBe(value); expect(result.day).not.toBe(value.day); expect(result.day.counts).not.toBe(value.day.counts);
    value.day.counts.measured = 999; expect(result.day.counts.measured).toBe(1);
  });
  it('accepts an empty snapshot with unavailable peak and monetary cost, never invented cost zero', () => {
    const value = snapshot(); empty(value.day); empty(value.month);
    const result = read_usage(value);
    expect(result.day.measured.peak_memory_bytes).toBeNull(); expect(result.month.measured.external_cost_usd_micro).toBeNull();
  });
  it('accepts an unmeasured historical request without pretending it consumed zero resources', () => {
    const value = snapshot(); empty(value.day); empty(value.month);
    for (const p of [value.day, value.month]) { p.request_count = 1; p.counts.legacy_unavailable = 1; }
    expect(read_usage(value).day.counts.legacy_unavailable).toBe(1);
  });
  it('retains a supported alias or a server-supported timezone missing from browser TZDB', () => {
    expect(read_usage(utcSnapshot()).timezone).toBe('UTC');
    const alias = snapshot(); alias.timezone = 'US/Eastern';
    Object.assign(alias.day, { local_key: '2026-10-10', start_at: '2026-10-10T04:00:00Z', end_at: '2026-10-11T04:00:00Z' });
    Object.assign(alias.month, { start_at: '2026-10-01T04:00:00Z', end_at: '2026-11-01T04:00:00Z' });
    expect(read_usage(alias).timezone).toBe('US/Eastern');
    for (const timezone of ['Etc/GMT-9', 'Server/New_Zone']) expect(read_usage({ ...snapshot(), timezone }).timezone).toBe(timezone);
    expect(usage_timezone_supported('Asia/Seoul')).toBe(true);
    expect(usage_timezone_supported('Server/New_Zone')).toBe(false);
  });
  it.each(['', 'KST', 'SystemV/EST5EDT', 'Asia/Seoul\n', 'Asia/Seoul<script>', 'Asia/ Seoul', 'A/'.repeat(41)])('rejects invalid timezone shape %j', timezone => {
    expect(() => read_usage({ ...snapshot(), timezone })).toThrow('INVALID_USAGE_RESPONSE');
  });
  it.each([
    ['root', (v: any) => { v.private_prompt = 'withheld'; }],
    ['period', (v: any) => { v.day.id = 'withheld'; }],
    ['counts', (v: any) => { v.day.counts.other = 0; }],
    ['measured', (v: any) => { v.day.measured.price = 1; }],
    ['reserved', (v: any) => { v.day.conservative_reserved.remaining = 1; }],
    ['missing root', (v: any) => { delete v.checked_at; }],
    ['missing count', (v: any) => { delete v.day.counts.uncertain; }],
    ['missing measured', (v: any) => { delete v.day.measured.peak_memory_bytes; }],
    ['missing reserved', (v: any) => { delete v.day.conservative_reserved.max_compute_ms; }],
    ['array', (v: any) => { v.day.counts = []; }],
    ['null', (v: any) => { v.month = null; }],
    ['wrong attribution', (v: any) => { v.attribution = 'COMPLETED_AT'; }],
    ['wrong quota timezone', (v: any) => { v.quota_timezone = 'Asia/Seoul'; }],
  ])('rejects malformed or expanded %s', (_, mutate) => {
    const value = snapshot(); mutate(value); expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
  });
  it.each([null, undefined, [], 0, 'summary'])('rejects non-object root %j', value => expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE'));
  it.each([-1, 0.1, Number.MAX_SAFE_INTEGER + 1, NaN, Infinity, '1', null, true])('rejects unsafe counter %j at every numeric leaf', invalid => {
    const paths = [
      ['request_count'], ...usage_count_keys.map(key => ['counts', key]),
      ...['request_count', 'input_tokens', 'output_tokens', 'compute_ms', 'peak_memory_bytes'].map(key => ['measured', key]),
      ...['request_count', 'max_compute_ms', 'max_output_tokens'].map(key => ['conservative_reserved', key]),
    ];
    for (const path of paths) {
      const value: any = snapshot(); let target = value.day;
      for (const key of path.slice(0, -1)) target = target[key];
      target[path.at(-1)!] = invalid;
      expect(() => read_usage(value), path.join('.')).toThrow('INVALID_USAGE_RESPONSE');
    }
  });
  it.each([
    ['disjoint sum', (p: UsagePeriod) => { p.request_count++; }],
    ['actual count', (p: UsagePeriod) => { p.measured.request_count++; }],
    ['input ceiling', (p: UsagePeriod) => { p.measured.input_tokens = 3585; }],
    ['output ceiling', (p: UsagePeriod) => { p.measured.output_tokens = 513; }],
    ['compute ceiling', (p: UsagePeriod) => { p.measured.compute_ms = 60001; }],
    ['missing observed peak', (p: UsagePeriod) => { p.measured.peak_memory_bytes = null; }],
    ['reserved count', (p: UsagePeriod) => { p.conservative_reserved.request_count = 7; }],
    ['reserved compute lower', (p: UsagePeriod) => { p.conservative_reserved.max_compute_ms = 4; }],
    ['reserved output lower', (p: UsagePeriod) => { p.conservative_reserved.max_output_tokens = 4; }],
    ['reserved compute upper', (p: UsagePeriod) => { p.conservative_reserved.max_compute_ms = 300001; }],
    ['reserved output upper', (p: UsagePeriod) => { p.conservative_reserved.max_output_tokens = 2561; }],
  ])('rejects inconsistent %s', (_, mutate) => { const value = snapshot(); mutate(value.day); expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE'); });
  it.each([0, 1, 0.01, '0', { currency: 'USD' }])('rejects monetary cost %j', cost => {
    const value: any = snapshot(); value.day.measured.external_cost_usd_micro = cost; expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
  });
  it.each(['input_tokens', 'output_tokens', 'compute_ms', 'peak_memory_bytes'])('rejects nonempty %s without measured requests', key => {
    const value: any = snapshot(); empty(value.day); value.day.measured[key] = 1; expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
  });
  it('rejects a false zero-memory observation when no requests were measured', () => {
    const value = snapshot(); empty(value.day); value.day.measured.peak_memory_bytes = 0; expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
  });
  it.each(['max_compute_ms', 'max_output_tokens'])('rejects nonzero %s with zero reserved requests', key => {
    const value: any = snapshot(); empty(value.day); value.day.conservative_reserved[key] = 1; expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
  });
  it('uses exact integer arithmetic for count sums and counter ceilings near the safe bound', () => {
    const value = snapshot();
    for (const p of [value.day, value.month]) {
      empty(p); p.request_count = Number.MAX_SAFE_INTEGER; p.counts.measured = Number.MAX_SAFE_INTEGER;
      p.measured = { request_count: Number.MAX_SAFE_INTEGER, input_tokens: Number.MAX_SAFE_INTEGER, output_tokens: Number.MAX_SAFE_INTEGER, compute_ms: Number.MAX_SAFE_INTEGER, peak_memory_bytes: Number.MAX_SAFE_INTEGER, external_cost_usd_micro: null };
      p.conservative_reserved = { request_count: Number.MAX_SAFE_INTEGER, max_compute_ms: Number.MAX_SAFE_INTEGER, max_output_tokens: Number.MAX_SAFE_INTEGER };
    }
    expect(read_usage(value).day.request_count).toBe(Number.MAX_SAFE_INTEGER);
    value.day.counts.pending = 1; expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
  });
  it.each([
    ...usage_count_keys.map(key => [`count ${key}`, (v: UsageSnapshot) => { v.day.counts[key] = 3; v.day.request_count = 8; if (key === 'measured') v.day.measured.request_count = 3; }] as const),
    ...(['input_tokens', 'output_tokens', 'compute_ms', 'peak_memory_bytes'] as const).map(key => [`actual ${key}`, (v: UsageSnapshot) => { v.day.measured[key] = v.month.measured[key]! + 1; }] as const),
    ...(['request_count', 'max_compute_ms', 'max_output_tokens'] as const).map(key => [`reserved ${key}`, (v: UsageSnapshot) => { v.month.conservative_reserved = { request_count: 1, max_compute_ms: 5000, max_output_tokens: 128 }; v.day.conservative_reserved = { request_count: 1, max_compute_ms: 5000, max_output_tokens: 128 }; v.day.conservative_reserved[key]++; }] as const),
  ])('rejects day/month subset violation for %s', (_, mutate) => { const value = snapshot(); mutate(value); expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE'); });
  it.each(['input_tokens', 'output_tokens', 'compute_ms', 'peak_memory_bytes'] as const)('equal measured counts require identical %s', key => {
    const value = snapshot(); value.month.counts.measured = 1; value.month.request_count = 11; value.month.measured = { ...value.day.measured };
    expect(read_usage(value).month.measured).toEqual(value.day.measured);
    value.month.measured[key]!++; expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
  });
  it.each([['input_tokens', 3584], ['output_tokens', 512], ['compute_ms', 60000]] as const)('bounds the remaining monthly %s by remaining measured requests', (key, cap) => {
    const value = snapshot(); value.month.measured[key] = value.day.measured[key] + cap + 1;
    expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
    value.month.measured[key]--; expect(read_usage(value).month.measured[key]).toBe(value.day.measured[key] + cap);
  });
  it.each(['max_compute_ms', 'max_output_tokens'] as const)('equal retained request counts require identical %s', key => {
    const value = snapshot(); value.day.conservative_reserved = { request_count: 1, max_compute_ms: 1000, max_output_tokens: 100 }; value.month.conservative_reserved = { ...value.day.conservative_reserved };
    expect(read_usage(value).month.conservative_reserved).toEqual(value.day.conservative_reserved);
    value.month.conservative_reserved[key]++; expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
  });
  it.each([['max_compute_ms', 60000], ['max_output_tokens', 512]] as const)('bounds the remaining monthly %s by remaining retained requests', (key, cap) => {
    const value = snapshot(); value.day.conservative_reserved = { request_count: 1, max_compute_ms: 1000, max_output_tokens: 100 }; value.month.conservative_reserved = { request_count: 2, max_compute_ms: 2000, max_output_tokens: 200 };
    value.month.conservative_reserved[key] = value.day.conservative_reserved[key]; expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
    value.month.conservative_reserved[key] = value.day.conservative_reserved[key] + cap + 1; expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
    value.month.conservative_reserved[key]--; expect(read_usage(value).month.conservative_reserved[key]).toBe(value.day.conservative_reserved[key] + cap);
  });
  it('accepts the same observed MAX across different measured record counts', () => {
    const value = snapshot(); value.month.measured.peak_memory_bytes = value.day.measured.peak_memory_bytes;
    expect(read_usage(value).month.measured.peak_memory_bytes).toBe(4096);
  });
});

describe('usage calendar and check timestamps', () => {
  it.each(['2026-02-30T18:00:00Z', '0000-10-10T18:00:00Z', '2026-10-10T24:00:00Z', '2026-10-10T18:00:60Z', '2026-10-10T18:00:00+00:00', '2026-10-10T18:00:00.0000001Z', '2026-10-10T18:00:00Z\n'])('rejects timestamp %j', checked_at => {
    expect(() => read_usage({ ...snapshot(), checked_at })).toThrow('INVALID_USAGE_RESPONSE');
  });
  it.each([
    ['day missing date', (v: UsageSnapshot) => { v.day.local_key = '2026-10'; }],
    ['invalid civil day', (v: UsageSnapshot) => { v.day.local_key = '2026-10-32'; }],
    ['month too specific', (v: UsageSnapshot) => { v.month.local_key = '2026-10-01'; }],
    ['mismatched month', (v: UsageSnapshot) => { v.month.local_key = '2026-09'; }],
    ['day begins after check', (v: UsageSnapshot) => { v.day.start_at = '2026-10-10T18:00:00.000002Z'; }],
    ['day end equals check', (v: UsageSnapshot) => { v.day.end_at = v.checked_at; }],
    ['month end equals check', (v: UsageSnapshot) => { v.month.end_at = v.checked_at; }],
    ['day outside month start', (v: UsageSnapshot) => { v.month.start_at = '2026-10-10T17:00:00Z'; }],
    ['day outside month end', (v: UsageSnapshot) => { v.month.end_at = '2026-10-11T14:59:59Z'; }],
  ])('rejects inconsistent %s', (_, mutate) => { const value = snapshot(); mutate(value); expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE'); });
  it('compares half-open bounds at microsecond precision', () => {
    const value = snapshot(); value.checked_at = value.day.start_at;
    expect(read_usage(value).checked_at).toBe(value.day.start_at);
    value.checked_at = '2026-10-10T14:59:59.999999Z'; expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
    value.checked_at = '2026-10-11T14:59:59.999999Z'; expect(read_usage(value).checked_at).toBe(value.checked_at);
    value.checked_at = value.day.end_at; expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
  });
  it.each(['UTC', 'Asia/Seoul', 'Server/New_Zone'])('rejects fabricated old calendar keys and multi-year windows in %s', timezone => {
    const value = snapshot(); value.timezone = timezone;
    Object.assign(value.day, { local_key: '2020-01-01', start_at: '2019-01-01T00:00:00Z', end_at: '2027-01-01T00:00:00Z' });
    Object.assign(value.month, { local_key: '2020-01', start_at: '2019-01-01T00:00:00Z', end_at: '2027-01-01T00:00:00Z' });
    expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
  });
  it.each(['day', 'month'] as const)('rejects non-UTC %s boundaries too far from the declared civil midnight', kind => {
    const value = snapshot(); value.timezone = 'Server/New_Zone';
    value[kind].start_at = kind === 'day' ? '2026-10-10T05:59:59Z' : '2026-09-30T05:59:59Z';
    expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
    const next = snapshot(); next.timezone = 'Server/New_Zone';
    next[kind].end_at = kind === 'day' ? '2026-10-12T18:00:01Z' : '2026-11-01T18:00:01Z';
    expect(() => read_usage(next)).toThrow('INVALID_USAGE_RESPONSE');
  });
  it('rejects a one-microsecond day and fractional timezone boundaries', () => {
    const tiny = snapshot(); tiny.day.start_at = '2026-10-10T18:00:00.000001Z'; tiny.day.end_at = '2026-10-10T18:00:00.000002Z';
    expect(() => read_usage(tiny)).toThrow('INVALID_USAGE_RESPONSE');
    for (const kind of ['day', 'month'] as const) for (const key of ['start_at', 'end_at'] as const) {
      const value = snapshot(); value[kind][key] = value[kind][key].replace('Z', '.000001Z');
      expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
    }
  });
  it.each([
    ['2024-02-29T23:59:59.999999Z', '2024-03-01T00:00:00.000Z', '2024-03-01T00:00:00.000Z'],
    ['2026-12-31T23:59:59.999999Z', '2027-01-01T00:00:00.000Z', '2027-01-01T00:00:00.000Z'],
    ['0001-01-01T00:00:00Z', '0001-01-02T00:00:00.000Z', '0001-02-01T00:00:00.000Z'],
  ])('validates exact UTC calendar including rollover for %s', (checked, day_end, month_end) => {
    const value = utcSnapshot(checked); expect(read_usage(value).day.end_at).toBe(day_end); expect(read_usage(value).month.end_at).toBe(month_end);
  });
  it('accepts equivalent whole-second UTC serialization without requiring identical formatting', () => {
    const value = utcSnapshot(); value.day.start_at = '2026-10-10T00:00:00.000000Z';
    expect(read_usage(value).day.start_at).toBe(value.day.start_at);
  });
  it.each([
    ['wrong current day', (v: UsageSnapshot) => { v.day.local_key = '2026-10-11'; }],
    ['wrong current month', (v: UsageSnapshot) => { v.month.local_key = '2026-09'; }],
    ['nonmidnight day start', (v: UsageSnapshot) => { v.day.start_at = '2026-10-10T01:00:00Z'; }],
    ['nonmidnight day end', (v: UsageSnapshot) => { v.day.end_at = '2026-10-11T01:00:00Z'; }],
    ['nonmidnight month start', (v: UsageSnapshot) => { v.month.start_at = '2026-10-01T01:00:00Z'; }],
    ['nonmidnight month end', (v: UsageSnapshot) => { v.month.end_at = '2026-11-01T01:00:00Z'; }],
  ])('rejects UTC %s even when the check is contained', (_, mutate) => {
    const value = utcSnapshot(); mutate(value); expect(() => read_usage(value)).toThrow('INVALID_USAGE_RESPONSE');
  });
  it('rejects a Seoul calendar mislabeled as UTC', () => {
    expect(() => read_usage({ ...snapshot(), timezone: 'UTC' })).toThrow('INVALID_USAGE_RESPONSE');
  });
  it.each([
    ['2026-03-08', '2026-03-08T05:00:00Z', '2026-03-09T04:00:00Z', '2026-03-01T05:00:00Z', '2026-04-01T04:00:00Z'],
    ['2026-11-01', '2026-11-01T04:00:00Z', '2026-11-02T05:00:00Z', '2026-11-01T04:00:00Z', '2026-12-01T05:00:00Z'],
  ])('preserves variable-length profile civil day %s', (local_key, start_at, end_at, month_start, month_end) => {
    const value = snapshot(); value.timezone = 'America/New_York'; value.checked_at = `${local_key}T15:00:00Z`;
    Object.assign(value.day, { local_key, start_at, end_at }); Object.assign(value.month, { local_key: local_key.slice(0, 7), start_at: month_start, end_at: month_end });
    expect(read_usage(value).day.end_at).toBe(end_at);
  });
  it.each([
    ['Pacific/Apia', '2011-12-29', '2011-12-29T10:00:00Z', '2011-12-30T10:00:00Z', '2011-12-01T10:00:00Z', '2011-12-31T10:00:00Z', '2011-12-29T18:00:00Z'],
    ['Pacific/Kwajalein', '1969-09-30', '1969-09-29T13:00:00Z', '1969-10-01T12:00:00Z', '1969-08-31T13:00:00Z', '1969-10-01T12:00:00Z', '1969-09-30T18:00:00Z'],
    ['America/Santiago', '2026-09-06', '2026-09-06T04:00:00Z', '2026-09-07T03:00:00Z', '2026-09-01T04:00:00Z', '2026-10-01T03:00:00Z', '2026-09-06T18:00:00Z'],
  ])('preserves server-owned date-line/skipped-midnight boundaries for %s', (timezone, local_key, start_at, end_at, month_start, month_end, checked_at) => {
    const value = snapshot(); value.timezone = timezone; value.checked_at = checked_at;
    Object.assign(value.day, { local_key, start_at, end_at }); Object.assign(value.month, { local_key: local_key.slice(0, 7), start_at: month_start, end_at: month_end });
    expect(read_usage(value).day).toEqual(value.day);
  });
  it('shows the checked instant in KST regardless of profile timezone', () => {
    const result = usage_checked_time(snapshot().checked_at);
    expect(result).toContain('2026. 10. 11.'); expect(result).toContain('03:00:00'); expect(result).toContain('KST');
    expect(() => usage_checked_time('invalid')).toThrow('INVALID_USAGE_RESPONSE');
  });
});
