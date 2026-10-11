import { useEffect, useRef, useState } from 'react';
import { Api, ApiError } from './api';
import { read_today, today_overdue, today_time, type TodaySnapshot, type TodayTask } from './today';
export interface TodayPanelProps { api: Api; sessionKey: string | number; onUnauthorized: () => void; selectionDisabled?: boolean }
interface Session { api: Api; key: string | number; alive: boolean; authorized: boolean; revision: number }
interface View { session: Session; snapshot: TodaySnapshot | null; busy: boolean; failed: boolean }
export function TodayPanel({ api, sessionKey, onUnauthorized }: TodayPanelProps) {
  const current = useRef<{ api: Api; key: string | number }>({ api, key: sessionKey }); current.current = { api, key: sessionKey };
  const callback = useRef(onUnauthorized); callback.current = onUnauthorized;
  const session = useRef<Session | null>(null);
  const [view, setView] = useState<View | null>(null);
  function same(s: Session) { return s.alive && s.authorized && current.current.api === s.api && current.current.key === s.key && session.current === s; }
  async function refresh(s: Session) {
    if (!same(s)) return;
    const revision = ++s.revision;
    setView(old => ({ session: s, snapshot: old?.session === s ? old.snapshot : null, busy: true, failed: false }));
    try {
      const raw = await s.api.request<unknown>('/today');
      if (!same(s) || revision !== s.revision) return;
      const snapshot = read_today(raw);
      setView({ session: s, snapshot, busy: false, failed: false });
    } catch (error) {
      if (!same(s)) return;
      // A same-session 401 is authoritative even when a newer read superseded this one.
      if (error instanceof ApiError && error.status === 401) { s.authorized = false; s.revision++; s.api.clear(); setView(null); callback.current(); return; }
      if (revision === s.revision) setView(old => ({ session: s, snapshot: old?.session === s ? old.snapshot : null, busy: false, failed: true }));
    }
  }
  useEffect(() => {
    const s: Session = { api, key: sessionKey, alive: true, authorized: true, revision: 0 }; session.current = s;
    setView(null); void refresh(s);
    return () => { s.alive = false; s.revision++; if (session.current === s) session.current = null; };
  }, [api, sessionKey]);
  const visible = view && same(view.session) ? view : null;
  const data = visible?.snapshot;
  function item(t: TodayTask) { return <li key={t.id}><strong>{t.title}</strong><span> · {t.priority} · {t.status}</span>{t.due_at ? <span> · {today_overdue(t, data!.checked_at) ? 'Overdue at check' : 'Due'}: <time dateTime={t.due_at}>{today_time(t.due_at, data!.timezone)}</time></span> : <span> · No deadline</span>}</li>; }
  return <section className="panel today today-panel" aria-label="Today overview" aria-busy={visible?.busy ?? false}>
    <h2>Today</h2><p>Read-only local overview. Top tasks use overdue first, then priority and deadline.</p>
    <button className="secondary" disabled={!session.current?.authorized} onClick={() => { if (session.current) void refresh(session.current); }}>Refresh Today</button>
    {visible?.busy && <p role="status">{data ? 'Refreshing Today. Showing the previous check until this finishes.' : 'Loading Today…'}</p>}
    {visible?.failed && <p role="alert">{data ? 'Today refresh failed. The previous check below is stale and has not been reverified.' : 'Today could not be verified. Task and deadline status is unknown.'}</p>}
    {data && <><p>Owner date: {data.local_date} · Timezone: {data.timezone}</p><p>Checked: <time dateTime={data.checked_at}>{today_time(data.checked_at, data.timezone)}</time></p>
      <p>Owner day: <time dateTime={data.day_start_at}>{today_time(data.day_start_at, data.timezone)}</time> to <time dateTime={data.next_day_start_at}>{today_time(data.next_day_start_at, data.timezone)}</time> (end excluded).</p>
      <section aria-label="Top open tasks"><h3>Top open tasks</h3><ol>{data.tasks.top_tasks.map(item)}</ol>{!data.tasks.top_tasks.length && <p>No open tasks at this check.</p>}</section>
      <section aria-label="Overdue and today deadlines"><h3>Overdue and today deadlines</h3><p>Showing {data.tasks.deadlines.length} of {data.tasks.deadline_total} deadlines. {data.tasks.deadlines_truncated ? 'Truncated to the first 20.' : 'All matching deadlines shown.'}</p><p>Includes older overdue tasks; deadlines at or after the owner-day end are excluded.</p><ol>{data.tasks.deadlines.map(item)}</ol>{!data.tasks.deadlines.length && <p>No overdue or today deadlines at this check.</p>}</section></>}
    <section aria-label="Calendar availability"><h3>Calendar</h3><p>Unavailable: calendar integration is not implemented.</p></section>
    <section aria-label="Briefing availability"><h3>Briefing</h3><p>Unavailable: briefing generation is not implemented.</p></section>
    <section aria-label="Device availability"><h3>Device</h3><p>Unavailable: device status is not implemented.</p></section>
  </section>;
}
