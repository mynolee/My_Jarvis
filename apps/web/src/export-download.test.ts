import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';

const id = '11111111-1111-4111-8111-111111111111';
const byte_limit = 8 * 1024 * 1024;
const error_limit = 1024 * 1024;
const encode = (text: string) => new TextEncoder().encode(text);
const attachment_headers = {
  'content-type': 'application/json; charset=UTF-8',
  'content-disposition': 'attachment; filename="my-jarvis-memories.json"',
  'cache-control': 'no-store',
  'x-content-type-options': 'nosniff',
};
function response(body: BodyInit | null = '{}', overrides: Record<string, string | null> = {}, status = 200): Response {
  const headers = new Headers(attachment_headers);
  for (const [key, value] of Object.entries(overrides)) { if (value === null) headers.delete(key); else headers.set(key, value); }
  return new Response(body, { status, headers });
}
function chunked(chunks: Uint8Array[], overrides: Record<string, string | null> = {}, status = 200) {
  let index = 0;
  const cancel = vi.fn();
  const pull = vi.fn((controller: ReadableStreamDefaultController<Uint8Array>) => {
    if (index === chunks.length) controller.close(); else controller.enqueue(chunks[index++]);
  });
  const body = new ReadableStream<Uint8Array>({ pull, cancel }, { highWaterMark: 0 });
  return { response: response(body, overrides, status), body, cancel, pull };
}
function fetch_response(value: Response) {
  const fetcher = vi.fn().mockResolvedValue(value); vi.stubGlobal('fetch', fetcher); return fetcher;
}
function error_response(code: string, status: number, details: unknown = null) {
  return response(JSON.stringify({ error: { code, details, retryable: false } }), { 'content-disposition': null }, status);
}
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('bounded raw export download transport', () => {
  it('returns untouched bytes using only the fixed local GET route and read-only request options', async () => {
    const bytes = encode('{"synthetic":"한글"}');
    const incoming = chunked([bytes.slice(0, 4), bytes.slice(4)], { 'content-length': String(bytes.length) });
    const fetcher = fetch_response(incoming.response);
    const api = new Api(); api.set_csrf('memory-only-csrf');
    expect(Array.from(await api.download_export(id))).toEqual(Array.from(bytes));
    expect(fetcher).toHaveBeenCalledExactlyOnceWith(`/api/v1/exports/${id}/download`, {
      method: 'GET', credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: expect.any(AbortSignal),
      headers: { Accept: 'application/json' },
    });
    expect(incoming.body.locked).toBe(false);
    expect(incoming.cancel).not.toHaveBeenCalled();
    expect(fetcher.mock.calls[0][1].signal.aborted).toBe(false);
  });

  it('leaves full document and success UTF-8 validation to the dedicated UI parser', async () => {
    const bytes = new Uint8Array([0xff, 0xfe]); fetch_response(response(bytes));
    await expect(new Api().download_export(id)).resolves.toEqual(bytes);
  });

  it.each(['', '../escape', `${id}/download`, `${id}?scope=GLOBAL`, `${id}#fragment`, `${id}\n`, `${id}\r`,
    'AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA', 'https://example.invalid/file.json', `//evil.invalid/${id}`, null, 1])(
    'rejects noncanonical UUID %j before any fetch', async value => {
      const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher);
      await expect(new Api().download_export(value as string)).rejects.toMatchObject({ code: 'INVALID_EXPORT_ID', uncertain: false, status: 0 });
      expect(fetcher).not.toHaveBeenCalled();
    },
  );

  it.each([null, '', 'text/html', 'application/jsonp', 'application/problem+json', 'application/json; charset=ISO-8859-1',
    'text/plain, application/json', 'application/json; charset=UTF-8; extra=true'])(
    'rejects unexpected JSON media type %j and cancels the unread body', async type => {
      const incoming = chunked([encode('{}')], { 'content-type': type }); const fetcher = fetch_response(incoming.response);
      await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: false, status: 200 });
      expect(incoming.cancel).toHaveBeenCalledOnce(); expect(incoming.pull).not.toHaveBeenCalled();
      expect(incoming.body.locked).toBe(false); expect(fetcher.mock.calls[0][1].signal.aborted).toBe(true);
    },
  );

  it.each(['application/json', 'APPLICATION/JSON; charset=utf-8', 'application/json;charset=UTF-8'])(
    'accepts only equivalent JSON/UTF-8 media types %j', async type => {
      fetch_response(response('{}', { 'content-type': type }));
      expect(Array.from(await new Api().download_export(id))).toEqual(Array.from(encode('{}')));
    },
  );

  it.each([null, 'inline', 'attachment', 'attachment; filename="private-owner-name.json"',
    'attachment; filename="../../my-jarvis-memories.json"', 'attachment; filename="my-jarvis-memories.json"; filename*=UTF-8\'\'other.json',
    'attachment; filename="my-jarvis-memories.json.html"', 'attachment; filename="my-jarvis-memories.json", inline'])(
    'rejects nonfixed attachment header %j', async disposition => {
      const incoming = chunked([encode('{}')], { 'content-disposition': disposition }); fetch_response(incoming.response);
      await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', status: 200 });
      expect(incoming.cancel).toHaveBeenCalledOnce(); expect(incoming.body.locked).toBe(false);
    },
  );

  it.each([
    ['cache-control', null], ['cache-control', 'public'], ['cache-control', 'no-store, public'],
    ['x-content-type-options', null], ['x-content-type-options', 'sniff'],
  ])('requires fixed private attachment protection %s=%s', async (header, value) => {
    const incoming = chunked([encode('{}')], { [header!]: value }); fetch_response(incoming.response);
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', status: 200 });
    expect(incoming.cancel).toHaveBeenCalledOnce();
  });

  it.each(['-1', '+2', '1.5', '2, 2', 'NaN', '02'])('rejects malformed Content-Length %s', async length => {
    const incoming = chunked([encode('{}')], { 'content-length': length }); fetch_response(incoming.response);
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', status: 200 });
    expect(incoming.cancel).toHaveBeenCalledOnce(); expect(incoming.pull).not.toHaveBeenCalled();
  });

  it.each([String(byte_limit + 1), '9999999999999999999999999999999999999999999'])(
    'rejects an oversized declared download before reading %s', async length => {
      const incoming = chunked([encode('{}')], { 'content-length': length }); fetch_response(incoming.response);
      await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'RESPONSE_TOO_LARGE', status: 200 });
      expect(incoming.cancel).toHaveBeenCalledOnce(); expect(incoming.pull).not.toHaveBeenCalled();
    },
  );

  it('accepts exactly 8MiB without relying on Content-Length', async () => {
    const incoming = chunked([new Uint8Array(byte_limit - 1), new Uint8Array([123])]); fetch_response(incoming.response);
    const bytes = await new Api().download_export(id);
    expect(bytes.byteLength).toBe(byte_limit); expect(bytes[byte_limit - 1]).toBe(123);
    expect(incoming.body.locked).toBe(false);
  });

  it.each([null, '2'])('cuts off chunked bytes over 8MiB despite declared length %s', async declared => {
    const incoming = chunked([new Uint8Array(byte_limit), new Uint8Array([1]), new Uint8Array([2])], { 'content-length': declared });
    const fetcher = fetch_response(incoming.response);
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'RESPONSE_TOO_LARGE', uncertain: false, status: 200 });
    expect(incoming.pull).toHaveBeenCalledTimes(2); expect(incoming.cancel).toHaveBeenCalledOnce();
    expect(incoming.body.locked).toBe(false); expect(fetcher).toHaveBeenCalledOnce();
  });

  it('rejects a single oversized chunk without returning a partial result', async () => {
    const incoming = chunked([new Uint8Array(byte_limit + 1)]); fetch_response(incoming.response);
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'RESPONSE_TOO_LARGE' });
    expect(incoming.cancel).toHaveBeenCalledOnce(); expect(incoming.body.locked).toBe(false);
  });

  it.each([202, 204, 206, 301, 302, 307, 308])('never treats HTTP %s as a complete attachment', async status => {
    const body = status === 204 ? null : '{}'; fetch_response(response(body, {}, status));
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: false, status });
  });

  it.each(['redirected', 'opaqueredirect'])('rejects intercepted redirect metadata %s', async kind => {
    const incoming = chunked([encode('{}')]);
    Object.defineProperty(incoming.response, kind === 'redirected' ? 'redirected' : 'type', { value: kind === 'redirected' ? true : kind });
    fetch_response(incoming.response);
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', status: 200 });
    expect(incoming.cancel).toHaveBeenCalledOnce(); expect(incoming.pull).not.toHaveBeenCalled();
  });

  it('reports fetch redirect rejection as a failed read without retry', async () => {
    const fetcher = vi.fn().mockRejectedValue(new TypeError('redirect blocked')); vi.stubGlobal('fetch', fetcher);
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'CONNECTION_UNAVAILABLE', uncertain: false, status: 0 });
    expect(fetcher).toHaveBeenCalledOnce(); expect(fetcher.mock.calls[0][1].redirect).toBe('error');
  });

  it('rejects a successful response with no readable body', async () => {
    fetch_response(response(null)); await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', status: 200 });
  });

  it('releases the reader after an interrupted stream and never retries', async () => {
    let reads = 0;
    const body = new ReadableStream<Uint8Array>({ pull(controller) {
      if (++reads === 1) controller.enqueue(encode('{"synthetic":')); else controller.error(new TypeError('interrupted'));
    } }, { highWaterMark: 0 });
    const fetcher = fetch_response(response(body));
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'CONNECTION_UNAVAILABLE', uncertain: false, status: 200 });
    expect(body.locked).toBe(false); expect(fetcher).toHaveBeenCalledOnce(); expect(fetcher.mock.calls[0][1].signal.aborted).toBe(true);
  });

  it('aborts an unresponsive fetch at 15 seconds and clears its deadline', async () => {
    vi.useFakeTimers(); const fetcher = vi.fn((_url: string, _options: RequestInit) => new Promise<Response>(() => {})); vi.stubGlobal('fetch', fetcher);
    const read = new Api().download_export(id).catch(error => error);
    await vi.advanceTimersByTimeAsync(14999); expect(fetcher.mock.calls[0][1].signal!.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(await read).toMatchObject({ code: 'CONNECTION_UNAVAILABLE', uncertain: false, status: 0 });
    expect(fetcher.mock.calls[0][1].signal!.aborted).toBe(true); expect(vi.getTimerCount()).toBe(0); expect(fetcher).toHaveBeenCalledOnce();
  });

  it('cancels a stalled body at 15 seconds without awaiting a stuck cancellation', async () => {
    vi.useFakeTimers(); const cancel = vi.fn(() => new Promise<void>(() => {}));
    const body = new ReadableStream<Uint8Array>({ pull() { return new Promise(() => {}); }, cancel }, { highWaterMark: 0 });
    const fetcher = fetch_response(response(body)); const read = new Api().download_export(id).catch(error => error);
    await vi.advanceTimersByTimeAsync(15000);
    expect(await read).toMatchObject({ code: 'CONNECTION_UNAVAILABLE', uncertain: false, status: 200 });
    expect(cancel).toHaveBeenCalledOnce(); expect(body.locked).toBe(false);
    expect(fetcher.mock.calls[0][1].signal.aborted).toBe(true); expect(vi.getTimerCount()).toBe(0);
  });

  it('cancels a response arriving after an already-expired fetch deadline', async () => {
    vi.useFakeTimers(); let finish!: (response: Response) => void;
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { finish = resolve; })));
    const read = new Api().download_export(id).catch(error => error); await vi.advanceTimersByTimeAsync(15000);
    expect(await read).toMatchObject({ code: 'CONNECTION_UNAVAILABLE', status: 0 });
    const incoming = chunked([encode('{}')]); finish(incoming.response); await Promise.resolve(); await Promise.resolve();
    expect(incoming.cancel).toHaveBeenCalledOnce(); expect(incoming.pull).not.toHaveBeenCalled(); expect(incoming.body.locked).toBe(false);
  });

  it('clears deadline timers after success and metadata failure', async () => {
    vi.useFakeTimers(); const fetcher = vi.fn().mockResolvedValueOnce(response('{}')).mockResolvedValueOnce(response('{}', { 'content-type': 'text/html' }));
    vi.stubGlobal('fetch', fetcher); const api = new Api();
    await api.download_export(id); expect(vi.getTimerCount()).toBe(0);
    await expect(api.download_export(id)).rejects.toBeInstanceOf(ApiError); expect(vi.getTimerCount()).toBe(0);
  });

  it('removes every per-read abort listener even for empty and one-byte chunks', async () => {
    const add = vi.spyOn(AbortSignal.prototype, 'addEventListener');
    const remove = vi.spyOn(AbortSignal.prototype, 'removeEventListener');
    const incoming = chunked(Array.from({ length: 192 }, (_, i) => i % 3 ? new Uint8Array([i]) : new Uint8Array()));
    fetch_response(incoming.response); const bytes = await new Api().download_export(id);
    expect(bytes.byteLength).toBe(128);
    const listeners = add.mock.calls.filter(([event]) => event === 'abort').map(([, listener]) => listener);
    const removed = remove.mock.calls.filter(([event]) => event === 'abort').map(([, listener]) => listener);
    expect(listeners.length).toBe(194); expect(removed).toEqual(listeners); expect(incoming.body.locked).toBe(false);
  });

  it('rejects nonbyte stream values and safely ignores failed cancellation', async () => {
    const cancel = vi.fn().mockRejectedValue(new Error('cancel failed'));
    const body = new ReadableStream({ pull(controller) { controller.enqueue('invalid bytes'); }, cancel }, { highWaterMark: 0 });
    fetch_response(response(body));
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', status: 200, uncertain: false });
    expect(cancel).toHaveBeenCalledOnce(); expect(body.locked).toBe(false);
  });
});

describe('bounded export error envelopes', () => {
  it.each([
    [403, 'REAUTH_REQUIRED'], [403, 'FORBIDDEN'], [404, 'NOT_FOUND'], [409, 'IDEMPOTENCY_CONFLICT'],
    [410, 'EXPORT_EXPIRED'], [413, 'EXPORT_ROW_LIMIT'], [413, 'EXPORT_BYTE_LIMIT'], [429, 'EXPORT_JOB_LIMIT'], [503, 'EXPORT_TIME_LIMIT'],
  ])('preserves HTTP %s and %s as a read failure', async (status, code) => {
    const details = { synthetic: true }; fetch_response(error_response(code as string, status as number, details));
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code, status, details, uncertain: false });
  });

  it.each(['not JSON', '{', 'null', '[]', '{}', '{"error":null}', '{"error":[]}', '{"error":{"code":42}}',
    '{"error":{"code":""}}', '{"error":{"code":"<script>private</script>"}}'])(
    'rejects malformed error metadata without exposing its text %s', async value => {
      fetch_response(response(value, {}, 403));
      await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', status: 403, details: null, uncertain: false });
    },
  );

  it('decodes error bytes with fatal UTF-8', async () => {
    fetch_response(response(new Uint8Array([0xff, 0xfe]), {}, 503));
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', status: 503, uncertain: false });
  });

  it('accepts exactly 1MiB of valid error JSON', async () => {
    const base = JSON.stringify({ error: { code: 'EXPORT_BYTE_LIMIT', details: '' } });
    const incoming = chunked([encode(base.replace('"details":""', `"details":"${'x'.repeat(error_limit - encode(base).length)}"`))], {}, 413);
    fetch_response(incoming.response);
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'EXPORT_BYTE_LIMIT', status: 413, uncertain: false });
    expect(incoming.body.locked).toBe(false);
  });

  it.each([null, '1'])('cuts off error bytes at 1MiB even with declared length %s', async declared => {
    const incoming = chunked([new Uint8Array(error_limit), new Uint8Array([1])], { 'content-length': declared }, 413); fetch_response(incoming.response);
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'RESPONSE_TOO_LARGE', status: 413, uncertain: false });
    expect(incoming.cancel).toHaveBeenCalledOnce(); expect(incoming.body.locked).toBe(false);
  });

  it('rejects declared error bodies over 1MiB before reading', async () => {
    const incoming = chunked([encode('{}')], { 'content-length': String(error_limit + 1) }, 503); fetch_response(incoming.response);
    await expect(new Api().download_export(id)).rejects.toMatchObject({ code: 'RESPONSE_TOO_LARGE', status: 503 });
    expect(incoming.pull).not.toHaveBeenCalled(); expect(incoming.cancel).toHaveBeenCalledOnce();
  });
});

describe('export credential-generation fencing', () => {
  it.each(['logout', 'reauthentication', 'fence'])('does not expose a late success after %s', async kind => {
    let finish!: (response: Response) => void;
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { finish = resolve; })));
    const api = new Api(); api.set_csrf('before'); const read = api.download_export(id).catch(error => error);
    if (kind === 'logout') api.clear(); else if (kind === 'reauthentication') api.set_csrf('after'); else api.fence_requests();
    const incoming = chunked([encode('{"synthetic":"old-session"}')]); finish(incoming.response);
    expect(await read).toMatchObject({ code: 'STALE_SESSION', status: 200, uncertain: false });
    expect(incoming.cancel).toHaveBeenCalledOnce(); expect(incoming.pull).not.toHaveBeenCalled(); expect(incoming.body.locked).toBe(false);
  });

  it.each(['logout', 'reauthentication'])('discards partially-read bytes when %s happens midstream', async kind => {
    let controller!: ReadableStreamDefaultController<Uint8Array>; let started!: () => void;
    const second_read = new Promise<void>(resolve => { started = resolve; }); let reads = 0; const cancel = vi.fn();
    const body = new ReadableStream<Uint8Array>({ pull(value) {
      controller = value; if (++reads === 1) value.enqueue(encode('{"synthetic":')); else started();
    }, cancel }, { highWaterMark: 0 }); fetch_response(response(body));
    const api = new Api(); api.set_csrf('before'); const read = api.download_export(id).catch(error => error); await second_read;
    if (kind === 'logout') api.clear(); else api.set_csrf('after'); controller.enqueue(encode('"late"}'));
    expect(await read).toMatchObject({ code: 'STALE_SESSION', status: 200 }); expect(cancel).toHaveBeenCalledOnce(); expect(body.locked).toBe(false);
  });

  it('checks the revision when the final stream read closes, before returning any bytes', async () => {
    const api = new Api(); api.set_csrf('before'); let reads = 0;
    const body = new ReadableStream<Uint8Array>({ pull(controller) {
      if (++reads === 1) controller.enqueue(encode('{}')); else { api.fence_requests(); controller.close(); }
    } }, { highWaterMark: 0 }); fetch_response(response(body));
    await expect(api.download_export(id)).rejects.toMatchObject({ code: 'STALE_SESSION', status: 200 }); expect(body.locked).toBe(false);
  });

  it('does not parse old-session error details after the credentials change', async () => {
    let finish!: (response: Response) => void;
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { finish = resolve; })));
    const api = new Api(); api.set_csrf('before'); const read = api.download_export(id).catch(error => error);
    api.set_csrf('after'); finish(error_response('EXPORT_EXPIRED', 410, { private: 'old-session' }));
    expect(await read).toMatchObject({ code: 'STALE_SESSION', status: 410, details: null });
  });

  it.each(['replacement', 'fence'])('does not clear newer credentials on an obsolete 401 after %s', async kind => {
    let finish!: (response: Response) => void;
    const fetcher = vi.fn().mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve; }))
      .mockResolvedValue(new Response('{"data":{}}', { headers: { 'content-type': 'application/json' } })); vi.stubGlobal('fetch', fetcher);
    const api = new Api(); api.set_csrf('before'); const read = api.download_export(id).catch(error => error);
    if (kind === 'replacement') { api.clear(); api.set_csrf('after'); } else api.fence_requests();
    const incoming = chunked([encode('ignored')], { 'content-type': 'text/html' }, 401); finish(incoming.response);
    expect(await read).toMatchObject({ code: 'UNAUTHENTICATED', uncertain: false, status: 401 });
    expect(incoming.cancel).toHaveBeenCalledOnce(); expect(incoming.pull).not.toHaveBeenCalled();
    await api.request('/projects', { name: 'synthetic' });
    expect(fetcher.mock.calls[1][1].headers['X-CSRF-Token']).toBe(kind === 'replacement' ? 'after' : 'before');
  });

  it('still clears current credentials on a current-generation 401 without needing a JSON body', async () => {
    const incoming = chunked([encode('ignored')], { 'content-type': 'text/html' }, 401); const fetcher = fetch_response(incoming.response);
    const api = new Api(); api.set_csrf('current'); api.fence_requests();
    await expect(api.download_export(id)).rejects.toMatchObject({ code: 'UNAUTHENTICATED', uncertain: false, status: 401 });
    await expect(api.request('/projects', { name: 'synthetic' })).rejects.toMatchObject({ code: 'CSRF_REQUIRED' });
    expect(fetcher).toHaveBeenCalledOnce(); expect(incoming.cancel).toHaveBeenCalledOnce(); expect(incoming.body.locked).toBe(false);
  });
});
