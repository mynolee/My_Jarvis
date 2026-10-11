import { useEffect, useRef, useState } from 'react';
import { Api, ApiError } from './api';
import { delete_confirm_body, delete_confirm_path, delete_preparation_path, delete_prepare_body, deletion_receipt_path, deletion_state_path, deletion_time, read_delete_preparation, read_deletion_outcome, read_deletion_state, valid_deletion_id, type ConversationDeletePreparation, type DeletionBlocker, type EligibleDeletionState, type PendingConversationDeletion } from './conversation-deletion';

export interface ConversationDeletionPanelProps {
  api: Api; sessionKey: number; selectedConversationId: string | null; snapshotKey?: number;
  onUnauthorized: () => void; onReauthenticate: () => void;
  writesPaused?: boolean; selectionDisabled?: boolean;
  onAcquirePending?: () => boolean; onPendingChange?: (pending: boolean) => void;
  /** Separate source-display fence: preparation is inert and must not remount history. */
  onContentHiddenChange?: (hidden: boolean) => void;
  onDeleted: (conversationId: string) => void;
  /** Warning only: this must never acquire a fence in a later authenticated session. */
  onOutcomeUnknown?: () => void;
}
type Busy = 'state' | 'prepare' | 'confirm' | 'receipt' | null;
interface Session {
  api: Api; key: number; alive: boolean; revision: number; selected: string | null; snapshot: number; paused: boolean;
  preparation: ConversationDeletePreparation | null; consent: boolean; pending: PendingConversationDeletion | null;
  preparationUnknown: boolean; busy: Busy; notice: string; deletedAt: string | null;
  timer: ReturnType<typeof setTimeout> | null; deadline: number; ownsFence: boolean;
  discardedWarning: (() => void) | undefined; contentHidden: boolean; discardedContentHidden: ((hidden: boolean) => void) | undefined;
}
interface View {
  session: Session; preparation: ConversationDeletePreparation | null; consent: boolean;
  pending: PendingConversationDeletion | null; preparationUnknown: boolean; busy: Busy; notice: string; deletedAt: string | null;
}
const safeCode = (error: unknown): string => error instanceof ApiError && /^[A-Z][A-Z0-9_]{0,63}$(?![\s\S])/.test(error.code) ? error.code : 'RESPONSE_NOT_VERIFIED';
const blockerCopy: Record<DeletionBlocker, string> = {
  ACTIVE_WORK: '진행 중인 작업이 있어 삭제할 수 없습니다. 기존 작업 제어에서 상태를 먼저 해결해야 하며 여기서 작업을 자동 취소하지 않습니다.',
  UNKNOWN_WORK: '결과가 UNKNOWN인 작업이 있어 삭제할 수 없습니다. 기존 작업 제어에서 결과가 확정되어야 합니다.',
  PENDING_APPROVAL: '대기 중인 승인이 있어 삭제할 수 없습니다. 기존 승인 절차에서 상태가 확정되어야 합니다.',
  UNSUPPORTED_LEGACY_INFERENCE: '로컬 모델 요청의 검증된 비캐시 실행 기록이 없어 이 버전에서는 이 대화를 삭제할 수 없습니다. 과거 요청과 실행 전에 취소되어 기록이 없는 요청도 포함됩니다. 삭제하지 않았습니다.',
  INTEGRITY_UNVERIFIED: '저장된 삭제 범위의 무결성을 확인할 수 없어 삭제하지 않았습니다.',
};
const unknownCopy = 'UNKNOWN: 삭제 결과를 확인할 수 없습니다. 다시 보내지 마세요. 원래 삭제 요청의 영수증만 확인하세요. 연결 중단이나 화면 닫기는 서버의 삭제를 취소하지 않습니다.';

/** Owner-form only. Confirmation and recovery keys remain content-free and in memory. */
export function ConversationDeletionPanel({ api, sessionKey, selectedConversationId, snapshotKey = 0, onUnauthorized, onReauthenticate, writesPaused = false, selectionDisabled = false, onAcquirePending, onPendingChange, onContentHiddenChange, onDeleted, onOutcomeUnknown }: ConversationDeletionPanelProps) {
  const live = useRef({ api, sessionKey, selectedConversationId, snapshotKey, onUnauthorized, onReauthenticate, writesPaused, selectionDisabled, onAcquirePending, onPendingChange, onContentHiddenChange, onDeleted, onOutcomeUnknown });
  live.current = { api, sessionKey, selectedConversationId, snapshotKey, onUnauthorized, onReauthenticate, writesPaused, selectionDisabled, onAcquirePending, onPendingChange, onContentHiddenChange, onDeleted, onOutcomeUnknown };
  const session = useRef<Session | null>(null), reviewButton = useRef<HTMLButtonElement>(null), keepButton = useRef<HTMLButtonElement>(null);
  const [view, setView] = useState<View | null>(null);
  const same = (s: Session) => s.alive && session.current === s && live.current.api === s.api && live.current.sessionKey === s.key;
  const current = (s: Session, revision: number) => same(s) && s.revision === revision && s.selected === live.current.selectedConversationId && s.snapshot === live.current.snapshotKey;
  function publish(s: Session) { if (same(s)) setView({ session: s, preparation: s.preparation, consent: s.consent, pending: s.pending, preparationUnknown: s.preparationUnknown, busy: s.busy, notice: s.notice, deletedAt: s.deletedAt }); }
  function purgeReview(s: Session) { if (s.timer !== null) clearTimeout(s.timer); s.timer = null; s.preparation = null; s.consent = false; s.deadline = 0; }
  function release(s: Session) { if (!s.ownsFence || s.pending) return; s.ownsFence = false; if (same(s)) live.current.onPendingChange?.(false); }
  function hideContent(s: Session, hidden: boolean) { if (s.contentHidden === hidden) return; s.contentHidden = hidden; if (same(s)) live.current.onContentHiddenChange?.(hidden); }
  function acquire(s: Session): boolean { if (live.current.onAcquirePending?.() === false) return false; s.ownsFence = true; live.current.onPendingChange?.(true); return true; }
  function discard(s: Session) {
    if (!s.alive) return;
    if (s.pending) s.discardedWarning?.();
    else release(s);
    if (s.contentHidden) { s.contentHidden = false; s.discardedContentHidden?.(false); }
    s.alive = false; s.revision++; purgeReview(s); s.pending = null; s.preparationUnknown = false; s.busy = null; s.notice = ''; s.deletedAt = null;
  }
  function unauthorized(s: Session) { if (!same(s)) return; discard(s); setView(null); s.api.clear(); live.current.onUnauthorized(); }
  function message(error: unknown): string {
    if (error instanceof ApiError) {
      if (error.code === 'REAUTHENTICATION_REQUIRED') return '최근 본인 확인이 필요합니다. 재인증 후 같은 대화의 삭제 상태를 확인하고 새로 검토하세요. 이전 준비와 삭제 요청은 자동으로 반복되지 않습니다.';
      if (error.code === 'CONVERSATION_DELETION_UNSUPPORTED') return blockerCopy.UNSUPPORTED_LEGACY_INFERENCE;
      if (error.code === 'AUTHORITY_NOT_VALID' || error.code === 'STALE_SESSION') return '이전 세션의 준비나 요청은 사용할 수 없습니다. 새 세션에서 삭제 상태를 확인하고 필요한 경우 직접 새 검토를 시작하세요.';
      if (error.code === 'CONVERSATION_DELETED') return '이미 삭제된 대화입니다. 삭제 상태 확인으로 현재 상태를 확인하세요.';
    }
    return `${safeCode(error)}: 요청 결과를 검증하지 못했습니다. 자동 재시도하지 않습니다.`;
  }
  function blockedWrite(s: Session) { return !same(s) || s.selected !== live.current.selectedConversationId || s.snapshot !== live.current.snapshotKey || !!s.busy || !!s.pending || s.preparationUnknown || live.current.writesPaused || live.current.selectionDisabled; }
  function invalidate(s: Session, notice: string) {
    s.revision++; purgeReview(s); s.busy = null; s.deletedAt = null;
    if (s.pending) { s.notice = unknownCopy; }
    else { s.preparationUnknown = false; s.notice = notice; release(s); }
    publish(s);
  }
  useEffect(() => {
    const s: Session = { api, key: sessionKey, alive: true, revision: 0, selected: selectedConversationId, snapshot: snapshotKey, paused: writesPaused, preparation: null, consent: false, pending: null, preparationUnknown: false, busy: null, notice: '', deletedAt: null, timer: null, deadline: 0, ownsFence: false, discardedWarning: onOutcomeUnknown, contentHidden: false, discardedContentHidden: onContentHiddenChange };
    session.current = s; publish(s);
    const pagehide = () => { if (same(s)) { discard(s); setView(null); } };
    window.addEventListener('pagehide', pagehide);
    return () => { window.removeEventListener('pagehide', pagehide); discard(s); if (session.current === s) session.current = null; };
  }, [api, sessionKey]);
  useEffect(() => {
    const s = session.current; if (!s || !same(s)) return;
    if (s.selected !== selectedConversationId || s.snapshot !== snapshotKey || s.paused !== writesPaused) {
      s.selected = selectedConversationId; s.snapshot = snapshotKey; s.paused = writesPaused;
      invalidate(s, '선택, 대화 내용 또는 실행 상태가 바뀌어 이전 검토를 닫았습니다. 삭제하려면 새로 검토하세요.');
    }
  }, [selectedConversationId, snapshotKey, writesPaused]);
  useEffect(() => { if (view?.preparation) keepButton.current?.focus(); }, [view?.preparation?.id]);

  async function readState(prepare: boolean) {
    const s = session.current;
    if (!s || !same(s) || s.busy || s.pending || s.preparationUnknown || !valid_deletion_id(s.selected) || s.selected !== live.current.selectedConversationId || prepare && blockedWrite(s)) return;
    const target = s.selected, revision = ++s.revision;
    purgeReview(s); s.busy = 'state'; s.notice = ''; s.deletedAt = null; publish(s);
    let issuedPreparation = false;
    try {
      const raw = await s.api.request<unknown>(deletion_state_path(target));
      if (!current(s, revision)) return;
      const state = read_deletion_state(raw, target);
      if (state.status === 'DELETED') {
        s.deletedAt = state.deleted_at; s.notice = '이 대화의 저장 원문이 삭제된 상태임을 확인했습니다. 이전 요청 중 어느 요청이 처리되었는지를 뜻하지는 않습니다.';
        live.current.onDeleted(target); return;
      }
      if (state.eligibility.status === 'BLOCKED') { s.notice = state.eligibility.blockers.map(code => blockerCopy[code]).join(' '); return; }
      if (!prepare) { s.notice = '현재 삭제되지 않은 대화입니다. 이 조회는 삭제를 준비하거나 실행하지 않습니다. 삭제하려면 새 검토와 명시적 확인이 필요합니다.'; return; }
      if (live.current.writesPaused || live.current.selectionDisabled || !acquire(s)) { s.notice = '다른 작업 또는 실행 중지로 삭제 준비를 시작하지 않았습니다.'; return; }
      s.busy = 'prepare'; publish(s);
      const started = performance.now(); issuedPreparation = true;
      const preparationRaw = await s.api.request<unknown>(delete_preparation_path(target), delete_prepare_body(state as EligibleDeletionState), crypto.randomUUID());
      if (!current(s, revision)) return;
      const preparation = read_delete_preparation(preparationRaw, state as EligibleDeletionState);
      // Counting from request dispatch is conservative under network delay and independent of wall-clock skew.
      s.deadline = started + 300000;
      if (performance.now() >= s.deadline) { s.notice = '삭제 준비가 만료되었습니다. 대화를 새로 검토하세요.'; return; }
      s.preparation = preparation;
      s.timer = setTimeout(() => { if (!current(s, revision) || s.pending) return; purgeReview(s); s.notice = '삭제 준비가 만료되었습니다. 서버 시간이 최종 기준이며 새 검토가 필요합니다.'; publish(s); }, Math.max(0, s.deadline - performance.now()));
    } catch (error) {
      if (!same(s)) return;
      if (error instanceof ApiError && error.status === 401) { unauthorized(s); return; }
      if (!current(s, revision)) return;
      purgeReview(s);
      if (issuedPreparation && (!(error instanceof ApiError) || error.uncertain || error.code === 'INVALID_DELETION_RESPONSE')) {
        s.preparationUnknown = true; s.notice = '삭제 준비 응답을 확인하지 못했습니다. 삭제 확인 요청은 보내지 않았습니다. 준비만 포기한 뒤 직접 새로 검토할 수 있습니다.';
      } else s.notice = message(error);
    } finally { if (current(s, revision)) { s.busy = null; release(s); publish(s); } }
  }
  function committed(s: Session, target: string, deletedAt: string) {
    purgeReview(s); s.pending = null; s.preparationUnknown = false; s.deletedAt = deletedAt;
    s.notice = '이 대화의 저장 원문 삭제를 확인했습니다. 별도로 저장한 기억과 안내한 보존 기록은 남습니다.';
    release(s); hideContent(s, false); publish(s); live.current.onDeleted(target);
  }
  async function confirm() {
    const s = session.current;
    if (!s || blockedWrite(s) || !s.preparation || !s.consent || s.preparation.conversation.id !== s.selected) return;
    if (performance.now() >= s.deadline) { purgeReview(s); s.notice = '삭제 준비가 만료되었습니다. 대화를 새로 검토하세요.'; publish(s); return; }
    if (!acquire(s)) return;
    const body = delete_confirm_body(s.preparation), revision = ++s.revision;
    const pending: PendingConversationDeletion = { stage: 'CONFIRM', key: crypto.randomUUID(), conversation_id: s.preparation.conversation.id, preparation_id: s.preparation.id, expected_version: s.preparation.expected_version, prepared_at: s.preparation.prepared_at, expires_at: s.preparation.expires_at };
    s.pending = pending; s.busy = 'confirm'; s.notice = ''; purgeReview(s); hideContent(s, true); publish(s);
    try {
      const raw = await s.api.request<unknown>(delete_confirm_path(pending.preparation_id), body, pending.key);
      if (!current(s, revision)) return;
      const outcome = read_deletion_outcome(raw, pending);
      if (outcome.status !== 'COMMITTED') throw new ApiError('UNKNOWN', true);
      committed(s, pending.conversation_id, outcome.receipt.deleted_at);
    } catch (error) {
      if (!same(s)) return;
      if (error instanceof ApiError && error.status === 401) { unauthorized(s); return; }
      if (!current(s, revision)) return;
      if (!(error instanceof ApiError) || error.uncertain || error.code === 'INVALID_DELETION_RESPONSE') s.notice = unknownCopy;
      else { s.pending = null; s.notice = message(error); release(s); hideContent(s, false); }
    } finally { if (current(s, revision)) { s.busy = null; publish(s); } }
  }
  async function reconcile() {
    const s = session.current; if (!s || !same(s) || s.busy || !s.pending) return;
    const pending = s.pending, revision = ++s.revision;
    s.busy = 'receipt'; s.notice = unknownCopy; publish(s);
    try {
      const raw = await s.api.request<unknown>(deletion_receipt_path(pending.key));
      if (!current(s, revision)) return;
      const outcome = read_deletion_outcome(raw, pending);
      if (outcome.status === 'COMMITTED') committed(s, pending.conversation_id, outcome.receipt.deleted_at);
      else s.notice = 'NOT_OBSERVED: 처리된 기록이 아직 보이지 않습니다. 실패나 미실행의 증거가 아니므로 UNKNOWN과 쓰기 차단을 유지합니다. 삭제를 다시 보내지 마세요.';
    } catch (error) {
      if (!same(s)) return;
      if (error instanceof ApiError && error.status === 401) { unauthorized(s); return; }
      if (current(s, revision)) s.notice = `${message(error)} ${unknownCopy}`;
    } finally { if (current(s, revision)) { s.busy = null; publish(s); } }
  }
  function keep() {
    const s = session.current; if (!s || !same(s) || s.pending || s.busy === 'confirm' || s.busy === 'receipt') return;
    invalidate(s, '삭제 확인 요청을 보내지 않고 검토를 닫았습니다.'); reviewButton.current?.focus();
  }
  const visible = view && same(view.session) && view.session.selected === selectedConversationId && view.session.snapshot === snapshotKey && view.session.paused === writesPaused ? view : null;
  const busy = !!visible?.busy, pending = !!visible?.pending, preparation = visible?.preparation;
  const disabled = !visible || busy || pending || !!visible?.preparationUnknown || writesPaused || selectionDisabled || !valid_deletion_id(selectedConversationId);
  return <section className="panel conversation-deletion" aria-label="대화 원문 삭제" aria-busy={busy} onKeyDown={event => { if (event.key === 'Escape' && !pending) { event.preventDefault(); keep(); } }}>
    <h2>대화 원문 삭제</h2>
    <p>선택한 대화 하나의 저장 원문을 영구 삭제합니다. 앱에서 되돌릴 수 없습니다. 검토만으로 삭제되지 않으며 별도의 체크와 삭제 버튼이 필요합니다.</p>
    <p>진행 중인 작업, UNKNOWN 작업, 대기 승인 또는 검증된 비캐시 실행 기록이 없는 로컬 모델 요청이 있으면 삭제할 수 없습니다. 작업 취소나 과거 기록 변환을 자동으로 하지 않습니다.</p>
    <p>표시 시간은 한국시간(KST), Asia/Seoul입니다. 기계용 시간은 UTC를 유지합니다.</p>
    <div className="actions">
      <button ref={reviewButton} type="button" className="secondary" disabled={disabled} onClick={() => void readState(true)}>선택 대화 삭제 검토</button>
      <button type="button" className="secondary" disabled={!visible || busy || pending || !!visible?.preparationUnknown || !valid_deletion_id(selectedConversationId)} onClick={() => void readState(false)}>삭제 상태만 확인</button>
      <button type="button" className="secondary" disabled={!visible || busy || pending || selectionDisabled} onClick={() => { const s = session.current; if (!s || !same(s) || s.busy || s.pending || live.current.selectionDisabled) return; invalidate(s, '본인 확인 후 삭제 상태를 다시 확인하고 직접 새로 검토하세요.'); live.current.onReauthenticate(); }}>삭제 본인 확인</button>
    </div>
    {visible?.notice && <p role="alert">{visible.notice}</p>}
    {busy && <p role="status">{visible?.busy === 'confirm' ? '삭제 확인 요청 결과를 기다리는 중입니다. 닫거나 연결을 끊어도 삭제가 취소되지 않습니다.' : visible?.busy === 'prepare' ? '삭제하지 않고 검토할 범위를 준비하는 중…' : '본문 없는 삭제 상태를 확인하는 중…'}</p>}
    {visible?.preparationUnknown && <button type="button" className="secondary" onClick={keep}>미확인 준비 포기</button>}
    {pending && <section aria-label="삭제 결과 불확실"><p>원래 대화 ID: {visible?.pending?.conversation_id}. 결과 확인까지 다른 쓰기와 선택 변경을 멈춥니다.</p><button type="button" className="secondary" disabled={busy} onClick={() => void reconcile()}>원래 삭제 요청 결과 확인</button></section>}
    {visible?.deletedAt && <p>삭제 시간: <time dateTime={visible.deletedAt}>{deletion_time(visible.deletedAt)}</time></p>}
    {preparation && <section role="dialog" aria-modal="false" aria-labelledby="conversation-delete-review-title">
      <h3 id="conversation-delete-review-title">이 대화의 영구 삭제 검토</h3>
      <p>대화 제목: {preparation.conversation.title}</p><p>대화 ID: {preparation.conversation.id}</p><p>프로젝트 ID: {preparation.conversation.project_id ?? '없음'}</p><p>대화 내용 버전: {preparation.expected_version}</p>
      <p>대화 생성: <time dateTime={preparation.conversation.created_at}>{deletion_time(preparation.conversation.created_at)}</time></p>
      <p>준비: <time dateTime={preparation.prepared_at}>{deletion_time(preparation.prepared_at)}</time></p><p>만료: <time dateTime={preparation.expires_at}>{deletion_time(preparation.expires_at)}</time></p>
      <p>메시지 {preparation.source_counts.messages}개 · 실행 {preparation.source_counts.runs}개 · 응답 복사본 {preparation.source_counts.run_results}개 · 작업 내용 복사본 {preparation.source_counts.action_payloads}개 · 추론 문맥 {preparation.source_counts.inference_contexts}개</p>
      <p>My_Jarvis에 저장된 이 대화의 제목, 메시지, 응답 복사본과 작업 내용 복사본을 영구 삭제합니다. 앱에서 되돌릴 수 없습니다.</p>
      <p>별도로 저장한 기억, 작업, 초안, 알림, 프로필과 프로젝트는 남습니다. 실행 이력, 사용량 합계와 보호된 요청 검증 기록도 남으며 대화 본문은 포함하지 않습니다. 검증 기록에는 무결성을 위한 내용 유래 키 기반 바인딩이 남습니다.</p>
      <p>저장한 파일, 복사한 텍스트, 다른 기기에 이미 표시된 화면, 이미 생성되었거나 전송 중인 응답을 회수할 수 없습니다. 기존 백업과 데이터베이스 저장 잔여물은 이 기능의 범위 밖이며 제거가 검증되지 않았습니다. 이 버전은 과거 백업의 검증된 삭제나 안전한 복원 절차를 제공하지 않습니다.</p>
      <label><input type="checkbox" checked={visible.consent} disabled={disabled} onChange={event => { const s = session.current; if (!s || blockedWrite(s) || !s.preparation) return; s.consent = event.target.checked; publish(s); }}/>이 제한을 이해했으며 정확히 이 대화의 영구 삭제를 확인합니다</label>
      <div className="actions"><button ref={keepButton} type="button" className="secondary" onClick={keep}>대화 유지</button><button type="button" className="danger" disabled={disabled || !visible.consent} onClick={() => void confirm()}>이 대화 영구 삭제</button></div>
    </section>}
  </section>;
}
