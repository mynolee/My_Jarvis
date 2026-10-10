import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Api, ApiError } from './api';
import { empty_task, read_intent, read_task, task_priorities, task_statuses, task_time_micros, valid_date, valid_fields, type Task, type TaskArgs, type TaskFields, type TaskStatus, type TaskIntent } from './tasks';

const task_draft = (task: Task) => ({ title: task.title, description: task.description, project_id: task.project_id, due_at: task.due_at, priority: task.priority, estimated_minutes: task.estimated_minutes, status: task.status });

type Review = { args: TaskArgs; target: string | null };
type Pending = { key: string; target: string | null; mutation: boolean };
export function TasksPanel({ api, onUnauthorized, writesPaused = false, onPendingChange }: { api: Api; onUnauthorized: () => void; writesPaused?: boolean; onPendingChange?: (pending: boolean) => void }) {
  const [items, setItems] = useState<Task[]>([]), [cursor, setCursor] = useState<string | null>(null);
  const [status, setStatus] = useState(''), [from, setFrom] = useState(''), [to, setTo] = useState('');
  const [base, setBase] = useState<Task | null>(null), [draft, setDraft] = useState(empty_task);
  const [issued, setIssued] = useState<TaskIntent | null>(null);
  const [review, setReview] = useState<Review | null>(null), [conflict, setConflict] = useState<Task | null>(null);
  const [pending, setPending] = useState<Pending | null>(null), [observed, setObserved] = useState<Task | null>(null);
  const [notice, setNotice] = useState(''), [busy, setBusy] = useState(false);
  useEffect(() => { onPendingChange?.(busy || pending !== null); }, [busy, pending, onPendingChange]);
  useEffect(() => () => onPendingChange?.(false), [onPendingChange]);
  const lock = useRef(false), live = useRef(true), listEpoch = useRef(0);
  useEffect(() => { live.current = true; return () => { live.current = false; listEpoch.current++; }; }, []);
  function fail(error: unknown) {
    if (!live.current) return;
    if (error instanceof ApiError && error.status === 401) { live.current = false; listEpoch.current++; setItems([]); setDraft(empty_task()); setBase(null); setReview(null); setIssued(null); setConflict(null); setPending(null); setObserved(null); api.clear(); onUnauthorized(); return; }
    setNotice(error instanceof ApiError && /^[A-Z][A-Z0-9_]{0,63}$/.test(error.code) ? error.code : 'Task request could not be verified.');
  }
  async function load(next: string | null = null) {
    const epoch = ++listEpoch.current;
    if ((from && !valid_date(from)) || (to && !valid_date(to)) || (from && to && task_time_micros(from) >= task_time_micros(to))) { setNotice('Use valid RFC3339 dates; from must precede to.'); return; }
    const query = new URLSearchParams({ limit: '20' }); if (status) query.set('status', status); if (from) query.set('from', from); if (to) query.set('to', to); if (next) query.set('cursor', next);
    try { const data = await api.request<{items: unknown[]; next_cursor: string | null}>(`/tasks?${query}`); const tasks = data.items.map(read_task); if (data.next_cursor !== null && (typeof data.next_cursor !== 'string' || data.next_cursor.length > 512)) throw new Error('INVALID_CURSOR'); if (!live.current || epoch !== listEpoch.current) return; setItems(tasks); setCursor(data.next_cursor); } catch (error) { if (epoch === listEpoch.current) fail(error); }
  }
  useEffect(() => { void load(); }, []);
  const disabled = busy || writesPaused || pending !== null;
  function edit(task: Task | null) { if (disabled) return; setBase(task); setDraft(task ? task_draft(task) : empty_task()); setReview(null); setIssued(null); setConflict(null); setNotice(''); }
  function prepare(event: FormEvent) {
    event.preventDefault(); if (disabled || conflict) return;
    if (!valid_fields(draft)) { setNotice('Check title (1–200 characters), description (up to 8000), RFC3339 deadline and estimate (1–525600 minutes).'); return; }
    const { status: selectedStatus, ...fields } = draft;
    const args: TaskArgs = base ? { ...fields, status: selectedStatus, expected_version: base.version } : fields;
    setIssued(null); setReview({ target: base?.id ?? null, args }); setNotice('');
  }
  async function commit() {
    if (!review || disabled || lock.current) return;
    lock.current = true; onPendingChange?.(true); setBusy(true); setNotice('');
    let attempt: Pending = { key: crypto.randomUUID(), target: review.target, mutation: false };
    try {
      const tool = review.target ? 'task.update' : 'task.create';
      if (!issued) {
        const raw = await api.request<unknown>('/user-intents', { tool_id: tool, target_id: review.target, arguments: review.args }, attempt.key);
        if (!live.current) return;
        setIssued(read_intent(raw, tool, review.target, review.args));
        return;
      }
      const intent = read_intent(issued, tool, review.target, review.args);
      attempt = { key: crypto.randomUUID(), target: intent.target_id, mutation: true };
      const rawTask = await api.request<unknown>(review.target ? `/tasks/${review.target}` : '/tasks', { user_intent_id: intent.id, ...intent.arguments }, attempt.key, review.target ? 'PATCH' : 'POST');
      if (!live.current) return;
      let task: Task; try { task = read_task(rawTask); if (task.id !== intent.target_id) throw new Error('WRONG_TASK'); } catch { throw new ApiError('UNKNOWN', true); }
      setBase(task); setDraft(task_draft(task)); setReview(null); setIssued(null); setConflict(null); setNotice(`Server confirmed task ${task.status}, version ${task.version}.`); await load();
    } catch (error) {
      if (!live.current) return;
      if (error instanceof ApiError && error.status === 401) { fail(error); }
      else if (error instanceof ApiError && error.status === 409 && error.code === 'PRECONDITION_CHANGED') {
        try { const current = read_task((error.details as {current_task?: unknown})?.current_task); if (current.id !== review.target) throw new Error('WRONG_TASK'); setConflict(current); setReview(null); setIssued(null); setNotice('The task changed. Your unsaved draft is preserved. Review the current server task before creating a new intent.'); } catch { fail(error); setReview(null); setIssued(null); }
      } else if (error instanceof ApiError && error.uncertain) { setPending(attempt); setReview(null); setIssued(null); setNotice('UNKNOWN: task writes are paused. The request may have committed. Read server state; do not resend.'); }
      else { fail(error); setReview(null); setIssued(null); }
    } finally { lock.current = false; if (live.current) setBusy(false); }
  }
  async function reconcile() {
    if (!pending || lock.current) return; lock.current = true; onPendingChange?.(true); setBusy(true);
    try {
      if (!pending.mutation) { setNotice('The intent response is unknown. No task mutation was sent by this page. Writes remain paused; sign out and review server state before further work.'); return; }
      const receipt = await api.request<{status: string; task: unknown}>(`/task-mutations/${pending.key}`);
      if (!live.current) return;
      if (receipt.status === 'COMMITTED') {
        const committed = read_task(receipt.task); if (committed.id !== pending.target) throw new Error('WRONG_TASK');
        const current = read_task(await api.request(`/tasks/${committed.id}`)); if (!live.current) return;
        if (current.id !== committed.id) throw new Error('WRONG_TASK');
        setObserved(current); setBase(current); setDraft(task_draft(current)); setPending(null); setNotice(`Server receipt confirms committed version ${committed.version}. Current task version is ${current.version}; review before any new change.`); await load();
      } else if (receipt.status === 'NOT_OBSERVED' && receipt.task === null) {
        if (pending.target) { try { const current = read_task(await api.request(`/tasks/${pending.target}`)); if (live.current && current.id === pending.target) setObserved(current); } catch (error) { if (!(error instanceof ApiError && error.status === 404)) throw error; } }
        if (live.current) setNotice('No committed receipt is visible yet. A missing task is not proof of failure. Writes remain paused; do not resend.');
      } else throw new Error('INVALID_RECEIPT');
    } catch (error) { fail(error); } finally { lock.current = false; if (live.current) setBusy(false); }
  }
  return <section className="panel tasks" aria-label="Internal tasks"><h2>Internal tasks</h2><p className="hint">Personal to-dos, separate from model runs. Changes happen only after your explicit review; no external service is called.</p>
    {notice && <p role="alert" className="notice">{notice}</p>}
    <form className="task-filters" onSubmit={e => { e.preventDefault(); void load(); }}><label>Filter status<select value={status} onChange={e => setStatus(e.target.value)}><option value="">All statuses</option>{task_statuses.map(s => <option key={s}>{s}</option>)}</select></label><label>Due from (inclusive RFC3339)<input value={from} onChange={e => setFrom(e.target.value)} placeholder="2026-10-10T09:00:00Z"/></label><label>Due to (exclusive RFC3339)<input value={to} onChange={e => setTo(e.target.value)} placeholder="2026-10-11T09:00:00Z"/></label><button className="secondary">Apply task filters</button></form>
    <ul className="task-list">{items.map(task => <li key={task.id}><div><strong>{task.title}</strong><p>{task.status} · {task.priority} · version {task.version}</p><p>{task.due_at ?? 'No deadline'}</p></div><button className="secondary" disabled={disabled} onClick={() => edit(task)}>Edit {task.title}</button></li>)}</ul>{!items.length && <p>No tasks in this loaded page.</p>}{cursor && <button className="secondary" onClick={() => void load(cursor)}>Next task page</button>}
    <button className="secondary" disabled={disabled} onClick={() => edit(null)}>New personal task</button>
    <form onSubmit={prepare}><h3>{base ? 'Edit task draft' : 'Create task draft'}</h3><fieldset disabled={disabled || review !== null}><legend>Task details</legend><label>Task title<input value={draft.title} onChange={e => setDraft({ ...draft, title: e.target.value })} required/></label><label>Task description<textarea value={draft.description} onChange={e => setDraft({ ...draft, description: e.target.value })} rows={3}/></label><label>Task deadline (RFC3339, optional)<input value={draft.due_at ?? ''} onChange={e => setDraft({ ...draft, due_at: e.target.value || null })} placeholder="2026-10-10T09:00:00Z"/></label><label>Task priority<select value={draft.priority} onChange={e => setDraft({ ...draft, priority: e.target.value as TaskFields['priority'] })}>{task_priorities.map(s => <option key={s}>{s}</option>)}</select></label><label>Estimated minutes (optional)<input type="number" min="1" max="525600" step="1" value={draft.estimated_minutes ?? ''} onChange={e => setDraft({ ...draft, estimated_minutes: e.target.value === '' ? null : Number(e.target.value) })}/></label>{base && <label>Task status<select value={draft.status} onChange={e => setDraft({ ...draft, status: e.target.value as TaskStatus })}>{task_statuses.map(s => <option key={s}>{s}</option>)}</select></label>}<p className="hint">Project: {draft.project_id ?? 'Personal (no project)'}. To reopen, select TODO or IN_PROGRESS and review.</p><button disabled={disabled || conflict !== null}>Review task change</button></fieldset></form>
    {conflict && <section className="notice" aria-label="Task version conflict"><h3>Current server task</h3><pre>{JSON.stringify(conflict, null, 2)}</pre><h3>Preserved unsaved draft</h3><pre>{JSON.stringify(draft, null, 2)}</pre><button disabled={disabled} onClick={() => { setBase(conflict); setConflict(null); setReview(null); setNotice('Compare your preserved draft with the reviewed server version, then review the new change.'); }}>I reviewed this version; keep my draft</button></section>}
    {review && <section aria-label="Review task change"><h3>{issued ? 'Review server-bound task intent' : 'Review exact task change'}</h3><pre>{JSON.stringify(issued ?? review.args, null, 2)}</pre><p>{issued ? 'The server bound this one-time intent to these exact arguments. Apply it or cancel without changing the task.' : 'Request a one-time server intent, then review it before applying the task change.'}</p><div className="actions"><button disabled={disabled} onClick={() => void commit()}>{issued ? 'Apply server-bound task change' : 'Request task intent'}</button><button className="secondary" disabled={busy} onClick={() => { setReview(null); setIssued(null); }}>Back to draft</button></div></section>}
    {pending && <section className="notice"><h3>Task writes paused</h3><p>Request: {pending.key}</p>{pending.target && <p>Task: {pending.target}</p>}<button className="secondary" disabled={busy} onClick={() => void reconcile()}>Check task outcome</button></section>}
    {observed && <section aria-label="Observed server task"><h3>Observed server task</h3><pre>{JSON.stringify(observed, null, 2)}</pre></section>}
  </section>;
}
