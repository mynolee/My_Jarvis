import { afterEach, expect, it, vi } from 'vitest';
import { watch_events } from './events';
const id = '11111111-1111-4111-8111-111111111111';
class Source {
  static last: Source;
  listeners = new Map<string, (event: MessageEvent) => void>();
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;
  constructor(readonly url: string, readonly options: unknown) { Source.last = this; }
  addEventListener(name: string, listener: (event: MessageEvent) => void) { this.listeners.set(name, listener); }
  close() { this.closed = true; }
  send(sequence: number, conversation_id = id) { this.listeners.get('run.status')?.({ data: JSON.stringify({ event_id: id, run_id: id, conversation_id, sequence }) } as MessageEvent); }
}
afterEach(() => vi.unstubAllGlobals());
it('uses a same-origin scoped stream with cookie credentials and closes on cleanup', () => { vi.stubGlobal('EventSource', Source); const update = vi.fn(), state = vi.fn(); const stop = watch_events(id, update, state); expect(Source.last.url).toBe(`/api/v1/conversations/${id}/events`); expect(Source.last.options).toEqual({ withCredentials: true }); Source.last.onopen?.(); expect(update).toHaveBeenCalledTimes(1); Source.last.onerror?.(); expect(state).toHaveBeenLastCalledWith('RECONNECTING'); stop(); expect(Source.last.closed).toBe(true); });
it('closes foreign-conversation and replayed event streams', () => { vi.stubGlobal('EventSource', Source); const update = vi.fn(), state = vi.fn(); watch_events(id, update, state); Source.last.send(1, '22222222-2222-4222-8222-222222222222'); expect(Source.last.closed).toBe(true); expect(update).not.toHaveBeenCalled(); watch_events(id, update, state); Source.last.send(1); Source.last.send(1); expect(Source.last.closed).toBe(true); expect(update).toHaveBeenCalledTimes(1); });
it('closes a stream exceeding ten messages per second', () => { vi.stubGlobal('EventSource', Source); const update = vi.fn(), state = vi.fn(); watch_events(id, update, state); for (let i = 1; i <= 11; i++) Source.last.send(i); expect(Source.last.closed).toBe(true); expect(update).toHaveBeenCalledTimes(10); expect(state).toHaveBeenLastCalledWith('BLOCKED'); });
