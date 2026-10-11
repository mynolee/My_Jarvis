import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Api, ApiError } from './api';
import { execution_body, execution_path, execution_time, read_execution_result, read_execution_state, type ExecutionAction, type ExecutionState } from './execution-controls';

export interface ExecutionControlsPanelProps {
  api: Api;
  sessionKey: number;
  refreshKey?: number;
  onUnauthorized: () => void;
  onStateChange: (state: ExecutionState | null) => void;
  /** This separate safety boundary is deliberately independent of ordinary mutation locks. */
  onBoundaryStart: () => boolean | void;
  onBoundaryEnd: () => void;
  unresolvedWork?: boolean;
  identityPending?: boolean;
  identityUnknown?: boolean;
  onIdentityUnknown?: () => void;
  outcomeUnknown?: boolean;
  onOutcomeUnknown?: () => void;
}
interface Session {
  api: Api; key: number; alive: boolean; authorized: boolean; revision: number; boundary: number;
  state: ExecutionState | null; current: boolean; busy: 'read' | 'reauth' | 'pause' | 'resume' | null;
  reviewed: boolean; showIdentity: boolean; identityUnknown: boolean; outcomeUnknown: boolean; notice: string;
}
interface View { session: Session; state: ExecutionState | null; current: boolean; busy: Session['busy']; reviewed: boolean; showIdentity: boolean; identityUnknown: boolean; outcomeUnknown: boolean; notice: string }

/** Read-only reconciliation never attributes a previous write or replays it. */
export function ExecutionControlsPanel({ api, sessionKey, refreshKey = 0, onUnauthorized, onStateChange, onBoundaryStart, onBoundaryEnd, unresolvedWork = false, identityPending = false, identityUnknown = false, onIdentityUnknown, outcomeUnknown = false, onOutcomeUnknown }: ExecutionControlsPanelProps) {
  const live = useRef({ api, sessionKey, onUnauthorized, onStateChange, onBoundaryStart, onBoundaryEnd, identityPending, identityUnknown, onIdentityUnknown, onOutcomeUnknown });
  live.current = { api, sessionKey, onUnauthorized, onStateChange, onBoundaryStart, onBoundaryEnd, identityPending, identityUnknown, onIdentityUnknown, onOutcomeUnknown };
  const session = useRef<Session | null>(null), lastRefresh = useRef(refreshKey);
  const [view, setView] = useState<View | null>(null);
  const same = (s: Session) => s.alive && s.authorized && session.current === s && live.current.api === s.api && live.current.sessionKey === s.key;
  function publish(s: Session) { if (same(s)) setView({ session: s, state: s.state, current: s.current, busy: s.busy, reviewed: s.reviewed, showIdentity: s.showIdentity, identityUnknown: s.identityUnknown, outcomeUnknown: s.outcomeUnknown, notice: s.notice }); }
  function unauthorized(s: Session) {
    if (!same(s)) return;
    s.authorized = false; s.revision++; s.state = null; s.busy = null; s.reviewed = false;
    setView(null); s.api.clear(); live.current.onUnauthorized();
  }
  function isUnauthorized(error: unknown) { return error instanceof ApiError && error.status === 401; }
  function safeCode(error: unknown): string { return error instanceof ApiError && /^[A-Z][A-Z0-9_]{0,63}$/.test(error.code) ? error.code : 'RESPONSE_NOT_VERIFIED'; }
  async function read(s: Session) {
    if (!same(s) || live.current.identityPending || s.busy !== null && s.busy !== 'read') return;
    const revision = ++s.revision, boundary = s.boundary;
    s.busy = 'read'; s.current = false; s.reviewed = false; s.notice = '';
    live.current.onStateChange(null); publish(s);
    try {
      const raw = await s.api.request<unknown>('/execution');
      if (!same(s) || revision !== s.revision) return;
      s.state = read_execution_state(raw, s.state); s.current = true; s.busy = null;
      live.current.onStateChange(s.identityUnknown || live.current.identityUnknown ? null : s.state); publish(s);
    } catch (error) {
      if (!same(s) || boundary !== s.boundary) return;
      if (isUnauthorized(error)) { unauthorized(s); return; }
      if (revision !== s.revision) return;
      s.busy = null; s.current = false; s.notice = 'Account execution state could not be confirmed. New work stays blocked. Refresh performs only a read.';
      live.current.onStateChange(null); publish(s);
    }
  }
  useEffect(() => {
    const s: Session = { api, key: sessionKey, alive: true, authorized: true, revision: 0, boundary: 0, state: null, current: false, busy: null, reviewed: false, showIdentity: false, identityUnknown: false, outcomeUnknown: false, notice: '' };
    session.current = s; lastRefresh.current = refreshKey; setView(null); void read(s);
    return () => { s.alive = false; s.authorized = false; s.revision++; s.state = null; s.busy = null; if (session.current === s) session.current = null; };
  }, [api, sessionKey]);
  useEffect(() => { if (lastRefresh.current === refreshKey) return; lastRefresh.current = refreshKey; if (session.current) void read(session.current); }, [refreshKey]);
  useEffect(() => { const s = session.current; if (!identityPending && s && same(s) && !s.current && s.busy === null) void read(s); }, [identityPending]);
  async function change(action: ExecutionAction) {
    const s = session.current;
    if (!s || !same(s) || live.current.identityPending || live.current.identityUnknown || s.busy || !s.current || !s.state || !s.reviewed || s.identityUnknown || s.state.version === Number.MAX_SAFE_INTEGER || s.state.execution_paused === (action === 'pause')) return;
    if (live.current.onBoundaryStart() === false) return;
    const snapshot = s.state, revision = ++s.revision; s.boundary++;
    s.busy = action; s.current = false; s.reviewed = false; s.showIdentity = false; s.notice = '';
    live.current.onStateChange(null); publish(s);
    try {
      const raw = await s.api.request<unknown>(execution_path(action), execution_body(snapshot.version));
      if (!same(s) || revision !== s.revision) return;
      let confirmed: ExecutionState;
      try { confirmed = read_execution_result(raw, action, snapshot); } catch { throw new ApiError('UNKNOWN', true); }
      s.state = confirmed; s.current = true;
      s.notice = action === 'pause' ? 'The server confirmed account execution paused. In-flight outcomes still need separate inspection.' : 'The server confirmed new work may be requested. Previous permissions and cancelled work are not restored.';
      live.current.onStateChange(confirmed);
    } catch (error) {
      if (!same(s)) return;
      if (isUnauthorized(error)) { unauthorized(s); return; }
      if (revision !== s.revision) return;
      if (!(error instanceof ApiError) || error.uncertain) {
        s.outcomeUnknown = true; live.current.onOutcomeUnknown?.();
        s.notice = 'UNKNOWN: the execution-control request may have committed. It will not be repeated. Refresh reads current account state only.';
      } else {
        s.notice = error.code === 'PRECONDITION_CHANGED' ? 'Account version changed. Refresh and review the latest account state before any new action.' : error.code === 'REAUTHENTICATION_REQUIRED' ? 'A recent identity confirmation is required. Confirm explicitly, then refresh and review the account state. No action is repeated.' : safeCode(error);
        if (error.code === 'REAUTHENTICATION_REQUIRED') s.showIdentity = true;
      }
    } finally {
      if (same(s) && revision === s.revision) { s.busy = null; live.current.onBoundaryEnd(); publish(s); }
    }
  }
  async function reauthenticate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget, s = session.current;
    if (!s || !same(s) || live.current.identityPending || live.current.identityUnknown || s.busy || s.identityUnknown || !s.showIdentity) { form.reset(); return; }
    const password = String(new FormData(form).get('password') ?? ''); form.reset();
    if (!password || password.length > 128) return;
    if (live.current.onBoundaryStart() === false) return;
    const revision = ++s.revision; s.boundary++;
    s.busy = 'reauth'; s.current = false; s.reviewed = false; s.showIdentity = false; s.notice = '';
    live.current.onStateChange(null); publish(s);
    try {
      const raw = await s.api.request<unknown>('/auth/reauthenticate', { password });
      if (!same(s) || revision !== s.revision) return;
      if (!raw || typeof raw !== 'object' || Array.isArray(raw) || Object.keys(raw).length !== 2 || !Object.hasOwn(raw, 'csrf_token') || !Object.hasOwn(raw, 'authenticated') || (raw as { authenticated?: unknown }).authenticated !== true || typeof (raw as { csrf_token?: unknown }).csrf_token !== 'string' || !(raw as { csrf_token: string }).csrf_token || (raw as { csrf_token: string }).csrf_token.length > 4096) throw new ApiError('UNKNOWN', true);
      s.api.set_csrf((raw as { csrf_token: string }).csrf_token);
      s.notice = 'Identity confirmed. Refresh account execution state, review the impact, then choose a separate action. Nothing was paused or resumed automatically.';
    } catch (error) {
      if (!same(s)) return;
      if (isUnauthorized(error)) { unauthorized(s); return; }
      if (revision !== s.revision) return;
      if (!(error instanceof ApiError) || error.uncertain) { s.identityUnknown = true; live.current.onIdentityUnknown?.(); s.notice = 'UNKNOWN: identity confirmation may have rotated this session. Sign out and sign in explicitly before any new execution-control request. No password or action is resent.'; }
      else { s.showIdentity = true; s.notice = safeCode(error); }
    } finally {
      if (same(s) && revision === s.revision) { s.busy = null; live.current.onBoundaryEnd(); publish(s); }
    }
  }
  const visible = view && same(view.session) ? view : null, state = visible?.state;
  const busy = visible?.busy !== null && visible?.busy !== undefined;
  const canChange = !!visible?.current && !!state && visible.reviewed && !busy && !identityPending && !identityUnknown && !visible.identityUnknown && state.version < Number.MAX_SAFE_INTEGER;
  return <section className="panel execution-controls" aria-label="Account execution controls" aria-busy={busy}>
    <h2>Account execution controls</h2>
    <p>Emergency pause remains available while another request is pending or UNKNOWN. Every control request still requires server authorization, CSRF protection and recent identity confirmation.</p>
    <p role="status">{visible?.current && state ? state.execution_paused ? 'Account state: PAUSED' : 'Account state: AVAILABLE FOR NEW WORK' : 'Account state: UNKNOWN'}</p>
    {state && <p>{visible?.current ? 'Server check' : 'Last confirmed check (stale)'}: <time dateTime={state.checked_at}>{execution_time(state.checked_at)}</time> · Account version {state.version}</p>}
    <p>Display timezone: 한국시간(KST), Asia/Seoul. Current state is only a snapshot at the check time.</p>
    <p>Changing account execution state invalidates other signed-in sessions and old execution permissions. Pending approvals and eligible queued work are cancelled or revoked. In-flight work may remain UNKNOWN; pause is not proof that every operation stopped.</p>
    <p>Resume permits new requests only. It does not revive old approvals, grants, cancelled jobs or reminders that require fresh confirmation.</p>
    {unresolvedWork && <p role="alert">Earlier work remains unresolved across this safety boundary. New ordinary writes remain blocked; inspect saved records and execution history. Nothing was retried or marked resolved.</p>}
    {(visible?.outcomeUnknown || outcomeUnknown) && <p role="alert">An earlier execution-control request has an UNKNOWN outcome. A current-state read cannot prove what that request did. Any later control is a new explicit request for the reviewed version.</p>}
    {(visible?.identityUnknown || identityUnknown) && <p role="alert">Identity confirmation outcome is UNKNOWN. A status read cannot restore or verify the session credentials.</p>}
    {visible?.notice && <p role="alert">{visible.notice}</p>}
    {identityPending && <p role="status">Another identity confirmation is in progress. Execution controls wait for that session boundary.</p>}
    {busy && <p role="status">{visible?.busy === 'read' ? 'Reading account state…' : visible?.busy === 'reauth' ? 'Confirming identity…' : 'Waiting for the server execution-control result…'}</p>}
    <div className="actions"><button type="button" className="secondary" disabled={!visible || identityPending || busy && visible.busy !== 'read'} onClick={() => { if (session.current) void read(session.current); }}>Refresh account execution state</button><button type="button" className="secondary" disabled={!visible || busy || identityPending || identityUnknown || visible.identityUnknown} onClick={() => { const s = session.current; if (!s || !same(s) || live.current.identityPending || live.current.identityUnknown || s.busy || s.identityUnknown) return; s.showIdentity = !s.showIdentity; publish(s); }}>{visible?.showIdentity ? 'Close execution identity form' : 'Confirm identity for execution controls'}</button></div>
    {visible?.showIdentity && <form onSubmit={event => void reauthenticate(event)}><p>Submitting rotates this session. Pending work may already have committed and will remain unresolved. The password is submitted only by this form and is immediately cleared.</p><label>Execution control password<input name="password" type="password" autoComplete="current-password" required maxLength={128} disabled={busy || identityPending || identityUnknown || visible.identityUnknown}/></label><button disabled={busy || identityPending || identityUnknown || visible.identityUnknown}>Confirm execution identity</button></form>}
    <label><input type="checkbox" checked={visible?.reviewed ?? false} disabled={!visible?.current || busy || identityPending || identityUnknown || visible.identityUnknown} onChange={event => { const s = session.current; if (!s || !same(s) || live.current.identityPending || live.current.identityUnknown || !s.current || s.busy) return; s.reviewed = event.target.checked; publish(s); }}/>I reviewed the account-wide impact for this checked version</label>
    <div className="actions"><button type="button" disabled={!canChange || state?.execution_paused} onClick={() => void change('pause')}>Pause account execution</button><button type="button" className="secondary" disabled={!canChange || !state?.execution_paused} onClick={() => void change('resume')}>Resume new execution</button></div>
    {state?.version === Number.MAX_SAFE_INTEGER && <p role="alert">Account version is exhausted. No state-changing request can be issued.</p>}
  </section>;
}
