import { parse_deletion_json } from './conversation-deletion-json';
import { parse_closed_json } from './closed-json';
/** No credentials or private data are persisted in browser storage. */
export class ApiError extends Error {
  constructor(readonly code: string, readonly uncertain = false, readonly status = 0, readonly details: unknown = null) { super(code); }
}
const LIMIT = 1024 * 1024;
const EXPORT_LIMIT = 8 * LIMIT;
const EXPORT_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$(?![\s\S])/;
const JSON_CONTENT_TYPE = /^application\/json(?:;\s*charset=utf-8)?$(?![\s\S])/i;
export class Api {
  private csrf = '';
  private credential_revision = 0;
  clear() { this.credential_revision++; this.csrf = ''; }
  /** Isolate obsolete in-flight responses while retaining the initiating session's CSRF. */
  fence_requests() { this.credential_revision++; }
  set_csrf(value: string) { if (!value || value.length > 4096) throw new ApiError('INVALID_CSRF'); this.credential_revision++; this.csrf = value; }
  async request<T>(path: string, body?: unknown, idempotency_key?: string, method: 'POST' | 'PATCH' = 'POST'): Promise<T> {
    if (!/^\/[a-z0-9/_-]+(?:\?[a-z0-9=&_%:.,+~-]*)?$/i.test(path) || path.split('?')[0].includes('//') || path.split('?')[0].includes('..') || path.length > 4096) throw new ApiError('INVALID_PATH');
    if (body !== undefined && !this.csrf) throw new ApiError('CSRF_REQUIRED');
    const deletion_route = /^\/(?:conversations\/[0-9a-f-]{36}\/(?:deletion-state|delete-preparations)|conversation-delete-preparations\/[0-9a-f-]{36}\/confirm|conversation-deletions\/requests\/[0-9a-f-]{36})$(?![\s\S])/.test(path);
    const study_route = /^\/(?:conversations\/[0-9a-f-]{36}\/(?:study-sessions|study-index(?:\?[^#]*)?)|study-sessions\/[0-9a-f-]{36}(?:\/(?:answers|close))?|study-mutations\/[0-9a-f-]{36}|runs\/[0-9a-f-]{36}\/study-lesson)$(?![\s\S])/.test(path);
    const strict_route = deletion_route || study_route;
    // Durable-study caps include the complete transport envelope.
    const study_limit = /^\/study-sessions\/[0-9a-f-]{36}$/.test(path) && body === undefined ? 256 * 1024 : 64 * 1024;
    const response_limit = study_route ? study_limit : LIMIT;
    const serialized = body === undefined ? undefined : JSON.stringify(body);
    if (serialized && new TextEncoder().encode(serialized).length > 65536) throw new ApiError('REQUEST_TOO_LARGE');
    const request_revision = this.credential_revision;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    let response_status = 0;
    let study_reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    const bounded = <V>(operation: Promise<V>): Promise<V> => !study_route ? operation : new Promise((resolve, reject) => {
      const aborted = () => reject(new ApiError(body === undefined ? 'CONNECTION_UNAVAILABLE' : 'UNKNOWN', body !== undefined, response_status));
      controller.signal.addEventListener('abort', aborted, { once: true });
      operation.then(value => { controller.signal.removeEventListener('abort', aborted); if (controller.signal.aborted) aborted(); else resolve(value); }, error => { controller.signal.removeEventListener('abort', aborted); reject(error); });
      if (controller.signal.aborted) { controller.signal.removeEventListener('abort', aborted); aborted(); }
    });
    try {
      const response = await bounded(fetch(`/api/v1${path}`, { method: body === undefined ? 'GET' : method, credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: controller.signal, headers: { Accept: 'application/json', ...(serialized !== undefined ? { 'Content-Type': 'application/json', 'X-CSRF-Token': this.csrf } : {}), ...(idempotency_key ? { 'Idempotency-Key': idempotency_key } : {}) }, body: serialized }).then(candidate => {
        if (study_route && controller.signal.aborted) { void candidate.body?.cancel().catch(() => {}); throw new ApiError(body === undefined ? 'CONNECTION_UNAVAILABLE' : 'UNKNOWN', body !== undefined, candidate.status); }
        return candidate;
      }));
      response_status = response.status;
      if (response.status === 401) { if (request_revision === this.credential_revision) this.clear(); throw new ApiError('UNAUTHENTICATED', false, 401); }
      if (strict_route && request_revision !== this.credential_revision) throw new ApiError('STALE_SESSION', body !== undefined, response_status);
      if (strict_route && (response.redirected || response.type === 'opaqueredirect' || !JSON_CONTENT_TYPE.test(response.headers.get('content-type') ?? ''))) throw new ApiError('INVALID_RESPONSE', body !== undefined, response_status);
      if (!response.headers.get('content-type')?.includes('application/json')) throw new ApiError('INVALID_RESPONSE', body !== undefined, response.status);
      const reader = response.body?.getReader();
      if (study_route) study_reader = reader;
      if (!reader) throw new ApiError('INVALID_RESPONSE', body !== undefined, response_status);
      const chunks: Uint8Array[] = []; let length = 0;
      for (;;) { const { value, done } = await bounded(reader.read()); if (study_route && request_revision !== this.credential_revision) { void reader.cancel().catch(() => {}); throw new ApiError('STALE_SESSION', body !== undefined, response_status); } if (done) break; length += value.length; if (length > response_limit) { void reader.cancel().catch(() => {}); throw new ApiError('RESPONSE_TOO_LARGE', body !== undefined, response_status); } chunks.push(value); }
      const bytes = new Uint8Array(length); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
      let envelope; try { const decoded = new TextDecoder('utf-8', { fatal: true }).decode(bytes); envelope = study_route ? parse_closed_json(decoded, response_limit) : deletion_route ? parse_deletion_json(decoded) : JSON.parse(decoded); } catch { throw new ApiError('INVALID_RESPONSE', body !== undefined, response_status); }
      if (strict_route && request_revision !== this.credential_revision) throw new ApiError('STALE_SESSION', body !== undefined, response_status);
      if (!response.ok) throw new ApiError(typeof envelope?.error?.code === 'string' ? envelope.error.code : 'REQUEST_REJECTED', body !== undefined && response.status >= 500, response.status, envelope?.error?.details);
      if (strict_route && (response.status !== 200 || !envelope || typeof envelope !== 'object' || Array.isArray(envelope) || Object.keys(envelope).sort().join(',') !== 'data,request_id' || typeof envelope.request_id !== 'string' || !EXPORT_UUID.test(envelope.request_id))) throw new ApiError('INVALID_RESPONSE', body !== undefined, response_status);
      if (!envelope || !Object.hasOwn(envelope, 'data')) throw new ApiError('INVALID_RESPONSE', body !== undefined, response_status);
      if (study_route && new TextEncoder().encode(JSON.stringify(envelope.data)).length > study_limit) throw new ApiError('RESPONSE_TOO_LARGE', body !== undefined, response_status);
      return envelope.data as T;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      // Never automatically retry a mutation: its outcome may already be committed.
      throw new ApiError(body === undefined ? 'CONNECTION_UNAVAILABLE' : 'UNKNOWN', body !== undefined, response_status);
    } finally { clearTimeout(timeout); if (study_reader) { void study_reader.cancel().catch(() => {}); study_reader.releaseLock(); } }
  }
  /** Bounded in-memory privacy read. The caller validates the document before offering a local save. */
  async download_export(jobId: string): Promise<Uint8Array> {
    if (typeof jobId !== 'string' || !EXPORT_UUID.test(jobId)) throw new ApiError('INVALID_EXPORT_ID');
    const request_revision = this.credential_revision;
    const controller = new AbortController();
    let response_status = 0;
    let response: Response | undefined;
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    let buffer: Uint8Array | undefined;
    let completed = false;
    const timeout = setTimeout(() => controller.abort(), 15000);
    const bounded = <T>(operation: Promise<T>): Promise<T> => new Promise((resolve, reject) => {
      const aborted = () => reject(new ApiError('CONNECTION_UNAVAILABLE', false, response_status));
      controller.signal.addEventListener('abort', aborted, { once: true });
      // Remove each settled operation's listener instead of retaining one deadline reaction per chunk.
      operation.then(value => {
        controller.signal.removeEventListener('abort', aborted);
        if (controller.signal.aborted) aborted(); else resolve(value);
      }, error => { controller.signal.removeEventListener('abort', aborted); reject(error); });
      if (controller.signal.aborted) { controller.signal.removeEventListener('abort', aborted); aborted(); }
    });
    const current_session = () => {
      if (request_revision !== this.credential_revision) throw new ApiError('STALE_SESSION', false, response_status);
    };
    try {
      response = await bounded<Response>(fetch(`/api/v1/exports/${jobId}/download`, {
        method: 'GET', credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: controller.signal,
        headers: { Accept: 'application/json' },
      }).then(candidate => {
        // A late mocked/intercepted fetch must not leave an unread private body behind after the deadline.
        if (controller.signal.aborted) { void candidate.body?.cancel().catch(() => {}); throw new ApiError('CONNECTION_UNAVAILABLE', false, candidate.status); }
        return candidate;
      }));
      response_status = response.status;
      if (response.status === 401) {
        if (request_revision === this.credential_revision) this.clear();
        throw new ApiError('UNAUTHENTICATED', false, 401);
      }
      current_session();
      if (response.redirected || response.type === 'opaqueredirect' || response.status >= 300 && response.status < 400
        || !JSON_CONTENT_TYPE.test(response.headers.get('content-type') ?? '')) throw new ApiError('INVALID_RESPONSE', false, response_status);
      if (response.ok && (response.status !== 200
        || response.headers.get('content-disposition') !== 'attachment; filename="my-jarvis-memories.json"'
        || response.headers.get('cache-control') !== 'no-store'
        || response.headers.get('x-content-type-options') !== 'nosniff')) throw new ApiError('INVALID_RESPONSE', false, response_status);
      const limit = response.ok ? EXPORT_LIMIT : LIMIT;
      const declared = response.headers.get('content-length');
      if (declared !== null) {
        if (!/^(?:0|[1-9][0-9]*)$(?![\s\S])/.test(declared)) throw new ApiError('INVALID_RESPONSE', false, response_status);
        if (declared.length > String(limit).length || Number(declared) > limit) throw new ApiError('RESPONSE_TOO_LARGE', false, response_status);
      }
      reader = response.body?.getReader();
      if (!reader) throw new ApiError('INVALID_RESPONSE', false, response_status);
      // A fixed buffer also bounds overhead from arbitrarily tiny stream chunks.
      buffer = new Uint8Array(limit); let length = 0;
      for (;;) {
        current_session();
        const { value, done } = await bounded(reader.read());
        current_session();
        if (done) break;
        if (!ArrayBuffer.isView(value) || Object.prototype.toString.call(value) !== '[object Uint8Array]') throw new ApiError('INVALID_RESPONSE', false, response_status);
        if (value.byteLength > limit - length) throw new ApiError('RESPONSE_TOO_LARGE', false, response_status);
        buffer.set(value, length);
        length += value.byteLength;
      }
      const bytes = buffer.slice(0, length);
      if (!response.ok) {
        let envelope;
        try { envelope = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
        catch { throw new ApiError('INVALID_RESPONSE', false, response_status); }
        if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)
          || !envelope.error || typeof envelope.error !== 'object' || Array.isArray(envelope.error)
          || typeof envelope.error.code !== 'string' || !/^[A-Z][A-Z0-9_]{0,95}$(?![\s\S])/.test(envelope.error.code)) throw new ApiError('INVALID_RESPONSE', false, response_status);
        throw new ApiError(envelope.error.code, false, response_status, envelope.error.details);
      }
      current_session();
      completed = true;
      return bytes;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError('CONNECTION_UNAVAILABLE', false, response_status);
    } finally {
      clearTimeout(timeout);
      buffer?.fill(0);
      if (!completed) {
        controller.abort();
        // Cancellation is best effort and never extends the read deadline, even for a stalled source.
        if (reader) void reader.cancel().catch(() => {});
        else if (response?.body) void response.body.cancel().catch(() => {});
      }
      reader?.releaseLock();
    }
  }
  async bootstrap_csrf() { const revision = this.credential_revision; const data = await this.request<{ csrf_token: string }>('/auth/csrf'); if (revision === this.credential_revision) this.set_csrf(data.csrf_token); }
}
