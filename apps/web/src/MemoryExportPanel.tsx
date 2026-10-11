import { useEffect, useRef, useState } from 'react';
import { Api, ApiError } from './api';
import { EXPORT_FILENAME, export_job_path, export_receipt_path, export_request, export_time, parse_export_bytes, read_export_job, read_export_receipt, valid_export_scope, type ExportJob, type ExportScope } from './memory-export';

export interface MemoryExportPanelProps {
  api: Api; sessionKey: number; selectedProjectId: string | null;
  onUnauthorized: () => void; onReauthenticate: () => void;
  writesPaused?: boolean; selectionDisabled?: boolean;
  onAcquirePending?: () => boolean; onPendingChange?: (pending: boolean) => void;
  /** A boundary discarded unresolved private recovery metadata; this carries no identifying data. */
  onOutcomeUnknown?: () => void;
}
interface Attempt extends ExportScope { key: string }
interface Session {
  api: Api; key: number; alive: boolean; authorized: boolean; revision: number; projectSelection: string | null;
  scope: ExportScope['scope']; reviewed: boolean; busy: 'create' | 'status' | 'reconcile' | 'download' | null;
  pending: Attempt | null; creating: Attempt | null; job: ExportJob | null; current: boolean; notice: string;
  url: string | null; timer: ReturnType<typeof setTimeout> | null;
}
interface View { session: Session; scope: ExportScope['scope']; reviewed: boolean; busy: Session['busy']; pending: Attempt | null; job: ExportJob | null; current: boolean; notice: string }
const safeCode = (error: unknown): string => error instanceof ApiError && /^[A-Z][A-Z0-9_]{0,63}$/.test(error.code) ? error.code : 'RESPONSE_NOT_VERIFIED';

/** Only explicit actions issue requests. No file bytes, credentials or recovery metadata enter browser persistence. */
export function MemoryExportPanel({ api, sessionKey, selectedProjectId, onUnauthorized, onReauthenticate, writesPaused = false, selectionDisabled = false, onAcquirePending, onPendingChange, onOutcomeUnknown }: MemoryExportPanelProps) {
  const live = useRef({ api, sessionKey, selectedProjectId, onUnauthorized, onReauthenticate, writesPaused, selectionDisabled, onAcquirePending, onPendingChange, onOutcomeUnknown });
  live.current = { api, sessionKey, selectedProjectId, onUnauthorized, onReauthenticate, writesPaused, selectionDisabled, onAcquirePending, onPendingChange, onOutcomeUnknown };
  const session = useRef<Session | null>(null);
  const [view, setView] = useState<View | null>(null);
  const same = (s: Session) => s.alive && s.authorized && session.current === s && live.current.api === s.api && live.current.sessionKey === s.key;
  const active = (s: Session, revision: number) => same(s) && s.revision === revision && s.projectSelection === live.current.selectedProjectId;
  const binding = (s: Session): ExportScope => ({ scope: s.scope, project_id: s.scope === 'PROJECT' ? s.projectSelection : null });
  function publish(s: Session) {
    if (same(s)) setView({ session: s, scope: s.scope, reviewed: s.reviewed, busy: s.busy, pending: s.pending, job: s.job, current: s.current, notice: s.notice });
  }
  function revoke(s: Session) {
    if (s.timer !== null) { clearTimeout(s.timer); s.timer = null; }
    if (s.url !== null) { URL.revokeObjectURL(s.url); s.url = null; }
  }
  function purge(s: Session) { revoke(s); s.job = null; s.current = false; s.reviewed = false; }
  function reportPending(s: Session) { live.current.onPendingChange?.(s.creating !== null || s.pending !== null); }
  function discard(s: Session) {
    if (s.creating || s.pending) live.current.onOutcomeUnknown?.();
    s.alive = false; s.authorized = false; s.revision++; purge(s); s.pending = null; s.creating = null; s.busy = null; s.notice = '';
  }
  function unauthorized(s: Session) {
    if (!same(s)) return;
    // The parent inspects its synchronous pending fence before clearing the session.
    discard(s); setView(null); s.api.clear(); live.current.onUnauthorized();
  }
  function failure(error: unknown, s: Session, revision: number): boolean {
    if (!same(s)) return false;
    if (error instanceof ApiError && error.status === 401) { unauthorized(s); return false; }
    if (!active(s, revision)) return false;
    if (error instanceof ApiError && (error.code === 'AUTHORITY_NOT_VALID' || error.code === 'SESSION_REVOKED' || error.code === 'STALE_SESSION')) {
      purge(s); s.notice = '이전 세션의 내보내기 작업은 사용할 수 없습니다. 재인증 후 범위를 다시 검토하고 새 작업을 직접 요청하세요. 이전 요청은 반복되지 않습니다.';
    } else if (error instanceof ApiError && error.code === 'REAUTHENTICATION_REQUIRED') {
      purge(s); s.notice = '최근 본인 확인이 필요합니다. 재인증은 세션을 바꾸므로 이전 작업을 사용할 수 없습니다. 확인 후 새 작업을 직접 요청하세요.';
    } else if (error instanceof ApiError && error.code === 'EXPORT_EXPIRED') {
      purge(s); s.notice = '내보내기 작업이 만료되었습니다. 범위를 다시 검토하고 새 작업을 직접 요청하세요.';
    } else {
      s.notice = `${safeCode(error)}: 응답을 확인하지 못했습니다. 파일이 저장되었다고 확인할 수 없습니다. 자동 재시도는 없습니다.`;
    }
    publish(s); return true;
  }
  useEffect(() => {
    const s: Session = { api, key: sessionKey, alive: true, authorized: true, revision: 0, projectSelection: selectedProjectId, scope: 'GLOBAL', reviewed: false, busy: null, pending: null, creating: null, job: null, current: false, notice: '', url: null, timer: null };
    session.current = s; publish(s);
    const pagehide = () => { if (session.current === s) { discard(s); setView(null); live.current.onPendingChange?.(false); } };
    window.addEventListener('pagehide', pagehide);
    return () => { window.removeEventListener('pagehide', pagehide); discard(s); if (session.current === s) { session.current = null; live.current.onPendingChange?.(false); } };
  }, [api, sessionKey]);
  useEffect(() => {
    const s = session.current; if (!s || !same(s) || s.projectSelection === selectedProjectId) return;
    s.revision++; if (s.creating) { s.pending = s.creating; s.creating = null; }
    s.projectSelection = selectedProjectId; s.busy = null; purge(s);
    s.notice = s.pending ? '선택 범위가 바뀌었습니다. 이전 생성 요청의 결과는 UNKNOWN이며 원래 요청의 메타데이터만 확인할 수 있습니다. 다른 범위로 다시 요청하지 않습니다.' : '';
    reportPending(s); publish(s);
  }, [selectedProjectId]);
  async function create() {
    const s = session.current;
    if (!s || !same(s) || s.projectSelection !== live.current.selectedProjectId || s.busy || s.pending || !s.reviewed || live.current.writesPaused || live.current.selectionDisabled || !valid_export_scope(binding(s))) return;
    if (live.current.onAcquirePending?.() === false) return;
    const attempt: Attempt = { ...binding(s), key: crypto.randomUUID() }, revision = ++s.revision;
    purge(s); s.creating = attempt; s.busy = 'create'; s.notice = ''; reportPending(s); publish(s);
    try {
      const raw = await s.api.request<unknown>('/exports', export_request(attempt), attempt.key);
      if (!active(s, revision)) return;
      let job: ExportJob;
      try { job = read_export_job(raw, attempt); } catch { throw new ApiError('UNKNOWN', true); }
      s.job = job; s.current = true; s.notice = '범위 작업의 생성만 확인했습니다. 아래 다운로드 버튼을 직접 눌러야 현재 기억을 조회합니다. 저장된 파일은 아직 없습니다.';
    } catch (error) {
      if (!same(s)) return;
      if (error instanceof ApiError && error.status === 401) { unauthorized(s); return; }
      if (!active(s, revision)) return;
      if (!(error instanceof ApiError) || error.uncertain) {
        s.pending = attempt; purge(s); s.notice = 'UNKNOWN: 생성 요청이 처리되었을 수 있습니다. 원래 요청의 메타데이터를 확인할 때까지 새 생성과 범위 변경을 멈춥니다. 요청을 다시 보내지 않습니다.';
      } else failure(error, s, revision);
    } finally {
      if (active(s, revision)) { s.creating = null; s.busy = null; reportPending(s); publish(s); }
    }
  }
  async function refresh(reconcile: boolean) {
    const s = session.current;
    if (!s || !same(s) || s.projectSelection !== live.current.selectedProjectId || s.busy || (reconcile ? !s.pending : !s.job)) return;
    const attempt = s.pending, previous = s.job, revision = ++s.revision;
    s.busy = reconcile ? 'reconcile' : 'status'; s.current = false; s.notice = ''; publish(s);
    try {
      if (reconcile && attempt) {
        const raw = await s.api.request<unknown>(export_receipt_path(attempt.key));
        if (!active(s, revision)) return;
        const receipt = read_export_receipt(raw, attempt);
        if (receipt.status === 'NOT_OBSERVED') { s.notice = 'NOT_OBSERVED: 처리된 기록이 아직 보이지 않습니다. 실패했다는 증거가 아니므로 UNKNOWN과 생성 차단을 유지합니다. 요청은 다시 보내지 않습니다.'; }
        else {
          s.pending = null;
          const selected = binding(s);
          if (receipt.job.scope === selected.scope && receipt.job.project_id === selected.project_id) { s.job = receipt.job; s.current = true; }
          else { purge(s); }
          s.notice = '원래 생성 요청의 처리를 메타데이터로 확인했습니다. 다운로드는 시작하지 않았습니다. 범위가 바뀌었다면 새 범위를 별도로 검토하세요.';
        }
      } else if (previous) {
        const raw = await s.api.request<unknown>(export_job_path(previous.id));
        if (!active(s, revision)) return;
        s.job = read_export_job(raw, previous, previous); s.current = true;
        s.notice = '범위 작업의 상태만 확인했습니다. 파일 존재나 저장 완료를 확인한 결과가 아닙니다.';
      }
    } catch (error) { failure(error, s, revision); }
    finally { if (active(s, revision)) { s.busy = null; reportPending(s); publish(s); } }
  }
  async function download() {
    const s = session.current;
    if (!s || !same(s) || s.projectSelection !== live.current.selectedProjectId || s.busy || s.pending || !s.job || !s.current || s.job.status !== 'AVAILABLE') return;
    const job = s.job, selected = binding(s);
    if (job.scope !== selected.scope || job.project_id !== selected.project_id) return;
    const revision = ++s.revision; revoke(s); s.busy = 'download'; s.notice = ''; publish(s);
    let bytes: Uint8Array | null = null;
    try {
      bytes = await s.api.download_export(job.id);
      if (!active(s, revision)) return;
      parse_export_bytes(bytes, job);
      if (!active(s, revision)) return;
      const blob = new Blob([new Uint8Array(bytes).buffer], { type: 'application/json;charset=utf-8' });
      s.url = URL.createObjectURL(blob);
      const anchor = document.createElement('a'); anchor.href = s.url; anchor.download = EXPORT_FILENAME; anchor.hidden = true;
      try { document.body.append(anchor); anchor.click(); }
      catch { revoke(s); throw new ApiError('DOWNLOAD_NOT_VERIFIED'); }
      finally { anchor.remove(); }
      // Release the temporary URL after the browser has received the explicit activation.
      s.timer = setTimeout(() => revoke(s), 0);
      s.notice = '검증한 현재 기억으로 브라우저 다운로드를 시작했습니다. 파일이 실제로 저장되었는지는 확인할 수 없습니다. 브라우저의 다운로드 목록을 확인하세요.';
    } catch (error) {
      revoke(s); failure(error, s, revision);
    } finally {
      bytes?.fill(0);
      if (active(s, revision)) { s.busy = null; publish(s); }
    }
  }
  const visible = view && same(view.session) && view.session.projectSelection === selectedProjectId ? view : null;
  const busy = visible?.busy !== null && visible?.busy !== undefined, unresolved = !!visible?.pending;
  const blocked = !visible || busy || unresolved || writesPaused || selectionDisabled;
  const currentScope: ExportScope = { scope: visible?.scope ?? 'GLOBAL', project_id: visible?.scope === 'PROJECT' ? selectedProjectId : null };
  const job = visible?.job;
  return <section className="panel memory-export" aria-label="기억 내보내기" aria-busy={busy}>
    <h2>기억 내보내기</h2>
    <p>계정 소유자가 직접 저장한 현재 기억을 JSON 파일로 내보냅니다. 전역 기억 또는 선택한 소유 프로젝트 하나만 포함하며 두 범위를 합치지 않습니다.</p>
    <p>애플리케이션 비밀정보 열, 자격증명, 삭제된 기억과 과거 수정 기록은 제외됩니다. 사용자가 기억 본문이나 변경 이유에 쓴 개인정보·비밀 문구는 수정 없이 포함될 수 있습니다. 저장한 파일의 보관과 공유에 주의하세요.</p>
    <p>최대 1,000개, UTF-8 8 MiB입니다. 초과 시 부분 파일을 만들지 않습니다. 서버는 작업에 본문 복사본을 저장하지 않고 다운로드 요청 때 현재 내용을 다시 조회합니다. 이미 생성·전송 중인 다운로드나 저장한 파일은 이후 기억 삭제로 회수하거나 지울 수 없습니다.</p>
    <p>새 작업과 다운로드에는 최근 본인 확인이 필요합니다. 재인증은 세션을 바꾸므로 이전 작업은 무효가 되며 새 범위를 검토하고 직접 새 작업을 만들어야 합니다. 생성·조회·재인증은 다운로드를 자동으로 시작하지 않습니다.</p>
    <p>표시 시간: 한국시간(KST), Asia/Seoul. JSON과 기계용 시간은 정확한 UTC입니다. 브라우저의 실제 파일 저장과 모바일 동작은 검증되지 않았습니다.</p>
    <label>내보내기 범위<select value={visible?.scope ?? 'GLOBAL'} disabled={blocked} onChange={event => { const s = session.current; if (!s || !same(s) || s.busy || s.pending || live.current.writesPaused || live.current.selectionDisabled) return; const next = event.target.value; if (next !== 'GLOBAL' && next !== 'PROJECT') return; s.revision++; purge(s); s.scope = next; s.notice = ''; publish(s); }}><option value="GLOBAL">전역 기억만</option><option value="PROJECT" disabled={!selectedProjectId}>선택한 프로젝트 기억만</option></select></label>
    {visible?.scope === 'PROJECT' && <p>선택한 프로젝트: {selectedProjectId ?? '프로젝트를 먼저 선택하세요'}</p>}
    <label><input type="checkbox" checked={visible?.reviewed ?? false} disabled={blocked || !valid_export_scope(currentScope)} onChange={event => { const s = session.current; if (!s || !same(s) || s.busy || s.pending || live.current.writesPaused || live.current.selectionDisabled) return; s.reviewed = event.target.checked; publish(s); }}/>선택한 범위와 개인정보 포함 가능성을 확인했습니다</label>
    <div className="actions"><button type="button" className="secondary" disabled={blocked} onClick={() => { const s = session.current; if (!s || !same(s) || s.busy || s.pending || live.current.writesPaused || live.current.selectionDisabled) return; s.revision++; purge(s); publish(s); live.current.onReauthenticate(); }}>내보내기 본인 확인</button><button type="button" disabled={blocked || !visible?.reviewed || !valid_export_scope(currentScope)} onClick={() => void create()}>선택 범위 내보내기 작업 생성</button></div>
    {visible?.notice && <p role="alert">{visible.notice}</p>}
    {busy && <p role="status">{visible?.busy === 'create' ? '생성 결과를 기다리는 중…' : visible?.busy === 'download' ? '현재 기억 파일을 읽고 검증하는 중…' : '본문 없는 메타데이터를 확인하는 중…'}</p>}
    {unresolved && <section aria-label="내보내기 생성 결과 불확실"><p>생성 결과: UNKNOWN. 원래 범위: {visible?.pending?.scope}{visible?.pending?.project_id ? ` · ${visible.pending.project_id}` : ''}</p><button type="button" className="secondary" disabled={busy} onClick={() => void refresh(true)}>원래 생성 요청 결과 확인</button></section>}
    {job && <section aria-label="내보내기 작업 메타데이터"><h3>범위 작업 메타데이터</h3><p>{visible?.current ? '확인된 상태' : '이전 상태 · 재검증 안 됨'}: {job.status}</p><p>범위: {job.scope}{job.project_id ? ` · ${job.project_id}` : ''}</p><p>생성: <time dateTime={job.created_at}>{export_time(job.created_at)}</time></p><p>만료: <time dateTime={job.expires_at}>{export_time(job.expires_at)}</time></p><p>상태 확인: <time dateTime={job.checked_at}>{export_time(job.checked_at)}</time></p><p>AVAILABLE은 범위 작업의 상태이며 파일 존재, 내용 고정 또는 저장 완료의 증거가 아닙니다. 매 다운로드는 그 시점의 현재 기억을 새로 조회합니다.</p><div className="actions"><button type="button" className="secondary" disabled={busy || unresolved} onClick={() => void refresh(false)}>작업 상태만 새로고침</button><button type="button" disabled={busy || unresolved || !visible?.current || job.status !== 'AVAILABLE'} onClick={() => void download()}>현재 기억 JSON 다운로드</button></div></section>}
  </section>;
}
