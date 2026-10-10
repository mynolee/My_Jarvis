import { useEffect, useRef, useState } from 'react';
import { Api, ApiError } from './api';
import {
  AUDIT_WINDOW_LIMIT, audit_history_path, audit_time, merge_audit_history,
  read_audit_history, resource_labels, type AuditRow, type AuditScope,
} from './auditHistory';

export interface AuditHistoryPanelProps {
  api: Api;
  sessionKey: string | number;
  onUnauthorized: () => void;
  /** Reads do not select a run or participate in mutation/navigation locks. */
  selectionDisabled?: boolean;
}
interface Data { rows: AuditRow[]; next_cursor: string | null; checked_at: string; trimmed: boolean }
interface Session {
  api: Api; key: string | number; alive: boolean; authorized: boolean;
  revision: number; scope: AuditScope; busy: boolean; data: Data | null;
}
interface View { session: Session; scope: AuditScope; data: Data | null; busy: boolean; failed: boolean }

/** Metadata stays in memory. Fences discard obsolete reads; they do not cancel transport. */
export function AuditHistoryPanel({ api, sessionKey, onUnauthorized }: AuditHistoryPanelProps) {
  const live = useRef({ api, sessionKey, onUnauthorized });
  live.current = { api, sessionKey, onUnauthorized };
  const session = useRef<Session | null>(null);
  const [view, setView] = useState<View | null>(null);
  const same = (s: Session) => s.alive && s.authorized && session.current === s && live.current.api === s.api && live.current.sessionKey === s.key;

  async function load(s: Session, append = false) {
    if (!same(s) || append && (s.busy || !s.data?.next_cursor)) return;
    const revision = ++s.revision, scope = s.scope;
    const previous = s.data, cursor = append ? previous!.next_cursor : null;
    s.busy = true;
    setView({ session: s, scope, data: previous, busy: true, failed: false });
    try {
      const raw = await s.api.request<unknown>(audit_history_path(scope, cursor));
      if (!same(s) || revision !== s.revision || scope !== s.scope) return;
      const page = read_audit_history(raw, scope);
      if (cursor !== null && page.next_cursor === cursor) throw new ApiError('INVALID_AUDIT_RESPONSE');
      const merged = merge_audit_history(append ? previous!.rows : [], page);
      const data: Data = { rows: merged.rows, next_cursor: page.next_cursor, checked_at: page.checked_at, trimmed: (append && previous!.trimmed) || merged.trimmed };
      s.data = data; s.busy = false;
      setView({ session: s, scope, data, busy: false, failed: false });
    } catch (error) {
      if (!same(s)) return;
      // A 401 from an older read still revokes this session, including after a scope change.
      if (error instanceof ApiError && error.status === 401) {
        s.authorized = false; s.revision++; s.data = null; s.busy = false;
        setView(null); s.api.clear(); live.current.onUnauthorized(); return;
      }
      if (revision !== s.revision || scope !== s.scope) return;
      s.busy = false;
      setView({ session: s, scope, data: s.data, busy: false, failed: true });
    }
  }
  useEffect(() => {
    const s: Session = { api, key: sessionKey, alive: true, authorized: true, revision: 0, scope: 'ALL', busy: false, data: null };
    session.current = s; setView(null); void load(s);
    return () => {
      s.alive = false; s.authorized = false; s.revision++; s.data = null; s.busy = false;
      if (session.current === s) session.current = null;
    };
  }, [api, sessionKey]);
  function selectScope(scope: AuditScope) {
    const s = session.current;
    if (!s || !same(s) || s.scope === scope) return;
    s.scope = scope; s.data = null;
    void load(s);
  }
  const visible = view && same(view.session) && view.scope === view.session.scope ? view : null;
  const data = visible?.data;
  const scope = visible?.scope ?? 'ALL';
  const authorized = session.current !== null && same(session.current);
  return <section className="panel audit-history" aria-label="Execution history" aria-busy={visible?.busy ?? false}>
    <h2>Execution history</h2>
    <p>Read-only owner audit metadata. Event time records when an event was logged; run state is the current server state at that row's page check.</p>
    <p>These records do not establish an execution mode or verify a real external action. Mock events describe simulated activity only. UNKNOWN remains an unconfirmed outcome.</p>
    <p>Display timezone: 한국시간(KST), Asia/Seoul. Original server timestamps remain UTC.</p>
    <nav aria-label="Execution history scope">
      <button type="button" className="secondary" aria-pressed={scope === 'ALL'} disabled={!authorized} onClick={() => selectScope('ALL')}>All owner events</button>
      <button type="button" className="secondary" aria-pressed={scope === 'RUNS'} disabled={!authorized} onClick={() => selectScope('RUNS')}>Run events</button>
      <button type="button" className="secondary" disabled={!authorized} onClick={() => { if (session.current) void load(session.current); }}>Refresh execution history</button>
    </nav>
    {visible?.busy && <p role="status">{data ? 'Reading execution history. Previously loaded metadata remains below until the read finishes.' : 'Loading execution history…'}</p>}
    {visible?.failed && <p role="alert">{data ? 'Execution history read failed. Previously loaded metadata is stale and has not been rechecked. Refresh to try a new read.' : 'Execution history could not be read. Whether matching events exist is unknown.'}</p>}
    {data && <>
      <p>Latest page check: <time dateTime={data.checked_at}>{audit_time(data.checked_at)}</time></p>
      <p>Showing {data.rows.length} loaded events. This view keeps at most {AUDIT_WINDOW_LIMIT} rows; loading older events beyond that limit removes the newest rows. Refresh returns to the latest events.</p>
      {data.trimmed && <p>Newer rows were removed from this bounded view. Refresh to return to the latest events.</p>}
      <ol aria-label="Audit events">{data.rows.map(({ event, checked_at }) => <li key={event.id}>
        <h3>Event: {event.event_type}</h3>
        {event.event_type.startsWith('MOCK_') && <p>Simulated mock event; no real external execution is established.</p>}
        <dl>
          <dt>Event ID</dt><dd>{event.id}</dd>
          <dt>Event time</dt><dd><time dateTime={event.happened_at}>{audit_time(event.happened_at)}</time></dd>
          <dt>Resource</dt><dd>{resource_labels[event.resource_kind]}</dd>
          <dt>Resource ID</dt><dd>{event.resource_id ?? 'Unavailable'}</dd>
          {event.run && <><dt>Current run state</dt><dd>{event.run.current_status}</dd><dt>Run state checked</dt><dd><time dateTime={checked_at}>{audit_time(checked_at)}</time></dd></>}
        </dl>
        {event.resource_kind === 'RUN' && event.run === null && <p>Current run state is unavailable.</p>}
        {event.run?.current_status === 'UNKNOWN' && <p>UNKNOWN: the run outcome is not confirmed.</p>}
        {event.run?.current_status === 'SUCCEEDED' && <p>SUCCEEDED is a server state, not verification of a real external action.</p>}
        {event.event_type === 'OTHER' && <p>Unrecognized event metadata is withheld.</p>}
      </li>)}</ol>
      {!visible?.busy && !visible?.failed && data.rows.length === 0 && <p>No matching audit events in this read.</p>}
      {data.next_cursor && <button type="button" className="secondary" disabled={visible?.busy || visible?.failed || !authorized} onClick={() => { if (session.current) void load(session.current, true); }}>Load older events</button>}
      {!visible?.busy && !visible?.failed && data.rows.length > 0 && data.next_cursor === null && <p>No older events in this read.</p>}
    </>}
  </section>;
}
