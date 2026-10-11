import { useEffect, useRef, useState } from 'react';
import { Api, ApiError } from './api';
import { StudyFeedbackPanel } from './StudyFeedbackPanel';
import { feedback_study, type FeedbackStudy } from './study-feedback';
import { read_accepted_lesson, read_durable_study, read_study_index, read_study_outcome, study_id, study_index_path, study_path, study_receipt_path, valid_study_text, type DurableStudy, type PendingStudy, type StudyIndex, type StudyIndexItem, type StudyLesson, type StudyReceipt } from './study';

export interface StudyPanelProps {
  api: Api; sessionKey: number; conversationId: string | null; projectId: string | null; snapshotKey: number;
  onUnauthorized: () => void; onDeleted: (id: string) => void;
  writesPaused?: boolean; selectionDisabled?: boolean; contentHidden?: boolean; sourceWriteBarrier?: boolean;
  onFeedbackBarrierChange?: (studyId: string, active: boolean) => void; onAcquireFeedbackPending?: (revoking: boolean) => boolean;
  onAcquirePending?: () => boolean; onPendingChange?: (pending: boolean) => void; onOutcomeUnknown?: () => void; onCommitted?: () => void;
}
interface Session {
  api: Api; key: number; alive: boolean; revision: number; conversation: string | null; project: string | null; snapshot: number; paused: boolean;
  index: StudyIndex | null; selected: StudyIndexItem | null; lesson: StudyLesson | null; study: DurableStudy | null; drafts: Record<string, string>;
  busy: 'index' | 'select' | 'mutation' | 'reconcile' | 'read' | null; sending: PendingStudy | null; pending: PendingStudy | null; notice: string;
}
interface View { session: Session; index: StudyIndex | null; selected: StudyIndexItem | null; lesson: StudyLesson | null; study: DurableStudy | null; drafts: Record<string, string>; busy: Session['busy']; pending: PendingStudy | null; notice: string }
const unknownNotice = 'OUTCOME_UNKNOWN: The request may have committed. Answer drafts and private source content were cleared. Check the original outcome; the request will not be sent again.';

/** In-memory, explicit owner actions only. Reads do not depend on model readiness. */
export function StudyPanel(props: StudyPanelProps) {
  const live = useRef(props); live.current = props;
  const session = useRef<Session | null>(null), [view, setView] = useState<View | null>(null);
  const feedbackPending = useRef(false); const [feedbackBusy, setFeedbackBusy] = useState(false);
  const feedbackRecovery = useRef(false);
  const [feedbackStudy, setFeedbackStudy] = useState<FeedbackStudy | null>(null);
  const same = (s: Session) => s.alive && session.current === s && live.current.api === s.api && live.current.sessionKey === s.key;
  const active = (s: Session, revision: number) => same(s) && revision === s.revision && s.conversation === live.current.conversationId && s.project === live.current.projectId && s.snapshot === live.current.snapshotKey && s.paused === !!live.current.writesPaused && !live.current.contentHidden;
  function publish(s: Session) { if (same(s) && s.study) { feedbackRecovery.current = false; setFeedbackStudy(feedback_study(s.study)); } if (same(s)) setView({ session: s, index: s.index, selected: s.selected, lesson: s.lesson, study: s.study, drafts: s.drafts, busy: s.busy, pending: s.pending, notice: s.notice }); }
  function purge(s: Session, keepFeedback = false) { if (same(s)) { feedbackRecovery.current = keepFeedback; if (!keepFeedback) setFeedbackStudy(null); } s.index = null; s.selected = null; s.lesson = null; s.study = null; s.drafts = {}; }
  function report(s: Session) { live.current.onPendingChange?.(!!s.pending || !!s.sending || feedbackPending.current); }
  function discard(s: Session) { if (s.pending || s.sending) live.current.onOutcomeUnknown?.(); purge(s); s.alive = false; s.revision++; s.pending = null; s.sending = null; s.busy = null; }
  function unauthorized(s: Session) { if (!same(s)) return; discard(s); setView(null); s.api.clear(); live.current.onUnauthorized(); }
  function failed(error: unknown, s: Session, revision: number): void {
    if (!same(s)) return;
    if (error instanceof ApiError && error.status === 401) { unauthorized(s); return; }
    if (!active(s, revision)) return;
    if (error instanceof ApiError && error.status === 410 && error.code === 'CONVERSATION_DELETED') { const id = s.conversation; purge(s); if (id) live.current.onDeleted(id); return; }
    purge(s);
    s.notice = error instanceof ApiError && /^[A-Z][A-Z0-9_]{0,63}$(?![\s\S])/.test(error.code) ? `${error.code}: Read the current study state before making a new explicit change. No request was repeated.` : 'RESPONSE_NOT_VERIFIED: The current study could not be verified. No request was repeated.';
    publish(s);
  }
  useEffect(() => {
    const s: Session = { api: props.api, key: props.sessionKey, alive: true, revision: 0, conversation: props.conversationId, project: props.projectId, snapshot: props.snapshotKey, paused: !!props.writesPaused, index: null, selected: null, lesson: null, study: null, drafts: {}, busy: null, sending: null, pending: null, notice: '' };
    session.current = s; publish(s);
    const hide = () => { if (session.current === s) { discard(s); setView(null); live.current.onPendingChange?.(false); } };
    window.addEventListener('pagehide', hide);
    return () => { window.removeEventListener('pagehide', hide); discard(s); if (session.current === s) { session.current = null; live.current.onPendingChange?.(false); } };
  }, [props.api, props.sessionKey]);
  useEffect(() => {
    const s = session.current; if (!s || !same(s)) return;
    if (s.conversation === props.conversationId && s.project === props.projectId && s.snapshot === props.snapshotKey && s.paused === !!props.writesPaused && !props.contentHidden) return;
    s.revision++; if (s.sending) { s.pending = s.sending; s.sending = null; }
    s.conversation = props.conversationId; s.project = props.projectId; s.snapshot = props.snapshotKey; s.paused = !!props.writesPaused; s.busy = null; purge(s);
    s.notice = s.pending ? unknownNotice : ''; report(s); publish(s);
  }, [props.conversationId, props.projectId, props.snapshotKey, props.contentHidden, props.writesPaused]);
  function canWrite(s: Session) { return same(s) && active(s, s.revision) && !s.busy && !s.pending && !s.sending && !live.current.writesPaused && !live.current.selectionDisabled && !live.current.sourceWriteBarrier && !feedbackPending.current; }
  function acquireFeedback(target: FeedbackStudy, revoking: boolean) {
    const s = session.current;
    if (!s || !same(s) || !active(s, s.revision) || s.busy || s.pending || s.sending || feedbackPending.current || live.current.selectionDisabled || s.conversation !== target.conversation_id || !(s.study?.id === target.id || feedbackRecovery.current && feedbackStudy?.id === target.id) || !revoking && (live.current.writesPaused || live.current.sourceWriteBarrier)) return false;
    return live.current.onAcquireFeedbackPending?.(revoking) ?? live.current.onAcquirePending?.() ?? true;
  }
  async function discover(next = false) {
    const s = session.current; if (!s || !same(s) || !active(s, s.revision) || !study_id(s.conversation) || s.busy || s.pending || s.sending || feedbackPending.current || live.current.selectionDisabled) return;
    const revision = ++s.revision, conversation = s.conversation, cursor = next ? s.index?.next_cursor ?? null : null;
    if (next && !cursor) return;
    purge(s); s.busy = 'index'; s.notice = ''; publish(s);
    try { const raw = await s.api.request<unknown>(study_index_path(conversation, cursor)); if (!active(s, revision)) return; s.index = read_study_index(raw, conversation); }
    catch (error) { failed(error, s, revision); }
    finally { if (active(s, revision)) { s.busy = null; publish(s); } }
  }
  async function select(item: StudyIndexItem) {
    const s = session.current; if (!s || !same(s) || !active(s, s.revision) || !s.index?.items.includes(item) || s.busy || s.pending || s.sending || feedbackPending.current || live.current.selectionDisabled || !study_id(s.conversation)) return;
    const revision = ++s.revision; feedbackRecovery.current = false; setFeedbackStudy(null); s.selected = item; s.lesson = null; s.study = null; s.drafts = {}; s.busy = 'select'; s.notice = ''; publish(s);
    try {
      if (item.study_session_id) {
        const raw = await s.api.request<unknown>(study_path(item.study_session_id)); if (!active(s, revision)) return;
        s.study = read_durable_study(raw, { id: item.study_session_id, conversation_id: s.conversation, lesson_run_id: item.lesson_run_id, project_id: s.project, minimum_version: item.study_version ?? undefined, minimum_conversation_version: s.index?.conversation_content_version, checked_after: s.index?.checked_at }); s.lesson = s.study.lesson;
      } else {
        const raw = await s.api.request<unknown>(`/runs/${item.lesson_run_id}/study-lesson`); if (!active(s, revision)) return; s.lesson = read_accepted_lesson(raw, item.lesson_run_id);
      }
    } catch (error) { failed(error, s, revision); }
    finally { if (active(s, revision)) { s.busy = null; publish(s); } }
  }
  async function canonical(s: Session, revision: number, receipt: StudyReceipt) {
    const raw = await s.api.request<unknown>(study_path(receipt.study_session_id)); if (!active(s, revision)) return;
    if (s.conversation !== receipt.conversation_id) { purge(s); s.notice = 'The original mutation is recorded. Read the selected conversation separately.'; return; }
    s.study = read_durable_study(raw, { id: receipt.study_session_id, conversation_id: receipt.conversation_id, lesson_run_id: receipt.lesson_run_id, project_id: s.project }, receipt); s.lesson = s.study.lesson;
  }
  async function mutate(operation: PendingStudy['operation'], questionId?: string) {
    const s = session.current; if (!s || !canWrite(s) || !study_id(s.conversation)) return;
    let pending: PendingStudy, body: object, path: string;
    if (operation === 'START') {
      if (!s.index || !s.selected || !s.lesson || s.selected.study_session_id || s.study || s.index.conversation_content_version > Number.MAX_SAFE_INTEGER - 2) return;
      pending = { operation, key: crypto.randomUUID(), conversation_id: s.conversation, lesson_run_id: s.selected.lesson_run_id, study_session_id: null, expected_version: null, expected_conversation_version: s.index.conversation_content_version };
      body = { lesson_run_id: pending.lesson_run_id, expected_conversation_version: pending.expected_conversation_version }; path = `/conversations/${s.conversation}/study-sessions`;
    } else {
      const study = s.study; if (!study || study.lifecycle !== 'OPEN' || study.conversation_content_version > Number.MAX_SAFE_INTEGER - 2) return;
      const base = { key: crypto.randomUUID(), conversation_id: s.conversation, lesson_run_id: study.lesson_run_id, study_session_id: study.id, expected_version: study.version, expected_conversation_version: study.conversation_content_version };
      if (operation === 'ANSWER') {
        if (!questionId || !study.lesson.questions.some(q => q.id === questionId) || study.attempts.some(a => a.question_id === questionId) || !valid_study_text(s.drafts[questionId])) return;
        pending = { operation, ...base, question_id: questionId }; body = { expected_version: study.version, expected_conversation_version: study.conversation_content_version, question_id: questionId, text: s.drafts[questionId] }; path = `${study_path(study.id)}/answers`;
      } else { pending = { operation, ...base }; body = { expected_version: study.version, expected_conversation_version: study.conversation_content_version }; path = `${study_path(study.id)}/close`; }
    }
    if (live.current.onAcquirePending?.() === false) return;
    const revision = ++s.revision; s.sending = pending; s.busy = 'mutation'; s.drafts = {}; s.notice = ''; report(s); publish(s);
    let receipt: StudyReceipt | null = null;
    try {
      const raw = await s.api.request<unknown>(path, body, pending.key); if (!active(s, revision)) return;
      try { const outcome = read_study_outcome(raw, pending, true); if (outcome.status !== 'COMMITTED') throw new Error(); receipt = outcome.receipt; } catch { throw new ApiError('UNKNOWN', true); }
      s.sending = null; purge(s);
      s.notice = operation === 'ANSWER' ? 'Answer recorded. Feedback has not been generated.' : operation === 'CLOSE' ? 'Study closed by owner. This does not certify mastery or create a review task.' : 'Study session created. Original questions are retained.';
      await canonical(s, revision, receipt);
      if (active(s, revision)) live.current.onCommitted?.();
    } catch (error) {
      if (!same(s)) return;
      if (error instanceof ApiError && error.status === 401) { unauthorized(s); return; }
      if (!active(s, revision)) return;
      if (!receipt && (!(error instanceof ApiError) || error.uncertain || error.status >= 500)) { s.pending = pending; purge(s); s.notice = unknownNotice; }
      else { failed(error, s, revision); if (receipt && active(s, revision)) s.notice = 'The mutation receipt was verified, but the current study could not be read. Discover saved studies again; do not repeat the prior request.'; }
    } finally { if (active(s, revision)) { s.sending = null; s.busy = null; report(s); publish(s); } }
  }
  async function reconcile() {
    const s = session.current; if (!s || !same(s) || !active(s, s.revision) || !s.pending || s.busy) return;
    const pending = s.pending, revision = ++s.revision; s.busy = 'reconcile'; s.notice = ''; publish(s);
    try {
      const raw = await s.api.request<unknown>(study_receipt_path(pending.key)); if (!active(s, revision)) return;
      const outcome = read_study_outcome(raw, pending);
      if (outcome.status === 'NOT_OBSERVED') { s.notice = 'NOT_OBSERVED: No committed receipt is currently visible. This does not prove failure. The outcome remains unknown; no resend or replacement key is allowed.'; }
      else { s.pending = null; s.notice = 'The original mutation is recorded. Feedback has not been generated.'; if (s.conversation === pending.conversation_id) await canonical(s, revision, outcome.receipt); if (active(s, revision)) live.current.onCommitted?.(); }
    } catch (error) { failed(error, s, revision); }
    finally { if (active(s, revision)) { s.busy = null; report(s); publish(s); } }
  }
  const visible = view && same(view.session) && view.session.conversation === props.conversationId && view.session.project === props.projectId && view.session.snapshot === props.snapshotKey && view.session.paused === !!props.writesPaused && !props.contentHidden ? view : null;
  const versionExhausted = (visible?.study?.conversation_content_version ?? visible?.index?.conversation_content_version ?? 1) > Number.MAX_SAFE_INTEGER - 2;
  const busy = !!visible?.busy, pending = !!visible?.pending, blocked = !visible || busy || pending || feedbackBusy || props.writesPaused || props.sourceWriteBarrier || props.selectionDisabled || versionExhausted;
  const study = visible?.study, lesson = visible?.lesson;
  return <section className="panel study" aria-label="Durable study" aria-busy={busy}>
    <h2>Durable study</h2>
    <p>Choose an accepted typed lesson, record your own answers, and resume from saved state. Recording an answer does not call a model or send an ordinary message.</p>
    <p>Saved answers can be reviewed in the separate model-feedback panel. Saving or opening a study never requests generation.</p>
    {!props.conversationId && <p>Select a conversation to discover accepted lessons.</p>}
    <button type="button" className="secondary" disabled={!visible || busy || pending || !study_id(props.conversationId) || props.selectionDisabled || feedbackBusy} onClick={() => void discover()}>Find saved study lessons</button>
    {versionExhausted && <p role="status">The conversation version has no safe headroom for another study change. Saved study data remains read-only.</p>}
    {visible?.notice && <p role="alert">{visible.notice}</p>}
    {busy && <p role="status">{visible?.busy === 'mutation' ? 'Waiting for the exact mutation receipt…' : 'Reading canonical study state…'}</p>}
    {pending && <section aria-label="Study outcome unknown"><p>Other changes remain paused. Only the original content-free receipt can be checked.</p><button type="button" disabled={busy} onClick={() => void reconcile()}>Check original study outcome</button></section>}
    {visible?.index && <section aria-label="Accepted study lessons">{visible.index.items.length === 0 ? <p>No accepted typed study lessons are available in this conversation.</p> : <ul>{visible.index.items.map(item => <li key={item.lesson_run_id}><button type="button" className="secondary" disabled={busy || pending || feedbackBusy || props.selectionDisabled} onClick={() => void select(item)}>{item.study_session_id ? 'Resume' : 'View'} lesson {item.lesson_run_id}</button></li>)}</ul>}{visible.index.next_cursor && <button type="button" disabled={busy || pending || feedbackBusy || props.selectionDisabled} onClick={() => void discover(true)}>Next study lessons</button>}</section>}
    {lesson && <section aria-label="Original study lesson"><h3>Original lesson</h3><ul>{lesson.concepts.map((concept, index) => <li key={index}>{concept}</li>)}</ul>
      {lesson.questions.map((question, index) => { const answer = study?.attempts.find(a => a.question_id === question.id); return <section key={question.id} aria-label={`Study question ${index + 1}`}><h4>Question {index + 1}</h4><p>{question.text}</p>{answer ? <><p>Answer recorded. Feedback status is shown separately.</p><p className="result">{answer.text}</p></> : study && study.lifecycle === 'OPEN' ? <form onSubmit={event => { event.preventDefault(); void mutate('ANSWER', question.id); }}><label>Answer {index + 1}<textarea value={visible?.drafts[question.id] ?? ''} maxLength={16000} disabled={blocked} onChange={event => { const s = session.current; if (!s || !canWrite(s)) return; s.drafts = { ...s.drafts, [question.id]: event.target.value }; publish(s); }}/></label><button disabled={blocked || !valid_study_text(visible?.drafts[question.id])}>Record answer {index + 1}</button></form> : <p>No answer recorded.</p>}</section>; })}
      {!study && visible?.selected && <button type="button" disabled={blocked} onClick={() => void mutate('START')}>Start study session</button>}
    </section>}
    {visible && feedbackStudy && (study?.id === feedbackStudy.id || feedbackRecovery.current) && <StudyFeedbackPanel key={`${props.sessionKey}:${feedbackStudy.id}`} api={props.api} sessionKey={props.sessionKey} snapshotKey={props.snapshotKey} study={feedbackStudy} writesPaused={props.writesPaused} selectionDisabled={props.selectionDisabled || busy || pending} onUnauthorized={props.onUnauthorized} onDeleted={props.onDeleted} onAcquirePending={revoking => acquireFeedback(feedbackStudy, revoking)} onPendingChange={value => { feedbackPending.current = value; setFeedbackBusy(value); const s = session.current; if (s && same(s)) report(s); }} onOutcomeUnknown={props.onOutcomeUnknown} onBarrierChange={value => props.onFeedbackBarrierChange?.(feedbackStudy.id, value)} onPrivateClear={() => { const s = session.current; if (s && same(s)) { purge(s, true); publish(s); } }}/>}
    {study && <section aria-label="Saved study status"><p>Study version: {study.version}</p><p>Lifecycle: {study.lifecycle}</p>{study.stage === 'FEEDBACK_PENDING' && <p>FEEDBACK_PENDING describes two recorded answers. Use the separate feedback controls to request or inspect model feedback.</p>}{study.lifecycle === 'OPEN' && <button type="button" className="secondary" disabled={blocked} onClick={() => void mutate('CLOSE')}>Close study session</button>}</section>}
  </section>;
}
