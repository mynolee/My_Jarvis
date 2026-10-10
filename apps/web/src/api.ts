/** No credentials or private data are persisted in browser storage. */
export class ApiError extends Error {
  constructor(readonly code: string, readonly uncertain = false, readonly status = 0) { super(code); }
}
const LIMIT = 1024 * 1024;
export class Api {
  private csrf = '';
  clear() { this.csrf = ''; }
  set_csrf(value: string) { if (!value || value.length > 4096) throw new ApiError('INVALID_CSRF'); this.csrf = value; }
  async request<T>(path: string, body?: unknown, idempotency_key?: string): Promise<T> {
    if (!/^\/[a-z0-9/?=&_-]+$/i.test(path) || path.includes('//') || path.includes('..')) throw new ApiError('INVALID_PATH');
    if (body !== undefined && !this.csrf) throw new ApiError('CSRF_REQUIRED');
    const serialized = body === undefined ? undefined : JSON.stringify(body);
    if (serialized && new TextEncoder().encode(serialized).length > 65536) throw new ApiError('REQUEST_TOO_LARGE');
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch(`/api/v1${path}`, { method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: controller.signal, headers: { Accept: 'application/json', ...(serialized !== undefined ? { 'Content-Type': 'application/json', 'X-CSRF-Token': this.csrf } : {}), ...(idempotency_key ? { 'Idempotency-Key': idempotency_key } : {}) }, body: serialized });
      if (!response.headers.get('content-type')?.includes('application/json')) throw new ApiError('INVALID_RESPONSE', body !== undefined, response.status);
      const reader = response.body?.getReader();
      if (!reader) throw new ApiError('INVALID_RESPONSE', body !== undefined);
      const chunks: Uint8Array[] = []; let length = 0;
      for (;;) { const { value, done } = await reader.read(); if (done) break; length += value.length; if (length > LIMIT) { await reader.cancel(); throw new ApiError('RESPONSE_TOO_LARGE', body !== undefined); } chunks.push(value); }
      const bytes = new Uint8Array(length); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
      let envelope; try { envelope = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); } catch { throw new ApiError('INVALID_RESPONSE', body !== undefined); }
      if (!response.ok) throw new ApiError(typeof envelope?.error?.code === 'string' ? envelope.error.code : 'REQUEST_REJECTED', body !== undefined && response.status >= 500, response.status);
      if (!envelope || !Object.hasOwn(envelope, 'data')) throw new ApiError('INVALID_RESPONSE', body !== undefined);
      return envelope.data as T;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      // Never automatically retry a mutation: its outcome may already be committed.
      throw new ApiError(body === undefined ? 'CONNECTION_UNAVAILABLE' : 'UNKNOWN', body !== undefined);
    } finally { clearTimeout(timeout); }
  }
  async bootstrap_csrf() { const data = await this.request<{ csrf_token: string }>('/auth/csrf'); this.set_csrf(data.csrf_token); }
}
