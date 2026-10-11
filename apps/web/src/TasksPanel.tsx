import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Api, ApiError } from './api';
import { empty_task, read_intent, read_task, task_priorities, task_statuses, task_time_micros, valid_date, valid_fields, type Task, type TaskArgs, type TaskFields, type TaskStatus, type TaskIntent } from './tasks';
import type { StudyReviewCopy } from './study-review-task';

const task_draft = (task: Task) => ({ title: task.title, description: task.description, project_id: task.project_id, due_at: task.due_at, priority: task.priority, estimated_minutes: task.estimated_minutes, status: task.status });
const independent_copy_notice = 'This is an editable copy for an independent task. Requesting an intent stores the draft in task records; Apply creates the task. Deleting the study conversation does not erase these task records.';
type Review = { args: TaskArgs; target: string | null };
type Pending = { key: string; target: string | null; mutation: boolean; readonly kind: 'ORDINARY_TASK' | 'REVIEW_TASK_CREATE'; readonly disposal: number };
type Imported = { origin: StudyReviewCopy['origin']; disposal: number };
type Props = { api: Api; onUnauthorized: () => void; writesPaused?: boolean; onPendingChange?: (pending: boolean) => void; onAcquirePending?: () => boolean; incomingReviewCopy?: StudyReviewCopy | null; reviewCopyGeneration?: number; onConsumeReviewCopy?: (id: string) => boolean; onDismissReviewCopy?: (id: string) => void };
function exact_created_copy(task: Task, args: TaskArgs) {
  return task.status === 'TODO' && task.version === 1 && task.title === args.title && task.description === args.description && task.project_id === args.project_id && task.priority === args.priority && task.estimated_minutes === args.estimated_minutes && (task.due_at === args.due_at || (task.due_at !== null && typeof args.due_at === 'string' && task_time_micros(task.due_at) === task_time_micros(args.due_at)));
}
export function TasksPanel({ api, onUnauthorized, writesPaused = false, onPendingChange, onAcquirePending, incomingReviewCopy = null, reviewCopyGeneration = 0, onConsumeReviewCopy, onDismissReviewCopy }: Props) {
  const [items, setItems] = useState<Task[]>([]), [cursor, setCursor] = useState<string | null>(null);
  const [status, setStatus] = useState(''), [from, setFrom] = useState(''), [to, setTo] = useState('');
  const [base, setBase] = useState<Task | null>(null), [draft, setDraft] = useState(empty_task);
  const [issued, setIssued] = useState<TaskIntent | null>(null);
  const [review, setReview] = useState<Review | null>(null), [conflict, setConflict] = useState<Task | null>(null);
  const [pending, setPending] = useState<Pending | null>(null), [observed, setObserved] = useState<Task | null>(null);
  const [notice, setNotice] = useState(''), [busy, setBusy] = useState(false);
  const [incoming, setIncoming] = useState<{ copy: StudyReviewCopy; disposal: number } | null>(null), [imported, setImported] = useState<Imported | null>(null);
  const handledCopies = useRef(new Set<string>()), importedRef = useRef<Imported | null>(null);
  const incomingIdentity = useRef<string | null>(null);
  incomingIdentity.current = incomingReviewCopy?.ui_id ?? null;
  const lock = useRef(false), live = useRef(true), listEpoch = useRef(0);
  // Local cancellation retires future consent immediately, independently of a sent request.
  const consentEpoch = useRef(0);
  const renderedConsent = consentEpoch.current;
  // Retire request authority synchronously, before effects can clear rendered content.
  const owner = useRef({ api, generation: reviewCopyGeneration, disposal: 0, auth: 0 });
  const clearedDisposal = useRef(0), previousApi = useRef(api);
  if (owner.current.api !== api || owner.current.generation !== reviewCopyGeneration) {
    owner.current = { api, generation: reviewCopyGeneration, disposal: owner.current.disposal + 1, auth: owner.current.auth + (owner.current.api !== api ? 1 : 0) };
  }
  const auth = owner.current.auth;
  const current = () => live.current && owner.current.api === api && owner.current.auth === auth;
  const ownsConsent = () => current() && renderedConsent === consentEpoch.current;
  const copyDisposed = () => imported !== null && imported.disposal !== owner.current.disposal;
  const copiedDraftDisposed = copyDisposed();
  const shownDraft = copiedDraftDisposed ? empty_task() : draft;
  const shownReview = copiedDraftDisposed ? null : review;
  const shownIncoming = incoming && incoming.disposal === owner.current.disposal && incomingReviewCopy?.ui_id === incoming.copy.ui_id ? incoming.copy : null;
  useEffect(() => { onPendingChange?.(busy || pending !== null); }, [busy, pending, onPendingChange]);
  useEffect(() => () => onPendingChange?.(false), [onPendingChange]);
  useEffect(() => { live.current = true; return () => { live.current = false; listEpoch.current++; }; }, []);
  function clearCopiedEditor() {
    consentEpoch.current++;
    importedRef.current = null; setImported(null); setDraft(empty_task()); setBase(null); setReview(null); setIssued(null); setConflict(null); setObserved(null);
  }
  useEffect(() => {
    if (clearedDisposal.current !== owner.current.disposal) {
      clearedDisposal.current = owner.current.disposal;
      if (incomingReviewCopy) handledCopies.current.add(incomingReviewCopy.ui_id);
      setIncoming(null);
      if (importedRef.current && importedRef.current.disposal !== owner.current.disposal) clearCopiedEditor();
    }
    if (previousApi.current !== api) {
      previousApi.current = api; clearCopiedEditor(); setItems([]); setCursor(null); setPending(null); setBusy(false); lock.current = false; setNotice('');
    }
  }, [api, reviewCopyGeneration]);
  useEffect(() => {
    const hide = () => { owner.current.disposal++; setIncoming(null); if (incomingReviewCopy) handledCopies.current.add(incomingReviewCopy.ui_id); if (importedRef.current) clearCopiedEditor(); };
    window.addEventListener('pagehide', hide); return () => window.removeEventListener('pagehide', hide);
  }, [incomingReviewCopy]);
  useEffect(() => {
    if (!incomingReviewCopy) { setIncoming(null); return; }
    if (handledCopies.current.has(incomingReviewCopy.ui_id)) return;
    if (busy || pending || writesPaused || lock.current) {
      handledCopies.current.add(incomingReviewCopy.ui_id); onDismissReviewCopy?.(incomingReviewCopy.ui_id); setIncoming(null); return;
    }
    setIncoming(previous => previous?.disposal === owner.current.disposal ? previous : { copy: incomingReviewCopy, disposal: owner.current.disposal });
  }, [incomingReviewCopy, busy, pending, writesPaused, onDismissReviewCopy]);
  function fail(error: unknown) {
    if (!current()) return;
    if (error instanceof ApiError && error.status === 401) { live.current = false; listEpoch.current++; setItems([]); clearCopiedEditor(); setIncoming(null); setPending(null); api.clear(); onUnauthorized(); return; }
    setNotice(error instanceof ApiError && /^[A-Z][A-Z0-9_]{0,63}$/.test(error.code) ? error.code : 'Task request could not be verified.');
  }
  async function load(next: string | null = null) {
    const epoch = ++listEpoch.current;
    if ((from && !valid_date(from)) || (to && !valid_date(to)) || (from && to && task_time_micros(from) >= task_time_micros(to))) { setNotice('Use valid RFC3339 dates; from must precede to.'); return; }
    const query = new URLSearchParams({ limit: '20' }); if (status) query.set('status', status); if (from) query.set('from', from); if (to) query.set('to', to); if (next) query.set('cursor', next);
    try { const data = await api.request<{items: unknown[]; next_cursor: string | null}>(`/tasks?${query}`); const tasks = data.items.map(read_task); if (data.next_cursor !== null && (typeof data.next_cursor !== 'string' || data.next_cursor.length > 512)) throw new Error('INVALID_CURSOR'); if (!current() || epoch !== listEpoch.current) return; setItems(tasks); setCursor(data.next_cursor); } catch (error) { if (epoch === listEpoch.current) fail(error); }
  }
  useEffect(() => { void load(); }, [api]);
  const disabled = busy || writesPaused || pending !== null;
  const hasDraft = base !== null || imported !== null || review !== null || issued !== null || conflict !== null || draft.title !== '' || draft.description !== '' || draft.project_id !== null || draft.due_at !== null || draft.priority !== 'NORMAL' || draft.estimated_minutes !== null;
  function edit(task: Task | null) { if (!ownsConsent() || disabled || lock.current) return; consentEpoch.current++; importedRef.current = null; setImported(null); setBase(task); setDraft(task ? task_draft(task) : empty_task()); setReview(null); setIssued(null); setConflict(null); setObserved(null); setNotice(''); }
  function importCopy() {
    if (!ownsConsent() || !shownIncoming || incoming?.disposal !== owner.current.disposal || disabled || lock.current || handledCopies.current.has(shownIncoming.ui_id)) return;
    const copy = shownIncoming;
    if (onConsumeReviewCopy?.(copy.ui_id) === false || !ownsConsent() || incoming?.disposal !== owner.current.disposal) return;
    consentEpoch.current++;
    handledCopies.current.add(copy.ui_id); setIncoming(null);
    const marker = { origin: { ...copy.origin, selected_indexes: [...copy.origin.selected_indexes] }, disposal: owner.current.disposal };
    importedRef.current = marker; setImported(marker); setBase(null); setDraft({ ...copy.fields, status: 'TODO' }); setReview(null); setIssued(null); setConflict(null); setObserved(null); setNotice('');
  }
  function changeDraft(patch: Partial<typeof draft>) {
    if (!ownsConsent() || disabled || lock.current || review || copyDisposed()) return;
    consentEpoch.current++; setDraft({ ...draft, ...patch });
  }
  function prepare(event: FormEvent) {
    event.preventDefault(); if (!ownsConsent() || disabled || lock.current || conflict || copyDisposed()) return;
    if (!valid_fields(draft)) { setNotice('Check title (1–200 characters), description (up to 8000), RFC3339 deadline and estimate (1–525600 minutes).'); return; }
    const { status: selectedStatus, ...fields } = draft;
    const args: TaskArgs = base ? { ...fields, status: selectedStatus, expected_version: base.version } : fields;
    consentEpoch.current++; setIssued(null); setReview({ target: base?.id ?? null, args }); setNotice('');
  }
  async function commit() {
    if (!ownsConsent() || !review || disabled || copyDisposed() || lock.current || onAcquirePending?.() === false) return;
    const requestReview: Review = { target: review.target, args: { ...review.args } };
    const attempt: Pending = { key: crypto.randomUUID(), target: review.target, mutation: issued !== null, kind: imported ? 'REVIEW_TASK_CREATE' : 'ORDINARY_TASK', disposal: owner.current.disposal };
    const operationConsent = ++consentEpoch.current;
    const mayAdopt = () => operationConsent === consentEpoch.current;
    lock.current = true; onPendingChange?.(true); setBusy(true); setNotice('');
    let writeSent = false;
    try {
      const tool = requestReview.target ? 'task.update' : 'task.create';
      if (!issued) {
        writeSent = true;
        const raw = await api.request<unknown>('/user-intents', { tool_id: tool, target_id: requestReview.target, arguments: requestReview.args }, attempt.key);
        if (!current()) return;
        const intent = read_intent(raw, tool, requestReview.target, requestReview.args);
        if (!mayAdopt() || (attempt.kind === 'REVIEW_TASK_CREATE' && attempt.disposal !== owner.current.disposal)) {
          setNotice('Server confirmed an intent was recorded. No task mutation was sent by this page. Clearing the local draft did not erase that server record.');
        } else setIssued(intent);
        return;
      }
      const intent = read_intent(issued, tool, requestReview.target, requestReview.args);
      attempt.target = intent.target_id;
      writeSent = true;
      const rawTask = await api.request<unknown>(requestReview.target ? `/tasks/${requestReview.target}` : '/tasks', { user_intent_id: intent.id, ...intent.arguments }, attempt.key, requestReview.target ? 'PATCH' : 'POST');
      if (!current()) return;
      let task: Task; try { task = read_task(rawTask); if (task.id !== intent.target_id || (attempt.kind === 'REVIEW_TASK_CREATE' && !exact_created_copy(task, requestReview.args))) throw new Error('WRONG_TASK'); } catch { throw new ApiError('UNKNOWN', true); }
      if (!mayAdopt() || (attempt.kind === 'REVIEW_TASK_CREATE' && attempt.disposal !== owner.current.disposal)) {
        setNotice(`Server confirmed ${attempt.kind === 'REVIEW_TASK_CREATE' ? 'independent task' : 'task'} ${task.id}, ${task.status}, version ${task.version}. Read the current task before editing it.`);
      } else {
        importedRef.current = null; setImported(null); setBase(task); setDraft(task_draft(task)); setReview(null); setIssued(null); setConflict(null); setNotice(`Server confirmed task ${task.status}, version ${task.version}.`); await load();
      }
    } catch (error) {
      if (!current()) return;
      if (error instanceof ApiError && error.status === 401) { fail(error); }
      else if (attempt.kind === 'REVIEW_TASK_CREATE' && error instanceof ApiError && !error.uncertain && error.status !== undefined && error.status >= 400 && error.status < 500) {
        fail(error); if (mayAdopt()) { setReview(null); setIssued(null); }
      } else if (attempt.kind === 'REVIEW_TASK_CREATE' && writeSent) {
        clearCopiedEditor(); setPending(attempt); setNotice('UNKNOWN: task writes are paused. The request may have committed. Read server state; do not resend.');
      } else if (error instanceof ApiError && error.status === 409 && error.code === 'PRECONDITION_CHANGED') {
        try { const currentTask = read_task((error.details as {current_task?: unknown})?.current_task); if (currentTask.id !== requestReview.target) throw new Error('WRONG_TASK'); setConflict(currentTask); setReview(null); setIssued(null); setNotice('The task changed. Your unsaved draft is preserved. Review the current server task before creating a new intent.'); } catch { fail(error); setReview(null); setIssued(null); }
      } else if (error instanceof ApiError && error.uncertain) { setPending(attempt); setReview(null); setIssued(null); setNotice('UNKNOWN: task writes are paused. The request may have committed. Read server state; do not resend.'); }
      else { fail(error); setReview(null); setIssued(null); }
    } finally { if (current()) { if (mayAdopt()) consentEpoch.current++; lock.current = false; setBusy(false); } }
  }
  async function reconcile() {
    if (!ownsConsent() || !pending || lock.current || onAcquirePending?.() === false) return;
    const attempt = pending, disposal = owner.current.disposal, reconciliationConsent = ++consentEpoch.current;
    lock.current = true; onPendingChange?.(true); setBusy(true);
    try {
      if (!attempt.mutation) { setNotice('The intent response is unknown. No task mutation was sent by this page. Writes remain paused; sign out and review server state before further work.'); return; }
      const raw = await api.request<unknown>(`/task-mutations/${attempt.key}`);
      if (!current()) return;
      if (attempt.kind === 'REVIEW_TASK_CREATE' && (!raw || typeof raw !== 'object' || Array.isArray(raw) || Object.keys(raw).length !== 2 || !Object.hasOwn(raw, 'status') || !Object.hasOwn(raw, 'task'))) throw new Error('INVALID_RECEIPT');
      const receipt = raw as {status: string; task: unknown};
      if (receipt.status === 'COMMITTED') {
        const committed = read_task(receipt.task); if (committed.id !== attempt.target || (attempt.kind === 'REVIEW_TASK_CREATE' && (committed.status !== 'TODO' || committed.version !== 1))) throw new Error('WRONG_TASK');
        const currentTask = read_task(await api.request(`/tasks/${committed.id}`)); if (!current()) return;
        if (currentTask.id !== committed.id) throw new Error('WRONG_TASK');
        setPending(null); importedRef.current = null; setImported(null);
        if (attempt.kind !== 'REVIEW_TASK_CREATE' || disposal === owner.current.disposal) { setObserved(currentTask); setBase(currentTask); setDraft(task_draft(currentTask)); }
        setNotice(`Server receipt confirms committed version ${committed.version}. Current task version is ${currentTask.version}; review before any new change.`);
        if (attempt.kind !== 'REVIEW_TASK_CREATE' || disposal === owner.current.disposal) await load();
      } else if (receipt.status === 'NOT_OBSERVED' && receipt.task === null) {
        if (attempt.target) { try { const currentTask = read_task(await api.request(`/tasks/${attempt.target}`)); if (current() && currentTask.id === attempt.target && (attempt.kind !== 'REVIEW_TASK_CREATE' || disposal === owner.current.disposal)) setObserved(currentTask); } catch (error) { if (!(error instanceof ApiError && error.status === 404)) throw error; } }
        if (current()) setNotice('No committed receipt is visible yet. A missing task is not proof of failure. Writes remain paused; do not resend.');
      } else throw new Error('INVALID_RECEIPT');
    } catch (error) { fail(error); } finally { if (current()) { if (reconciliationConsent === consentEpoch.current) consentEpoch.current++; lock.current = false; setBusy(false); } }
  }
  return <section className="panel tasks" aria-label="Internal tasks"><h2>Internal tasks</h2><p className="hint">Personal to-dos, separate from model runs. Changes happen only after your explicit review; no external service is called.</p>
    {notice && <p role="alert" className="notice">{notice}</p>}
    {shownIncoming && <section aria-label="Incoming study review copy"><h3>Incoming study review copy</h3><p>This preview will become a new independent task draft only if you choose to use it.</p><strong>{shownIncoming.fields.title}</strong><pre>{shownIncoming.fields.description}</pre><p>Project: {shownIncoming.fields.project_id ?? 'Personal (no project)'}</p><button disabled={disabled} onClick={importCopy}>{hasDraft ? 'Replace my current unsaved draft with this copy' : 'Use this review draft'}</button><button className="secondary" onClick={() => { if (!current() || incoming?.disposal !== owner.current.disposal || incomingIdentity.current !== shownIncoming.ui_id || handledCopies.current.has(shownIncoming.ui_id)) return; handledCopies.current.add(shownIncoming.ui_id); setIncoming(previous => previous?.copy.ui_id === shownIncoming.ui_id ? null : previous); onDismissReviewCopy?.(shownIncoming.ui_id); }}>Dismiss review copy</button></section>}
    <form className="task-filters" onSubmit={e => { e.preventDefault(); void load(); }}><label>Filter status<select value={status} onChange={e => setStatus(e.target.value)}><option value="">All statuses</option>{task_statuses.map(s => <option key={s}>{s}</option>)}</select></label><label>Due from (inclusive RFC3339)<input value={from} onChange={e => setFrom(e.target.value)} placeholder="2026-10-10T09:00:00Z"/></label><label>Due to (exclusive RFC3339)<input value={to} onChange={e => setTo(e.target.value)} placeholder="2026-10-11T09:00:00Z"/></label><button className="secondary">Apply task filters</button></form>
    <ul className="task-list">{items.map(task => <li key={task.id}><div><strong>{task.title}</strong><p>{task.status} · {task.priority} · version {task.version}</p><p>{task.due_at ?? 'No deadline'}</p></div><button className="secondary" disabled={disabled} onClick={() => edit(task)}>Edit {task.title}</button></li>)}</ul>{!items.length && <p>No tasks in this loaded page.</p>}{cursor && <button className="secondary" onClick={() => void load(cursor)}>Next task page</button>}
    <button className="secondary" disabled={disabled} onClick={() => edit(null)}>New personal task</button>
    <form onSubmit={prepare}><h3>{base && !copiedDraftDisposed ? 'Edit task draft' : 'Create task draft'}</h3><fieldset disabled={disabled || shownReview !== null}><legend>Task details</legend><label>Task title<input value={shownDraft.title} onChange={e => changeDraft({ title: e.target.value })} required/></label><label>Task description<textarea value={shownDraft.description} onChange={e => changeDraft({ description: e.target.value })} rows={3}/></label><label>Task deadline (RFC3339, optional)<input value={shownDraft.due_at ?? ''} onChange={e => changeDraft({ due_at: e.target.value || null })} placeholder="2026-10-10T09:00:00Z"/></label><label>Task priority<select value={shownDraft.priority} onChange={e => changeDraft({ priority: e.target.value as TaskFields['priority'] })}>{task_priorities.map(s => <option key={s}>{s}</option>)}</select></label><label>Estimated minutes (optional)<input type="number" min="1" max="525600" step="1" value={shownDraft.estimated_minutes ?? ''} onChange={e => changeDraft({ estimated_minutes: e.target.value === '' ? null : Number(e.target.value) })}/></label>{base && !copiedDraftDisposed && <label>Task status<select value={draft.status} onChange={e => changeDraft({ status: e.target.value as TaskStatus })}>{task_statuses.map(s => <option key={s}>{s}</option>)}</select></label>}<p className="hint">Project: {shownDraft.project_id ?? 'Personal (no project)'}. To reopen, select TODO or IN_PROGRESS and review.</p><button disabled={disabled || conflict !== null}>Review task change</button></fieldset></form>
    {imported && !copiedDraftDisposed && <><p>{independent_copy_notice}</p><button className="secondary" disabled={disabled} onClick={() => { if (!ownsConsent() || disabled || lock.current || copyDisposed()) return; clearCopiedEditor(); setNotice('Local draft discarded; no task change. Any previously requested intent remains in task records.'); }}>Discard review task draft</button></>}
    {conflict && !copiedDraftDisposed && <section className="notice" aria-label="Task version conflict"><h3>Current server task</h3><pre>{JSON.stringify(conflict, null, 2)}</pre><h3>Preserved unsaved draft</h3><pre>{JSON.stringify(draft, null, 2)}</pre><button disabled={disabled} onClick={() => { if (!ownsConsent() || disabled || lock.current) return; consentEpoch.current++; setBase(conflict); setConflict(null); setReview(null); setNotice('Compare your preserved draft with the reviewed server version, then review the new change.'); }}>I reviewed this version; keep my draft</button></section>}
    {shownReview && <section aria-label="Review task change"><h3>{issued ? 'Review server-bound task intent' : 'Review exact task change'}</h3><pre>{JSON.stringify(issued ?? shownReview.args, null, 2)}</pre>{imported && <section aria-label="Source read when copied"><h4>Source read when copied</h4><p>Historical context for this independent copy; it does not guarantee the source still exists when the task is saved.</p><pre>{JSON.stringify(imported.origin, null, 2)}</pre></section>}<p>{issued ? 'The server bound this one-time intent to these exact arguments. Apply it or cancel without changing the task.' : 'Request a one-time server intent, then review it before applying the task change.'}</p><div className="actions"><button disabled={disabled} onClick={() => void commit()}>{issued ? 'Apply server-bound task change' : 'Request task intent'}</button><button className="secondary" disabled={busy} onClick={() => { if (!ownsConsent() || busy || lock.current || copyDisposed()) return; consentEpoch.current++; setReview(null); setIssued(null); if (imported) setNotice('Back to draft; no task change. Any previously requested intent remains in task records.'); }}>Back to draft</button></div></section>}
    {pending && <section className="notice"><h3>Task writes paused</h3><p>Request: {pending.key}</p>{pending.target && <p>Task: {pending.target}</p>}<button className="secondary" disabled={busy} onClick={() => void reconcile()}>Check task outcome</button></section>}
    {observed && <section aria-label="Observed server task"><h3>Observed server task</h3><pre>{JSON.stringify(observed, null, 2)}</pre></section>}
  </section>;
}
