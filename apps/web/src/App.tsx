import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { flushSync } from 'react-dom';
import { Api, ApiError } from './api';
import { TasksPanel } from './TasksPanel';
import { MemoriesPanel } from './MemoriesPanel';
import { LearningProfilePanel } from './LearningProfilePanel';
import { ProfilePanel } from './ProfilePanel';
import { SchedulesPanel } from './SchedulesPanel';
import { NotificationsPanel } from './NotificationsPanel';
import { MailDraftsPanel } from './MailDraftsPanel';
import { TodayPanel } from './TodayPanel';
import { AuditHistoryPanel } from './AuditHistoryPanel';
import { UsagePanel } from './UsagePanel';
import { MemoryExportPanel } from './MemoryExportPanel';
import { ConversationDeletionPanel } from './ConversationDeletionPanel';
import { ExecutionControlsPanel } from './ExecutionControlsPanel';
import type { ExecutionState } from './execution-controls';
import { ProjectsPanel } from './ProjectsPanel';
import type { Project } from './projects';
import { can_decide, decision_body, terminal, UUID, valid_snapshot, type Approval, type Conversation, type Run } from './domain';
import { ConversationHistoryPanel } from './ConversationHistoryPanel';
import type { HistoryPage } from './conversation-history';
import { read_runtime, type LocalRuntime } from './runtime';

export function App({ api = new Api() }: { api?: Api }) {
  const client = useRef(api).current;
  const [authenticated, set_authenticated] = useState(false);
  const execution_session = useRef(0), execution_locked = useRef(false), account_blocked = useRef(true), unresolved_work = useRef(false), main_ticket = useRef(0), main_unknown = useRef(false), identity_rotating = useRef(false), identity_unknown_ref = useRef(false), control_unknown_ref = useRef(false);
  const [identity_pending, set_identity_pending] = useState(false), [identity_unknown, set_identity_unknown] = useState(false);
  const [control_outcome_unknown, set_control_outcome_unknown] = useState(false);
  const [execution_state, set_execution_state] = useState<ExecutionState | null>(null);
  const [execution_refresh, set_execution_refresh] = useState(0);
  const [execution_pending, set_execution_pending] = useState(false);
  const [workspace_suspended, set_workspace_suspended] = useState(false);
  const [interrupted_work, set_interrupted_work] = useState(false);
  const recovery_boundary_lost = useRef(false);
  const [tasks_open, set_tasks_open] = useState(false);
  const [projects_open, set_projects_open] = useState(false);
  const [memories_open, set_memories_open] = useState(false);
  const [learning_open, set_learning_open] = useState(false);
  const memory_editor = useRef<'generic' | 'learning' | null>(null), memory_editor_epoch = useRef(0);
  const editor_epoch = memory_editor_epoch.current;
  const [memory_pending, set_memory_pending] = useState(false);
  const [profile_open, set_profile_open] = useState(false);
  const [profile_pending, set_profile_pending] = useState(false);
  const [schedules_open, set_schedules_open] = useState(false);
  const [notifications_open, set_notifications_open] = useState(false);
  const [schedule_pending, set_schedule_pending] = useState(false);
  const [notification_pending, set_notification_pending] = useState(false);
  const [mail_drafts_open, set_mail_drafts_open] = useState(false);
  const [audit_history_open, set_audit_history_open] = useState(false);
  const [usage_open, set_usage_open] = useState(false);
  const [deletion_open, set_deletion_open] = useState(false);
  const [deletion_pending, set_deletion_pending] = useState(false);
  const [deletion_source_hidden, set_deletion_source_hidden] = useState(false);
  const deletion_hidden = useRef(false);
  const [export_open, set_export_open] = useState(false);
  const [export_pending, set_export_pending] = useState(false);
  const [mail_draft_pending, set_mail_draft_pending] = useState(false);
  const domain_locked = useRef(false), navigation_paused = useRef(false), main_navigation_paused = useRef(false);
  const pending_domains = useRef({ task: false, project: false, memory: false, profile: false, schedule: false, notification: false, mail_draft: false, memory_export: false, conversation_deletion: false });
  const update_pending = useCallback((domain: 'task' | 'project' | 'memory' | 'profile' | 'schedule' | 'notification' | 'mail_draft' | 'memory_export' | 'conversation_deletion', pending: boolean) => { pending_domains.current[domain] = pending; domain_locked.current = Object.values(pending_domains.current).some(Boolean); navigation_paused.current = main_navigation_paused.current || domain_locked.current; }, []);
  const task_pending_changed = useCallback((pending: boolean) => { update_pending('task', pending); set_task_pending(pending); }, [update_pending]);
  const project_pending_changed = useCallback((pending: boolean) => { update_pending('project', pending); set_project_pending(pending); }, [update_pending]);
  const memory_pending_changed = useCallback((pending: boolean) => { update_pending('memory', pending); set_memory_pending(pending); }, [update_pending]);
  const profile_pending_changed = useCallback((pending: boolean) => { update_pending('profile', pending); set_profile_pending(pending); }, [update_pending]);
  const schedule_pending_changed = useCallback((pending: boolean) => { update_pending('schedule', pending); set_schedule_pending(pending); }, [update_pending]);
  const notification_pending_changed = useCallback((pending: boolean) => { update_pending('notification', pending); set_notification_pending(pending); }, [update_pending]);
  const mail_draft_pending_changed = useCallback((pending: boolean) => { update_pending('mail_draft', pending); set_mail_draft_pending(pending); }, [update_pending]);
  const export_pending_changed = useCallback((pending: boolean) => { update_pending('memory_export', pending); set_export_pending(pending); }, [update_pending]);
  const deletion_pending_changed = useCallback((pending: boolean) => { update_pending('conversation_deletion', pending); set_deletion_pending(pending); }, [update_pending]);
  const export_outcome_unknown = useCallback(() => { recovery_boundary_lost.current = true; }, []);
  const [task_pending, set_task_pending] = useState(false);
  const [project_pending, set_project_pending] = useState(false);
  const acquire_pending = useCallback((domain: keyof typeof pending_domains.current) => {
    if (locked.current || execution_locked.current || account_blocked.current || unresolved_work.current || main_navigation_paused.current || Object.entries(pending_domains.current).some(([key, value]) => key !== domain && value)) return false;
    pending_domains.current[domain] = true; domain_locked.current = true; navigation_paused.current = true; return true;
  }, []);
  const acquire_task = useCallback(() => acquire_pending('task'), [acquire_pending]);
  const acquire_project = useCallback(() => acquire_pending('project'), [acquire_pending]);
  const acquire_memory = useCallback(() => acquire_pending('memory'), [acquire_pending]);
  const acquire_profile = useCallback(() => acquire_pending('profile'), [acquire_pending]);
  const acquire_schedule = useCallback(() => acquire_pending('schedule'), [acquire_pending]);
  const acquire_notification = useCallback(() => acquire_pending('notification'), [acquire_pending]);
  const acquire_mail_draft = useCallback(() => acquire_pending('mail_draft'), [acquire_pending]);
  const acquire_deletion = useCallback(() => acquire_pending('conversation_deletion'), [acquire_pending]);
  const acquire_export = useCallback(() => acquire_pending('memory_export'), [acquire_pending]);
  const [new_project, set_new_project] = useState<Project | null>(null);
  const [ready, set_ready] = useState(false);
  const [runtime, set_runtime] = useState<LocalRuntime | null>(null);
  const [runtime_checked_at, set_runtime_checked_at] = useState(0);
  const [busy, set_busy] = useState(false);
  const locked = useRef(false);
  const generation = useRef(0);
  const auth_generation = useRef(0);
  const [notice, set_notice] = useState('');
  const [uncertain, set_uncertain] = useState(false);
  const [reauth_required, set_reauth_required] = useState(false);
  const [conversation, set_conversation] = useState<Conversation | null>(null);
  const selected_id = useRef<string | null>(null);
  const snapshot_revision = useRef(0);
  const [history_refresh, set_history_refresh] = useState(0);
  const [run, set_run] = useState<Run | null>(null);
  const [approval, set_approval] = useState<Approval | null>(null);
  const [text, set_text] = useState('');
  const [now, set_now] = useState(Date.now());
  const clear_private = useCallback(() => { if (locked.current || main_unknown.current || execution_locked.current || control_unknown_ref.current || unresolved_work.current || Object.values(pending_domains.current).some(Boolean)) recovery_boundary_lost.current = true; execution_session.current++; control_unknown_ref.current = false; set_control_outcome_unknown(false); identity_rotating.current = false; identity_unknown_ref.current = false; set_identity_pending(false); set_identity_unknown(false); execution_locked.current = false; account_blocked.current = true; unresolved_work.current = false; main_ticket.current++; locked.current = false; set_busy(false); set_execution_state(null); set_execution_pending(false); set_workspace_suspended(false); set_interrupted_work(false); pending_domains.current = { task: false, project: false, memory: false, profile: false, schedule: false, notification: false, mail_draft: false, memory_export: false, conversation_deletion: false }; domain_locked.current = false; navigation_paused.current = false; auth_generation.current++; generation.current++; client.clear(); set_authenticated(false); set_tasks_open(false); set_projects_open(false); set_memories_open(false); set_learning_open(false); memory_editor.current = null; memory_editor_epoch.current++; set_profile_open(false); set_schedules_open(false); set_notifications_open(false); set_mail_drafts_open(false); set_audit_history_open(false); set_usage_open(false); set_deletion_open(false); set_export_open(false); deletion_hidden.current = false; set_deletion_source_hidden(false); set_deletion_pending(false); set_export_pending(false); set_mail_draft_pending(false); set_schedule_pending(false); set_notification_pending(false); set_profile_pending(false); set_memory_pending(false); set_task_pending(false); set_project_pending(false); set_new_project(null); set_runtime(null); set_runtime_checked_at(0); set_notice(''); set_conversation(null); selected_id.current = null; snapshot_revision.current++; set_run(null); set_approval(null); set_text(''); set_uncertain(false); set_reauth_required(false); }, [client]);
  const conversation_deleted = useCallback((id: string) => {
    if (id !== selected_id.current) return;
    generation.current++; snapshot_revision.current++; main_ticket.current++; selected_id.current = null;
    set_conversation(null); set_run(null); set_approval(null); set_text(''); set_uncertain(false);
    set_reauth_required(false); set_notice('대화 원문 삭제가 확인되었습니다. 별도로 저장한 기억과 이미 전달된 사본은 삭제 대상이 아닙니다.');
    set_history_refresh(value => value + 1);
  }, []);
  const execution_state_changed = useCallback((state: ExecutionState | null) => { account_blocked.current = identity_unknown_ref.current || state?.execution_paused !== false; set_execution_state(identity_unknown_ref.current ? null : state); }, []);
  const execution_outcome_unknown = useCallback(() => { control_unknown_ref.current = true; set_control_outcome_unknown(true); }, []);
  const execution_identity_unknown = useCallback(() => { identity_unknown_ref.current = true; set_identity_unknown(true); account_blocked.current = true; set_execution_state(null); }, []);
  const execution_boundary_start = useCallback(() => {
    if (identity_rotating.current || identity_unknown_ref.current || execution_locked.current) return false;
    // A session/epoch boundary makes old private recovery metadata unusable, not resolved.
    if (locked.current || main_unknown.current || Object.values(pending_domains.current).some(Boolean)) { unresolved_work.current = true; set_interrupted_work(true); recovery_boundary_lost.current = true; }
    client.fence_requests();
    execution_locked.current = true; set_execution_pending(true); set_workspace_suspended(true);
    auth_generation.current++; generation.current++; snapshot_revision.current++; main_ticket.current++;
    locked.current = false; set_busy(false); set_approval(null); set_reauth_required(false);
    pending_domains.current = { task: false, project: false, memory: false, profile: false, schedule: false, notification: false, mail_draft: false, memory_export: false, conversation_deletion: false };
    domain_locked.current = false; deletion_hidden.current = false; set_deletion_source_hidden(false); set_deletion_pending(false); set_export_pending(false); set_task_pending(false); set_project_pending(false); set_memory_pending(false); set_profile_pending(false); set_schedule_pending(false); set_notification_pending(false); set_mail_draft_pending(false);
    return true;
  }, [client]);
  const execution_boundary_end = useCallback(() => { execution_locked.current = false; set_execution_pending(false); set_workspace_suspended(false); set_history_refresh(value => value + 1); }, []);
  const fail = useCallback((error: unknown) => { if (error instanceof ApiError) { if (error.status === 410 && error.code === 'CONVERSATION_DELETED' && selected_id.current) { conversation_deleted(selected_id.current); return; } if (error.status === 401) clear_private(); if (error.uncertain) set_uncertain(true); if (error.code === 'REAUTHENTICATION_REQUIRED') set_reauth_required(true); if (error.status === 409) set_approval(null); if (error.code === 'MODEL_UNAVAILABLE' || error.code === 'LOCAL_MODEL_UNAVAILABLE') set_runtime(null); set_notice(error.uncertain ? 'UNKNOWN: The request may have reached the server. Writes are paused. Reconcile server state; do not resend.' : /^[A-Z][A-Z0-9_]{0,63}$/.test(error.code) ? error.code : 'REQUEST_REJECTED'); } else set_notice('BLOCKED: Invalid server data or unavailable operation.'); }, [clear_private, conversation_deleted]);
  const refresh_runtime = useCallback(async () => {
    if (execution_locked.current || identity_rotating.current) return;
    const current = generation.current, session_epoch = auth_generation.current;
    try { const status = read_runtime(await client.request<unknown>('/local-model/status')); if (current !== generation.current) return; set_runtime(status); set_runtime_checked_at(Date.now()); }
    catch (error) { if (session_epoch === auth_generation.current && error instanceof ApiError && error.status === 401) { clear_private(); return; } if (current !== generation.current) return; set_runtime(null); }
  }, [client, clear_private]);
  const refresh = useCallback(async () => { set_history_refresh(value => value + 1); await refresh_runtime(); }, [refresh_runtime]);
  const select_conversation = useCallback((selected: Conversation) => {
    if (locked.current || execution_locked.current || navigation_paused.current || !UUID.test(selected.id) || selected.id === selected_id.current) return;
    generation.current++; snapshot_revision.current++; selected_id.current = selected.id;
    set_conversation(selected); set_run(null); set_approval(null); set_text(''); set_notice('');
  }, []);
  const accept_snapshot = useCallback((id: string, page: HistoryPage) => {
    if (id !== selected_id.current || deletion_hidden.current) return;
    const current = generation.current, session_epoch = auth_generation.current, revision = ++snapshot_revision.current;
    set_run(page.current_run); set_approval(null);
    const approval_id = page.current_run?.approval_id;
    if (!approval_id) return;
    if (!UUID.test(approval_id)) { fail(new Error('INVALID_SNAPSHOT')); return; }
    void client.request<Approval>(`/approvals/${approval_id}`).then(snapshot => {
      if (current !== generation.current || revision !== snapshot_revision.current || id !== selected_id.current) return;
      if (!valid_snapshot(snapshot) || snapshot.id !== approval_id) throw new Error('INVALID_SNAPSHOT');
      set_approval(snapshot);
    }).catch(error => { if (session_epoch === auth_generation.current && error instanceof ApiError && error.status === 401) { clear_private(); return; } if (current === generation.current && revision === snapshot_revision.current && id === selected_id.current) fail(error); });
  }, [client, fail, clear_private]);
  useEffect(() => { let live = true; const current = generation.current; void (async () => { try { await client.bootstrap_csrf(); if (!live || current !== generation.current) return; await client.request('/me'); if (live && current === generation.current) set_authenticated(true); } catch (error) { if (live && current === generation.current && !(error instanceof ApiError && error.status === 401)) fail(error); } finally { if (live) set_ready(true); } })(); return () => { live = false; main_ticket.current++; execution_session.current++; auth_generation.current++; generation.current++; client.clear(); }; }, [client, fail]);
  useEffect(() => { if (authenticated) void refresh(); }, [authenticated, refresh]);
  useEffect(() => { if (!authenticated) return; const timer = setInterval(() => { set_now(Date.now()); void refresh_runtime(); }, 3000); return () => clearInterval(timer); }, [authenticated, refresh_runtime]);
  // Clear private state on page disposal and never persist it in CacheStorage/IDB/Web Storage.
  useEffect(() => { const hide = () => flushSync(() => clear_private()); window.addEventListener('pagehide', hide); return () => window.removeEventListener('pagehide', hide); }, [clear_private]);
  async function mutate(operation: (current: number) => Promise<void>, allowDomainPending = false) { if (locked.current || execution_locked.current || domain_locked.current && !allowDomainPending) return; const current = generation.current, ticket = ++main_ticket.current; locked.current = true; set_busy(true); set_notice(''); try { await operation(current); } catch (error) { if (current === generation.current) fail(error); } finally { if (ticket === main_ticket.current) { locked.current = false; set_busy(false); } } }
  function login(event: FormEvent<HTMLFormElement>) { event.preventDefault(); const form = event.currentTarget; const data = new FormData(form); const login_name = String(data.get('login_name') ?? ''); const password = String(data.get('password') ?? ''); form.reset(); void mutate(async current => { await client.bootstrap_csrf(); if (current !== generation.current) return; const result = await client.request<{ csrf_token: string }>('/auth/login', { login_name, password }); if (current !== generation.current) return; client.set_csrf(result.csrf_token); set_authenticated(true); set_uncertain(false); if (recovery_boundary_lost.current) set_notice('A previous request may still have committed. Private recovery identifiers were cleared at sign-out. Review saved data before making a new change; no request has been repeated automatically.'); }); }
  main_unknown.current = uncertain || run?.status === 'UNKNOWN';
  const outcome_paused = main_unknown.current || interrupted_work;
  const writes_paused = outcome_paused || execution_state?.execution_paused !== false || execution_pending;
  const domain_pending = task_pending || project_pending || memory_pending || profile_pending || schedule_pending || notification_pending || mail_draft_pending || export_pending || deletion_pending;
  const deletion_session = auth_generation.current;
  const navigation_disabled = busy || outcome_paused || domain_pending || execution_pending;
  main_navigation_paused.current = busy || outcome_paused || execution_pending; domain_locked.current = Object.values(pending_domains.current).some(Boolean); navigation_paused.current = main_navigation_paused.current || domain_locked.current;
  const local_ready = runtime?.ready === true && runtime.status === 'READY' && Math.max(now, Date.now()) - runtime_checked_at < 10000;
  function send(event: FormEvent, local = true) { event.preventDefault(); if (writes_paused || account_blocked.current || execution_locked.current || (local && !local_ready) || !text.trim()) return; if (local && Date.now() - runtime_checked_at >= 10000) { set_runtime(null); set_notice('Runtime status is stale. Refresh server state before sending.'); return; } if (local ? [...text].length > 8000 : new TextEncoder().encode(text).length > 16000) { set_notice(local ? 'Message exceeds the 8000-character local input limit.' : 'Message exceeds the 16000-byte mock input limit.'); return; } const message = text; const current = generation.current; void mutate(async () => { let selected = conversation; if (!selected) { selected = await client.request<Conversation>('/conversations', { title: 'Operator session', project_id: new_project?.id ?? null }, crypto.randomUUID()); if (current !== generation.current) return; if (!UUID.test(selected.id)) throw new Error('INVALID_CONVERSATION'); selected_id.current = selected.id; set_conversation(selected); } if (!UUID.test(selected.id)) throw new Error('INVALID_CONVERSATION'); await client.request(`/conversations/${selected.id}/${local ? 'local-inference' : 'messages'}`, { client_message_id: crypto.randomUUID(), text: message, ...(local ? {} : { attachment_ids: [] }) }); if (current !== generation.current) return; set_text(''); await refresh(); }); }
  function decide(decision: 'APPROVE' | 'REJECT') { if (!approval || writes_paused || account_blocked.current || execution_locked.current) return; const snapshot = approval; void mutate(async current => { await client.request(`/approvals/${snapshot.id}/decision`, decision_body(snapshot, decision), crypto.randomUUID()); if (current === generation.current) await refresh(); }); }
  function cancel() { if (!run || writes_paused || account_blocked.current || execution_locked.current) return; const current = run; void mutate(async epoch => { await client.request(`/runs/${current.id}/cancel`, { expected_version: current.version }, crypto.randomUUID()); if (epoch === generation.current) await refresh(); }); }
  function reauthenticate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (locked.current || execution_locked.current || identity_rotating.current || identity_unknown_ref.current || writes_paused || domain_locked.current) { set_notice('Resolve pending requests before confirming identity. Reauthentication would rotate the session.'); return; }
    const form = event.currentTarget, password = String(new FormData(form).get('password') ?? ''); form.reset();
    client.fence_requests(); identity_rotating.current = true; set_identity_pending(true); set_workspace_suspended(true);
    execution_session.current++; auth_generation.current++; generation.current++; snapshot_revision.current++;
    account_blocked.current = true; set_execution_state(null); set_approval(null);
    const current = generation.current, ticket = ++main_ticket.current;
    locked.current = true; set_busy(true); set_notice('');
    void (async () => {
      try {
        const result = await client.request<unknown>('/auth/reauthenticate', { password });
        if (current !== generation.current) return;
        if (!result || typeof result !== 'object' || Array.isArray(result) || Object.keys(result).length !== 2 || !Object.hasOwn(result, 'csrf_token') || !Object.hasOwn(result, 'authenticated') || (result as { authenticated?: unknown }).authenticated !== true || typeof (result as { csrf_token?: unknown }).csrf_token !== 'string' || !(result as { csrf_token: string }).csrf_token || (result as { csrf_token: string }).csrf_token.length > 4096) throw new ApiError('UNKNOWN', true);
        client.set_csrf((result as { csrf_token: string }).csrf_token); set_reauth_required(false);
        set_notice('Identity confirmed. Review the current snapshot before deciding.');
      } catch (error) {
        if (current !== generation.current) return;
        if (error instanceof ApiError && error.status === 401) { clear_private(); return; }
        if (!(error instanceof ApiError) || error.uncertain) { execution_identity_unknown(); set_uncertain(true); set_notice('UNKNOWN: identity confirmation may have rotated this session. Sign out and sign in explicitly before any new write or identity confirmation. Nothing is retried.'); }
        else fail(error);
      } finally {
        if (ticket === main_ticket.current) { identity_rotating.current = false; set_identity_pending(false); locked.current = false; set_busy(false); set_workspace_suspended(false); if (!identity_unknown_ref.current) void refresh(); }
      }
    })();
  }
  function reconcile_mock() { if (execution_locked.current || account_blocked.current || !run || run.execution_mode !== 'MOCK' || run.status !== 'UNKNOWN') return; const selected = run; void mutate(async current => { await client.request(`/runs/${selected.id}/reconcile`, {}); if (current === generation.current) await refresh(); }); }
  function start_new_conversation() {
    if (locked.current || execution_locked.current || navigation_paused.current) return;
    generation.current++; snapshot_revision.current++; selected_id.current = null;
    set_conversation(null); set_run(null); set_approval(null); set_text(''); set_notice('');
  }
  function logout() {
    if (locked.current || execution_locked.current) return;
    const request = client.request('/auth/logout', {}); clear_private();
    const session = auth_generation.current, ticket = ++main_ticket.current;
    locked.current = true; set_busy(true); set_notice('Local private view cleared. Waiting for the server sign-out result.');
    void request.then(result => { if (!result || typeof result !== 'object' || Array.isArray(result) || Object.keys(result).length !== 1 || !Object.hasOwn(result, 'logged_out') || (result as { logged_out?: unknown }).logged_out !== true) throw new Error('SIGN_OUT_NOT_VERIFIED'); if (session === auth_generation.current) set_notice('The server accepted the sign-out request.'); }).catch(() => { if (session === auth_generation.current) set_notice('Local private view cleared. Server session termination is unconfirmed; no sign-out request was repeated.'); }).finally(() => { if (ticket === main_ticket.current) { locked.current = false; set_busy(false); } });
  }
  function open_memory_editor(editor: 'generic' | 'learning') {
    if (locked.current || execution_locked.current || navigation_paused.current || memory_editor.current === editor) return;
    // Change ownership synchronously: an outgoing handler cannot acquire the shared domain
    // in the same event batch, and outgoing cleanup cannot release the new owner's lock.
    memory_editor_epoch.current++; memory_editor.current = editor;
    set_memories_open(editor === 'generic'); set_learning_open(editor === 'learning');
  }
  const acquire_memory_editor = (editor: 'generic' | 'learning') => editor_epoch === memory_editor_epoch.current && memory_editor.current === editor && acquire_memory();
  const memory_editor_pending = (editor: 'generic' | 'learning', pending: boolean) => { if (editor_epoch === memory_editor_epoch.current && memory_editor.current === editor) memory_pending_changed(pending); };
  function request_memory_reauthentication() { if (locked.current || writes_paused || domain_locked.current) return; set_reauth_required(true); set_notice('Confirm your identity, then select the memory and prepare a fresh deletion preview.'); }
  function request_schedule_reauthentication() { if (locked.current || writes_paused || domain_locked.current) return; set_reauth_required(true); set_notice('Confirm your identity, then prepare and review a fresh reminder preview. No reminder confirmation is repeated automatically.'); }
  return <main>
    <header><div><span className="eyebrow">MY JARVIS / LOCAL TEXT</span><h1>Operator console</h1></div><span className="badge">Server-reported state</span></header>
    <p className="boundary">Local text inference requires a ready server runtime. Mock demonstrations remain separate. Calendar, external mail, and device integrations are not enabled here. Internal mail drafts are saved locally and are never sent. No additional model training. This is not a production release.</p>
    {notice && <div className="notice" role="alert">{notice}</div>}
    {!authenticated ? <section className="panel login"><h2>Owner sign in</h2><p>Use an owner account provisioned through the server’s local administration flow.</p><form onSubmit={login}><label>Login name<input name="login_name" autoComplete="username" required maxLength={128} disabled={!ready || busy}/></label><label>Password<input name="password" type="password" autoComplete="current-password" required maxLength={256} disabled={!ready || busy}/></label><button disabled={!ready || busy}>{ready ? 'Sign in' : 'Connecting securely…'}</button></form></section> : <>
      <nav aria-label="Session controls"><span>Session active</span><button className="secondary" disabled={busy || execution_pending} onClick={() => { set_execution_refresh(value => value + 1); void refresh(); }}>Refresh server state</button><button className="secondary" disabled={busy || execution_pending} onClick={logout}>Sign out</button></nav>
      <ExecutionControlsPanel api={client} sessionKey={execution_session.current} refreshKey={execution_refresh} onUnauthorized={clear_private} onStateChange={execution_state_changed} onBoundaryStart={execution_boundary_start} onBoundaryEnd={execution_boundary_end} unresolvedWork={interrupted_work} identityPending={identity_pending} identityUnknown={identity_unknown} onIdentityUnknown={execution_identity_unknown} outcomeUnknown={control_outcome_unknown} onOutcomeUnknown={execution_outcome_unknown}/>
      {workspace_suspended && <p role="status">Session safety boundary in progress. Previous work is not cancelled or resolved by this view change.</p>}
      {!workspace_suspended && <>
      <TodayPanel key={`today:${auth_generation.current}`} api={client} sessionKey={auth_generation.current} onUnauthorized={clear_private} selectionDisabled={navigation_disabled}/>
      <button className="secondary" aria-expanded={audit_history_open} aria-controls="audit-history-region" onClick={() => set_audit_history_open(value => !value)}>{audit_history_open ? 'Close execution history' : 'Open execution history'}</button>
      {audit_history_open && <div id="audit-history-region"><AuditHistoryPanel key={auth_generation.current} api={client} sessionKey={auth_generation.current} onUnauthorized={clear_private}/></div>}
      <button className="secondary" aria-expanded={usage_open} aria-controls="usage-region" onClick={() => set_usage_open(value => !value)}>{usage_open ? '사용량 요약 닫기' : '사용량 요약 열기'}</button>
      {usage_open && <div id="usage-region"><UsagePanel key={auth_generation.current} api={client} sessionKey={auth_generation.current} onUnauthorized={clear_private}/></div>}
      <button className="secondary" aria-expanded={deletion_open} aria-controls="conversation-deletion-region" disabled={deletion_open && deletion_pending} onClick={() => { if (!pending_domains.current.conversation_deletion) set_deletion_open(value => !value); }}>{deletion_open ? '대화 원문 삭제 닫기' : '대화 원문 삭제 열기'}</button>
      {deletion_open && <div id="conversation-deletion-region"><ConversationDeletionPanel key={auth_generation.current} api={client} sessionKey={auth_generation.current} selectedConversationId={conversation?.id ?? null} snapshotKey={snapshot_revision.current} onUnauthorized={clear_private} onReauthenticate={() => { if (locked.current || writes_paused || domain_locked.current) return; set_reauth_required(true); set_notice('신원을 다시 확인한 뒤 삭제할 대화와 범위를 새로 검토하세요. 이전 삭제 확인은 재사용하지 않습니다.'); }} writesPaused={writes_paused || busy || task_pending || project_pending || memory_pending || profile_pending || schedule_pending || notification_pending || mail_draft_pending || export_pending} selectionDisabled={busy || outcome_paused || execution_pending || task_pending || project_pending || memory_pending || profile_pending || schedule_pending || notification_pending || mail_draft_pending || export_pending} onContentHiddenChange={hidden => { if (deletion_session === auth_generation.current) { deletion_hidden.current = hidden; set_deletion_source_hidden(hidden); } }} onAcquirePending={() => deletion_session === auth_generation.current && acquire_deletion()} onPendingChange={pending => { if (deletion_session === auth_generation.current) deletion_pending_changed(pending); }} onOutcomeUnknown={export_outcome_unknown} onDeleted={id => { if (deletion_session === auth_generation.current) conversation_deleted(id); }}/></div>}
      <button className="secondary" aria-expanded={export_open} aria-controls="memory-export-region" disabled={export_open && export_pending} onClick={() => { if (!pending_domains.current.memory_export) set_export_open(value => !value); }}>{export_open ? '기억 내보내기 닫기' : '기억 내보내기 열기'}</button>
      {export_open && <div id="memory-export-region"><p className="hint">프로젝트 범위 선택은 현재 대화의 프로젝트를 사용합니다. 대화가 없으면 새 대화용으로 선택한 프로젝트를 사용합니다. 전체 기억이 아니라 선택 범위의 현재 명시적 기억만 내보냅니다.</p><MemoryExportPanel key={auth_generation.current} api={client} sessionKey={auth_generation.current} selectedProjectId={conversation ? conversation.project_id : new_project?.id ?? null} onUnauthorized={clear_private} onReauthenticate={() => { if (locked.current || writes_paused || domain_locked.current) return; set_reauth_required(true); set_notice('Confirm your identity, then review the export scope and explicitly create a fresh export job. Previous jobs do not survive session rotation.'); }} writesPaused={deletion_pending || writes_paused || busy || task_pending || project_pending || memory_pending || profile_pending || schedule_pending || notification_pending || mail_draft_pending} selectionDisabled={deletion_pending || busy || outcome_paused || execution_pending || task_pending || project_pending || memory_pending || profile_pending || schedule_pending || notification_pending || mail_draft_pending} onAcquirePending={acquire_export} onPendingChange={export_pending_changed} onOutcomeUnknown={export_outcome_unknown}/></div>}
      <button className="secondary" disabled={tasks_open} onClick={() => set_tasks_open(true)}>Open internal tasks</button>
      {tasks_open && <TasksPanel key={`tasks:${auth_generation.current}`} api={client} onUnauthorized={clear_private} writesPaused={deletion_pending || export_pending || writes_paused || busy || memory_pending || project_pending || profile_pending || schedule_pending || notification_pending || mail_draft_pending} onPendingChange={task_pending_changed} onAcquirePending={acquire_task}/>}
      <button className="secondary" disabled={projects_open} onClick={() => set_projects_open(true)}>Choose project for a new conversation</button>
      {projects_open && <ProjectsPanel api={client} sessionKey={auth_generation.current} selectedProjectId={new_project?.id ?? null} onSelect={set_new_project} onUnauthorized={clear_private} writesPaused={deletion_pending || export_pending || writes_paused || busy || memory_pending || task_pending || profile_pending || schedule_pending || notification_pending || mail_draft_pending} selectionDisabled={navigation_disabled} onPendingChange={project_pending_changed} onAcquirePending={acquire_project}/>}
      <button className="secondary" disabled={profile_open} onClick={() => set_profile_open(true)}>Open owner settings</button>
      {profile_open && <ProfilePanel api={client} sessionKey={auth_generation.current} onUnauthorized={clear_private} writesPaused={deletion_pending || export_pending || writes_paused || busy || task_pending || project_pending || memory_pending || schedule_pending || notification_pending || mail_draft_pending} onPendingChange={profile_pending_changed} onAcquirePending={acquire_profile}/>}
      <p className="hint">Learning profile and Explicit memories edit the same saved records. Switching editors discards unsaved drafts and staged reviews.</p>
      <button className="secondary" disabled={learning_open || navigation_disabled} onClick={() => open_memory_editor('learning')}>Open learning profile</button>
      <button className="secondary" disabled={memories_open || navigation_disabled} onClick={() => open_memory_editor('generic')}>Open explicit memories</button>
      {memories_open && <MemoriesPanel api={client} sessionKey={auth_generation.current} selectedProjectId={conversation ? conversation.project_id : new_project?.id ?? null} onUnauthorized={clear_private} onReauthenticate={request_memory_reauthentication} writesPaused={deletion_pending || export_pending || writes_paused || busy || task_pending || project_pending || profile_pending || schedule_pending || notification_pending || mail_draft_pending} selectionDisabled={navigation_disabled} onPendingChange={pending => memory_editor_pending('generic', pending)} onAcquirePending={() => acquire_memory_editor('generic')}/>}
      {learning_open && <LearningProfilePanel key={`learning:${auth_generation.current}:${editor_epoch}`} api={client} sessionKey={auth_generation.current} selectedProjectId={conversation ? conversation.project_id : new_project?.id ?? null} onUnauthorized={clear_private} writesPaused={deletion_pending || export_pending || writes_paused || busy || task_pending || project_pending || profile_pending || schedule_pending || notification_pending || mail_draft_pending} selectionDisabled={navigation_disabled} onPendingChange={pending => memory_editor_pending('learning', pending)} onAcquirePending={() => acquire_memory_editor('learning')}/>}
      <button className="secondary" disabled={schedules_open} onClick={() => set_schedules_open(true)}>Open internal reminders</button>
      {schedules_open && <SchedulesPanel api={client} sessionKey={auth_generation.current} selectedProjectId={new_project?.id ?? null} onUnauthorized={clear_private} onReauthenticate={request_schedule_reauthentication} writesPaused={deletion_pending || export_pending || writes_paused || busy || task_pending || project_pending || memory_pending || profile_pending || notification_pending || mail_draft_pending} selectionDisabled={navigation_disabled} onPendingChange={schedule_pending_changed} onAcquirePending={acquire_schedule}/>}
      <button className="secondary" disabled={notifications_open} onClick={() => set_notifications_open(true)}>Open internal inbox</button>
      {notifications_open && <NotificationsPanel api={client} sessionKey={auth_generation.current} onUnauthorized={clear_private} writesPaused={deletion_pending || export_pending || writes_paused || busy || task_pending || project_pending || memory_pending || profile_pending || schedule_pending || mail_draft_pending} selectionDisabled={navigation_disabled} onPendingChange={notification_pending_changed} onAcquirePending={acquire_notification}/>}
      <button className="secondary" disabled={mail_drafts_open} onClick={() => set_mail_drafts_open(true)}>Open internal mail drafts</button>
      {mail_drafts_open && <MailDraftsPanel api={client} sessionKey={auth_generation.current} selectedProjectId={new_project?.id ?? null} onUnauthorized={clear_private} writesPaused={deletion_pending || export_pending || writes_paused || busy || task_pending || project_pending || memory_pending || profile_pending || schedule_pending || notification_pending} selectionDisabled={navigation_disabled} onPendingChange={mail_draft_pending_changed} onAcquirePending={acquire_mail_draft}/>}
      {busy && <p role="status">Processing the current request…</p>}
      {domain_pending && <p role="status">{deletion_pending ? 'A conversation deletion request is pending or unresolved.' : export_pending ? 'An explicit-memory export request is pending or unresolved.' : mail_draft_pending ? 'An internal mail draft request is pending.' : profile_pending ? 'An owner settings request is pending.' : schedule_pending || notification_pending ? 'A reminder or inbox request is pending.' : 'A task, project, or memory request is pending.'} Other changes and navigation are paused; any available outcome check remains enabled.</p>}
      <p className="hint">Next new conversation project: {new_project?.name ?? 'No project'}. Existing conversations keep their original project.</p>
      <button className="secondary" disabled={navigation_disabled || writes_paused} onClick={start_new_conversation}>Start new conversation</button>
      <section className="panel" aria-label="Local model runtime"><h2>Local model runtime</h2><span className="badge" role="status">{runtime?.status === 'READY' && !local_ready ? 'UNKNOWN' : runtime?.status ?? 'UNKNOWN'}</span><p>{local_ready ? 'The server reports the local worker is ready. Responses are generated only after you send a message.' : runtime?.status === 'LOADING' ? 'The local model is loading. Sending is disabled until the server confirms readiness.' : runtime?.status === 'DISABLED' ? 'Local inference is disabled on this server.' : runtime?.status === 'UNAVAILABLE' ? 'MODEL_UNAVAILABLE: the local runtime is unavailable.' : 'Runtime state could not be confirmed. Local sending is disabled.'}</p><p className="hint">No external AI fallback. Download, loading, and successful inference are separate states. No training progress is reported.</p></section>
      {reauth_required && <section className="panel"><h2>Confirm your identity</h2><p>Approval requires a recent password confirmation. The decision will not be retried automatically.</p><form onSubmit={reauthenticate}><label>Confirm password<input name="password" type="password" autoComplete="current-password" required maxLength={256} disabled={navigation_disabled}/></label><button disabled={navigation_disabled}>Confirm identity</button></form></section>}
      {outcome_paused && <section className="notice"><strong>Writes paused</strong><p>Refresh checks the canonical server state without repeating the request. Do not resend while the outcome is unknown. A new sign-in does not cancel a running request.</p></section>}
      {deletion_source_hidden ? <p role="status">삭제 결과 확인 중에는 선택한 대화의 원문과 실행 결과를 숨깁니다.</p> : <div className="workspace"><section className="panel"><h2>Conversation</h2><p className="hint">Send to the local model only when READY. The separate mock button uses synthetic fixtures (/calendar, /calendar-timeout, /deny) and never performs real provider actions.</p><ConversationHistoryPanel key={auth_generation.current} api={client} conversationId={conversation?.id ?? null} onSelect={select_conversation} onSnapshot={accept_snapshot} onDeleted={conversation_deleted} onUnauthorized={clear_private} refreshKey={history_refresh} selectionDisabled={navigation_disabled}/><form onSubmit={event => send(event)}><label>Message<textarea value={text} onChange={event => set_text(event.target.value)} maxLength={16000} disabled={navigation_disabled || writes_paused} rows={3}/></label><div className="actions"><button disabled={navigation_disabled || writes_paused || !local_ready || !text.trim()}>Send to local model</button><button type="button" className="secondary" disabled={navigation_disabled || writes_paused || !text.trim()} onClick={event => send(event, false)}>Send mock message</button></div></form></section>
      <aside><section className="panel"><h2>Server execution state</h2>{run ? <><span className="badge">{run.status}</span><dl><dt>Run</dt><dd>{run.id}</dd><dt>Version</dt><dd>{run.version}</dd></dl>{run.status === 'QUEUED' && <p>Waiting for a local worker slot. Generation has not completed.</p>}{run.status === 'RUNNING' && <p>The server is processing this request. No completion has been confirmed.</p>}{run.status === 'CANCEL_REQUESTED' && <p>Cancellation requested; worker termination is not yet confirmed.</p>}{run.status === 'CANCELLED' && <p>The server reports this run cancelled.</p>}{run.execution_mode && <p className="hint">Mode: {run.execution_mode}</p>}{run.result && <p className="result">{run.result}</p>}<p className="hint">This status is read from the server. A generated answer is not proof of any external action; mock results prove only fixture outcomes.</p>{run.status === 'UNKNOWN' && <><p className="notice">UNKNOWN: outcome not confirmed. No action is repeated.</p>{run.execution_mode === 'MOCK' && <button className="secondary" disabled={busy || domain_pending || execution_pending || account_blocked.current} onClick={reconcile_mock}>Reconcile mock outcome</button>}</>}{!terminal.has(run.status) && <button className="secondary" disabled={navigation_disabled || writes_paused} onClick={cancel}>Cancel run</button>}</> : <p className="empty">No run selected.</p>}</section>
      {approval && <section className="panel approval"><h2>Review exact server snapshot</h2><span className="badge">{approval.status}</span><dl><dt>Action version</dt><dd>{approval.action_version}</dd><dt>Expires</dt><dd>{approval.expires_at}</dd><dt>SHA-256 payload hash</dt><dd>{approval.payload_hash}</dd></dl><pre aria-label="Frozen approval payload">{JSON.stringify(approval.payload, null, 2)}</pre><p className="hint">Approval applies to this exact snapshot once. Content cannot be edited here.</p><div className="actions"><button disabled={navigation_disabled || writes_paused || reauth_required || !can_decide(approval, now)} onClick={() => decide('APPROVE')}>Approve exact action</button><button className="secondary" disabled={navigation_disabled || writes_paused || reauth_required || !can_decide(approval, now)} onClick={() => decide('REJECT')}>Reject action</button></div>{!can_decide(approval, now) && <p>BLOCKED: this snapshot is expired, decided, or invalid.</p>}</section>}</aside></div>}
      </>}
    </>}
    <footer>Untrusted text stays text. No private offline cache. Server policy remains authoritative.</footer>
  </main>;
}
