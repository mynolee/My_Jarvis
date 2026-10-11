import { vi } from 'vitest';
import { Api, ApiError } from '../src/api';
import type { DurableStudy } from '../src/study';
import type { FeedbackOutcome, FeedbackProjection, FeedbackRequest, PendingFeedback } from '../src/study-feedback';
import { at, attempt, cid, input, key, other, q1, q2, run, sid, study } from './study-fixtures';
export const feedbackId = 'a1111111-1111-4111-8111-111111111111';
export const saved = (): DurableStudy => ({ ...study(), version: 3, conversation_content_version: 13, stage: 'FEEDBACK_PENDING', attempts: [{ question_id: q1, input_id: input, attempt_id: attempt, text: 'Saved synthetic first answer', feedback: null }, { question_id: q2, input_id: other, attempt_id: key, text: 'Saved synthetic second answer', feedback: null }] });
export const queued = (): FeedbackRequest => ({ id: feedbackId, feedback_version: 1, status: 'QUEUED', output_authority: 'ACTIVE', execution_evidence: 'UNCONFIRMED', accounting: 'PENDING', created_at: at, updated_at: at, result: null });
export const acceptedFeedback = (): FeedbackRequest => ({ ...queued(), feedback_version: 3, status: 'ACCEPTED', output_authority: 'CLOSED', execution_evidence: 'COMPLETED', accounting: 'MEASURED', result: { revision: 1, assessment: 'PARTIALLY_CORRECT', explanation: 'Synthetic boundary feedback', example: '<script>untrusted example</script>', assumptions: ['The list is finite.'], review_checklist: ['Check the last element.'], code_execution: 'NOT_RUN' } });
export function projection(request: FeedbackRequest | null = null): FeedbackProjection { return { revision: 1, study_session_id: sid, conversation_id: cid, study_version: 3, conversation_version: request ? 17 : 13, lifecycle: 'OPEN', checked_at: at, targets: saved().attempts.map((answer, index) => ({ question_id: answer.question_id, input_id: answer.input_id, attempt_id: answer.attempt_id, availability: index === 0 && request ? 'ALREADY_REQUESTED' : request?.output_authority === 'ACTIVE' ? 'CONVERSATION_BUSY' : 'AVAILABLE', request: index === 0 ? request : null })) }; }
export function pending(revoking = false): PendingFeedback { return { operation: revoking ? 'REVOKE_FEEDBACK' : 'REQUEST_FEEDBACK', key, feedback_request_id: revoking ? feedbackId : null, conversation_id: cid, study_session_id: sid, lesson_run_id: run, question_id: q1, input_id: input, attempt_id: attempt, expected_feedback_version: revoking ? 1 : null, expected_conversation_version: revoking ? 17 : 13 }; }
export function outcome(p: PendingFeedback): FeedbackOutcome { return { status: 'COMMITTED', receipt: { mutation_id: p.key, operation: p.operation, feedback_request_id: p.feedback_request_id ?? feedbackId, conversation_id: p.conversation_id, study_session_id: p.study_session_id, lesson_run_id: p.lesson_run_id, question_id: p.question_id, input_id: p.input_id, attempt_id: p.attempt_id, prior_feedback_version: p.expected_feedback_version, resulting_feedback_version: (p.expected_feedback_version ?? 0) + 1, study_version: 3, prior_conversation_version: p.expected_conversation_version, resulting_conversation_version: p.expected_conversation_version + 4, committed_at: at }, checked_at: at }; }
export const deferred = <T,>() => { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };

// Actual App tests use this local API double; every mutation is counted and no transport is opened.

export function appFixture() {
  const api = new Api(), state = { extra: {} as Record<string, unknown>, authenticated: true, current: saved(), feedback: projection(), read: null as Promise<unknown> | null, mutation: null as Promise<unknown> | null, receipt: { status: 'NOT_OBSERVED', receipt: null, checked_at: at } as unknown };
  vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation(async (path, body, k) => {
    const route = path.split('?')[0];
    if (Object.hasOwn(state.extra, route)) return state.extra[route];
    if (route === '/me') { if (!state.authenticated) throw new ApiError('UNAUTHENTICATED', false, 401); return {}; }
    if (route === '/auth/logout') { state.authenticated = false; return { logged_out: true }; }
    if (route === '/auth/login') { state.authenticated = true; return { csrf_token: 'synthetic-replacement' }; }
    if (route === '/execution') return { execution_paused: false, version: 1, checked_at: at };
    if (route === '/execution/pause') return { execution_paused: true, version: 2, checked_at: at };
    if (route === '/local-model/status') return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', status: 'READY', ready: true };
    if (route === '/conversations') return { items: [{ id: cid, title: 'Feedback conversation', project_id: null, created_at: at }, { id: other, title: 'Other conversation', project_id: null, created_at: at }], next_cursor: null };
    if (route.endsWith('/messages') && body === undefined) return { items: [], next_cursor: null, stream_cursor: null, current_run: null };
    if (route.endsWith('/study-index')) return { conversation_id: route.includes(cid) ? cid : other, conversation_content_version: 13, items: route.includes(cid) ? [{ lesson_run_id: run, lesson_run_version: 4, created_at: at, study_session_id: sid, study_version: 3, lifecycle: 'OPEN' }] : [], next_cursor: null, checked_at: at };
    if (route === `/study-sessions/${sid}`) return state.current;
    if (route === `/study-sessions/${sid}/feedback`) return state.read ?? state.feedback;
    if (route.startsWith('/study-feedback-mutations/')) return state.receipt;
    if (route.endsWith('/feedback-requests') || route.endsWith('/revoke')) { if (state.mutation) return state.mutation; const revoking = route.endsWith('/revoke'), p = { ...pending(revoking), key: k! }; state.feedback = revoking ? { ...projection({ ...queued(), feedback_version: 2, status: 'REVOKED', output_authority: 'REVOKED', accounting: 'UNSTARTED' }), conversation_version: 21 } : projection(queued()); return outcome(p); }
    throw new Error('Unexpected synthetic feedback route');
  });
  return { api, state, request };
}
