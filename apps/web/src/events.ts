import { UUID } from './domain';
const names = new Set(['message.accepted', 'response.delta', 'run.status', 'step.status', 'approval.required', 'artifact.ready', 'usage.updated', 'run.completed']);
export interface EventCursor { event_id: string; sequence: number }
/** SSE notifications are invalidation hints only. Never use streamed content as an approval. */
export function read_event(text: string, kind: string, conversation_id: string, previous: EventCursor | null): EventCursor | null {
  if (!names.has(kind) || new TextEncoder().encode(text).length > 65536) return null;
  try { const value = JSON.parse(text); if (!UUID.test(value.event_id) || value.conversation_id !== conversation_id || !UUID.test(value.run_id) || !Number.isSafeInteger(value.sequence) || value.sequence < 1 || (previous && value.sequence <= previous.sequence)) return null; return { event_id: value.event_id, sequence: value.sequence }; } catch { return null; }
}
export function watch_events(conversation_id: string, on_update: () => void, on_state: (state: string) => void): () => void {
  if (!UUID.test(conversation_id)) throw new Error('INVALID_CONVERSATION');
  // EventSource reconnects with Last-Event-ID on this same authenticated stream.
  // Cursor never moves between conversations or into browser storage.
  const source = new EventSource(`/api/v1/conversations/${conversation_id}/events`, { withCredentials: true });
  let cursor: EventCursor | null = null; let times: number[] = [];
  source.onopen = () => { on_state('CONNECTED'); on_update(); };
  source.onerror = () => on_state('RECONNECTING');
  for (const kind of names) source.addEventListener(kind, (event) => { const now = Date.now(); times = times.filter(t => now - t < 1000); times.push(now); if (times.length > 10) { source.close(); on_state('BLOCKED'); return; } const next = read_event((event as MessageEvent).data, kind, conversation_id, cursor); if (!next) { source.close(); on_state('BLOCKED'); return; } cursor = next; on_update(); });
  return () => source.close();
}
