import { useEffect, useRef, useState } from 'react';
import { Api, ApiError } from './api';
import { task_time_micros } from './tasks';
import { select_review_items, type StudyReviewSelection } from './study-review-task';
import { feedback_path, feedback_receipt_path, feedback_request_path, feedback_revoke_path, read_feedback, read_feedback_outcome, type FeedbackStudy, type FeedbackProjection, type FeedbackReceipt, type FeedbackTarget, type PendingFeedback } from './study-feedback';

export interface StudyFeedbackPanelProps { api: Api; sessionKey: number; snapshotKey: number; study: FeedbackStudy; writesPaused?: boolean; selectionDisabled?: boolean; onUnauthorized: () => void; onDeleted: (id: string) => void; onAcquirePending?: (revoking: boolean) => boolean; onPendingChange?: (pending: boolean) => void; onOutcomeUnknown?: () => void; onBarrierChange?: (active: boolean) => void; onCommitted?: () => void; onPrivateClear?: () => void; onCopyReviewSelection?: (selection: StudyReviewSelection) => void; copyDisabled?: boolean }
interface Session { api: Api; auth: number; snapshot: number; target: string; live: boolean; revision: number; projection: FeedbackProjection | null; selected: Record<string, number[]>; receipts: Record<string, FeedbackReceipt>; busy: boolean; sending: PendingFeedback | null; pending: PendingFeedback | null; notice: string }
const unknown = 'OUTCOME_UNKNOWN: The operation may have committed. Private feedback was cleared. Check its original receipt; no request will be sent again.';
function identity(study: FeedbackStudy) { return JSON.stringify([study.id, study.conversation_id, study.version, study.lesson.questions.map(q => q.id), study.attempts.map(a => [a.question_id, a.input_id, a.attempt_id])]); }

/** All recovery is content-free and in memory. Navigation never cancels authorized work. */
export function StudyFeedbackPanel(props: StudyFeedbackPanelProps) {
  const current = useRef(props); current.current = props;
  const state = useRef<Session | null>(null), [view, setView] = useState<Session | null>(null);
  const target = identity(props.study);
  function same(s: Session) { return s.live && state.current === s && current.current.api === s.api && current.current.sessionKey === s.auth && current.current.snapshotKey === s.snapshot && identity(current.current.study) === s.target; }
  function active(s: Session, revision: number) { return same(s) && revision === s.revision; }
  function publish(s: Session) { if (same(s)) { setView({ ...s }); current.current.onPendingChange?.(!!s.pending || !!s.sending); if (s.projection) current.current.onBarrierChange?.(s.projection.targets.some(t => t.request?.output_authority === 'ACTIVE')); } }
  function clear(s: Session) { s.live = false; s.revision++; s.projection = null; s.selected = {}; s.receipts = {}; s.pending = null; s.sending = null; s.busy = false; }
  function failed(error: unknown, s: Session, revision: number) {
    if (!same(s)) return;
    if (error instanceof ApiError && error.status === 401) { clear(s); setView(null); s.api.clear(); current.current.onUnauthorized(); return; }
    if (!active(s, revision)) return; s.projection = null;
    if (error instanceof ApiError && error.status === 410) { const conversation = current.current.study.conversation_id; clear(s); setView(null); current.current.onDeleted(conversation); return; }
    s.notice = error instanceof ApiError && /^[A-Z][A-Z0-9_]{0,63}$(?![\s\S])/.test(error.code) ? `${error.code}: Refresh saved state before another explicit action. Nothing was repeated.` : 'RESPONSE_NOT_VERIFIED: Feedback could not be verified. Nothing was repeated.'; publish(s);
  }
  async function load(s: Session, revision: number) {
    const study = current.current.study; const raw = await s.api.request<unknown>(feedback_path(study.id)); if (!active(s, revision)) return;
    const projection = read_feedback(raw, study);
    for (const known of Object.values(s.receipts)) { const request = projection.targets.find(t => t.input_id === known.input_id)?.request; if (projection.conversation_version < known.resulting_conversation_version || task_time_micros(projection.checked_at) < task_time_micros(known.committed_at) || !request || task_time_micros(request.updated_at) < task_time_micros(known.committed_at) || request.id !== known.feedback_request_id || request.feedback_version < known.resulting_feedback_version || known.operation === 'REVOKE_FEEDBACK' && (request.feedback_version !== known.resulting_feedback_version || request.status !== 'REVOKED' || request.output_authority !== 'REVOKED' || request.result !== null)) throw new ApiError('INVALID_FEEDBACK_RESPONSE'); }
    s.projection = projection; publish(s);
  }
  async function refresh() {
    const s = state.current; if (!s || !same(s) || s.busy || s.pending || s.sending) return;
    const revision = ++s.revision; s.busy = true; s.projection = null; s.selected = {}; s.notice = ''; publish(s);
    try { await load(s, revision); } catch (error) { failed(error, s, revision); }
    finally { if (active(s, revision)) { s.busy = false; publish(s); } }
  }
  useEffect(() => {
    const s: Session = { api: props.api, auth: props.sessionKey, snapshot: props.snapshotKey, target, live: true, revision: 0, projection: null, selected: {}, receipts: {}, busy: false, sending: null, pending: null, notice: '' }; state.current = s; publish(s); void refresh();
    const hide = () => { if (s.pending || s.sending) current.current.onOutcomeUnknown?.(); clear(s); setView(null); current.current.onPendingChange?.(false); };
    window.addEventListener('pagehide', hide);
    return () => { window.removeEventListener('pagehide', hide); if (s.pending || s.sending) current.current.onOutcomeUnknown?.(); clear(s); if (state.current === s) { state.current = null; current.current.onPendingChange?.(false); } };
  }, [props.api, props.sessionKey, props.snapshotKey, target]);
  function include(target: FeedbackTarget, index: number, checked: boolean) {
    const s = state.current;
    if (!s || !same(s) || !s.projection?.targets.includes(target) || s.busy || s.pending || s.sending || current.current.selectionDisabled || current.current.copyDisabled || current.current.writesPaused || target.request?.status !== 'ACCEPTED') return;
    const id = target.request.id, before = s.selected[id] ?? [];
    s.selected = { [id]: checked ? [...new Set([...before, index])].sort((a, b) => a - b) : before.filter(value => value !== index) }; publish(s);
  }
  function copy(target: FeedbackTarget) {
    const s = state.current;
    if (!s || !same(s) || !s.projection || s.busy || s.pending || s.sending || current.current.selectionDisabled || current.current.copyDisabled || current.current.writesPaused || !current.current.onCopyReviewSelection || !target.request) return;
    try { current.current.onCopyReviewSelection(select_review_items(current.current.study, s.projection, target, s.selected[target.request.id] ?? [])); }
    catch { s.notice = 'REVIEW_SOURCE_NOT_VERIFIED: Select saved checklist items again. No task change was made.'; publish(s); }
  }
  async function mutate(target: FeedbackTarget, revoking: boolean) {
    const s = state.current; if (!s || !same(s) || !s.projection || s.busy || s.pending || s.sending || current.current.selectionDisabled || !s.projection.targets.includes(target) || !target.input_id || !target.attempt_id || !revoking && current.current.writesPaused) return;
    if (revoking ? target.request?.output_authority !== 'ACTIVE' : target.availability !== 'AVAILABLE') return;
    if (current.current.onAcquirePending?.(revoking) === false) return;
    const study = current.current.study;
    const pending: PendingFeedback = { operation: revoking ? 'REVOKE_FEEDBACK' : 'REQUEST_FEEDBACK', key: crypto.randomUUID(), feedback_request_id: revoking ? target.request!.id : null, conversation_id: study.conversation_id, study_session_id: study.id, lesson_run_id: study.lesson_run_id, question_id: target.question_id, input_id: target.input_id, attempt_id: target.attempt_id, expected_feedback_version: revoking ? target.request!.feedback_version : null, expected_conversation_version: s.projection.conversation_version };
    const body = revoking ? { expected_feedback_version: pending.expected_feedback_version, expected_conversation_version: pending.expected_conversation_version } : { expected_study_version: 3, expected_conversation_version: pending.expected_conversation_version, question_id: pending.question_id, input_id: pending.input_id, attempt_id: pending.attempt_id };
    const revision = ++s.revision; s.sending = pending; s.busy = true; s.notice = ''; publish(s); let receipt: FeedbackReceipt | null = null;
    try {
      const raw = await s.api.request<unknown>(revoking ? feedback_revoke_path(pending.feedback_request_id!) : feedback_request_path(study.id), body, pending.key); if (!active(s, revision)) return;
      try { const outcome = read_feedback_outcome(raw, pending, true); if (outcome.status !== 'COMMITTED') throw new Error(); receipt = outcome.receipt; } catch { throw new ApiError('UNKNOWN', true); }
      s.receipts = { ...s.receipts, [receipt.feedback_request_id]: receipt };
      current.current.onBarrierChange?.(!revoking);
      s.sending = null; s.projection = null; s.notice = revoking ? 'The result is revoked. Execution and usage require their own evidence.' : 'The feedback request is recorded. Refresh to check its result.';
      await load(s, revision); if (active(s, revision)) current.current.onCommitted?.();
    } catch (error) {
      if (!same(s)) return;
      if (error instanceof ApiError && error.status === 401) { failed(error, s, revision); return; }
      if (!active(s, revision)) return;
      if (!receipt && (!(error instanceof ApiError) || error.uncertain || error.status >= 500)) { s.pending = pending; s.projection = null; s.notice = unknown; current.current.onPrivateClear?.(); }
      else { failed(error, s, revision); if (receipt && active(s, revision)) s.notice = 'The operation receipt was verified. Refresh saved feedback; do not repeat that operation.'; }
    } finally { if (active(s, revision)) { s.sending = null; s.busy = false; publish(s); } }
  }
  async function reconcile() {
    const s = state.current; if (!s || !same(s) || !s.pending || s.busy) return; const pending = s.pending, revision = ++s.revision; s.busy = true; publish(s);
    try { const raw = await s.api.request<unknown>(feedback_receipt_path(pending.key)); if (!active(s, revision)) return; const outcome = read_feedback_outcome(raw, pending);
      if (outcome.status === 'NOT_OBSERVED') s.notice = 'NOT_OBSERVED does not prove rollback. The outcome remains unknown. No new key or request will be sent.';
      else { s.receipts = { ...s.receipts, [outcome.receipt.feedback_request_id]: outcome.receipt }; current.current.onBarrierChange?.(pending.operation === 'REQUEST_FEEDBACK'); s.pending = null; s.notice = 'The original operation is recorded.'; await load(s, revision); if (active(s, revision)) current.current.onCommitted?.(); }
    } catch (error) { failed(error, s, revision); }
    finally { if (active(s, revision)) { s.busy = false; publish(s); } }
  }
  const s = state.current, currentView = view && s && same(s) && view.target === s.target && view.auth === s.auth && view.snapshot === s.snapshot ? view : null;
  const blocked = !currentView || currentView.busy || !!currentView.pending || !!currentView.sending || props.selectionDisabled;
  return <section className="study-feedback" aria-label="Saved answer feedback" aria-busy={!!currentView?.busy}>
    <h3>Saved answer feedback</h3><p>Request model feedback for one recorded answer. This does not mark mastery, run code, or create review tasks.</p>
    <button type="button" className="secondary" disabled={blocked} onClick={() => void refresh()}>Refresh feedback status</button>
    {currentView?.notice && <p role="alert">{currentView.notice}</p>}
    {currentView?.pending && <button type="button" disabled={currentView.busy} onClick={() => void reconcile()}>Check original feedback outcome</button>}
    {currentView?.projection?.targets.some(t => t.request?.output_authority === 'ACTIVE') && <p role="status">Feedback is active. Changes to this conversation are temporarily blocked. You can revoke the result.</p>}
    {currentView?.projection?.targets.map((target, index) => <section key={target.question_id} aria-label={`Feedback for question ${index + 1}`}><h4>Question {index + 1} feedback</h4>
      <p>{target.availability}</p>{!target.request && <button type="button" disabled={blocked || props.writesPaused || target.availability !== 'AVAILABLE'} onClick={() => void mutate(target, false)}>Request feedback for question {index + 1}</button>}
      {target.request && <><p>Request: {target.request.status}</p><p>Output authority: {target.request.output_authority}</p><p>Execution evidence: {target.request.execution_evidence}</p><p>Accounting: {target.request.accounting}</p>
        {target.request.output_authority === 'ACTIVE' && <button type="button" className="secondary" disabled={blocked} onClick={() => void mutate(target, true)}>Do not keep question {index + 1} result</button>}
        {target.request.output_authority === 'REVOKED' && target.request.execution_evidence === 'UNCONFIRMED' && <p>The result is revoked. Execution may still be running; usage and stop evidence are being checked.</p>}
        {target.request.result && <section aria-label={`Model feedback for question ${index + 1}`}><p>Model assessment: {target.request.result.assessment}</p><p className="result">{target.request.result.explanation}</p><h5>Example, not executed</h5><pre>{target.request.result.example}</pre><p>Code execution: NOT_RUN. This is not a compilation or correctness check.</p><h5>Assumptions</h5><ul>{target.request.result.assumptions.map((text, i) => <li key={i}>{text}</li>)}</ul><h5>Review checklist</h5><ul>{target.request.result.review_checklist.map((text, i) => <li key={i}>{props.onCopyReviewSelection && target.request?.status === 'ACCEPTED' ? <label><input type="checkbox" aria-label={`Include review item ${i + 1} from question ${index + 1} in task draft`} checked={(currentView.selected[target.request.id] ?? []).includes(i)} disabled={blocked || props.writesPaused || props.copyDisabled} onChange={event => include(target, i, event.target.checked)}/>{text}</label> : text}</li>)}</ul>{props.onCopyReviewSelection && target.request.status === 'ACCEPTED' && <><p>Include checkboxes select text for an independent task draft. They do not record review completion.</p><button type="button" disabled={blocked || props.writesPaused || props.copyDisabled || !(currentView.selected[target.request.id]?.length)} onClick={() => copy(target)}>Copy selected items to task draft</button></>}</section>}
      </>}
    </section>)}
  </section>;
}
