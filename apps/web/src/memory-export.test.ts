import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { EXPORT_FILENAME, EXPORT_MAX_BYTES, export_job_path, export_receipt_path, export_request, export_time, parse_export_bytes, read_export_document, read_export_job, read_export_receipt } from './memory-export';

const fixture = (name: string) => JSON.parse(readFileSync(`../../packages/contracts/fixtures/exports/${name}.json`, 'utf8'));
const global = { scope: 'GLOBAL' as const, project_id: null };
const job = read_export_job(fixture('job-available'), global);
const document = fixture('document-current');
const bytes = (v: unknown) => new TextEncoder().encode(JSON.stringify(v));
const project = '72000000-0000-4000-8000-000000000001';

describe('strict memory-export protocol', () => {
  it('accepts shared global/project creation and current/empty document fixtures', () => {
    expect(export_request(global)).toEqual(fixture('create-global'));
    const request = fixture('create-project'); expect(export_request(request)).toEqual(request);
    expect(read_export_document(document, job).items[0].value).toBe('가상의 기억 🐱');
    expect(read_export_document(fixture('document-empty'), job).items).toEqual([]);
    expect(parse_export_bytes(bytes(document), job)).toEqual(read_export_document(document, job));
  });
  it('accepts bound metadata and both receipt variants without file inference', () => {
    expect(read_export_receipt(fixture('receipt-committed'), global)).toEqual({ status: 'COMMITTED', job });
    expect(read_export_receipt(fixture('receipt-not-observed'), global)).toEqual({ status: 'NOT_OBSERVED', job: null });
    expect(read_export_job(fixture('job-expired'), global).status).toBe('EXPIRED');
    expect(EXPORT_FILENAME).toBe('my-jarvis-memories.json');
  });
  it.each([
    { scope: 'GLOBAL', project_id: project }, { scope: 'PROJECT', project_id: null }, { scope: 'ALL', project_id: null }, { scope: 'PROJECT', project_id: `${project}\n` },
  ])('rejects invalid scope %j', (value) => { expect(() => export_request(value as never)).toThrow(); });
  it.each(['../x', `${job.id}\n`, 'https://x.invalid', job.id.toUpperCase().replace('000', 'ABC'), ''])('rejects untrusted route key %j', (id) => {
    expect(() => export_job_path(id)).toThrow(); expect(() => export_receipt_path(id)).toThrow();
  });
  it('only creates fixed local metadata paths', () => {
    expect(export_job_path(job.id)).toBe(`/exports/${job.id}`); expect(export_receipt_path(job.id)).toBe(`/exports/requests/${job.id}`);
  });
  it.each([
    { secret: 'application-column' }, { scope: 'PROJECT', project_id: project }, { status: 'READY' }, { checked_at: '2026-10-10T12:15:00Z' }, { expires_at: '2026-10-10T11:59:59Z' }, { expires_at: '2026-10-10T12:14:59Z' }, { expires_at: '2026-10-10T12:15:00.000001Z' }, { checked_at: '2026-10-10T11:59:59Z' }, { created_at: '0000-01-01T00:00:00Z' }, { checked_at: '2026-02-30T12:00:00Z' }, { checked_at: '2026-10-10T12:00:00+00:00' }, { checked_at: '2026-10-10T12:00:00Z\n' },
  ])('rejects malformed or mismatched job metadata %j', (patch) => { expect(() => read_export_job({ ...job, ...patch }, global)).toThrow(); });
  it('rejects stale, rebound or revived status reads', () => {
    const previous = { ...job, checked_at: '2026-10-10T12:01:00Z' };
    expect(() => read_export_job(job, global, previous)).toThrow();
    expect(() => read_export_job({ ...job, id: project }, global, job)).toThrow();
    expect(() => read_export_job({ ...job, expires_at: '2026-10-10T12:16:00Z' }, global, job)).toThrow();
    expect(() => read_export_job(job, global, read_export_job(fixture('job-expired'), global))).toThrow();
  });
  it.each([{ status: 'NOT_OBSERVED', job }, { status: 'COMMITTED', job: null }, { status: 'FAILED', job: null }, { status: 'NOT_OBSERVED', job: null, value: 'leak' }])('rejects malformed receipt %j', (v) => { expect(() => read_export_receipt(v, global)).toThrow(); });
  it.each([
    { schema_version: 2 }, { export_job_id: project }, { scope: 'PROJECT', project_id: project }, { semantics: 'AT_CREATE' }, { credentials: 'leak' }, { exported_at: '2026-10-10T11:59:59Z' }, { exported_at: '2026-10-10T12:15:00Z' }, { items: Array(1001).fill(document.items[0]) }, { items: [document.items[0], document.items[0]] },
  ])('rejects unsafe document %j', (patch) => { expect(() => read_export_document({ ...document, ...patch }, job)).toThrow(); });
  it.each([
    { value: '\0' }, { value: '\uD800' }, { value: ' '.repeat(20) }, { value: 'x'.repeat(2001) }, { change_reason: 'x'.repeat(301) }, { version: 0 }, { version: 1.5 }, { version: Number.MAX_SAFE_INTEGER + 1 }, { key: 'trailing\n' }, { generation: 1 }, { deleted_at: null }, { source: { type: 'EXPLICIT_OWNER_FORM', submitted_at: '2026-10-10T11:00:00Z', source_hash: 'a'.repeat(64) } }, { updated_at: '2026-10-10T12:02:00Z' }, { confirmed_at: '2026-10-10T10:59:59Z' }, { source: { type: 'EXPLICIT_OWNER_FORM', submitted_at: '2026-10-10T10:59:59Z' } },
  ])('rejects unsafe item %j', (patch) => { expect(() => read_export_document({ ...document, items: [{ ...document.items[0], ...patch }] }, job)).toThrow(); });
  it('preserves user text literally and accepts exactly 1000 unique current rows', () => {
    const value = '<script>alert(1)</script> https://example.invalid/?token=text 🐱';
    expect(read_export_document({ ...document, items: [{ ...document.items[0], value }] }, job).items[0].value).toBe(value);
    const items = Array.from({ length: 1000 }, (_, i) => ({ ...document.items[0], id: `71000000-0000-4000-8000-${i.toString(16).padStart(12, '0')}`, key: `key.${i}` }));
    expect(read_export_document({ ...document, items }, job).items).toHaveLength(1000);
  });
  it('rejects duplicate identities even when their other fields differ', () => {
    expect(() => read_export_document({ ...document, items: [document.items[0], { ...document.items[0], id: project }] }, job)).toThrow();
    expect(() => read_export_document({ ...document, items: [document.items[0], { ...document.items[0], key: 'different' }] }, job)).toThrow();
  });
  it('binds project documents to exactly one job and selected project', () => {
    const projectJob = read_export_job({ ...job, scope: 'PROJECT', project_id: project }, { scope: 'PROJECT', project_id: project });
    expect(read_export_document({ ...document, scope: 'PROJECT', project_id: project }, projectJob).project_id).toBe(project);
    expect(() => read_export_document(document, projectJob)).toThrow();
  });
  it('rejects malformed, non-UTF8, BOM-prefixed, empty, and oversized raw bytes', () => {
    for (const b of [new Uint8Array(), new Uint8Array([0xff]), bytes('{no'), new Uint8Array(EXPORT_MAX_BYTES + 1), new TextEncoder().encode('\uFEFF' + JSON.stringify(document))]) expect(() => parse_export_bytes(b, job)).toThrow();
  });
  it('rejects duplicate property names including escaped aliases and nested duplicates', () => {
    const raw = JSON.stringify(document);
    for (const replaced of [raw.replace('"schema_version":1', '"schema_version":1,"schema_version":1'), raw.replace('"schema_version":1', '"schema_version":1,"schema_\\u0076ersion":1'), raw.replace('"value":', '"value":"hidden secret","value":')]) expect(() => parse_export_bytes(new TextEncoder().encode(replaced), job)).toThrow();
  });
  it('rejects non-integer lexical numbers and noncanonical item order', () => {
    for (const n of ['1.0', '1e0', '-0', '9007199254740992']) expect(() => parse_export_bytes(new TextEncoder().encode(JSON.stringify(document).replace('"schema_version":1', `"schema_version":${n}`)), job)).toThrow();
    expect(() => read_export_document({ ...document, items: [{ ...document.items[0], id: project, key: 'first' }, document.items[0]] }, job)).toThrow();
  });
  it('supports escaped quotes, braces and slashes in literal user text', () => {
    const value = '\\ "} ] , ": \\n'; expect(parse_export_bytes(bytes({ ...document, items: [{ ...document.items[0], value }] }), job).items[0].value).toBe(value);
  });
  it('uses Korean Standard Time presentation while preserving UTC machine text', () => {
    expect(export_time('2026-10-10T18:00:00.123456Z')).toContain('2026. 10. 11.');
    expect(export_time('2026-10-10T18:00:00.123456Z')).toContain('03:00:00');
    expect(export_time('2026-10-10T18:00:00.123456Z')).toContain('KST');
  });
});
