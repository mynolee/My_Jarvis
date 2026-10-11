import { afterEach, expect, it, vi } from 'vitest';
import { Api } from './api';
import { read_durable_study } from './study';
import { answer, at, attempt, cid, input, key, lesson, other, outcome, q1, q2, run, sid, study } from '../tests/study-fixtures';
const wire = (data: unknown) => JSON.stringify({ data, request_id: key });
const response = (text: string, status = 200, headers: Record<string, string> = { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }) => new Response(text, { status, headers });
const api = () => { const a = new Api(); a.set_csrf('synthetic-csrf'); return a; };
const body = { expected_version: 1, expected_conversation_version: 11, question_id: answer().operation === 'ANSWER' ? (answer() as any).question_id : '', text: '  Exact new answer\n😀  ' };
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });
it('sends one dedicated same-origin no-store POST with only exact owner input fields and one key', async () => {
  const fetch = vi.fn(async () => response(wire(outcome(answer())))); vi.stubGlobal('fetch', fetch); await api().request(`/study-sessions/${sid}/answers`, body, key);
  expect(fetch).toHaveBeenCalledTimes(1); expect(fetch.mock.calls[0]).toEqual([`/api/v1/study-sessions/${sid}/answers`, expect.objectContaining({ method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error', headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-CSRF-Token': 'synthetic-csrf', 'Idempotency-Key': key }, body: JSON.stringify(body) })]);
});
it.each([['duplicate', `{"data":{},"data":{},"request_id":"${key}"}`], ['float', wire(outcome(answer())).replace('"resulting_version":2', '"resulting_version":2.0')], ['exponent', wire(outcome(answer())).replace('"resulting_version":2', '"resulting_version":2e0')], ['envelope extension', JSON.stringify({ data: {}, request_id: key, raw: 'answer' })], ['missing request id', JSON.stringify({ data: {} })], ['malformed', '{']])('treats malformed 2xx %s as unknown, without any resend', async (_, text) => {
  const fetch = vi.fn(async () => response(text)); vi.stubGlobal('fetch', fetch); await expect(api().request(`/study-sessions/${sid}/answers`, body, key)).rejects.toMatchObject({ uncertain: true }); expect(fetch).toHaveBeenCalledTimes(1);
});
it.each(['text/plain', 'application/json;bad=1', 'application/jsonp'])('does not accept noncanonical content type %s', async content => { vi.stubGlobal('fetch', vi.fn(async () => response(wire(outcome(answer())), 200, { 'Content-Type': content }))); await expect(api().request(`/study-sessions/${sid}/answers`, body, key)).rejects.toMatchObject({ uncertain: true }); });
it('bounds envelope-inclusive index to 64KiB while allowing a real two-answer detail over64KiB below256KiB', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => response(wire({ ...study(), opaque: 'x'.repeat(65536) })))); await expect(api().request(`/conversations/${cid}/study-index?limit=20`)).rejects.toMatchObject({ code: 'RESPONSE_TOO_LARGE' });
  const large = { ...study(), version: 3, conversation_content_version: 13, stage: 'FEEDBACK_PENDING', lesson: { ...lesson, concepts: Array(3).fill('😀'.repeat(1000)) }, attempts: [{ input_id: input, attempt_id: attempt, question_id: q1, text: '😀'.repeat(8000), feedback: null }, { input_id: other, attempt_id: key, question_id: q2, text: '😀'.repeat(8000), feedback: null }] };
  const detail = wire(large); expect(new TextEncoder().encode(detail).length).toBeGreaterThan(65536); vi.stubGlobal('fetch', vi.fn(async () => response(detail))); expect(read_durable_study(await api().request(`/study-sessions/${sid}`), { id: sid, conversation_id: cid, lesson_run_id: run }).attempts).toHaveLength(2);
  vi.stubGlobal('fetch', vi.fn(async () => response(wire({ opaque: 'x'.repeat(262144) })))); await expect(api().request(`/study-sessions/${sid}`)).rejects.toMatchObject({ code: 'RESPONSE_TOO_LARGE' });
});
it('fences held response credentials after rotation', async () => {
  let resolve!: (response: Response) => void; vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(yes => { resolve = yes; }))); const a = api(); const promise = a.request(`/study-sessions/${sid}/answers`, body, key); a.set_csrf('new-session'); resolve(response(wire(outcome(answer())))); await expect(promise).rejects.toMatchObject({ code: 'STALE_SESSION', uncertain: true });
});
it('fails a stalled mutation at its deadline and discards an eventual late successful response', async () => {
  vi.useFakeTimers(); let resolve!: (response: Response) => void; const fetch = vi.fn(() => new Promise<Response>(yes => { resolve = yes; })); vi.stubGlobal('fetch', fetch); const pending = api().request(`/study-sessions/${sid}/answers`, body, key); const rejection = expect(pending).rejects.toMatchObject({ uncertain: true }); await vi.advanceTimersByTimeAsync(15001); await rejection; resolve(response(wire(outcome(answer())))); await Promise.resolve(); expect(fetch).toHaveBeenCalledTimes(1);
});
it('uses only metadata GET for outcome lookup with no raw answer in URL or body', async () => { const fetch = vi.fn(async () => response(wire({ status: 'NOT_OBSERVED', receipt: null, checked_at: at }))); vi.stubGlobal('fetch', fetch); await api().request(`/study-mutations/${key}`); expect(fetch.mock.calls[0]).toEqual([`/api/v1/study-mutations/${key}`, expect.objectContaining({ method: 'GET', body: undefined, headers: { Accept: 'application/json' } })]); });
