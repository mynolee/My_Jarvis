import { useEffect, useRef, useState } from 'react';
import { Api, ApiError } from './api';
import { read_reminder_page } from './schedules';
import { read_notification, same_notification_content, type InboxNotification } from './notifications';
export interface NotificationsPanelProps { api: Api; sessionKey: number; onUnauthorized: () => void; writesPaused?: boolean; selectionDisabled?: boolean; onPendingChange?: (pending: boolean) => void; onAcquirePending?: () => boolean }
/** A pending open retains only an identifier. No private content or retry body is persisted. */
export function NotificationsPanel({ api, sessionKey, onUnauthorized, writesPaused = false, selectionDisabled = false, onPendingChange, onAcquirePending }: NotificationsPanelProps) {
  const [items, setItems] = useState<InboxNotification[]>([]), [cursor, setCursor] = useState<string | null>(null), [selected, setSelected] = useState<InboxNotification | null>(null);
  const [pending, setPending] = useState<string | null>(null), [busy, setBusy] = useState(false), [loading, setLoading] = useState(false), [notice, setNotice] = useState('');
  const mounted = useRef(false), authenticated = useRef(true), epoch = useRef(0), listEpoch = useRef(0), lock = useRef(false), pendingRef = useRef<string | null>(null);
  const live = useRef({ api, sessionKey, writesPaused, selectionDisabled, onUnauthorized, onPendingChange, onAcquirePending }); live.current = { api, sessionKey, writesPaused, selectionDisabled, onUnauthorized, onPendingChange, onAcquirePending };
  const sameSession = () => mounted.current && authenticated.current && live.current.api === api && live.current.sessionKey === sessionKey;
  const current = (e: number) => sameSession() && epoch.current === e;
  function purge() { setItems([]); setCursor(null); setSelected(null); }
  function fail(error: unknown, e: number) {
    if (!sameSession()) return;
    if (error instanceof ApiError && error.status === 401) { authenticated.current = false; pendingRef.current = null; setPending(null); epoch.current++; listEpoch.current++; purge(); setNotice(''); setBusy(false); setLoading(false); lock.current = false; api.clear(); live.current.onUnauthorized(); live.current.onPendingChange?.(false); return; }
    if (!current(e)) return;
    setNotice(error instanceof ApiError && /^[A-Z][A-Z0-9_]{0,63}$/.test(error.code) ? error.code : 'Inbox response could not be verified.');
  }
  async function load(next: string | null = null) {
    const e = epoch.current, read = ++listEpoch.current; if (!sameSession()) return; setLoading(true);
    const query = new URLSearchParams({ limit: '20' }); if (next) query.set('cursor', next);
    try { const page = read_reminder_page(await api.request(`/notifications?${query}`), read_notification); if (!current(e) || read !== listEpoch.current) return; setItems(old => next ? [...old, ...page.items.filter(item => !old.some(o => o.id === item.id))] : page.items); setCursor(page.next_cursor); }
    catch (error) { if (read === listEpoch.current || error instanceof ApiError && error.status === 401) fail(error, e); }
    finally { if (sameSession() && read === listEpoch.current) setLoading(false); }
  }
  useEffect(() => { mounted.current = true; authenticated.current = true; epoch.current++; listEpoch.current++; lock.current = false; purge(); setPending(pendingRef.current); setBusy(false); setLoading(false); setNotice(pendingRef.current ? 'A previous open still needs a read-only outcome check. It will not be resent.' : ''); void load(); return () => { mounted.current = false; epoch.current++; listEpoch.current++; }; }, [api, sessionKey]);
  useEffect(() => { onPendingChange?.(busy || pending !== null); }, [busy, pending, onPendingChange]);
  useEffect(() => () => { pendingRef.current = null; live.current.onPendingChange?.(false); }, []);
  function begin(write = false): number | null { if (!sameSession() || lock.current || pendingRef.current || live.current.selectionDisabled || write && live.current.writesPaused) return null; if (live.current.onAcquirePending?.() === false) return null; lock.current = true; setBusy(true); setNotice(''); live.current.onPendingChange?.(true); return epoch.current; }
  function finish(e: number) { if (!current(e)) return; lock.current = false; setBusy(false); live.current.onPendingChange?.(pendingRef.current !== null); }
  async function select(item: InboxNotification) {
    const e = begin(); if (e === null) return; setSelected(null);
    try { const result = read_notification(await api.request(`/notifications/${item.id}`)); if (!current(e)) return; if (result.id !== item.id) throw new ApiError('INVALID_REMINDER_RESPONSE'); setSelected(result); }
    catch (error) { fail(error, e); } finally { finish(e); }
  }
  function acceptOpened(item: InboxNotification) { pendingRef.current = null; setPending(null); setSelected(item); setItems(old => old.map(i => i.id === item.id ? item : i)); setNotice('OPENED recorded in your internal inbox.'); }
  async function open() {
    if (!selected || selected.status === 'OPENED') return; const e = begin(true); if (e === null) return; const snapshot = selected; pendingRef.current = snapshot.id; listEpoch.current++; setLoading(false);
    try {
      const raw = await api.request(`/notifications/${snapshot.id}/open`, {}); if (!current(e)) return;
      let result: InboxNotification; try { result = read_notification(raw); if (result.status !== 'OPENED' || !same_notification_content(result, snapshot)) throw new Error(); } catch { throw new ApiError('UNKNOWN', true); }
      acceptOpened(result);
    } catch (error) {
      if (!sameSession()) return;
      if (!(error instanceof ApiError) || error.uncertain || error.status === 401) { setPending(snapshot.id); setNotice('The open outcome is unknown. Other writes are paused. Check this item without resending the open request.'); if (error instanceof ApiError && error.status === 401) fail(error, e); }
      else { pendingRef.current = null; setPending(null); fail(error, e); }
    } finally { finish(e); }
  }
  async function reconcile() {
    if (!pending || lock.current || !sameSession()) return; const e = epoch.current, id = pending; if (live.current.onAcquirePending?.() === false) return; lock.current = true; setBusy(true); live.current.onPendingChange?.(true);
    try {
      const result = read_notification(await api.request(`/notifications/${id}`)); if (!current(e)) return;
      if (result.id !== id || selected && !same_notification_content(result, selected)) throw new ApiError('INVALID_REMINDER_RESPONSE');
      if (result.status === 'CREATED') { setNotice('CREATED is inconclusive while the original open may still commit. Writes remain paused; no open request was resent.'); return; }
      acceptOpened(result);
    } catch (error) { fail(error, e); } finally { finish(e); }
  }
  const blocked = busy || pending !== null || writesPaused || selectionDisabled;
  return <section className="panel notifications" aria-label="Internal inbox"><h2>Internal inbox</h2>
    <p>Only your own in-app inbox is enabled. CREATED means an inbox record exists; OPENED means you explicitly opened it. Native push and device delivery are not enabled or tested.</p>
    <p>Refreshing or viewing details is read-only. Reminder text stays plain text, with no external links or previews.</p>
    {notice && <p role="alert">{notice}</p>}{loading && <p role="status">Loading internal inbox…</p>}
    <button className="secondary" disabled={busy || loading} onClick={() => void load()}>Refresh internal inbox</button>
    <ul>{items.map(item => <li key={item.id}><h3 className="result">{item.title}</h3><p>{item.status} · Created {item.created_at}</p><button className="secondary" disabled={busy || pending !== null || selectionDisabled} onClick={() => void select(item)}>View details: {item.title}</button></li>)}</ul>
    {!items.length && !loading && <p>No internal inbox items loaded.</p>}
    {cursor && <button className="secondary" disabled={busy || loading} onClick={() => void load(cursor)}>Load more inbox items</button>}
    {selected && <section aria-label="Inbox item details"><h3 className="result">{selected.title}</h3><p className="result">{selected.text}</p><dl><dt>Status</dt><dd>{selected.status}</dd><dt>Scheduled time (UTC)</dt><dd>{selected.planned_fire_at_utc}</dd><dt>Created (UTC)</dt><dd>{selected.created_at}</dd><dt>Opened (UTC)</dt><dd>{selected.opened_at ?? 'Not explicitly opened'}</dd><dt>Lateness</dt><dd>{selected.lateness_seconds} seconds</dd><dt>Schedule version</dt><dd>{selected.schedule_version}</dd><dt>Project</dt><dd>{selected.project_id ?? 'No project'}</dd></dl>
      {selected.status === 'CREATED' && <button disabled={blocked} onClick={() => void open()}>Open this inbox item</button>}
      <button className="secondary" disabled={busy || pending !== null || selectionDisabled} onClick={() => { epoch.current++; setSelected(null); setNotice(''); }}>Close inbox details</button></section>}
    {pending && <section aria-label="Unknown inbox open outcome"><p>Only the pending item identifier is retained for recovery.</p><button className="secondary" disabled={busy} onClick={() => void reconcile()}>Check inbox open outcome</button></section>}
  </section>;
}
