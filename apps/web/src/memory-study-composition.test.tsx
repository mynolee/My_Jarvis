import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api, ApiError } from './api';
import type { Memory, MemoryArguments, MemoryIdentity, MemoryIntent, MemoryReceipt } from './memories';
import type { DurableStudy } from './study';
import { deferred, outcome as feedbackOutcome, pending as feedbackPending, projection, queued, saved } from '../tests/study-feedback-fixtures';
import { accepted, at, cid, input, other, q1, run, sid, study } from '../tests/study-fixtures';

// Real App, memory, study, feedback, export, deletion, and project components.
// Only transport and the unrelated Today reader/history event subscription are replaced.
vi.mock('./TodayPanel', () => ({ TodayPanel: () => <section aria-label="Synthetic Today"/> }));
vi.mock('./conversation-history-events', () => ({ watch_history_events: () => () => {} }));

type Editor = 'generic' | 'learning';
type MemoryAction = 'generic save' | 'generic deletion' | 'learning save';
const editors = ['generic', 'learning'] as const;
const actions = ['generic save', 'generic deletion', 'learning save'] as const;
const memoryId = 'b1111111-1111-4111-8111-111111111111';
const intentId = 'b2222222-2222-4222-8222-222222222222';
const projectId = 'b3333333-3333-4333-8333-333333333333';
const nextProjectId = 'b4444444-4444-4444-8444-444444444444';
const preparationId = 'b5555555-5555-4555-8555-555555555555';
const privateValue = 'Synthetic composed learning value';
const privateReason = 'Synthetic explicit learning reason';
const originalValue = 'Synthetic canonical saved level';
const lateValue = 'Synthetic retired response value';
const expires = '2099-01-01T00:00:00Z';
const notObserved: MemoryReceipt = { status: 'NOT_OBSERVED', memory_id: null, version: null, generation: null, operation: null };
const original = (): Memory => ({ id: memoryId, scope: 'GLOBAL', project_id: null, key: 'learning.level', kind: 'PROFILE', value: originalValue, change_reason: 'Synthetic original reason', source: { type: 'EXPLICIT_OWNER_FORM', submission_id: intentId, submitted_at: at, source_hash: 'a'.repeat(64) }, version: 1, generation: 1, confirmed_at: at, created_at: at, updated_at: at });
const project = (id: string) => ({ id, name: id === projectId ? 'Current project' : 'Next project', description: '', active: true, version: 1, created_at: at });
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name });
const click = (name: string) => fireEvent.click(button(name));
// Retain only the DOM element's rendered public event handler. This exercises
// stale UI handlers after React removes the element, without exposing component
// internals, invoking App callbacks, or replacing a component under test.
function retainAction(element: HTMLButtonElement): () => void {
  const props = (node: HTMLElement): Record<string, unknown> => {
    const key = Object.keys(node).find(name => name.startsWith('__reactProps$'));
    if (!key) throw new Error('Rendered React event props were not found');
    return (node as unknown as Record<string, Record<string, unknown>>)[key];
  };
  const onClick = props(element).onClick;
  if (typeof onClick === 'function') return () => { onClick({ preventDefault() {}, currentTarget: element, target: element }); };
  const form = element.closest('form');
  if (!form) throw new Error('Rendered public action has no click or submit handler');
  const onSubmit = props(form).onSubmit;
  if (typeof onSubmit !== 'function') throw new Error('Rendered submit handler was not found');
  return () => { onSubmit({ preventDefault() {}, currentTarget: form, target: element }); };
}
const change = (name: string, value: string) => fireEvent.change(screen.getByLabelText(name), { target: { value } });
const toggleName = (editor: Editor) => editor === 'generic' ? 'Open explicit memories' : 'Open learning profile';
const regionName = (editor: Editor) => editor === 'generic' ? 'Explicit memories' : 'Learning profile';
const otherEditor = (editor: Editor): Editor => editor === 'generic' ? 'learning' : 'generic';
const confirmName = (editor: Editor) => editor === 'generic' ? 'Confirm exact memory save' : 'Confirm exact learning field save';
const receiptName = (editor: Editor) => editor === 'generic' ? 'Check memory outcome' : 'Check learning save outcome';
const valueLabel = (editor: Editor) => editor === 'generic' ? 'Memory value' : 'Learning value';
const reasonLabel = (editor: Editor) => editor === 'generic' ? 'Change reason' : 'Learning change reason';
const reviewName = (editor: Editor) => editor === 'generic' ? 'Review memory save' : 'Review learning field save';
const refreshName = (editor: Editor) => editor === 'generic' ? 'Refresh memories' : 'Refresh learning profile';
const actionEditor = (action: MemoryAction): Editor => action === 'learning save' ? 'learning' : 'generic';
const actionConfirm = (action: MemoryAction) => action === 'generic deletion' ? 'Delete this memory and revisions' : confirmName(actionEditor(action));

function fixture(options: { paused?: boolean; project?: boolean; mode?: 'saved' | 'answer' | 'start' } = {}) {
  const api = new Api();
  const state = {
    authenticated: true, paused: options.paused ?? false, executionVersion: 1,
    conversationProject: options.project ? projectId : null,
    memory: original(), memoryDeleted: false, memoryRead: null as Promise<unknown> | null,
    listRead: null as Promise<unknown> | null, intent: null as Promise<unknown> | null,
    memoryWrite: null as Promise<unknown> | null, memoryReceipt: notObserved as unknown,
    current: (options.mode === 'answer' ? study() : saved()) as DurableStudy,
    mode: options.mode ?? 'saved', feedback: projection(), feedbackRead: null as Promise<unknown> | null,
    feedbackWrite: null as Promise<unknown> | null, feedbackReceipt: { status: 'NOT_OBSERVED', receipt: null, checked_at: at } as unknown,
    studyRead: null as Promise<unknown> | null, studyWrite: null as Promise<unknown> | null,
    historyRead: null as Promise<unknown> | null, conversationsRead: null as Promise<unknown> | null, conversationDeleted: false, idleReads: 0, heldHistoryReads: 0,
    exportWrite: null as Promise<unknown> | null, deletionWrite: null as Promise<unknown> | null,
    reauth: null as Promise<unknown> | null, failMemory: false,
  };
  if (state.conversationProject) { state.memory = { ...state.memory, scope: 'PROJECT', project_id: projectId }; state.current = { ...state.current, project_id: projectId }; }
  const conversations = () => ({ items: [...(state.conversationDeleted ? [] : [{ id: cid, title: 'Feedback conversation', project_id: state.conversationProject, created_at: at }]), { id: other, title: 'Other conversation', project_id: null, created_at: at }], next_cursor: null });
  const intent = (operation = 'REVISE', generation = 1): MemoryIntent => ({ id: intentId, tool_id: 'memory.write', operation: operation as 'REVISE' | 'CREATE', target_id: memoryId, expected_generation: generation, payload_hash: 'b'.repeat(64), expires_at: expires, status: 'ISSUED' });
  const memoryReceipt = (): MemoryReceipt => ({ status: 'COMMITTED', memory_id: memoryId, version: state.memory.version, generation: state.memory.generation, operation: 'REVISE' });
  const deletionState = () => ({ status: 'ACTIVE', conversation_id: cid, content_version: 13, content_digest: 'a'.repeat(64), eligibility: { status: 'ELIGIBLE', blockers: [] }, checked_at: at });
  const deletionPreparation = () => ({ id: preparationId, status: 'PREPARED', scope_policy: 'CONVERSATION_SOURCE_V1', conversation: { id: cid, title: 'Feedback conversation', project_id: state.conversationProject, created_at: at }, expected_version: 13, expected_digest: 'a'.repeat(64), source_counts: { messages: 0, runs: 1, run_results: 1, action_payloads: 0, inference_contexts: 0, study_sessions: 1, study_answers: 2, feedback_requests: 0, feedback_results: 0 }, payload_hash: 'b'.repeat(64), prepared_at: at, expires_at: '2026-10-11T00:05:00Z' });
  vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation(async (path, body, key) => {
    const route = path.split('?')[0];
    if (route === '/me') { if (!state.authenticated) throw new ApiError('UNAUTHENTICATED', false, 401); return {}; }
    if (route === '/auth/logout') { state.authenticated = false; return { logged_out: true }; }
    if (route === '/auth/login') { state.authenticated = true; return { csrf_token: 'synthetic-new-session' }; }
    if (route === '/auth/reauthenticate') return state.reauth ?? { csrf_token: 'synthetic-rotated-session', authenticated: true };
    if (route === '/execution') return { execution_paused: state.paused, version: state.executionVersion, checked_at: at };
    if (route === '/execution/pause' || route === '/execution/resume') { state.paused = route.endsWith('/pause'); state.executionVersion++; return { execution_paused: state.paused, version: state.executionVersion, checked_at: at }; }
    if (route === '/local-model/status') return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', status: 'READY', ready: true };
    if (route === '/conversations') return state.conversationsRead ?? conversations();
    if (route.endsWith('/messages') && body === undefined) { if (state.historyRead) { state.heldHistoryReads++; return state.historyRead; } return { items: [], next_cursor: null, stream_cursor: null, current_run: null }; }
    if (route === '/projects') return { items: [project(projectId), project(nextProjectId)], next_cursor: null };
    if (route === `/projects/${projectId}` || route === `/projects/${nextProjectId}`) return project(route.split('/').at(-1)!);
    if (route === '/tasks') return { items: [], next_cursor: null };
    if (route === '/memories' && body === undefined) {
      if (state.listRead) { state.idleReads++; return state.listRead; }
      const query = new URLSearchParams(path.split('?')[1]);
      return { items: !state.memoryDeleted && query.get('scope') === state.memory.scope && (query.get('project_id') ?? null) === state.memory.project_id ? [state.memory] : [], next_cursor: null };
    }
    if (route === '/memories/key-state') {
      if (state.failMemory) throw new ApiError('MEMORY_DELETED', false, 410);
      const identity = body as MemoryIdentity;
      const matches = identity.scope === state.memory.scope && identity.project_id === state.memory.project_id && identity.key === state.memory.key;
      return matches ? state.memoryDeleted ? { status: 'DELETED', generation: state.memory.generation, memory_id: null, version: null } : { status: 'ACTIVE', generation: state.memory.generation, memory_id: memoryId, version: state.memory.version } : { status: 'ABSENT', generation: 0, memory_id: null, version: null };
    }
    if (route === `/memories/${memoryId}` && body === undefined) { if (state.failMemory) throw new ApiError('MEMORY_DELETED', false, 410); if (state.memoryRead) { state.idleReads++; return state.memoryRead; } return state.memory; }
    if (route === `/memories/${memoryId}/revisions`) return { items: [{ memory_id: memoryId, version: state.memory.version, value: 'Synthetic saved revision', change_reason: 'Synthetic revision reason', source: state.memory.source, confirmed_at: at }], next_cursor: null };
    if (route === '/memory-intents') { const issue = body as { operation: string; arguments: MemoryArguments }; return state.intent ?? intent(issue.operation, issue.arguments.expected_generation); }
    if ((route === `/memories/${memoryId}` || route === '/memories') && body !== undefined) {
      if (state.memoryWrite) return state.memoryWrite;
      const args = (body as { arguments: MemoryArguments }).arguments;
      state.memory = { ...state.memory, ...args, version: state.memory.version + 1 };
      // The wire response keeps only the closed Memory shape, not request preconditions.
      state.memory = { ...original(), scope: state.memory.scope, project_id: state.memory.project_id, value: args.value, change_reason: args.change_reason, version: state.memory.version };
      return memoryReceipt();
    }
    if (route === `/memories/${memoryId}/delete-preparations`) return { id: intentId, memory: state.memory, payload_hash: 'c'.repeat(64), expected_version: state.memory.version, expected_generation: state.memory.generation, expires_at: expires, status: 'PREPARED' };
    if (route === `/memory-delete-preparations/${intentId}/confirm`) { if (state.memoryWrite) return state.memoryWrite; state.memoryDeleted = true; return { status: 'REDACTED_DELETED', memory_id: memoryId, version: state.memory.version, generation: state.memory.generation, operation: 'DELETE' }; }
    if (route.startsWith('/memory-mutations/')) return state.memoryReceipt;
    if (route.endsWith('/study-index')) return { conversation_id: route.includes(cid) ? cid : other, conversation_content_version: state.current.conversation_content_version, items: route.includes(cid) ? [{ lesson_run_id: run, lesson_run_version: 4, created_at: at, study_session_id: state.mode === 'start' ? null : sid, study_version: state.mode === 'start' ? null : state.current.version, lifecycle: state.mode === 'start' ? null : 'OPEN' }] : [], next_cursor: null, checked_at: at };
    if (route === `/runs/${run}/study-lesson`) return accepted;
    if (route === `/study-sessions/${sid}`) { if (state.conversationDeleted) throw new ApiError('CONVERSATION_DELETED', false, 410); return state.studyRead ?? state.current; }
    if (route === `/study-sessions/${sid}/feedback`) { if (state.conversationDeleted) throw new ApiError('CONVERSATION_DELETED', false, 410); return state.feedbackRead ?? state.feedback; }
    if (route.startsWith('/study-feedback-mutations/')) return state.feedbackReceipt;
    if (route.endsWith('/feedback-requests') || route.startsWith('/study-feedback-requests/') && route.endsWith('/revoke')) {
      if (state.feedbackWrite) return state.feedbackWrite;
      const revoking = route.endsWith('/revoke');
      state.feedback = revoking ? { ...projection({ ...queued(), feedback_version: 2, status: 'REVOKED', output_authority: 'REVOKED', accounting: 'UNSTARTED' }), conversation_version: 21 } : projection(queued());
      return feedbackOutcome({ ...feedbackPending(revoking), key: key! });
    }
    if (route.endsWith('/answers') || route.endsWith('/close') || route.endsWith('/study-sessions')) return state.studyWrite ?? Promise.reject(new ApiError('REQUEST_REJECTED', false, 409));
    if (route.startsWith('/study-mutations/')) return { status: 'NOT_OBSERVED', receipt: null, checked_at: at };
    if (route === '/exports') return state.exportWrite ?? Promise.reject(new ApiError('UNKNOWN', true));
    if (route.startsWith('/exports/requests/')) return { status: 'NOT_OBSERVED', job: null };
    if (route === `/conversations/${cid}/deletion-state`) return deletionState();
    if (route === `/conversations/${cid}/delete-preparations`) return deletionPreparation();
    if (route === `/conversation-delete-preparations/${preparationId}/confirm`) return state.deletionWrite ?? Promise.reject(new ApiError('UNKNOWN', true));
    if (route.startsWith('/conversation-deletions/requests/')) return { status: 'NOT_OBSERVED', receipt: null, checked_at: at };
    if (route.endsWith('/study-lessons')) throw new ApiError('MODEL_UNAVAILABLE', false, 409);
    throw new Error(`Unexpected synthetic composition route: ${route}`);
  });
  return { api, state, request, intent, memoryReceipt, conversations };
}
type Fixture = ReturnType<typeof fixture>;
const memoryWrites = (f: Fixture) => f.request.mock.calls.filter(([path, body]) => body !== undefined && (path === '/memories' || path === `/memories/${memoryId}` || path.startsWith('/memory-delete-preparations/')));
const feedbackWrites = (f: Fixture) => f.request.mock.calls.filter(([path, body]) => body !== undefined && (path.endsWith('/feedback-requests') || path.endsWith('/revoke')));
const studyWrites = (f: Fixture) => f.request.mock.calls.filter(([path, body]) => body !== undefined && (path.endsWith('/answers') || path.endsWith('/close') || path.endsWith('/study-sessions')));
async function open(f = fixture()) {
  render(<App api={f.api}/>);
  await screen.findByText(`Account state: ${f.state.paused ? 'PAUSED' : 'AVAILABLE FOR NEW WORK'}`);
  await screen.findByText('No saved messages.');
  await waitFor(() => expect(button('Find saved study lessons').disabled).toBe(false));
  return f;
}
async function resume(f: Fixture) {
  await waitFor(() => expect(button('Find saved study lessons').disabled).toBe(false));
  click('Find saved study lessons');
  const name = `${f.state.mode === 'start' ? 'View' : 'Resume'} lesson ${run}`;
  await screen.findByRole('button', { name }); click(name);
  await screen.findByText('Synthetic first question?');
  if (f.state.mode === 'saved') { const request = f.state.feedback.targets[0].request; if (request) await screen.findByText(`Request: ${request.status}`); else await screen.findByRole('button', { name: 'Request feedback for question 1' }); }
}
async function openEditor(editor: Editor) {
  click(toggleName(editor)); await screen.findByRole('region', { name: regionName(editor) });
  if (editor === 'learning') await waitFor(() => expect(button(refreshName(editor)).disabled).toBe(false));
  else await waitFor(() => expect(button('Refresh memories').disabled).toBe(false));
}
async function selectGeneric(f: Fixture) {
  const name = `learning.level · PROFILE · v${f.state.memory.version}`;
  await screen.findByRole('button', { name }); click(name);
  await screen.findByText(`Current saved value: ${f.state.memory.value}`);
  await waitFor(() => expect(button('Prepare memory deletion').disabled).toBe(false));
}
function fill(editor: Editor) { change(valueLabel(editor), privateValue); change(reasonLabel(editor), privateReason); }
async function stage(editor: Editor, f: Fixture) {
  if (editor === 'generic' && !screen.queryByRole('region', { name: 'Selected memory details' })) await selectGeneric(f);
  fill(editor); click(reviewName(editor));
  await screen.findByRole('button', { name: confirmName(editor) });
  await waitFor(() => expect(button(toggleName(otherEditor(editor))).disabled).toBe(false));
}
async function stageAction(action: MemoryAction, f: Fixture) {
  const editor = actionEditor(action); await openEditor(editor);
  if (action !== 'generic deletion') await stage(editor, f);
  else { await selectGeneric(f); click('Prepare memory deletion'); await screen.findByRole('region', { name: 'Confirm memory deletion' }); fireEvent.click(screen.getByLabelText('I confirm deletion of this exact memory and its revisions.')); await waitFor(() => expect(button(actionConfirm(action)).disabled).toBe(false)); }
}
async function unknownMemory(editor: Editor, f: Fixture) {
  const hold = deferred<unknown>(); f.state.memoryWrite = hold.promise;
  click(confirmName(editor)); await waitFor(() => expect(memoryWrites(f)).toHaveLength(1));
  await act(async () => hold.reject(new ApiError('UNKNOWN', true)));
  await screen.findByRole('button', { name: receiptName(editor) });
}
async function identityForm() {
  if (!screen.queryByRole('region', { name: '기억 내보내기' })) click('기억 내보내기 열기');
  click('내보내기 본인 확인'); await screen.findByLabelText('Confirm password');
  change('Confirm password', 'synthetic-password'); return screen.getByLabelText('Confirm password').closest('form')!;
}
async function login() {
  await screen.findByRole('heading', { name: 'Owner sign in' });
  await waitFor(() => expect(button('Sign in').disabled).toBe(false));
  change('Login name', 'synthetic-owner'); change('Password', 'synthetic-password'); click('Sign in');
  await screen.findByText('Account state: AVAILABLE FOR NEW WORK');
}
function pause() { fireEvent.click(screen.getByLabelText('I reviewed the account-wide impact for this checked version')); click('Pause account execution'); }
function assertNavigationBlocked(editor: Editor) {
  for (const name of [toggleName(otherEditor(editor)), 'Start new conversation', 'Other conversation', 'Find saved study lessons', 'Send mock message', 'Send to local model', 'Generate study lesson']) expect(button(name).disabled).toBe(true);
}
let network: ReturnType<typeof vi.fn>;
beforeEach(() => { network = vi.fn(() => Promise.reject(new Error('No real network allowed'))); vi.stubGlobal('fetch', network); });
afterEach(() => { cleanup(); expect(network).not.toHaveBeenCalled(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it.each(actions)('BC01: feedback wins the same event batch before %s', async action => {
  const f = await open(); await stageAction(action, f); await resume(f);
  const confirm = button(actionConfirm(action)), hold = deferred<unknown>(); f.state.feedbackWrite = hold.promise;
  act(() => { button('Request feedback for question 1').click(); confirm.click(); });
  expect(feedbackWrites(f)).toHaveLength(1); expect(memoryWrites(f)).toHaveLength(0);
  await act(async () => hold.reject(new ApiError('UNKNOWN', true)));
  await screen.findByRole('button', { name: 'Check original feedback outcome' });
  assertNavigationBlocked(actionEditor(action)); expect(screen.queryByRole('button', { name: receiptName(actionEditor(action)) })).toBeNull();
});

it.each(actions)('BC02: %s wins feedback and editor switching and retains UNKNOWN ownership', async action => {
  const f = await open(); await stageAction(action, f); await resume(f);
  const editor = actionEditor(action), confirm = button(actionConfirm(action)), feedback = button('Request feedback for question 1'), toggle = button(toggleName(otherEditor(editor)));
  const hold = deferred<unknown>(); f.state.memoryWrite = hold.promise;
  act(() => { confirm.click(); feedback.click(); toggle.click(); });
  await waitFor(() => expect(memoryWrites(f)).toHaveLength(1)); expect(feedbackWrites(f)).toHaveLength(0);
  expect(screen.getByRole('region', { name: regionName(editor) })).toBeTruthy(); expect(screen.queryByRole('region', { name: regionName(otherEditor(editor)) })).toBeNull();
  await act(async () => hold.reject(new ApiError('UNKNOWN', true)));
  await screen.findByRole('button', { name: receiptName(editor) }); assertNavigationBlocked(editor);
  expect(screen.getAllByRole('button', { name: receiptName(editor) })).toHaveLength(1);
});

it.each(editors)('BC03: %s stays fenced by feedback UNKNOWN and original GET-only recovery', async editor => {
  const f = await open(); await openEditor(editor); await stage(editor, f); await resume(f);
  const confirm = retainAction(button(confirmName(editor))), hold = deferred<unknown>(); f.state.feedbackWrite = hold.promise;
  click('Request feedback for question 1'); await act(async () => hold.reject(new ApiError('UNKNOWN', true)));
  await screen.findByRole('button', { name: 'Check original feedback outcome' });
  assertNavigationBlocked(editor); expect(button(toggleName(editor)).disabled).toBe(true); act(() => confirm());
  click('Check original feedback outcome'); await screen.findByText(/NOT_OBSERVED does not prove rollback/);
  assertNavigationBlocked(editor); expect(memoryWrites(f)).toHaveLength(0); expect(feedbackWrites(f)).toHaveLength(1);
  const originalKey = feedbackWrites(f)[0][2]!;
  const reads = f.request.mock.calls.filter(([path]) => path.startsWith('/study-feedback-mutations/'));
  expect(reads).toEqual([[`/study-feedback-mutations/${originalKey}`]]);
  f.state.feedbackReceipt = feedbackOutcome({ ...feedbackPending(), key: originalKey }); f.state.feedback = projection(queued());
  click('Check original feedback outcome'); await screen.findByText('Request: QUEUED');
  expect(feedbackWrites(f)).toHaveLength(1); expect(memoryWrites(f)).toHaveLength(0);
});

it.each(editors)('BC04: %s memory UNKNOWN keeps its original receipt through repeated checks and source refresh', async editor => {
  const f = await open(fixture({ project: true })); await openEditor(editor);
  change(editor === 'generic' ? 'Memory scope' : 'Learning scope', 'PROJECT');
  click('Choose project for a new conversation'); await screen.findByRole('option', { name: 'Next project' });
  change('New conversation project', nextProjectId); await screen.findByText(/Next new conversation project: Next project/);
  await stage(editor, f); await resume(f);
  const request = retainAction(button('Request feedback for question 1')), close = retainAction(button('Close study session')); 
  await unknownMemory(editor, f); assertNavigationBlocked(editor);
  act(() => { request(); close(); button(toggleName(otherEditor(editor))).click(); });
  const originalKey = memoryWrites(f)[0][2]!;
  for (let i = 0; i < 2; i++) { click(receiptName(editor)); await waitFor(() => expect(button(receiptName(editor)).disabled).toBe(false)); assertNavigationBlocked(editor); }
  click('Refresh server state'); await waitFor(() => expect(button('Refresh server state').disabled).toBe(false));
  click(receiptName(editor)); await waitFor(() => expect(button(receiptName(editor)).disabled).toBe(false));
  const reads = f.request.mock.calls.filter(([path]) => path.startsWith('/memory-mutations/'));
  expect(reads).toEqual(Array.from({ length: 3 }, () => [`/memory-mutations/${originalKey}`]));
  expect(memoryWrites(f)).toHaveLength(1); expect(feedbackWrites(f)).toHaveLength(0); expect(studyWrites(f)).toHaveLength(0);
  expect(screen.queryByText(/Save confirmed|This field save is confirmed/)).toBeNull(); assertNavigationBlocked(editor);
  // A canonical source-deletion response can force a real context transition
  // while optional navigation is fenced. The original memory receipt survives.
  const deletedSource = deferred<unknown>(); f.state.historyRead = deletedSource.promise;
  click('Refresh server state'); await waitFor(() => expect(f.state.heldHistoryReads).toBe(1));
  f.state.conversationDeleted = true; f.state.historyRead = null;
  await act(async () => deletedSource.reject(new ApiError('CONVERSATION_DELETED', false, 410)));
  await screen.findByText(/대화 원문 삭제가 확인되었습니다/);
  await screen.findByText(editor === 'generic' ? `Project: ${nextProjectId}` : `Selected project: ${nextProjectId}`);
  await waitFor(() => expect(screen.queryByText(editor === 'generic' ? 'Loading memories…' : 'Loading learning profile…')).toBeNull());
  expect(screen.queryByDisplayValue(privateValue)).toBeNull(); expect(screen.queryByText(originalValue)).toBeNull();
  expect(screen.queryByRole('button', { name: confirmName(editor) })).toBeNull();
  expect(screen.getAllByRole('button', { name: receiptName(editor) })).toHaveLength(1);
  click(receiptName(editor)); await waitFor(() => expect(button(receiptName(editor)).disabled).toBe(false));
  expect(f.request.mock.calls.filter(([path]) => path.startsWith('/memory-mutations/'))).toEqual(Array.from({ length: 4 }, () => [`/memory-mutations/${originalKey}`]));
  expect(memoryWrites(f)).toHaveLength(1); expect(feedbackWrites(f)).toHaveLength(0); expect(studyWrites(f)).toHaveLength(0);
  expect(screen.queryByText(/Save confirmed|This field save is confirmed/)).toBeNull(); assertNavigationBlocked(editor);
});

it.each(editors.flatMap(editor => ['answer', 'start', 'revoke'].map(mode => ({ editor, mode }))))('BC04: $editor UNKNOWN rejects captured study $mode admission', async ({ editor, mode }) => {
  const f = await open(fixture({ mode: mode === 'revoke' ? 'saved' : mode as 'answer' | 'start' }));
  if (mode === 'revoke') f.state.feedback = projection(queued());
  await openEditor(editor); await stage(editor, f); await resume(f);
  if (mode === 'answer') change('Answer 1', 'Synthetic unsubmitted answer');
  const captured = retainAction(button(mode === 'answer' ? 'Record answer 1' : mode === 'start' ? 'Start study session' : 'Do not keep question 1 result')); 
  await unknownMemory(editor, f); act(() => captured());
  expect(studyWrites(f)).toHaveLength(0); expect(feedbackWrites(f)).toHaveLength(0); expect(memoryWrites(f)).toHaveLength(1); assertNavigationBlocked(editor);
});

it.each(['save review', 'deletion checkbox'] as const)('BC05: generic revoked %s stays revoked after unrelated study settles', async kind => {
  const f = await open(); const action = kind === 'save review' ? 'generic save' : 'generic deletion';
  await stageAction(action, f); const retained = retainAction(button(actionConfirm(action))); 
  if (kind === 'save review') click('Cancel save review'); else fireEvent.click(screen.getByLabelText('I confirm deletion of this exact memory and its revisions.'));
  await resume(f); const hold = deferred<unknown>(); f.state.feedbackWrite = hold.promise;
  click('Request feedback for question 1'); await act(async () => hold.reject(new ApiError('REQUEST_REJECTED', false, 409)));
  await waitFor(() => expect(button(toggleName('learning')).disabled).toBe(false));
  act(() => retained()); expect(memoryWrites(f)).toHaveLength(0);
  if (kind === 'save review') { await stage('generic', f); }
  else { click('Cancel deletion'); click('Prepare memory deletion'); await screen.findByRole('region', { name: 'Confirm memory deletion' }); fireEvent.click(screen.getByLabelText('I confirm deletion of this exact memory and its revisions.')); }
  act(() => { button(actionConfirm(action)).click(); button(actionConfirm(action)).click(); });
  await waitFor(() => expect(memoryWrites(f)).toHaveLength(1));
});

it.each(editors)('BC06: switching from %s retires its same-batch confirmation while study is mounted', async editor => {
  const f = await open(); await openEditor(editor); await stage(editor, f); await resume(f);
  const retained = button(confirmName(editor)), before = f.request.mock.calls.length;
  act(() => { button(toggleName(otherEditor(editor))).click(); retained.click(); });
  await screen.findByRole('region', { name: regionName(otherEditor(editor)) });
  await waitFor(() => expect(button(refreshName(otherEditor(editor))).disabled).toBe(false));
  expect(screen.getByRole('region', { name: 'Durable study' })).toBeTruthy(); expect(memoryWrites(f)).toHaveLength(0);
  expect(screen.queryByRole('region', { name: regionName(editor) })).toBeNull(); expect(screen.queryByDisplayValue(privateValue)).toBeNull();
  expect(f.request.mock.calls.slice(before).some(([path]) => path.startsWith('/memories'))).toBe(true);
  expect(screen.queryByRole('button', { name: confirmName(editor) })).toBeNull();
});

it.each(editors)('BC07: normal %s unmount and late idle read cannot release the new memory owner', async outgoing => {
  const f = await open(); await openEditor(outgoing); await stage(outgoing, f); await resume(f);
  const idle = deferred<unknown>(); if (outgoing === 'generic') f.state.listRead = idle.promise; else f.state.memoryRead = idle.promise;
  click(refreshName(outgoing));
  await waitFor(() => expect(f.state.idleReads).toBe(1));
  f.state.listRead = null; f.state.memoryRead = null;
  const incoming = otherEditor(outgoing); await openEditor(incoming);
  expect(screen.queryByRole('region', { name: regionName(outgoing) })).toBeNull();
  await stage(incoming, f); await resume(f);
  const hold = deferred<unknown>(); f.state.memoryWrite = hold.promise;
  click(confirmName(incoming)); await waitFor(() => expect(memoryWrites(f)).toHaveLength(1));
  await act(async () => idle.resolve(outgoing === 'generic' ? { items: [{ ...original(), value: lateValue }], next_cursor: null } : { ...original(), value: lateValue }));
  expect(screen.queryByText(lateValue)).toBeNull(); assertNavigationBlocked(incoming);
  await act(async () => hold.reject(new ApiError('UNKNOWN', true)));
  expect(screen.getAllByRole('button', { name: receiptName(incoming) })).toHaveLength(1);
  click(receiptName(incoming)); await waitFor(() => expect(button(receiptName(incoming)).disabled).toBe(false));
  assertNavigationBlocked(incoming); expect(feedbackWrites(f)).toHaveLength(0); expect(memoryWrites(f)).toHaveLength(1);
});

it.each(editors)('BC08a: idle %s read cannot repaint after permitted identity rotation', async editor => {
  const f = await open(); const form = await identityForm(); await openEditor(editor); await stage(editor, f);
  const oldInput = screen.getByLabelText<HTMLTextAreaElement>(valueLabel(editor));
  const idle = deferred<unknown>(); if (editor === 'generic') f.state.listRead = idle.promise; else f.state.memoryRead = idle.promise;
  click(refreshName(editor)); await waitFor(() => expect(f.state.idleReads).toBe(1));
  f.state.listRead = null; f.state.memoryRead = null;
  fireEvent.submit(form); await screen.findByText('Identity confirmed. Review the current snapshot before deciding.');
  await screen.findByRole('region', { name: regionName(editor) });
  await waitFor(() => expect(button(refreshName(editor)).disabled).toBe(false));
  expect(oldInput.isConnected).toBe(false); expect(screen.getByLabelText<HTMLTextAreaElement>(valueLabel(editor)).value).toBe('');
  await act(async () => idle.resolve(editor === 'generic' ? { items: [{ ...original(), value: lateValue }], next_cursor: null } : { ...original(), value: lateValue }));
  expect(screen.queryByText(lateValue)).toBeNull(); expect(screen.queryByDisplayValue(privateValue)).toBeNull();
  expect(f.request.mock.calls.filter(([path]) => path === '/auth/reauthenticate')).toHaveLength(1); expect(memoryWrites(f)).toHaveLength(0);
});

it.each(editors.flatMap(editor => ['intent', 'write'].map(phase => ({ editor, phase }))))('BC08b: held $editor $phase refuses identity rotation and retains its original recovery', async ({ editor, phase }) => {
  const f = await open(); const form = await identityForm(); await openEditor(editor);
  const hold = deferred<unknown>();
  if (phase === 'intent') {
    if (editor === 'generic') await selectGeneric(f);
    fill(editor); f.state.intent = hold.promise; click(reviewName(editor));
    await waitFor(() => expect(f.request.mock.calls.filter(([path]) => path === '/memory-intents')).toHaveLength(1));
  } else { await stage(editor, f); f.state.memoryWrite = hold.promise; click(confirmName(editor)); await waitFor(() => expect(memoryWrites(f)).toHaveLength(1)); }
  const inputNode = screen.getByLabelText(valueLabel(editor));
  fireEvent.submit(form);
  expect(f.request.mock.calls.filter(([path]) => path === '/auth/reauthenticate')).toHaveLength(0);
  expect(screen.getByLabelText(valueLabel(editor))).toBe(inputNode);
  expect(screen.getByText('Resolve pending requests before confirming identity. Reauthentication would rotate the session.')).toBeTruthy();
  expect(button(toggleName(otherEditor(editor))).disabled).toBe(true);
  if (phase === 'intent') {
    await act(async () => hold.resolve(f.intent())); await screen.findByRole('button', { name: confirmName(editor) });
    expect(memoryWrites(f)).toHaveLength(0);
  } else {
    await act(async () => hold.reject(new ApiError('UNKNOWN', true))); await screen.findByRole('button', { name: receiptName(editor) });
    click(receiptName(editor)); await waitFor(() => expect(button(receiptName(editor)).disabled).toBe(false));
    expect(memoryWrites(f)).toHaveLength(1);
    const key = memoryWrites(f)[0][2]!;
    expect(f.request.mock.calls.filter(([path]) => path.startsWith('/memory-mutations/'))).toEqual([[`/memory-mutations/${key}`]]);
    f.state.memory = { ...f.state.memory, value: privateValue, change_reason: privateReason, version: 2 };
    f.state.memoryReceipt = f.memoryReceipt(); click(receiptName(editor));
    await waitFor(() => expect(screen.queryByRole('button', { name: receiptName(editor) })).toBeNull());
  }
  await waitFor(() => expect(button('Confirm identity').disabled).toBe(false));
  fireEvent.submit(form); await screen.findByText('Identity confirmed. Review the current snapshot before deciding.');
  expect(f.request.mock.calls.filter(([path]) => path === '/auth/reauthenticate')).toHaveLength(1);
  expect(screen.queryByRole('button', { name: confirmName(editor) })).toBeNull();
});

it.each(editors.flatMap(editor => ['read', 'intent', 'write'].flatMap(phase => ['logout', 'execution boundary'].map(boundary => ({ editor, phase, boundary })))))('BC08c: $editor held $phase is retired by $boundary without cancellation or replay', async ({ editor, phase, boundary }) => {
  const f = await open(); await openEditor(editor);
  const hold = deferred<unknown>(); let captured: (() => void) | null = null;
  if (phase === 'read') {
    await stage(editor, f); captured = retainAction(button(confirmName(editor))); 
    if (editor === 'generic') f.state.listRead = hold.promise; else f.state.memoryRead = hold.promise;
    click(refreshName(editor)); await waitFor(() => expect(f.state.idleReads).toBe(1));
    f.state.listRead = null; f.state.memoryRead = null;
  } else if (phase === 'intent') {
    if (editor === 'generic') await selectGeneric(f);
    fill(editor); f.state.intent = hold.promise; click(reviewName(editor));
    await waitFor(() => expect(f.request.mock.calls.filter(([path]) => path === '/memory-intents')).toHaveLength(1));
    f.state.intent = null;
  } else {
    await stage(editor, f); captured = retainAction(button(confirmName(editor)));  f.state.memoryWrite = hold.promise;
    click(confirmName(editor)); await waitFor(() => expect(memoryWrites(f)).toHaveLength(1)); f.state.memoryWrite = null;
  }
  const intentCount = f.request.mock.calls.filter(([path]) => path === '/memory-intents').length;
  if (boundary === 'logout') {
    const nextList = deferred<unknown>(); f.state.conversationsRead = nextList.promise;
    click('Sign out'); await login();
    // The advisory survives both the held list and its real canonical selection.
    // A read-only refresh without a loss signal must not invent a warning.
    if (phase !== 'read') expect(screen.getByRole('alert', { name: 'Previous request outcome unconfirmed' })).toBeTruthy();
    else expect(screen.queryByRole('alert', { name: 'Previous request outcome unconfirmed' })).toBeNull();
    await openEditor(otherEditor(editor));
    f.state.conversationsRead = null; await act(async () => nextList.resolve(f.conversations()));
    await screen.findByText('No saved messages.');
    if (phase !== 'read') expect(screen.getByRole('alert', { name: 'Previous request outcome unconfirmed' })).toBeTruthy();
    else expect(screen.queryByRole('alert', { name: 'Previous request outcome unconfirmed' })).toBeNull();
  } else { pause(); await screen.findByText('Account state: PAUSED'); await screen.findByRole('region', { name: regionName(editor) }); }
  expect(screen.queryByDisplayValue(privateValue)).toBeNull(); expect(screen.queryByText(`Reason: ${privateReason}`)).toBeNull();
  if (boundary === 'execution boundary' && phase !== 'read') expect(screen.getByText(/Earlier work remains unresolved across this safety boundary/)).toBeTruthy();
  await act(async () => hold.resolve(phase === 'read' ? editor === 'generic' ? { items: [{ ...original(), value: lateValue }], next_cursor: null } : { ...original(), value: lateValue } : phase === 'intent' ? f.intent() : { status: 'COMMITTED', memory_id: memoryId, version: 2, generation: 1, operation: 'REVISE' }));
  act(() => captured?.());
  expect(screen.queryByText(lateValue)).toBeNull(); expect(screen.queryByRole('button', { name: confirmName(editor) })).toBeNull();
  expect(memoryWrites(f)).toHaveLength(phase === 'write' ? 1 : 0);
  expect(f.request.mock.calls.filter(([path]) => path === '/memory-intents')).toHaveLength(intentCount);
  expect(screen.queryByRole('button', { name: receiptName(editor) })).toBeNull();
  expect(f.request.mock.calls.some(([path]) => path.includes('/cancel'))).toBe(false);
  if (boundary === 'logout') expect(screen.getByText('Session active')).toBeTruthy();
  else if (phase !== 'read') expect(button('Start new conversation').disabled).toBe(true);
});

it.each(editors)('BC08: superseded same-session %s read 401 remains authoritative while mounted', async editor => {
  const f = await open(); await openEditor(editor);
  click('Choose project for a new conversation'); await screen.findByRole('option', { name: 'Next project' });
  change('New conversation project', nextProjectId); await screen.findByText(/Next new conversation project: Next project/);
  const hold = deferred<unknown>();
  if (editor === 'generic') f.state.listRead = hold.promise; else f.state.memoryRead = hold.promise;
  click(refreshName(editor)); await waitFor(() => expect(f.state.idleReads).toBe(1));
  // This allowed navigation changes effective context without unmounting the editor.
  f.state.listRead = null; f.state.memoryRead = null; click('Start new conversation');
  await waitFor(() => expect(button(refreshName(editor)).disabled).toBe(false));
  await act(async () => hold.reject(new ApiError('UNAUTHENTICATED', false, 401)));
  await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByRole('region', { name: regionName(editor) })).toBeNull();
  expect(memoryWrites(f)).toHaveLength(0);
});

it.each(editors)('BC08: unmounted %s late 401 cannot clear the newly authenticated owner', async editor => {
  const f = await open(); await openEditor(editor);
  const hold = deferred<unknown>(); if (editor === 'generic') f.state.listRead = hold.promise; else f.state.memoryRead = hold.promise;
  click(refreshName(editor)); await waitFor(() => expect(f.state.idleReads).toBe(1));
  f.state.listRead = null; f.state.memoryRead = null; click('Sign out'); await login(); await openEditor(otherEditor(editor));
  await act(async () => hold.reject(new ApiError('UNAUTHENTICATED', false, 401)));
  expect(screen.getByText('Session active')).toBeTruthy(); expect(screen.getByRole('region', { name: regionName(otherEditor(editor)) })).toBeTruthy();
  expect(button('Start new conversation').disabled).toBe(false); expect(memoryWrites(f)).toHaveLength(0);
});

it.each(editors)('BC09: %s follows effective project synchronously and ignores next-project choices for an existing conversation', async editor => {
  const f = await open(fixture({ project: true })); await openEditor(editor);
  change(editor === 'generic' ? 'Memory scope' : 'Learning scope', 'PROJECT');
  await stage(editor, f); await resume(f);
  click('Choose project for a new conversation'); await screen.findByRole('option', { name: 'Next project' });
  change('New conversation project', nextProjectId); await screen.findByText(/Next new conversation project: Next project/);
  expect(screen.getByText(editor === 'generic' ? `Project: ${projectId}` : `Selected project: ${projectId}`)).toBeTruthy();
  expect(screen.getByRole('button', { name: confirmName(editor) })).toBeTruthy();
  const captured = button(confirmName(editor)), before = f.request.mock.calls.length;
  act(() => { button('Start new conversation').click(); captured.click(); });
  await screen.findByText(editor === 'generic' ? `Project: ${nextProjectId}` : `Selected project: ${nextProjectId}`);
  expect(memoryWrites(f)).toHaveLength(0); expect(screen.queryByDisplayValue(privateValue)).toBeNull();
  expect(screen.queryByRole('button', { name: confirmName(editor) })).toBeNull();
  if (editor === 'generic') {
    await waitFor(() => expect(button('Refresh memories').disabled).toBe(false));
    change('Memory key', 'learning.level'); fill(editor); click(reviewName(editor));
    await screen.findByRole('button', { name: confirmName(editor) });
  }
  await waitFor(() => expect(f.request.mock.calls.slice(before).some(([path, body]) => path === '/memories/key-state' && (body as MemoryIdentity).project_id === nextProjectId)).toBe(true));
  expect(f.request.mock.calls.slice(before).filter(([path, body]) => path === '/memories/key-state' && (body as MemoryIdentity).scope === 'PROJECT').every(([, body]) => (body as MemoryIdentity).project_id === nextProjectId)).toBe(true);
});

it.each(editors)('BC10: %s memory 410 purges values revisions and staged authority with no retry', async editor => {
  const f = await open(); await openEditor(editor);
  if (editor === 'generic') await selectGeneric(f);
  click(editor === 'generic' ? 'View active memory revisions' : 'View learning field revisions');
  await screen.findByText(/Synthetic saved revision/); await stage(editor, f);
  const captured = retainAction(button(confirmName(editor)));  f.state.failMemory = true;
  click(editor === 'generic' ? 'Refresh selected memory version' : 'Refresh learning profile');
  await screen.findByText(editor === 'generic' ? /This memory was deleted/ : /This memory is no longer active/);
  act(() => captured()); expect(screen.queryByDisplayValue(privateValue)).toBeNull(); expect(screen.queryByText(/Synthetic saved revision/)).toBeNull();
  expect(screen.queryByText(`Current saved value: ${originalValue}`)).toBeNull(); expect(screen.queryByText(`Saved value: ${originalValue}`)).toBeNull();
  expect(screen.queryByRole('button', { name: confirmName(editor) })).toBeNull(); expect(memoryWrites(f)).toHaveLength(0);
  expect(f.request.mock.calls.filter(([path]) => path.startsWith('/memory-mutations/'))).toHaveLength(0);
});

it('BC10: conversation 410 clears lesson answer and late feedback while preserving separate learning memory', async () => {
  const f = await open(); await openEditor('learning'); await resume(f);
  const oldFeedback = deferred<unknown>(); f.state.feedbackRead = oldFeedback.promise; click('Refresh feedback status');
  await waitFor(() => expect(button('Refresh feedback status').disabled).toBe(true));
  f.state.conversationDeleted = true; click('Find saved study lessons');
  await screen.findByRole('button', { name: `Resume lesson ${run}` }); click(`Resume lesson ${run}`);
  await screen.findByText(/대화 원문 삭제가 확인되었습니다/);
  await act(async () => oldFeedback.resolve(projection(queued())));
  expect(screen.queryByText('Synthetic first question?')).toBeNull(); expect(screen.queryByText('Saved synthetic first answer')).toBeNull(); expect(screen.queryByRole('region', { name: 'Saved answer feedback' })).toBeNull();
  expect(screen.getByText(`Saved value: ${originalValue}`)).toBeTruthy(); expect(screen.getByText(/별도로 저장한 기억과 이미 전달된 사본은 삭제 대상이 아닙니다/)).toBeTruthy();
  expect(memoryWrites(f)).toHaveLength(0); expect(f.request.mock.calls.some(([path]) => path.startsWith('/memory-delete-preparations/'))).toBe(false);
});

it.each(editors)('BC11: settled QUEUED feedback permits explicit %s save but keeps its source barrier until canonical revocation', async editor => {
  const f = await open(); await openEditor(editor); await resume(f);
  change('Message', 'Synthetic next message');
  click('Request feedback for question 1'); await screen.findByText('Request: QUEUED');
  expect(button('Send mock message').disabled).toBe(true);
  await stage(editor, f); click(confirmName(editor));
  await waitFor(() => expect(memoryWrites(f)).toHaveLength(1));
  await waitFor(() => expect(button(toggleName(otherEditor(editor))).disabled).toBe(false));
  expect(feedbackWrites(f)).toHaveLength(1); expect(feedbackWrites(f)[0][0]).toBe(`/study-sessions/${sid}/feedback-requests`);
  expect(button('Send mock message').disabled).toBe(true);
  // Memory invalidation is server authority. The client observes its later projection.
  f.state.feedback = { ...projection({ ...queued(), feedback_version: 2, status: 'REVOKED', output_authority: 'REVOKED', accounting: 'PENDING' }), conversation_version: 21 };
  await resume(f);
  await screen.findByText('Request: REVOKED'); expect(screen.getByText('Execution evidence: UNCONFIRMED')).toBeTruthy();
  expect(screen.getByText(/Execution may still be running/)).toBeTruthy();
  await waitFor(() => expect(button('Send mock message').disabled).toBe(false));
  expect(feedbackWrites(f)).toHaveLength(1); expect(memoryWrites(f)).toHaveLength(1);
  expect(memoryWrites(f)[0][0]).toBe(`/memories/${memoryId}`); expect(memoryWrites(f)[0][3]).toBe('PATCH');
});

async function preparePeer(peer: 'deletion' | 'export') {
  if (peer === 'export') {
    click('기억 내보내기 열기'); fireEvent.click(screen.getByLabelText('선택한 범위와 개인정보 포함 가능성을 확인했습니다'));
    await waitFor(() => expect(button('선택 범위 내보내기 작업 생성').disabled).toBe(false));
  } else {
    click('대화 원문 삭제 열기'); click('선택 대화 삭제 검토'); await screen.findByRole('dialog', { name: '이 대화의 영구 삭제 검토' });
    fireEvent.click(screen.getByLabelText('이 제한을 이해했으며 정확히 이 대화의 영구 삭제를 확인합니다'));
    await waitFor(() => expect(button('이 대화 영구 삭제').disabled).toBe(false));
  }
}
const peerConfirm = (peer: 'deletion' | 'export') => peer === 'deletion' ? '이 대화 영구 삭제' : '선택 범위 내보내기 작업 생성';
const peerRecovery = (peer: 'deletion' | 'export') => peer === 'deletion' ? '원래 삭제 요청 결과 확인' : '원래 생성 요청 결과 확인';
const peerWrites = (f: Fixture, peer: 'deletion' | 'export') => f.request.mock.calls.filter(([path, body]) => body !== undefined && (peer === 'deletion' ? path.startsWith('/conversation-delete-preparations/') : path === '/exports'));

it.each(editors.flatMap(editor => (['deletion', 'export'] as const).map(peer => ({ editor, peer }))))('BC12: $peer live lock rejects captured $editor confirmation and retains its own GET recovery', async ({ editor, peer }) => {
  const f = await open(); await openEditor(editor); await stage(editor, f);
  const captured = retainAction(button(confirmName(editor))); 
  // Real deletion preparation retires learning consent. Retain its earlier
  // public handler rather than inventing simultaneous mutually invalidating reviews.
  await preparePeer(peer);
  const hold = deferred<unknown>(); if (peer === 'deletion') f.state.deletionWrite = hold.promise; else f.state.exportWrite = hold.promise;
  act(() => { button(peerConfirm(peer)).click(); captured(); });
  await waitFor(() => expect(peerWrites(f, peer)).toHaveLength(1)); expect(memoryWrites(f)).toHaveLength(0);
  expect(button(toggleName(otherEditor(editor))).disabled).toBe(true); expect(button('Start new conversation').disabled).toBe(true);
  await act(async () => hold.reject(new ApiError('UNKNOWN', true))); await screen.findByRole('button', { name: peerRecovery(peer) });
  click(peerRecovery(peer)); await screen.findByText(/^NOT_OBSERVED:/);
  expect(memoryWrites(f)).toHaveLength(0); expect(peerWrites(f, peer)).toHaveLength(1); expect(button('Start new conversation').disabled).toBe(true);
  const originalKey = peerWrites(f, peer)[0][2]!;
  expect(f.request.mock.calls.some(([path, body, key]) => path === `${peer === 'deletion' ? '/conversation-deletions/requests/' : '/exports/requests/'}${originalKey}` && body === undefined && key === undefined)).toBe(true);
  if (peer === 'export') expect(peerWrites(f, peer)[0][1]).toEqual({ kind: 'EXPLICIT_MEMORIES', scope: 'GLOBAL', project_id: null, format: 'JSON_UTF8' });
  else expect(Object.keys(peerWrites(f, peer)[0][1] as object).sort()).toEqual(['confirm', 'expected_digest', 'expected_version', 'payload_hash']);
});

it.each(editors.flatMap(editor => (['deletion', 'export'] as const).map(peer => ({ editor, peer }))))('BC12: unresolved $editor memory fences $peer without changing its wire contract', async ({ editor, peer }) => {
  const f = await open(); await openEditor(editor); await stage(editor, f);
  if (peer === 'export') await preparePeer(peer); else click('대화 원문 삭제 열기');
  const retained = retainAction(button(peer === 'export' ? peerConfirm(peer) : '선택 대화 삭제 검토')); 
  await unknownMemory(editor, f); act(() => retained());
  expect(peerWrites(f, peer)).toHaveLength(0);
  expect(button(peer === 'export' ? peerConfirm(peer) : '선택 대화 삭제 검토').disabled).toBe(true);
  if (peer === 'export') expect(screen.getByLabelText<HTMLSelectElement>('내보내기 범위').disabled).toBe(true);
  expect(button(receiptName(editor)).disabled).toBe(false);
  expect(f.request.mock.calls.filter(([path]) => path === `/conversations/${cid}/delete-preparations`)).toHaveLength(0);
  click(receiptName(editor)); await waitFor(() => expect(button(receiptName(editor)).disabled).toBe(false));
  expect(memoryWrites(f)).toHaveLength(1); expect(peerWrites(f, peer)).toHaveLength(0);
});

it.each(editors)('BC13: paused account blocks %s and new study writes but preserves explicit feedback revoke', async editor => {
  const f = await open(fixture({ paused: true })); f.state.feedback = projection(queued());
  await openEditor(editor); await resume(f);
  expect(screen.getByLabelText(valueLabel(editor)).closest('fieldset')!.disabled).toBe(true);
  expect(button('Close study session').disabled).toBe(true); expect(button('Request feedback for question 2').disabled).toBe(true);
  expect(button('Do not keep question 1 result').disabled).toBe(false); click('Do not keep question 1 result');
  await screen.findByText('Request: REVOKED'); expect(screen.getByText('Execution evidence: UNCONFIRMED')).toBeTruthy();
  expect(feedbackWrites(f)).toHaveLength(1); expect(feedbackWrites(f)[0][0]).toContain('/revoke'); expect(memoryWrites(f)).toHaveLength(0); expect(studyWrites(f)).toHaveLength(0);
});

it.each(editors)('BC13: unresolved %s memory prevents paused-account revoke from stealing its domain', async editor => {
  const f = await open(); f.state.feedback = projection(queued()); await openEditor(editor); await stage(editor, f); await resume(f);
  const revoke = retainAction(button('Do not keep question 1 result')); await unknownMemory(editor, f);
  f.state.paused = true; f.state.executionVersion++; click('Refresh server state'); await screen.findByText('Account state: PAUSED');
  act(() => revoke()); expect(feedbackWrites(f)).toHaveLength(0); expect(memoryWrites(f)).toHaveLength(1);
  expect(button(toggleName(otherEditor(editor))).disabled).toBe(true); expect(button('Start new conversation').disabled).toBe(true);
  expect(screen.getByRole('button', { name: receiptName(editor) })).toBeTruthy();
});

it.each(editors)('BC14: %s private forms and recovery never persist and page boundaries remove private DOM', async editor => {
  const storage = vi.spyOn(Storage.prototype, 'setItem');
  const push = vi.spyOn(history, 'pushState'), replace = vi.spyOn(history, 'replaceState');
  const cacheOpen = vi.fn(), databaseOpen = vi.fn(), beacon = vi.fn();
  vi.stubGlobal('caches', { open: cacheOpen }); vi.stubGlobal('indexedDB', { open: databaseOpen });
  vi.stubGlobal('navigator', Object.assign(Object.create(navigator), { sendBeacon: beacon }));
  const consoleLog = vi.spyOn(console, 'log'), consoleInfo = vi.spyOn(console, 'info');
  const f = await open(); await openEditor(editor); await stage(editor, f);
  const hold = deferred<unknown>(); f.state.memoryWrite = hold.promise; click(confirmName(editor));
  await waitFor(() => expect(memoryWrites(f)).toHaveLength(1)); await act(async () => hold.reject(new ApiError('UNKNOWN', true)));
  await screen.findByRole('button', { name: receiptName(editor) }); click(receiptName(editor)); await waitFor(() => expect(button(receiptName(editor)).disabled).toBe(false));
  const key = memoryWrites(f)[0][2]!;
  fireEvent(window, new Event('pagehide')); await screen.findByRole('heading', { name: 'Owner sign in' });
  expect(screen.queryByDisplayValue(privateValue)).toBeNull(); expect(screen.queryByText(privateReason)).toBeNull(); expect(screen.queryByText(`Request: ${key}`)).toBeNull();
  expect(storage).not.toHaveBeenCalled(); expect(push).not.toHaveBeenCalled(); expect(replace).not.toHaveBeenCalled(); expect(cacheOpen).not.toHaveBeenCalled(); expect(databaseOpen).not.toHaveBeenCalled(); expect(beacon).not.toHaveBeenCalled();
  expect(consoleLog).not.toHaveBeenCalled(); expect(consoleInfo).not.toHaveBeenCalled(); expect(location.href).not.toContain(privateValue); expect(location.href).not.toContain(key);
});

it.each(editors)('BC14: after %s use lesson answer and feedback bodies retain their exact closed shapes', async editor => {
  const f = await open(); await openEditor(editor); await stage(editor, f); click(confirmName(editor));
  await waitFor(() => expect(memoryWrites(f)).toHaveLength(1)); await waitFor(() => expect(button(toggleName(otherEditor(editor))).disabled).toBe(false));
  change('Message', 'Synthetic lesson topic'); click('Generate study lesson'); await screen.findByText(/Study lesson generation is unavailable/);
  f.state.mode = 'answer'; f.state.current = study(); await resume(f); change('Answer 1', 'Synthetic answer only'); click('Record answer 1');
  await waitFor(() => expect(studyWrites(f)).toHaveLength(1));
  await waitFor(() => expect(button('Find saved study lessons').disabled).toBe(false));
  f.state.mode = 'saved'; f.state.current = saved(); await resume(f); click('Request feedback for question 1'); await screen.findByText('Request: QUEUED');
  const lessonRequest = f.request.mock.calls.find(([path]) => path.endsWith('/study-lessons'))!;
  expect(Object.keys(lessonRequest[1] as object).sort()).toEqual(['client_message_id', 'text']);
  expect(studyWrites(f)[0][1]).toEqual({ expected_version: 1, expected_conversation_version: 11, question_id: q1, text: 'Synthetic answer only' });
  expect(Object.keys(feedbackWrites(f)[0][1] as object).sort()).toEqual(['attempt_id', 'expected_conversation_version', 'expected_study_version', 'input_id', 'question_id']);
  expect((feedbackWrites(f)[0][1] as { input_id: string }).input_id).toBe(input);
  for (const [, body] of [lessonRequest, studyWrites(f)[0], feedbackWrites(f)[0]]) {
    const encoded = JSON.stringify(body); expect(encoded).not.toContain(privateValue); expect(encoded).not.toContain(privateReason); expect(encoded).not.toMatch(/learning|preference|memory|personalization/i);
  }
  click('Sign out'); await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByText(privateValue)).toBeNull();
});
