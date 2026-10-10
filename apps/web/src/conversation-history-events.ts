import { ApiError } from './api';
import { UUID } from './domain';
import type { EventCursor } from './events';
import { read_stream_cursor } from './conversation-history';
export type HistoryStreamState = 'CONNECTED' | 'RECONNECTING' | 'RECOVERING' | 'BLOCKED';
export interface HistoryWatchOptions { conversationId: string; cursor: EventCursor | null; onUpdate: () => void; onGap: () => Promise<EventCursor | null>; onState: (state: HistoryStreamState) => void; onUnauthorized: () => void }
export function read_history_event(frame: string, conversationId: string): EventCursor | null {
  if (new TextEncoder().encode(frame).length > 65536) throw new ApiError('INVALID_EVENT');
  let kind = ''; let eventId = ''; const data: string[] = [];
  for (const line of frame.split('\n')) { if (!line || line.startsWith(':')) continue; const index = line.indexOf(':'); const field = index < 0 ? line : line.slice(0, index); const value = index < 0 ? '' : line.slice(index + 1).replace(/^ /, ''); if (field === 'event') kind = value; if (field === 'id') eventId = value; if (field === 'data') data.push(value); }
  if (!data.length && !kind && !eventId) return null;
  try { const value = JSON.parse(data.join('\n')); if (kind !== 'run.status' || value.conversation_id !== conversationId || !UUID.test(value.run_id) || typeof value.created_at !== 'string' || !Number.isFinite(Date.parse(value.created_at))) throw new Error(); const cursor = read_stream_cursor(value); if (!cursor || eventId !== cursor.event_id) throw new Error(); return cursor; } catch { throw new ApiError('INVALID_EVENT'); }
}
/** Finite authenticated streams. Events invalidate canonical reads, never authorize actions. */
export function watch_history_events(options: HistoryWatchOptions): () => void {
  if (!UUID.test(options.conversationId)) throw new ApiError('INVALID_CONVERSATION');
  let stopped = false; let cursor = options.cursor; const seen = new Set<string>(cursor ? [cursor.event_id] : []); let initial = true; let failures = 0; let timer: ReturnType<typeof setTimeout> | undefined; let wake: (() => void) | undefined; let flush: ReturnType<typeof setTimeout> | undefined;
  const controller = new AbortController();
  const active = () => !stopped;
  const notify = () => { if (flush === undefined) flush = setTimeout(() => { flush = undefined; if (active()) options.onUpdate(); }, 30); };
  const pause = (ms: number) => new Promise<void>(resolve => { wake = resolve; timer = setTimeout(() => { timer = undefined; wake = undefined; resolve(); }, ms); });
  void (async () => {
    while (active()) {
      let timeout: ReturnType<typeof setTimeout> | undefined;
      const request = new AbortController(); const abort = () => request.abort(); controller.signal.addEventListener('abort', abort);
      try {
        const query = initial && cursor ? `?cursor=${encodeURIComponent(cursor.event_id)}` : '';
        const headers: Record<string, string> = { Accept: 'text/event-stream' }; if (!initial && cursor) headers['Last-Event-ID'] = cursor.event_id;
        timeout = setTimeout(() => request.abort(), 30000);
        const response = await fetch(`/api/v1/conversations/${options.conversationId}/events${query}`, { credentials: 'same-origin', cache: 'no-store', redirect: 'error', headers, signal: request.signal });
        if (response.status === 401) { stopped = true; options.onUnauthorized(); break; }
        if (!active()) break;
        if (response.status === 410) {
          const reader = response.body?.getReader(); if (!reader || !response.headers.get('content-type')?.includes('application/json')) throw new ApiError('INVALID_EVENT_RESPONSE');
          let errorText = ''; let size = 0; const decoder = new TextDecoder('utf-8', { fatal: true });
          try { for (;;) { const part = await reader.read(); if (part.done) break; size += part.value.byteLength; if (size > 16384) throw new ApiError('INVALID_EVENT_RESPONSE'); errorText += decoder.decode(part.value, { stream: true }); } errorText += decoder.decode(); } finally { await reader.cancel().catch(() => undefined); }
          if (JSON.parse(errorText)?.error?.code !== 'EVENT_CURSOR_UNAVAILABLE') throw new ApiError('INVALID_EVENT_RESPONSE');
          if (++failures > 3) throw new ApiError('STREAM_BLOCKED'); options.onState('RECOVERING'); cursor = await options.onGap(); if (!active()) break; seen.clear(); if (cursor) seen.add(cursor.event_id); initial = true; await pause(250 * failures); continue; }
        if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream') || !response.body) throw new ApiError('INVALID_EVENT_RESPONSE');
        initial = false; options.onState('CONNECTED'); const reader = response.body.getReader(); const decoder = new TextDecoder('utf-8', { fatal: true }); let buffer = ''; let bytes = 0; let frames = 0; let accepted = 0;
        try {
          for (;;) {
            const { value, done } = await reader.read(); if (!active()) break;
            if (value) { bytes += value.byteLength; if (bytes > 1024 * 1024) throw new ApiError('EVENT_RESPONSE_TOO_LARGE'); buffer += decoder.decode(value, { stream: true }); }
            if (done) buffer += decoder.decode();
            buffer = buffer.replace(/\r\n/g, '\n'); let split: number;
            while ((split = buffer.indexOf('\n\n')) >= 0) { const frame = buffer.slice(0, split); buffer = buffer.slice(split + 2); const next = read_history_event(frame, options.conversationId); if (!next) continue; if (++frames > 100) throw new ApiError('EVENT_RESPONSE_TOO_LARGE'); if (cursor && next.sequence <= cursor.sequence) continue; if (seen.has(next.event_id)) throw new ApiError('INVALID_EVENT'); seen.add(next.event_id); if (seen.size > 1000) seen.delete(seen.values().next().value!); cursor = next; accepted++; notify(); }
            if (buffer.length > 65536) throw new ApiError('INVALID_EVENT'); if (done) { if (buffer.trim() && !buffer.trim().startsWith(':')) throw new ApiError('TRUNCATED_EVENT'); break; }
          }
        } finally { await reader.cancel().catch(() => undefined); }
        failures = 0;
        if (active()) { options.onState('RECONNECTING'); await pause(accepted ? 0 : 1000); }
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) { stopped = true; options.onUnauthorized(); break; }
        if (!active()) break;
        if (++failures >= 3) { options.onState('BLOCKED'); break; }
        options.onState('RECONNECTING'); await pause(250 * 2 ** failures);
      } finally { if (timeout !== undefined) clearTimeout(timeout); controller.signal.removeEventListener('abort', abort); request.abort(); }
    }
  })();
  return () => { stopped = true; controller.abort(); if (timer !== undefined) clearTimeout(timer); if (flush !== undefined) clearTimeout(flush); wake?.(); };
}
