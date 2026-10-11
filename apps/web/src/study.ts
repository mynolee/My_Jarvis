import { ApiError } from './api';
import { task_time_micros, valid_date } from './tasks';

export interface StudyLesson { concepts: string[]; questions: { id: string; text: string }[] }
export interface StudyIndexItem { lesson_run_id: string; lesson_run_version: number; created_at: string; study_session_id: string | null; study_version: number | null; lifecycle: 'OPEN' | 'CLOSED_BY_OWNER' | null }
export interface StudyIndex { conversation_id: string; conversation_content_version: number; items: StudyIndexItem[]; next_cursor: string | null; checked_at: string }
export interface StudyAttempt { input_id: string; attempt_id: string; question_id: string; text: string; feedback: null }
export interface DurableStudy {
  id: string; conversation_id: string; project_id: string | null; lesson_run_id: string; version: number; conversation_content_version: number;
  lifecycle: 'OPEN' | 'CLOSED_BY_OWNER'; stage: 'WAITING_ANSWERS' | 'FEEDBACK_PENDING' | 'CLOSED_BY_OWNER'; lesson: StudyLesson; attempts: StudyAttempt[];
  feedback_availability: 'UNAVAILABLE'; created_at: string; updated_at: string; checked_at: string;
}
/** Deliberately content-free. Raw text and source displays are cleared on an ambiguous write. */
export type PendingStudy = { operation: 'START'; key: string; conversation_id: string; lesson_run_id: string; study_session_id: null; expected_version: null; expected_conversation_version: number }
  | { operation: 'ANSWER'; key: string; conversation_id: string; lesson_run_id: string; study_session_id: string; expected_version: number; expected_conversation_version: number; question_id: string }
  | { operation: 'CLOSE'; key: string; conversation_id: string; lesson_run_id: string; study_session_id: string; expected_version: number; expected_conversation_version: number };
interface ReceiptBase { operation: PendingStudy['operation']; mutation_id: string; conversation_id: string; study_session_id: string; lesson_run_id: string; prior_version: number | null; resulting_version: number; prior_conversation_version: number; resulting_conversation_version: number; committed_at: string }
export type StudyReceipt = ReceiptBase & ({ operation: 'START' | 'CLOSE' } | { operation: 'ANSWER'; question_id: string; attempt_id: string; input_id: string });
export type StudyOutcome = { status: 'COMMITTED'; receipt: StudyReceipt; checked_at: string } | { status: 'NOT_OBSERVED'; receipt: null; checked_at: string };
const fail = (): never => { throw new ApiError('INVALID_STUDY_RESPONSE'); };
export const study_id = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$(?![\s\S])/.test(v);
const integer = (v: unknown, min = 1, max = Number.MAX_SAFE_INTEGER): v is number => Number.isSafeInteger(v) && (v as number) >= min && (v as number) <= max;
const scalar = (v: unknown, max: number): v is string => typeof v === 'string' && !/[\u0000\uD800-\uDFFF]/u.test(v) && /[^\u0009-\u000D\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]/u.test(v) && [...v].length <= max;
export const valid_study_text = (v: unknown): v is string => scalar(v, 8000) && new TextEncoder().encode(v).length <= 32000;
function object(v: unknown, keys: string[]): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).length !== keys.length || !keys.every(key => Object.hasOwn(v, key))) return fail(); return v as Record<string, unknown>; }
function instant(v: unknown): string { return typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$(?![\s\S])/.test(v) && valid_date(v) ? v : fail(); }
function bounded(v: unknown, limit: number) { try { if (new TextEncoder().encode(JSON.stringify(v)).length > limit) return fail(); } catch { return fail(); } }
export function read_study_lesson(v: unknown): StudyLesson {
  const d = object(v, ['concepts', 'questions']);
  if (!Array.isArray(d.concepts) || d.concepts.length !== 3 || !d.concepts.every(x => scalar(x, 1000)) || !Array.isArray(d.questions) || d.questions.length !== 2) return fail();
  const questions = d.questions.map(value => { const q = object(value, ['id', 'text']); if (!study_id(q.id) || !scalar(q.text, 2000)) return fail(); return { id: q.id, text: q.text }; });
  if (questions[0].id === questions[1].id) return fail();
  return { concepts: [...d.concepts] as string[], questions };
}
export function read_accepted_lesson(v: unknown, runId: string): StudyLesson {
  bounded(v, 65536); const d = object(v, ['run_id', 'status', 'lesson']);
  if (!study_id(runId) || d.run_id !== runId || d.status !== 'SUCCEEDED') return fail();
  return read_study_lesson(d.lesson);
}
const cursor = (v: unknown): v is string => typeof v === 'string' && v.length >= 1 && v.length <= 1024 && /^[A-Za-z0-9_-]+\.[0-9a-f]{64}$(?![\s\S])/.test(v);
export function read_study_index(v: unknown, conversationId: string): StudyIndex {
  bounded(v, 65536); const d = object(v, ['conversation_id', 'conversation_content_version', 'items', 'next_cursor', 'checked_at']), checked_at = instant(d.checked_at);
  if (!study_id(conversationId) || d.conversation_id !== conversationId || !integer(d.conversation_content_version) || !Array.isArray(d.items) || d.items.length > 50 || d.next_cursor !== null && !cursor(d.next_cursor)) return fail();
  const ids = new Set<string>(), sessions = new Set<string>();
  const items = d.items.map(value => {
    const i = object(value, ['lesson_run_id', 'lesson_run_version', 'created_at', 'study_session_id', 'study_version', 'lifecycle']), created_at = instant(i.created_at);
    if (!study_id(i.lesson_run_id) || ids.has(i.lesson_run_id) || !integer(i.lesson_run_version) || task_time_micros(created_at) > task_time_micros(checked_at)) return fail(); ids.add(i.lesson_run_id);
    if (i.study_session_id === null) { if (i.study_version !== null || i.lifecycle !== null) return fail(); }
    else { if (!study_id(i.study_session_id) || sessions.has(i.study_session_id) || !integer(i.study_version, 1, 4) || (i.lifecycle !== 'OPEN' && i.lifecycle !== 'CLOSED_BY_OWNER') || i.lifecycle === 'OPEN' && i.study_version > 3 || i.lifecycle === 'CLOSED_BY_OWNER' && i.study_version < 2) return fail(); sessions.add(i.study_session_id); }
    return { lesson_run_id: i.lesson_run_id, lesson_run_version: i.lesson_run_version, created_at, study_session_id: i.study_session_id, study_version: i.study_version, lifecycle: i.lifecycle } as StudyIndexItem;
  });
  if (d.next_cursor !== null && items.length === 0) return fail();
  return { conversation_id: conversationId, conversation_content_version: d.conversation_content_version, items, next_cursor: d.next_cursor, checked_at };
}
export function read_durable_study(v: unknown, expected: { id: string; conversation_id: string; lesson_run_id: string; project_id?: string | null; minimum_version?: number; minimum_conversation_version?: number; checked_after?: string }, receipt?: StudyReceipt): DurableStudy {
  bounded(v, 256 * 1024);
  const d = object(v, ['id', 'conversation_id', 'project_id', 'lesson_run_id', 'version', 'conversation_content_version', 'lifecycle', 'stage', 'lesson', 'attempts', 'feedback_availability', 'created_at', 'updated_at', 'checked_at']);
  const lesson = read_study_lesson(d.lesson), created_at = instant(d.created_at), updated_at = instant(d.updated_at), checked_at = instant(d.checked_at);
  if (!study_id(expected.id) || !study_id(expected.conversation_id) || !study_id(expected.lesson_run_id) || d.id !== expected.id || d.conversation_id !== expected.conversation_id || d.lesson_run_id !== expected.lesson_run_id || (d.project_id !== null && !study_id(d.project_id)) || expected.project_id !== undefined && d.project_id !== expected.project_id || !integer(d.version, 1, 4) || !integer(d.conversation_content_version) || !Array.isArray(d.attempts) || d.attempts.length > 2 || d.feedback_availability !== 'UNAVAILABLE' || (d.lifecycle !== 'OPEN' && d.lifecycle !== 'CLOSED_BY_OWNER') || task_time_micros(created_at) > task_time_micros(updated_at) || task_time_micros(updated_at) > task_time_micros(checked_at)) return fail();
  if (expected.minimum_version !== undefined && d.version < expected.minimum_version || expected.minimum_conversation_version !== undefined && d.conversation_content_version < expected.minimum_conversation_version || expected.checked_after !== undefined && task_time_micros(checked_at) < task_time_micros(instant(expected.checked_after))) return fail();
  const inputs = new Set<string>(), attemptsIds = new Set<string>(), questions = new Set<string>();
  const attempts = d.attempts.map(value => {
    const a = object(value, ['input_id', 'attempt_id', 'question_id', 'text', 'feedback']);
    if (!study_id(a.input_id) || !study_id(a.attempt_id) || !study_id(a.question_id) || inputs.has(a.input_id) || attemptsIds.has(a.attempt_id) || questions.has(a.question_id) || !lesson.questions.some(q => q.id === a.question_id) || !valid_study_text(a.text) || a.feedback !== null) return fail();
    inputs.add(a.input_id); attemptsIds.add(a.attempt_id); questions.add(a.question_id);
    return { input_id: a.input_id, attempt_id: a.attempt_id, question_id: a.question_id, text: a.text, feedback: null } as StudyAttempt;
  });
  const stage = d.lifecycle === 'CLOSED_BY_OWNER' ? 'CLOSED_BY_OWNER' : attempts.length === 2 ? 'FEEDBACK_PENDING' : 'WAITING_ANSWERS';
  if (d.stage !== stage || d.version !== 1 + attempts.length + (d.lifecycle === 'CLOSED_BY_OWNER' ? 1 : 0) || d.version === 1 && created_at !== updated_at) return fail();
  if (receipt && (receipt.study_session_id !== d.id || receipt.conversation_id !== d.conversation_id || receipt.lesson_run_id !== d.lesson_run_id || d.version < receipt.resulting_version || d.conversation_content_version < receipt.resulting_conversation_version || task_time_micros(updated_at) < task_time_micros(receipt.committed_at) || receipt.operation === 'CLOSE' && d.lifecycle !== 'CLOSED_BY_OWNER' || receipt.operation === 'ANSWER' && !attempts.some(a => a.question_id === receipt.question_id && a.input_id === receipt.input_id && a.attempt_id === receipt.attempt_id))) return fail();
  return { id: d.id, conversation_id: d.conversation_id, project_id: d.project_id, lesson_run_id: d.lesson_run_id, version: d.version, conversation_content_version: d.conversation_content_version, lifecycle: d.lifecycle, stage, lesson, attempts, feedback_availability: 'UNAVAILABLE', created_at, updated_at, checked_at } as DurableStudy;
}
export function read_study_outcome(v: unknown, expected: PendingStudy, mutation = false): StudyOutcome {
  bounded(v, 65536);
  if (!study_id(expected.key) || !study_id(expected.conversation_id) || !study_id(expected.lesson_run_id) || !integer(expected.expected_conversation_version, 1, Number.MAX_SAFE_INTEGER - 2) || expected.operation === 'START' && (expected.study_session_id !== null || expected.expected_version !== null) || expected.operation !== 'START' && (!study_id(expected.study_session_id) || !integer(expected.expected_version, 1, expected.operation === 'ANSWER' ? 2 : 3)) || expected.operation === 'ANSWER' && !study_id(expected.question_id)) return fail();
  const d = object(v, ['status', 'receipt', 'checked_at']), checked_at = instant(d.checked_at);
  if (d.status === 'NOT_OBSERVED' && d.receipt === null && !mutation) return { status: 'NOT_OBSERVED', receipt: null, checked_at };
  if (d.status !== 'COMMITTED') return fail();
  const r = object(d.receipt, ['operation', 'mutation_id', 'conversation_id', 'study_session_id', 'lesson_run_id', 'prior_version', 'resulting_version', 'prior_conversation_version', 'resulting_conversation_version', 'committed_at', ...(expected.operation === 'ANSWER' ? ['question_id', 'attempt_id', 'input_id'] : [])]), committed_at = instant(r.committed_at);
  if (r.operation !== expected.operation || r.mutation_id !== expected.key || r.conversation_id !== expected.conversation_id || r.lesson_run_id !== expected.lesson_run_id || !study_id(r.study_session_id) || expected.study_session_id !== null && r.study_session_id !== expected.study_session_id || r.prior_version !== expected.expected_version || !integer(r.resulting_version, 1, 4) || r.resulting_version !== (expected.expected_version ?? 0) + 1 || r.prior_conversation_version !== expected.expected_conversation_version || r.resulting_conversation_version !== expected.expected_conversation_version + 1 || task_time_micros(committed_at) > task_time_micros(checked_at)) return fail();
  if (expected.operation === 'ANSWER' && (r.question_id !== expected.question_id || !study_id(r.attempt_id) || !study_id(r.input_id))) return fail();
  return { status: 'COMMITTED', receipt: { ...r, committed_at } as StudyReceipt, checked_at };
}
export function study_index_path(id: string, next: string | null = null): string { if (!study_id(id) || next !== null && !cursor(next)) return fail(); return `/conversations/${id}/study-index?limit=20${next ? `&cursor=${encodeURIComponent(next)}` : ''}`; }
export function study_path(id: string): string { if (!study_id(id)) return fail(); return `/study-sessions/${id}`; }
export function study_receipt_path(key: string): string { if (!study_id(key)) return fail(); return `/study-mutations/${key}`; }
