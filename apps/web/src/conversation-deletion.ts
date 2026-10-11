import { ApiError } from './api';
import { task_time_micros, valid_date } from './tasks';

export const DELETION_SCOPE = 'CONVERSATION_SOURCE_V1' as const;
export const deletion_blockers = ['ACTIVE_WORK', 'INTEGRITY_UNVERIFIED', 'PENDING_APPROVAL', 'UNKNOWN_WORK', 'UNSUPPORTED_LEGACY_INFERENCE'] as const;
export type DeletionBlocker = typeof deletion_blockers[number];
export type ConversationDeletionState =
  | { status: 'ACTIVE'; conversation_id: string; content_version: number; content_digest: string; eligibility: { status: 'ELIGIBLE'; blockers: [] }; checked_at: string }
  | { status: 'ACTIVE'; conversation_id: string; content_version: number; content_digest: null; eligibility: { status: 'BLOCKED'; blockers: DeletionBlocker[] }; checked_at: string }
  | { status: 'DELETED'; conversation_id: string; deletion_id: string; deleted_at: string; checked_at: string };
export type EligibleDeletionState = Extract<ConversationDeletionState, { content_digest: string }>;
export interface ConversationDeletePreparation {
  id: string; status: 'PREPARED'; scope_policy: typeof DELETION_SCOPE;
  conversation: { id: string; title: string; project_id: string | null; created_at: string };
  expected_version: number; expected_digest: string;
  source_counts: { messages: number; runs: number; run_results: number; action_payloads: number; inference_contexts: number; study_sessions: number; study_answers: number };
  payload_hash: string; prepared_at: string; expires_at: string;
}
export interface PendingConversationDeletion {
  stage: 'CONFIRM'; key: string; conversation_id: string; preparation_id: string; expected_version: number;
  /** Reviewed authority lifetime only; never retain title, source digest or confirmation hash here. */
  prepared_at: string; expires_at: string;
}
export interface ConversationDeletionReceipt {
  deletion_id: string; conversation_id: string; preparation_id: string; scope_policy: typeof DELETION_SCOPE;
  deleted_version: number; tombstone_version: number; deleted_at: string;
}
export type ConversationDeletionOutcome =
  | { status: 'COMMITTED'; receipt: ConversationDeletionReceipt; checked_at: string }
  | { status: 'NOT_OBSERVED'; receipt: null; checked_at: string };

const fail = (): never => { throw new ApiError('INVALID_DELETION_RESPONSE'); };
export const valid_deletion_id = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$(?![\s\S])/.test(value);
const digest = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$(?![\s\S])/.test(value);
const integer = (value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): value is number => Number.isSafeInteger(value) && (value as number) >= min && (value as number) <= max;
function object(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== keys.length || !keys.every(key => Object.hasOwn(value, key))) return fail();
  return value as Record<string, unknown>;
}
function instant(value: unknown): string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$(?![\s\S])/.test(value) && valid_date(value) ? value : fail();
}
function unicode(value: unknown): value is string {
  return typeof value === 'string' && !/[\u0000\uD800-\uDFFF]/u.test(value);
}
export function read_deletion_state(value: unknown, conversationId: string): ConversationDeletionState {
  if (!valid_deletion_id(conversationId)) return fail();
  if (value && typeof value === 'object' && (value as { status?: unknown }).status === 'DELETED') {
    const d = object(value, ['status', 'conversation_id', 'deletion_id', 'deleted_at', 'checked_at']);
    const deleted_at = instant(d.deleted_at), checked_at = instant(d.checked_at);
    if (d.conversation_id !== conversationId || !valid_deletion_id(d.deletion_id) || task_time_micros(deleted_at) > task_time_micros(checked_at)) return fail();
    return { status: 'DELETED', conversation_id: conversationId, deletion_id: d.deletion_id, deleted_at, checked_at };
  }
  const d = object(value, ['status', 'conversation_id', 'content_version', 'content_digest', 'eligibility', 'checked_at']);
  const e = object(d.eligibility, ['status', 'blockers']), checked_at = instant(d.checked_at);
  if (d.status !== 'ACTIVE' || d.conversation_id !== conversationId || !integer(d.content_version, 1) || !Array.isArray(e.blockers)) return fail();
  if (e.status === 'ELIGIBLE' && e.blockers.length === 0 && digest(d.content_digest)) return { status: 'ACTIVE', conversation_id: conversationId, content_version: d.content_version, content_digest: d.content_digest, eligibility: { status: 'ELIGIBLE', blockers: [] }, checked_at };
  const blockers = e.blockers;
  if (e.status !== 'BLOCKED' || d.content_digest !== null || blockers.length === 0 || blockers.length > deletion_blockers.length || !blockers.every((code, index) => deletion_blockers.includes(code) && (index === 0 || blockers[index - 1] < code))) return fail();
  return { status: 'ACTIVE', conversation_id: conversationId, content_version: d.content_version, content_digest: null, eligibility: { status: 'BLOCKED', blockers: [...e.blockers] as DeletionBlocker[] }, checked_at };
}
export function read_delete_preparation(value: unknown, expected: EligibleDeletionState): ConversationDeletePreparation {
  const p = object(value, ['id', 'status', 'scope_policy', 'conversation', 'expected_version', 'expected_digest', 'source_counts', 'payload_hash', 'prepared_at', 'expires_at']);
  const c = object(p.conversation, ['id', 'title', 'project_id', 'created_at']);
  const n = object(p.source_counts, ['messages', 'runs', 'run_results', 'action_payloads', 'inference_contexts', 'study_sessions', 'study_answers']);
  const prepared_at = instant(p.prepared_at), expires_at = instant(p.expires_at), created_at = instant(c.created_at);
  if (!valid_deletion_id(p.id) || p.status !== 'PREPARED' || p.scope_policy !== DELETION_SCOPE || c.id !== expected.conversation_id || !unicode(c.title) || [...c.title].length < 1 || [...c.title].length > 200 || c.project_id !== null && !valid_deletion_id(c.project_id)
    || !integer(p.expected_version, 1, Number.MAX_SAFE_INTEGER - 1) || p.expected_version !== expected.content_version || !digest(p.expected_digest) || p.expected_digest !== expected.content_digest || !digest(p.payload_hash)
    || !integer(n.messages, 0, 2000) || !integer(n.runs, 0, 1000) || !integer(n.run_results, 0, n.runs as number) || !integer(n.action_payloads, 0, 1000) || !integer(n.inference_contexts, 0, n.runs as number) || !integer(n.study_sessions, 0, 1000) || !integer(n.study_answers, 0, Math.min(2000, 2 * (n.study_sessions as number)))
    || task_time_micros(created_at) > task_time_micros(prepared_at) || task_time_micros(expected.checked_at) > task_time_micros(prepared_at) || task_time_micros(expires_at) - task_time_micros(prepared_at) !== 300000000n) return fail();
  return { id: p.id, status: 'PREPARED', scope_policy: DELETION_SCOPE, conversation: { id: c.id, title: c.title, project_id: c.project_id, created_at }, expected_version: p.expected_version, expected_digest: p.expected_digest,
    source_counts: { messages: n.messages, runs: n.runs, run_results: n.run_results, action_payloads: n.action_payloads, inference_contexts: n.inference_contexts, study_sessions: n.study_sessions, study_answers: n.study_answers }, payload_hash: p.payload_hash, prepared_at, expires_at };
}
export function read_deletion_outcome(value: unknown, expected: PendingConversationDeletion): ConversationDeletionOutcome {
  if (expected.stage !== 'CONFIRM' || !valid_deletion_id(expected.key) || !valid_deletion_id(expected.conversation_id) || !valid_deletion_id(expected.preparation_id) || !integer(expected.expected_version, 1, Number.MAX_SAFE_INTEGER - 1)) return fail();
  const prepared = task_time_micros(instant(expected.prepared_at)), expires = task_time_micros(instant(expected.expires_at));
  if (expires - prepared !== 300000000n) return fail();
  const o = object(value, ['status', 'receipt', 'checked_at']), checked_at = instant(o.checked_at);
  if (o.status === 'NOT_OBSERVED' && o.receipt === null) return { status: 'NOT_OBSERVED', receipt: null, checked_at };
  if (o.status !== 'COMMITTED') return fail();
  const r = object(o.receipt, ['deletion_id', 'conversation_id', 'preparation_id', 'scope_policy', 'deleted_version', 'tombstone_version', 'deleted_at']);
  const deleted_at = instant(r.deleted_at);
  const deleted = task_time_micros(deleted_at);
  if (!valid_deletion_id(r.deletion_id) || r.conversation_id !== expected.conversation_id || r.preparation_id !== expected.preparation_id || r.scope_policy !== DELETION_SCOPE || r.deleted_version !== expected.expected_version || !integer(r.tombstone_version, 2) || r.tombstone_version !== expected.expected_version + 1 || deleted < prepared || deleted >= expires || deleted > task_time_micros(checked_at)) return fail();
  return { status: 'COMMITTED', receipt: { deletion_id: r.deletion_id, conversation_id: expected.conversation_id, preparation_id: expected.preparation_id, scope_policy: DELETION_SCOPE, deleted_version: expected.expected_version, tombstone_version: r.tombstone_version, deleted_at }, checked_at };
}
export function deletion_state_path(id: string): string { if (!valid_deletion_id(id)) return fail(); return `/conversations/${id}/deletion-state`; }
export function delete_preparation_path(id: string): string { if (!valid_deletion_id(id)) return fail(); return `/conversations/${id}/delete-preparations`; }
export function delete_confirm_path(id: string): string { if (!valid_deletion_id(id)) return fail(); return `/conversation-delete-preparations/${id}/confirm`; }
export function deletion_receipt_path(key: string): string { if (!valid_deletion_id(key)) return fail(); return `/conversation-deletions/requests/${key}`; }
export function delete_prepare_body(state: EligibleDeletionState) {
  if (read_deletion_state(state, state.conversation_id).status !== 'ACTIVE' || !digest(state.content_digest)) return fail();
  return { expected_version: state.content_version, expected_digest: state.content_digest };
}
export function delete_confirm_body(preparation: ConversationDeletePreparation) {
  if (!digest(preparation.payload_hash) || !digest(preparation.expected_digest) || !integer(preparation.expected_version, 1, Number.MAX_SAFE_INTEGER - 1)) return fail();
  return { payload_hash: preparation.payload_hash, expected_version: preparation.expected_version, expected_digest: preparation.expected_digest, confirm: true as const };
}
export function deletion_time(value: string): string {
  return `${new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date(instant(value)))} KST`;
}

export { parse_deletion_json } from './conversation-deletion-json';
