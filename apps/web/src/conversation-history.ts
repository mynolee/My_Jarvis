import { Api, ApiError } from './api';
import { UUID, type Conversation, type Run } from './domain';
import type { EventCursor } from './events';
export interface HistoryMessage { id: string; run_id: string | null; role: string; text: string; created_at: string; execution_mode: 'LOCAL_INFERENCE' | 'MOCK' | null }
export interface ConversationPage { items: Conversation[]; next_cursor: string | null }
export interface HistoryPage { items: HistoryMessage[]; next_cursor: string | null; current_run: Run | null; stream_cursor: EventCursor | null }
const invalid = (): never => { throw new ApiError('INVALID_RESPONSE'); };
function record(value: unknown): Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid(); }
function id(value: unknown): string { return typeof value === 'string' && UUID.test(value) ? value : invalid(); }
function string(value: unknown, limit: number): string { return typeof value === 'string' && value.length <= limit ? value : invalid(); }
function date(value: unknown): string { const result = string(value, 64); return Number.isFinite(Date.parse(result)) ? result : invalid(); }
export function read_history_cursor(value: unknown): string | null { return value === null ? null : typeof value === 'string' && value.length > 0 && value.length <= 2048 ? value : invalid(); }
export function read_stream_cursor(value: unknown): EventCursor | null { if (value === null) return null; const item = record(value); if (!Number.isSafeInteger(item.sequence) || (item.sequence as number) < 1) return invalid(); return { event_id: id(item.event_id), sequence: item.sequence as number }; }
function mode(value: unknown): HistoryMessage['execution_mode'] { return value === null || value === 'MOCK' || value === 'LOCAL_INFERENCE' ? value : invalid(); }
export function read_history_run(value: unknown): Run | null {
  if (value === null) return null;
  const item = record(value); if (!Number.isSafeInteger(item.version) || (item.version as number) < 1 || typeof item.status !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(item.status)) return invalid();
  return { id: id(item.id), status: item.status, version: item.version as number, result: item.result === null ? null : string(item.result, 1000000), approval_id: item.approval_id === null ? null : id(item.approval_id), job_id: item.job_id === null ? null : id(item.job_id), ...(item.execution_mode === undefined ? {} : { execution_mode: mode(item.execution_mode) ?? undefined }) };
}
function items(value: unknown): unknown[] { return Array.isArray(value) && value.length <= 20 ? value : invalid(); }
function unique<T extends {id: string}>(values: T[]): T[] { return new Set(values.map(value => value.id)).size === values.length ? values : invalid(); }
export function read_conversation_page(value: unknown): ConversationPage {
  const page = record(value); return { next_cursor: read_history_cursor(page.next_cursor), items: unique(items(page.items).map(raw => { const item = record(raw); return { id: id(item.id), title: string(item.title, 1000), project_id: item.project_id === null ? null : id(item.project_id), created_at: date(item.created_at) }; })) };
}
export function read_history_page(value: unknown): HistoryPage {
  const page = record(value); return { next_cursor: read_history_cursor(page.next_cursor), current_run: read_history_run(page.current_run), stream_cursor: read_stream_cursor(page.stream_cursor), items: unique(items(page.items).map(raw => { const item = record(raw); const role = string(item.role, 32); if (!['USER', 'ASSISTANT'].includes(role)) return invalid(); return { id: id(item.id), run_id: item.run_id === null ? null : id(item.run_id), role, text: string(item.text, 1000000), created_at: date(item.created_at), execution_mode: mode(item.execution_mode) }; })) };
}
export function history_path(conversationId: string | null, cursor: string | null = null): string { if (conversationId !== null && !UUID.test(conversationId)) throw new ApiError('INVALID_CONVERSATION'); const query = new URLSearchParams({ limit: '20' }); if (cursor !== null) query.set('cursor', read_history_cursor(cursor)!); return `${conversationId === null ? '/conversations' : `/conversations/${conversationId}/messages`}?${query}`; }
/** Retry only transient reads, once; stale generations must not start another request. */
export async function read_history<T>(api: Api, path: string, parse: (value: unknown) => T, active: () => boolean = () => true): Promise<T> { for (let attempt = 0; ; attempt++) { if (!active()) throw new ApiError('CANCELLED'); try { const value = await api.request<unknown>(path); if (!active()) throw new ApiError('CANCELLED'); return parse(value); } catch (error) { if (!active() || attempt >= 1 || !(error instanceof ApiError) || !(error.code === 'CONNECTION_UNAVAILABLE' || error.status >= 500)) throw error; } } }
export function merge_history(older: HistoryMessage[], newer: HistoryMessage[]): HistoryMessage[] { const newerIds = new Set(newer.map(item => item.id)); return [...older.filter(item => !newerIds.has(item.id)), ...newer]; }
