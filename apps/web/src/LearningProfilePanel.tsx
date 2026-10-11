import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Api, ApiError } from './api';
import { effective_learning, learning_fields, read_learning_slot, same_learning_slot, type LearningEntry, type LearningSlot } from './learning-profile';
import { read_memory_intent, read_memory_page, read_memory_receipt, read_memory_revision, valid_memory_body, valid_memory_fields, type MemoryArguments, type MemoryIntent, type MemoryReceipt, type MemoryRevision, type MemoryScope } from './memories';

export interface LearningProfilePanelProps {
  api: Api; sessionKey: number; selectedProjectId: string | null; onUnauthorized: () => void;
  writesPaused?: boolean; selectionDisabled?: boolean; onPendingChange?: (pending: boolean) => void; onAcquirePending?: () => boolean;
}
type Review = { intent: MemoryIntent; arguments: MemoryArguments; slot: LearningSlot; authority: number };
/** Recovery keeps only content-free metadata; a submitted body is never queued for replay. */
type Pending = { key: string; stage: 'INTENT' | 'SAVE'; target: string | null; operation: 'CREATE' | 'REVISE'; version: number; generation: number; field: number; scope: MemoryScope; project: string | null };
const empty = () => ({ value: '', change_reason: '' });

export function LearningProfilePanel({ api, sessionKey, selectedProjectId, onUnauthorized, writesPaused = false, selectionDisabled = false, onPendingChange, onAcquirePending }: LearningProfilePanelProps) {
  const [entries, setEntries] = useState<LearningEntry[] | null>(null), [fieldIndex, setFieldIndex] = useState(0), [scope, setScope] = useState<MemoryScope>('GLOBAL');
  const [draft, setDraft] = useState(empty), [revive, setRevive] = useState(false), [review, setReview] = useState<Review | null>(null);
  const [revisions, setRevisions] = useState<MemoryRevision[]>([]), [revisionCursor, setRevisionCursor] = useState<string | null>(null);
  const [busy, setBusy] = useState(false), [loading, setLoading] = useState(false), [pending, setPending] = useState<Pending | null>(null), [notice, setNotice] = useState('');
  const mounted = useRef(false), authenticated = useRef(true), epoch = useRef(0), readEpoch = useRef(0), lock = useRef(false), attemptRef = useRef<Pending | null>(null), committedFence = useRef<(Pending & { redacted: boolean }) | null>(null);
  const authority = useRef(0), previousPause = useRef(writesPaused), boundary = useRef({ api, sessionKey });
  if (writesPaused && !previousPause.current) authority.current++;
  previousPause.current = writesPaused;
  const live = useRef({ api, sessionKey, selectedProjectId, writesPaused, selectionDisabled, onUnauthorized, onPendingChange, onAcquirePending });
  live.current = { api, sessionKey, selectedProjectId, writesPaused, selectionDisabled, onUnauthorized, onPendingChange, onAcquirePending };
  const current = (e: number) => mounted.current && authenticated.current && epoch.current === e && live.current.api === api && live.current.sessionKey === sessionKey && live.current.selectedProjectId === selectedProjectId;
  const slot = entries ? (scope === 'PROJECT' ? entries[fieldIndex].project : entries[fieldIndex].global) : null;
  function invalidateReview() { authority.current++; setReview(null); }
  function clearForm() { setDraft(empty()); invalidateReview(); setRevive(false); setRevisions([]); setRevisionCursor(null); }
  function purge() { readEpoch.current++; setEntries(null); clearForm(); }
  function fail(error: unknown, e: number) {
    if (!mounted.current || !authenticated.current || live.current.api !== api || live.current.sessionKey !== sessionKey) return;
    // A same-session 401 is authoritative even if a read was superseded.
    if (error instanceof ApiError && error.status === 401) {
      authenticated.current = false; epoch.current++; purge(); attemptRef.current = null; committedFence.current = null;
      setPending(null); lock.current = false; setBusy(false); setLoading(false); setNotice(''); api.clear(); live.current.onUnauthorized(); live.current.onPendingChange?.(false); return;
    }
    if (!current(e)) return;
    invalidateReview(); setRevive(false); setRevisions([]); setRevisionCursor(null);
    if (error instanceof ApiError && (error.status === 404 || error.status === 410)) { purge(); setNotice('This memory is no longer active. Refresh the profile; old values and revisions have been cleared.'); return; }
    if (error instanceof ApiError && error.status === 409) { setEntries(null); setNotice('The memory version or generation changed. Refresh the profile and review your draft again. Nothing was resent.'); return; }
    setNotice(error instanceof ApiError && /^[A-Z][A-Z0-9_]{0,63}$/.test(error.code) ? error.code : 'Learning profile response could not be verified.');
  }
  async function load() {
    const e = epoch.current, r = ++readEpoch.current; if (!current(e)) return;
    setLoading(true); setEntries(null); invalidateReview(); setRevive(false); setRevisions([]); setRevisionCursor(null);
    const fresh = () => current(e) && r === readEpoch.current;
    try {
      const next = await Promise.all(learning_fields.map(async field => {
        try { return { field, global: await read_learning_slot(api, field, 'GLOBAL', null, fresh), project: selectedProjectId ? await read_learning_slot(api, field, 'PROJECT', selectedProjectId, fresh) : null }; }
        catch (error) {
          // Every branch observes revocation even after another Promise.all branch failed.
          if (error instanceof ApiError && error.status === 401) fail(error, e);
          throw error;
        }
      }));
      if (!fresh()) return;
      const fence = committedFence.current;
      if (fence && fence.project === (fence.scope === 'PROJECT' ? selectedProjectId : null)) {
        const observed = fence.scope === 'PROJECT' ? next[fence.field].project : next[fence.field].global;
        if (!observed || observed.state.generation < fence.generation || observed.state.generation === fence.generation && observed.state.status === 'ACTIVE' && (fence.redacted || observed.state.memory_id !== fence.target || observed.state.version! < fence.version)) throw new ApiError('STALE_LEARNING_SNAPSHOT');
      }
      committedFence.current = null; setEntries(next);
    } catch (error) { if (fresh() || error instanceof ApiError && error.status === 401) fail(error, e); }
    finally { if (fresh()) setLoading(false); }
  }
  useEffect(() => {
    if (boundary.current.api !== api || boundary.current.sessionKey !== sessionKey) { attemptRef.current = null; committedFence.current = null; boundary.current = { api, sessionKey }; }
    mounted.current = true; authenticated.current = true; epoch.current++; authority.current++; lock.current = false; purge(); setBusy(false); setLoading(false); setNotice('');
    // App owns the unresolved-work fence if an authentication boundary destroys this panel.
    setPending(attemptRef.current); void load();
    return () => { mounted.current = false; epoch.current++; readEpoch.current++; };
  }, [api, sessionKey, selectedProjectId]);
  useEffect(() => { if (writesPaused) { invalidateReview(); setRevive(false); setRevisions([]); setRevisionCursor(null); } }, [writesPaused]);
  useEffect(() => { onPendingChange?.(busy || pending !== null); }, [busy, pending, onPendingChange]);
  useEffect(() => () => { live.current.onPendingChange?.(false); }, []);
  function begin(write = true): number | null {
    if (lock.current || !authenticated.current || write && (attemptRef.current || live.current.writesPaused) || live.current.onAcquirePending?.() === false) return null;
    lock.current = true; readEpoch.current++; setLoading(false); setBusy(true); live.current.onPendingChange?.(true); setNotice(''); return epoch.current;
  }
  function finish(e: number) { if (current(e)) { lock.current = false; setBusy(false); live.current.onPendingChange?.(attemptRef.current !== null); } }
  function uncertain(error: unknown, attempt: Pending, e: number) {
    if (error instanceof ApiError && error.status === 401) { fail(error, e); return; }
    if (!current(e)) return;
    if (!(error instanceof ApiError) || error.uncertain) { attemptRef.current = attempt; setPending(attempt); purge(); setNotice('UNKNOWN: the request outcome is unconfirmed. Other writes are blocked. Nothing will be resent.'); }
    else { attemptRef.current = null; setPending(null); fail(error, e); }
  }
  async function mutate<T>(path: string, body: unknown, attempt: Pending, parse: (raw: unknown) => T, method: 'POST' | 'PATCH' = 'POST'): Promise<T> {
    if (!valid_memory_body(body)) throw new ApiError('MEMORY_BODY_TOO_LARGE');
    attemptRef.current = attempt;
    const raw = await api.request(path, body, attempt.key, method);
    try { return parse(raw); } catch { throw new ApiError('UNKNOWN', true); }
  }
  async function requestReview(event: FormEvent) {
    event.preventDefault(); if (!slot || slot.conflict || loading || review || !valid_memory_fields(draft)) return;
    const e = begin(); if (e === null) return;
    const expected = slot, field = learning_fields[fieldIndex], approvalEpoch = authority.current; let attempt: Pending | null = null;
    try {
      const fresh = await read_learning_slot(api, field, scope, selectedProjectId, () => current(e) && !live.current.writesPaused);
      if (!current(e) || live.current.writesPaused || approvalEpoch !== authority.current) return;
      if (fresh.conflict) { setEntries(null); throw new ApiError('LEARNING_KIND_CONFLICT'); }
      if (!same_learning_slot(expected, fresh)) throw new ApiError('PRECONDITION_CHANGED', false, 409);
      if (fresh.state.status === 'DELETED' && !revive) { setNotice('Choose an explicit fresh save for this deleted key before requesting review.'); return; }
      if (fresh.state.generation === Number.MAX_SAFE_INTEGER || fresh.memory?.version === Number.MAX_SAFE_INTEGER) throw new ApiError('MEMORY_VERSION_LIMIT');
      const operation = fresh.memory ? 'REVISE' : 'CREATE';
      const args: MemoryArguments = fresh.memory ? { ...draft, expected_version: fresh.memory.version, expected_generation: fresh.memory.generation } : { ...fresh.identity, kind: field.kind, ...draft, expected_generation: fresh.state.generation, revive_deleted: fresh.state.status === 'DELETED' };
      attempt = { key: crypto.randomUUID(), stage: 'INTENT', target: fresh.memory?.id ?? null, operation, version: (fresh.memory?.version ?? 0) + 1, generation: fresh.state.generation + (operation === 'CREATE' ? 1 : 0), field: fieldIndex, scope, project: fresh.identity.project_id };
      const intent = await mutate('/memory-intents', { tool_id: 'memory.write', operation, target_id: fresh.memory?.id ?? null, arguments: args }, attempt, raw => {
        const result = read_memory_intent(raw); if (result.operation !== operation || result.expected_generation !== args.expected_generation || fresh.memory && result.target_id !== fresh.memory.id) throw new Error(); return result;
      });
      if (!current(e)) return;
      attemptRef.current = null; setPending(null);
      if (!live.current.writesPaused && approvalEpoch === authority.current) setReview({ intent, arguments: args, slot: fresh, authority: approvalEpoch });
    } catch (error) { if (attempt) uncertain(error, attempt, e); else fail(error, e); }
    finally { finish(e); }
  }
  function matches(receipt: MemoryReceipt, attempt: Pending) {
    return receipt.status !== 'NOT_OBSERVED' && receipt.memory_id === attempt.target && receipt.operation === attempt.operation && receipt.version === attempt.version && receipt.generation === attempt.generation;
  }
  async function applyReceipt(receipt: MemoryReceipt, attempt: Pending) {
    if (receipt.status === 'NOT_OBSERVED') { setNotice('NOT_OBSERVED is inconclusive. The original save may still commit. Writes remain blocked; do not resend.'); return; }
    if (attempt.stage !== 'SAVE' || !matches(receipt, attempt)) throw new ApiError('INVALID_MEMORY_RESPONSE');
    attemptRef.current = null; setPending(null); committedFence.current = { ...attempt, redacted: receipt.status === 'REDACTED_DELETED' }; purge();
    setNotice(receipt.status === 'REDACTED_DELETED' ? 'The saved memory has since been deleted. Old values and revisions are cleared.' : 'This field save is confirmed. The other fields were not changed by this save.');
    await load();
  }
  async function save() {
    if (!review || live.current.writesPaused || review.authority !== authority.current) return;
    const staged = review, e = begin(); if (e === null) return;
    const attempt: Pending = { key: crypto.randomUUID(), stage: 'SAVE', target: staged.intent.target_id, operation: staged.intent.operation, version: 'expected_version' in staged.arguments ? staged.arguments.expected_version + 1 : 1, generation: staged.arguments.expected_generation + (staged.intent.operation === 'CREATE' ? 1 : 0), field: fieldIndex, scope: staged.slot.identity.scope, project: staged.slot.identity.project_id };
    invalidateReview();
    try {
      if (Date.parse(staged.intent.expires_at) <= Date.now()) throw new ApiError('INTENT_EXPIRED');
      const receipt = await mutate(staged.intent.operation === 'CREATE' ? '/memories' : `/memories/${staged.intent.target_id}`, { intent_id: staged.intent.id, arguments: staged.arguments }, attempt, read_memory_receipt, staged.intent.operation === 'CREATE' ? 'POST' : 'PATCH');
      if (!current(e)) return;
      if (!matches(receipt, attempt)) throw new ApiError('UNKNOWN', true);
      await applyReceipt(receipt, attempt);
    } catch (error) { uncertain(error, attempt, e); } finally { finish(e); }
  }
  async function reconcile() {
    const attempt = attemptRef.current; if (!attempt || attempt.stage !== 'SAVE') return;
    const e = begin(false); if (e === null) return;
    try { const receipt = read_memory_receipt(await api.request(`/memory-mutations/${attempt.key}`)); if (current(e)) await applyReceipt(receipt, attempt); }
    catch (error) { fail(error, e); } finally { finish(e); }
  }
  async function history(next: string | null = null) {
    const memory = slot?.memory; if (!memory || attemptRef.current || loading) return;
    const e = begin(false); if (e === null) return; invalidateReview();
    try {
      const query = new URLSearchParams({ limit: '20' }); if (next) query.set('cursor', next);
      const page = read_memory_page(await api.request(`/memories/${memory.id}/revisions?${query}`), read_memory_revision);
      if (!current(e)) return;
      if (page.items.some(item => item.memory_id !== memory.id || item.version > memory.version)) throw new ApiError('INVALID_MEMORY_RESPONSE');
      setRevisions(old => next ? [...old, ...page.items.filter(item => !old.some(previous => previous.version === item.version))] : page.items); setRevisionCursor(page.next_cursor);
    } catch (error) { fail(error, e); } finally { finish(e); }
  }
  function select(nextIndex: number, nextScope: MemoryScope) {
    if (lock.current || attemptRef.current || live.current.selectionDisabled) return;
    epoch.current++; readEpoch.current++; setLoading(false); setFieldIndex(nextIndex); setScope(nextScope); clearForm(); setNotice('');
  }
  const blocked = busy || pending !== null || writesPaused || loading || !slot || slot.conflict;
  return <section className="panel learning-profile" aria-label="Learning profile"><h2>Learning profile</h2>
    <p>These are values you enter, not measured ability or a model assessment. Each field is saved separately as an explicit memory. Blank or UNKNOWN does not mean beginner.</p>
    <p>Eligible memories may enter local-model context, subject to selection and token limits. This panel does not verify model behavior, teach a lesson, assess answers, or compile examples.</p>
    <p>Project values override global values. A deleted project key masks the global value. Editing a project creates or revises that project’s own record.</p>
    {notice && <p role="alert">{notice}</p>}{loading && <p role="status">Loading learning profile…</p>}
    <button className="secondary" disabled={busy || pending !== null || loading} onClick={() => void load()}>Refresh learning profile</button>
    <div aria-label="Effective learning profile">{learning_fields.map((field, index) => {
      const effective = entries ? effective_learning(entries[index]) : null;
      return <section key={field.key} aria-label={`Effective ${field.label}`}><h3>{field.label}</h3>
        {!effective ? <p>UNKNOWN: no verified value loaded.</p> : effective.status === 'ACTIVE' ? <><p>{effective.memory.value}</p><p>Source: explicit owner form. Scope: {effective.memory.scope}. Last owner confirmation: {effective.memory.confirmed_at}.</p><p>This is a saved statement, not evidence of current ability. Version {effective.memory.version}; generation {effective.memory.generation}.</p></> : <p>{effective.status === 'CONFLICT' ? 'Key kind conflict. Review this record in Explicit memories; no fallback value is used.' : effective.status === 'MASKED' ? 'Deleted key: no effective value. A project deletion masks the global value.' : 'Not set. No default value is assumed.'}</p>}
      </section>;
    })}</div>
    <label>Learning field<select value={fieldIndex} disabled={busy || pending !== null || loading || selectionDisabled} onChange={event => select(Number(event.target.value), scope)}>{learning_fields.map((field, index) => <option key={field.key} value={index}>{field.label}</option>)}</select></label>
    <label>Learning scope<select value={scope} disabled={busy || pending !== null || loading || selectionDisabled} onChange={event => select(fieldIndex, event.target.value as MemoryScope)}><option value="GLOBAL">Global</option><option value="PROJECT" disabled={!selectedProjectId}>Selected project</option></select></label>
    {scope === 'PROJECT' && <p>Selected project: {selectedProjectId ?? 'None; select a project first.'}</p>}
    <p>Product key: {learning_fields[fieldIndex].key}; kind: {learning_fields[fieldIndex].kind}. Values and reasons are saved exactly as entered.</p>
    {slot?.conflict && <p role="alert">Key kind conflict. This slot already uses a different kind. Use Explicit memories to review it; this editor will not overwrite or coerce it.</p>}
    {slot && <p>Selected slot: {slot.state.status}; generation {slot.state.generation}{slot.state.version ? `; version ${slot.state.version}` : ''}.</p>}
    {slot?.memory && <section aria-label="Selected learning memory"><p>Saved value: {slot.memory.value}</p><p>Last owner confirmation: {slot.memory.confirmed_at}.</p><button className="secondary" disabled={blocked} onClick={() => { if (!lock.current && !attemptRef.current && !live.current.writesPaused) { setDraft({ value: slot.memory!.value, change_reason: '' }); invalidateReview(); } }}>Copy saved value into draft</button><button className="secondary" disabled={busy || pending !== null || loading} onClick={() => void history()}>View learning field revisions</button></section>}
    <form onSubmit={event => void requestReview(event)}><fieldset disabled={blocked || review !== null}><legend>Edit one learning field</legend>
      <label>Learning value<textarea value={draft.value} required onChange={event => { invalidateReview(); setDraft({ ...draft, value: event.target.value }); }}/></label>
      <label>Learning change reason<textarea value={draft.change_reason} required onChange={event => { invalidateReview(); setDraft({ ...draft, change_reason: event.target.value }); }}/></label>
      <p>Value: at most 2000 Unicode characters / 8000 bytes. Reason: at most 300 characters / 1200 bytes. Both must be nonblank.</p>
      {slot?.state.status === 'DELETED' && <label><input type="checkbox" checked={revive} onChange={event => { invalidateReview(); setRevive(event.target.checked); }}/>Save a fresh value for this deleted key. Old values and revisions will not be restored.</label>}
      <button disabled={!valid_memory_fields(draft)}>Review learning field save</button></fieldset></form>
    {review && !writesPaused && review.authority === authority.current && <section aria-label="Review exact learning field save"><h3>Review exact learning field save</h3><p>{review.intent.operation} · {review.slot.identity.scope} · {review.slot.identity.key} · {learning_fields[fieldIndex].kind}</p><p>{review.arguments.value}</p><p>Reason: {review.arguments.change_reason}</p><p>Generation: {review.arguments.expected_generation}{'expected_version' in review.arguments ? `; version: ${review.arguments.expected_version}` : ''}. Expires: {review.intent.expires_at}</p><button disabled={blocked} onClick={() => void save()}>Confirm exact learning field save</button><button className="secondary" disabled={busy} onClick={invalidateReview}>Cancel learning review</button></section>}
    {revisions.length > 0 && <section aria-label="Learning field revisions"><ol>{revisions.map(item => <li key={item.version}><p>Version {item.version}: {item.value}</p><p>Reason: {item.change_reason}</p><p>Last owner confirmation: {item.confirmed_at}. Source: explicit owner form.</p></li>)}</ol>{revisionCursor && <button className="secondary" disabled={busy || pending !== null} onClick={() => void history(revisionCursor)}>Load more learning revisions</button>}</section>}
    <p>Use Explicit memories to delete a field with its existing reauthentication and deletion preview. Switching editors discards unsaved drafts and staged reviews.</p>
    {pending && <section aria-label="Unknown learning outcome"><p>Pending request: {pending.key}</p>{pending.stage === 'SAVE' ? <button className="secondary" disabled={busy} onClick={() => void reconcile()}>Check learning save outcome</button> : <><p>No field save was submitted. An unconsumed intent may exist.</p><button className="secondary" disabled={busy} onClick={() => { if (lock.current) return; attemptRef.current = null; setPending(null); purge(); setNotice('Unconsumed intent abandoned. No field save was submitted or repeated.'); live.current.onPendingChange?.(false); void load(); }}>Abandon learning intent</button></>}</section>}
  </section>;
}
