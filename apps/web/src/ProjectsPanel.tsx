import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Api, ApiError } from './api';
import { empty_project, read_project, read_project_page, read_project_receipt, valid_project_fields, type Project, type ProjectFields } from './projects';

export interface ProjectsPanelProps {
  api: Api; sessionKey: number; selectedProjectId: string | null;
  onSelect: (project: Project | null) => void; onUnauthorized: () => void;
  writesPaused?: boolean; selectionDisabled?: boolean; onPendingChange?: (pending: boolean) => void; onAcquirePending?: () => boolean;
}
type Attempt = { key: string; fields: ProjectFields };
export function ProjectsPanel({ api, sessionKey, selectedProjectId, onSelect, onUnauthorized, writesPaused = false, selectionDisabled = false, onPendingChange, onAcquirePending }: ProjectsPanelProps) {
  const [items, setItems] = useState<Project[]>([]), [cursor, setCursor] = useState<string | null>(null);
  const [draft, setDraft] = useState(empty_project), [pending, setPending] = useState<Attempt | null>(null);
  const [notice, setNotice] = useState(''), [busy, setBusy] = useState(false), [loading, setLoading] = useState(false);
  useEffect(() => { onPendingChange?.(busy || pending !== null); }, [busy, pending, onPendingChange]);
  useEffect(() => () => onPendingChange?.(false), [onPendingChange]);
  const session = useRef(0), live = useRef(false), authenticated = useRef(true), lock = useRef(false), listEpoch = useRef(0), selectionEpoch = useRef(0);
  const callbacks = useRef({ onSelect, onUnauthorized, selectionDisabled, writesPaused }); callbacks.current = { onSelect, onUnauthorized, selectionDisabled, writesPaused };
  const identity = useRef({ api, sessionKey }); identity.current = { api, sessionKey };
  const active = (epoch: number) => live.current && authenticated.current && session.current === epoch && identity.current.api === api && identity.current.sessionKey === sessionKey;
  function fail(error: unknown, epoch: number) {
    if (!active(epoch)) return;
    if (error instanceof ApiError && error.status === 401) {
      authenticated.current = false; session.current++; listEpoch.current++; selectionEpoch.current++; lock.current = false;
      setItems([]); setCursor(null); setDraft(empty_project()); setPending(null); setNotice(''); setBusy(false); setLoading(false); api.clear(); callbacks.current.onUnauthorized(); return;
    }
    setNotice(error instanceof ApiError && /^[A-Z][A-Z0-9_]{0,63}$/.test(error.code) ? error.code : 'Project response could not be verified.');
  }
  async function load(next: string | null = null) {
    const epoch = session.current, request = ++listEpoch.current;
    if (!active(epoch)) return;
    setLoading(true);
    const query = new URLSearchParams({ limit: '20' }); if (next) query.set('cursor', next);
    try {
      const page = read_project_page(await api.request(`/projects?${query}`));
      if (!active(epoch) || request !== listEpoch.current) return;
      setItems(old => next ? [...old, ...page.items.filter(item => !old.some(existing => existing.id === item.id))] : page.items); setCursor(page.next_cursor);
    } catch (error) { if (request === listEpoch.current || error instanceof ApiError && error.status === 401) fail(error, epoch); }
    finally { if (active(epoch) && request === listEpoch.current) setLoading(false); }
  }
  useEffect(() => {
    live.current = true; authenticated.current = true; session.current++; lock.current = false; setItems([]); setCursor(null); setDraft(empty_project()); setPending(null); setNotice(''); setBusy(false); setLoading(false); void load();
    return () => { live.current = false; session.current++; listEpoch.current++; selectionEpoch.current++; };
  }, [api, sessionKey]);
  async function select(id: string | null) {
    if (!authenticated.current || callbacks.current.selectionDisabled || lock.current) return;
    const epoch = session.current, request = ++selectionEpoch.current;
    if (id === null) { callbacks.current.onSelect(null); return; }
    try {
      const project = read_project(await api.request(`/projects/${id}`));
      if (project.id !== id) throw new ApiError('INVALID_PROJECT_RESPONSE');
      if (active(epoch) && request === selectionEpoch.current && !callbacks.current.selectionDisabled) callbacks.current.onSelect(project);
    } catch (error) { if (request === selectionEpoch.current || error instanceof ApiError && error.status === 401) fail(error, epoch); }
  }
  async function create(event: FormEvent) {
    event.preventDefault();
    if (!authenticated.current || lock.current || pending || callbacks.current.writesPaused) return;
    if (!valid_project_fields(draft)) { setNotice('Enter a project name (1–200 characters) and description of up to 4000 characters.'); return; }
    if (onAcquirePending?.() === false) return;
    const epoch = session.current, attempt: Attempt = { key: crypto.randomUUID(), fields: { ...draft } };
    lock.current = true; onPendingChange?.(true); setBusy(true); setNotice('');
    try {
      const raw = await api.request('/projects', attempt.fields, attempt.key);
      if (!active(epoch)) return;
      let project: Project;
      try { project = read_project(raw); if (project.name !== attempt.fields.name || project.description !== attempt.fields.description) throw new Error('WRONG_PROJECT'); }
      catch { throw new ApiError('UNKNOWN', true); }
      setItems(old => [project, ...old.filter(item => item.id !== project.id)]); setDraft(empty_project()); setNotice('Project created. Select it for a new conversation.');
    } catch (error) {
      if (!active(epoch)) return;
      if (error instanceof ApiError && error.status === 401) fail(error, epoch);
      else if (!(error instanceof ApiError) || error.uncertain) { setPending(attempt); setNotice('The create outcome is unknown. Project writes are paused. Check the saved outcome without resending.'); }
      else { fail(error, epoch); if (error.status === 409) setNotice('Project creation conflicted. Your draft is preserved. Refresh projects and review before creating again.'); }
    } finally { if (active(epoch)) { lock.current = false; setBusy(false); } }
  }
  async function reconcile() {
    if (!pending || lock.current || !authenticated.current || onAcquirePending?.() === false) return;
    const epoch = session.current, attempt = pending; lock.current = true; onPendingChange?.(true); setBusy(true);
    try {
      const raw = await api.request<unknown>(`/project-creations/${attempt.key}`);
      if (!active(epoch)) return;
      if (raw && typeof raw === 'object' && 'status' in raw && raw.status === 'NOT_OBSERVED' && 'project' in raw && raw.project === null) { setNotice('No committed receipt is visible yet. This does not prove failure. Writes remain paused; do not resend.'); return; }
      const project = read_project_receipt(raw, attempt.fields);
      if (!active(epoch)) return;
      setItems(old => [project, ...old.filter(item => item.id !== project.id)]); setPending(null); setDraft(empty_project()); setNotice('Server receipt confirms the project was created. Select it for a new conversation.');
    } catch (error) {
      if (!active(epoch)) return;
      if (error instanceof ApiError && error.status === 404) setNotice('No committed receipt is visible yet. This does not prove failure. Writes remain paused; do not resend.');
      else fail(error, epoch);
    } finally { if (active(epoch)) { lock.current = false; setBusy(false); } }
  }
  return <section className="panel projects" aria-label="Projects"><h2>Projects</h2>
    <p>Choose a project for your next new conversation. Existing conversations keep their original project.</p>
    {notice && <p role="alert">{notice}</p>}
    <button className="secondary" disabled={loading} onClick={() => void load()}>Refresh projects</button>
    <label>New conversation project<select value={selectedProjectId ?? ''} disabled={selectionDisabled || busy} onChange={event => void select(event.target.value || null)}><option value="">Personal (no project)</option>{selectedProjectId && !items.some(item => item.id === selectedProjectId) && <option value={selectedProjectId}>Selected project ({selectedProjectId})</option>}{items.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>
    {loading && <p role="status">Loading projects…</p>}{cursor && <button className="secondary" disabled={loading} onClick={() => void load(cursor)}>Load more projects</button>}
    <form onSubmit={event => void create(event)}><fieldset disabled={busy || pending !== null || writesPaused}><legend>Create project</legend><label>Project name<input required value={draft.name} onChange={event => setDraft({ ...draft, name: event.target.value })}/></label><label>Project description<textarea value={draft.description} onChange={event => setDraft({ ...draft, description: event.target.value })}/></label><button>Create project</button></fieldset></form>
    {pending && <section aria-label="Unknown project creation"><p>Request: {pending.key}</p><button className="secondary" disabled={busy} onClick={() => void reconcile()}>Check project outcome</button></section>}
  </section>;
}
