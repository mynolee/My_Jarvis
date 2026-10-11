import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { StudyPanel } from './StudyPanel';
import { Api, ApiError } from './api';
import type { Task, TaskArgs } from './tasks';
import { acceptedFeedback, appFixture, deferred, projection } from '../tests/study-feedback-fixtures';
import { accepted, at, cid, index, other, outcome as studyOutcome, run, sid, start } from '../tests/study-fixtures';
vi.mock('./TodayPanel', () => ({ TodayPanel: () => <section aria-label="Synthetic Today"/> }));
vi.mock('./conversation-history-events', () => ({ watch_history_events: () => () => {} }));
const taskId = 'd1111111-1111-4111-8111-111111111111', intentId = 'd2222222-2222-4222-8222-222222222222';
const sourceProject = 'd3333333-3333-4333-8333-333333333333', nextProject = 'd4444444-4444-4444-8444-444444444444';
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name }), click = (name: string) => fireEvent.click(button(name));
const writes = (f: ReturnType<typeof fixture>) => f.request.mock.calls.filter(([, body]) => body !== undefined);
function issued(args: TaskArgs) { return { id: intentId, tool_id: 'task.create', target_id: taskId, arguments: args, status: 'ISSUED', payload_hash: 'a'.repeat(64), expires_at: '2099-01-01T00:00:00Z' }; }
function fixture(project: string | null = null) {
  const f = appFixture(); f.state.feedback = projection(acceptedFeedback()); f.state.current.conversation_content_version = 17; f.state.current.project_id = project;
  f.state.extra['/conversations'] = { items: [{ id: cid, title: 'Feedback conversation', project_id: project, created_at: at }, { id: other, title: 'Other conversation', project_id: null, created_at: at }], next_cursor: null };
  const state = { ...f.state, task: null as Task | null, intentRead: null as Promise<unknown> | null, taskWrite: null as Promise<unknown> | null, copyStudy: null as Promise<unknown> | null, receipt: { status: 'NOT_OBSERVED', task: null } as unknown, taskRead: null as Promise<unknown> | null };
  // Keep the original fixture's mutable fields; the extra task transport is independently synthetic.
  const original = f.request.getMockImplementation()!;
  f.request.mockImplementation((async (path: string, body?: unknown, key?: string, method?: 'POST' | 'PATCH') => {
    if (path.startsWith('/tasks?')) return { items: state.task ? [state.task] : [], next_cursor: null };
    if (path === '/user-intents') { if (state.intentRead) return state.intentRead; const b = body as { arguments: TaskArgs; tool_id: string }; return { ...issued(b.arguments), tool_id: b.tool_id }; }
    if ((path === '/tasks' || path === `/tasks/${taskId}`) && body !== undefined) {
      if (state.taskWrite) return state.taskWrite;
      const { user_intent_id: _intent, expected_version: _version, ...fields } = body as Record<string, unknown>;
      state.task = { ...state.task, id: taskId, status: 'TODO', ...fields, version: (state.task?.version ?? 0) + 1, created_at: at, updated_at: at } as Task; return state.task;
    }
    if (path.startsWith('/task-mutations/')) return state.receipt;
    if (path === `/tasks/${taskId}`) { if (state.taskRead) return state.taskRead; if (state.task) return state.task; throw new ApiError('NOT_FOUND', false, 404); }
    if (path === `/study-sessions/${sid}` && state.copyStudy) return state.copyStudy;
    return original(path, body, key, method);
  }) as Api['request']);
  return { ...f, taskState: state };
}
async function open(f = fixture()) {
  const view = render(<App api={f.api}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); await screen.findByText('No saved messages.'); await selectStudy(); return { ...f, ...view };
}
async function selectStudy() { await waitFor(() => expect(button('Find saved study lessons').disabled).toBe(false)); click('Find saved study lessons'); await screen.findByRole('button', { name: `Resume lesson ${run}` }); click(`Resume lesson ${run}`); await screen.findByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' }); }
async function copy() { fireEvent.click(screen.getByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' })); click('Copy selected items to task draft'); await screen.findByRole('region', { name: 'Incoming study review copy' }); }
async function importCopy() { await copy(); click('Use this review draft'); await screen.findByDisplayValue('Review study question 1'); }
async function intent() { click('Review task change'); click('Request task intent'); await screen.findByRole('button', { name: 'Apply server-bound task change' }); await waitFor(() => expect(button('Other conversation').disabled).toBe(false)); }
async function login() { await waitFor(() => expect(button('Sign in').disabled).toBe(false)); fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'synthetic-owner' } }); fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } }); click('Sign in'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); await screen.findByText('No saved messages.'); }
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it.each(['404', '410', '401', 'malformed', 'stale', 'unavailable'])('failed fresh durable read %s never offers cached text or writes and never retries automatically', async kind => {
  const f = await open(); const hold = deferred<unknown>(); f.taskState.copyStudy = hold.promise; const before = f.request.mock.calls.length;
  fireEvent.click(screen.getByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' })); click('Copy selected items to task draft');
  await act(async () => { if (kind === 'malformed') hold.resolve({ invalid: true }); else if (kind === 'stale') hold.resolve({ ...f.state.current, conversation_content_version: 13 }); else hold.reject(new ApiError(kind === '410' ? 'CONVERSATION_DELETED' : kind === '401' ? 'UNAUTHENTICATED' : 'UNAVAILABLE', false, kind === 'unavailable' ? 503 : Number(kind))); });
  expect(screen.queryByRole('region', { name: 'Incoming study review copy' })).toBeNull(); expect(writes(f)).toHaveLength(0); expect(f.request.mock.calls.slice(before).filter(([path]) => path.startsWith('/study-sessions/'))).toHaveLength(1);
  if (kind === '410') { expect(screen.queryByText('Synthetic first question?')).toBeNull(); await screen.findByText(/대화 원문 삭제가 확인되었습니다/); }
  else if (kind === '401') await screen.findByRole('heading', { name: 'Owner sign in' });
  else { await screen.findByText(/saved source could not be checked/); f.taskState.copyStudy = null; click('Copy selected items to task draft'); await screen.findByRole('region', { name: 'Incoming study review copy' }); expect(writes(f)).toHaveLength(0); }
});
it.each(['404', '410', '401', 'malformed', 'stale', 'unavailable'])('failed second fresh feedback read %s never reuses the displayed accepted result', async kind => {
  const f = await open(), hold = deferred<unknown>(); f.state.read = hold.promise; const before = f.request.mock.calls.length;
  fireEvent.click(screen.getByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' })); click('Copy selected items to task draft');
  await waitFor(() => expect(f.request.mock.calls.slice(before).filter(([path]) => path.endsWith('/feedback'))).toHaveLength(1));
  await act(async () => { if (kind === 'malformed') hold.resolve({ invalid: true }); else if (kind === 'stale') hold.resolve({ ...f.state.feedback, conversation_version: 13 }); else hold.reject(new ApiError(kind === '410' ? 'CONVERSATION_DELETED' : kind === '401' ? 'UNAUTHENTICATED' : 'UNAVAILABLE', false, kind === 'unavailable' ? 503 : Number(kind))); });
  expect(screen.queryByRole('region', { name: 'Incoming study review copy' })).toBeNull(); expect(writes(f)).toHaveLength(0); expect(f.request.mock.calls.slice(before).filter(([path]) => path.startsWith('/study-sessions/'))).toHaveLength(2);
});
it.each(['conversation', 'snapshot', 'logout', 'pagehide', 'unmount'])('held copy cannot install source text after %s boundary', async boundary => {
  const f = await open(), hold = deferred<unknown>(); f.taskState.copyStudy = hold.promise;
  fireEvent.click(screen.getByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' })); click('Copy selected items to task draft');
  if (boundary === 'conversation') click('Other conversation'); else if (boundary === 'snapshot') click('Refresh server state'); else if (boundary === 'logout') click('Sign out'); else if (boundary === 'pagehide') act(() => window.dispatchEvent(new Event('pagehide'))); else f.unmount();
  await act(async () => hold.resolve(f.state.current)); expect(screen.queryByRole('region', { name: 'Incoming study review copy' })).toBeNull(); expect(writes(f).filter(([path]) => path === '/user-intents' || path === '/tasks')).toHaveLength(0);
});
it.each(['snapshot', 'conversation'])('same-auth 401 from superseded copy still invalidates session after %s', async boundary => {
  const f = await open(), hold = deferred<unknown>(); f.taskState.copyStudy = hold.promise; fireEvent.click(screen.getByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' })); click('Copy selected items to task draft'); click(boundary === 'snapshot' ? 'Refresh server state' : 'Other conversation'); await act(async () => hold.reject(new ApiError('UNAUTHENTICATED', false, 401))); await screen.findByRole('heading', { name: 'Owner sign in' });
});
it('retired-auth copy401 cannot clear a new login', async () => { const f = await open(), hold = deferred<unknown>(); f.taskState.copyStudy = hold.promise; fireEvent.click(screen.getByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' })); click('Copy selected items to task draft'); click('Sign out'); await login(); await act(async () => hold.reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(screen.getByText('Session active')).toBeTruthy(); expect(screen.queryByRole('region', { name: 'Incoming study review copy' })).toBeNull(); });
it('newer source read wins and the retired read never overwrites the offered copy', async () => {
  const f = await open(), holdA = deferred<unknown>(); f.taskState.copyStudy = holdA.promise; fireEvent.click(screen.getByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' })); click('Copy selected items to task draft'); click('Refresh server state'); await waitFor(() => expect(screen.queryByRole('region', { name: 'Saved answer feedback' })).toBeNull()); f.taskState.copyStudy = null; await selectStudy(); await copy(); const preview = screen.getByRole('region', { name: 'Incoming study review copy' }); await act(async () => holdA.resolve({ ...f.state.current, project_id: sourceProject })); expect(screen.getByRole('region', { name: 'Incoming study review copy' })).toBe(preview); expect(within(preview).getByText('Project: Personal (no project)')).toBeTruthy(); expect(writes(f)).toHaveLength(0);
});
it('same-tick stale import after source selection is rejected before private text enters the editor', async () => {
  const f = await open(); await copy(); const importButton = button('Use this review draft'); act(() => { button('Other conversation').click(); importButton.click(); }); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe('');
  expect(writes(f)).toHaveLength(0);
});
it.each(['draft', 'issued', 'unknown'])('same-source history refresh preserves imported %s and original task recovery', async stage => {
  const f = await open(); await importCopy(); if (stage !== 'draft') await intent();
  if (stage === 'unknown') { f.taskState.taskWrite = Promise.reject(new ApiError('UNKNOWN', true)); f.taskState.taskWrite.catch(() => {}); click('Apply server-bound task change'); await screen.findByText(/UNKNOWN: task writes are paused/); }
  const title = screen.getByLabelText('Task title'), key = writes(f).find(([path]) => path === '/tasks')?.[2]; click('Refresh server state'); await waitFor(() => expect(screen.queryByRole('region', { name: 'Saved answer feedback' })).toBeNull()); expect(screen.getByLabelText('Task title')).toBe(title);
  if (stage === 'draft') expect((title as HTMLInputElement).value).toBe('Review study question 1');
  if (stage === 'issued') expect(button('Apply server-bound task change').disabled).toBe(false);
  if (stage === 'unknown') { expect(screen.getByText(`Request: ${key}`)).toBeTruthy(); click('Check task outcome'); await screen.findByText(/A missing task is not proof of failure/); expect(writes(f)).toHaveLength(2); }
});
it.each(['incoming', 'draft', 'issued'])('real source selection clears %s private copy without task mutation', async stage => {
  const f = await open(); if (stage === 'incoming') await copy(); else await importCopy(); if (stage === 'issued') await intent(); click('Other conversation'); expect(screen.queryByRole('region', { name: 'Incoming study review copy' })).toBeNull(); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe(''); expect(screen.queryByRole('region', { name: 'Source read when copied' })).toBeNull(); expect(writes(f).filter(([path]) => path === '/tasks')).toHaveLength(0);
});
it.each(['valid', 'invalid'])('late intent after verified source deletion stays disposed and %s settlement keeps correct authority', async result => {
  const f = await open(); await importCopy(); const hold = deferred<unknown>(); f.taskState.intentRead = hold.promise; click('Review task change'); click('Request task intent');
  f.state.extra[`/conversations/${cid}/deletion-state`] = { status: 'DELETED', conversation_id: cid, deletion_id: 'd5555555-5555-4555-8555-555555555555', deleted_at: at, checked_at: at }; click('대화 원문 삭제 열기'); click('삭제 상태만 확인'); await screen.findByText(/대화 원문 삭제가 확인되었습니다/);
  const args = (writes(f)[0][1] as { arguments: TaskArgs }).arguments; await act(async () => hold.resolve(result === 'valid' ? issued(args) : {}));
  expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe(''); expect(screen.queryByRole('region', { name: 'Source read when copied' })).toBeNull(); expect(screen.queryByRole('button', { name: 'Apply server-bound task change' })).toBeNull(); expect(writes(f).filter(([path]) => path === '/tasks')).toHaveLength(0);
  if (result === 'valid') await screen.findByText(/Server confirmed an intent was recorded/); else { await screen.findByText(/UNKNOWN: task writes are paused/); expect(button('New personal task').disabled).toBe(true); }
});
it.each(['valid', 'invalid'])('late Apply after verified source deletion remains classified imported CREATE and %s result never restores copy', async result => {
  const f = await open(); await importCopy(); await intent(); const hold = deferred<unknown>(); f.taskState.taskWrite = hold.promise; click('Apply server-bound task change');
  f.state.extra[`/conversations/${cid}/deletion-state`] = { status: 'DELETED', conversation_id: cid, deletion_id: 'd5555555-5555-4555-8555-555555555555', deleted_at: at, checked_at: at }; click('대화 원문 삭제 열기'); click('삭제 상태만 확인'); await screen.findByText(/대화 원문 삭제가 확인되었습니다/);
  const args = (writes(f)[0][1] as { arguments: TaskArgs }).arguments; await act(async () => hold.resolve({ ...args, id: taskId, status: result === 'valid' ? 'TODO' : 'DONE', version: 1, created_at: at, updated_at: at }));
  expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe(''); expect(screen.queryByRole('region', { name: 'Source read when copied' })).toBeNull(); expect(writes(f)).toHaveLength(2);
  if (result === 'valid') await screen.findByText(/Server confirmed independent task/); else { await screen.findByText(/UNKNOWN: task writes are paused/); expect(button('New personal task').disabled).toBe(true); }
});
it.each(['intent', 'apply'])('logout clears held %s but preserves warning through new successful ordinary task', async stage => {
  const f = await open(); await importCopy(); const hold = deferred<unknown>(); if (stage === 'intent') { f.taskState.intentRead = hold.promise; click('Review task change'); click('Request task intent'); } else { await intent(); f.taskState.taskWrite = hold.promise; click('Apply server-bound task change'); }
  click('Sign out'); await login(); await act(async () => hold.resolve({})); expect(screen.getByRole('alert', { name: 'Previous request outcome unconfirmed' })).toBeTruthy(); expect(screen.queryByText('Check the last element.')).toBeNull();
  f.taskState.intentRead = null; f.taskState.taskWrite = null; click('Open internal tasks'); fireEvent.change(await screen.findByLabelText('Task title'), { target: { value: 'A new ordinary task' } }); await intent(); click('Apply server-bound task change'); await screen.findByText('Server confirmed task TODO, version 1.'); expect(screen.getByRole('alert', { name: 'Previous request outcome unconfirmed' })).toBeTruthy();
});

it.each(['intent', 'apply', 'reconciliation'])('same-source canonical refresh during held %s keeps the same task operation and exact key', async stage => {
  const f = await open(); await importCopy(); const hold = deferred<unknown>();
  if (stage === 'intent') { f.taskState.intentRead = hold.promise; click('Review task change'); click('Request task intent'); }
  else { await intent(); f.taskState.taskWrite = stage === 'apply' ? hold.promise : Promise.reject(new ApiError('UNKNOWN', true)); f.taskState.taskWrite.catch(() => {}); click('Apply server-bound task change'); }
  const args = (writes(f)[0][1] as { arguments: TaskArgs }).arguments, originalKey = writes(f).at(-1)![2];
  const created = { ...args, id: taskId, status: 'TODO', version: 1, created_at: at, updated_at: at } as Task;
  if (stage === 'reconciliation') { await screen.findByText(/UNKNOWN: task writes are paused/); f.taskState.receipt = { status: 'COMMITTED', task: created }; f.taskState.taskRead = hold.promise; click('Check task outcome'); await waitFor(() => expect(f.request.mock.calls.some(([path]) => path === `/tasks/${taskId}`)).toBe(true)); }
  const editor = screen.getByLabelText('Task title'); click('Refresh server state'); await waitFor(() => expect(screen.queryByRole('region', { name: 'Saved answer feedback' })).toBeNull()); expect(screen.getByLabelText('Task title')).toBe(editor); expect(button('New personal task').disabled).toBe(true);
  f.taskState.task = created; await act(async () => hold.resolve(stage === 'intent' ? issued(args) : stage === 'reconciliation' ? { ...created, version: 2, status: 'DONE' } : created));
  if (stage === 'intent') await waitFor(() => expect(button('Apply server-bound task change').disabled).toBe(false)); else await screen.findByText(stage === 'apply' ? 'Server confirmed task TODO, version 1.' : /Server receipt confirms committed version 1. Current task version is 2/);
  expect(writes(f).at(-1)![2]).toBe(originalKey); expect(writes(f)).toHaveLength(stage === 'intent' ? 1 : 2);
});
it('canonical snapshot drops an unimported preview and a retired import control cannot restore it', async () => {
  const f = await open(); await copy(); const retired = button('Use this review draft'); click('Refresh server state'); await waitFor(() => expect(screen.queryByRole('region', { name: 'Incoming study review copy' })).toBeNull()); fireEvent.click(retired); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe(''); expect(writes(f)).toHaveLength(0);
});
it('real deletion confirmation hides imported source text before its uncertain result and keeps independent task intent unsent', async () => {
  const f = await open(); await importCopy(); await intent(); const preparationId = 'd6666666-6666-4666-8666-666666666666', hold = deferred<unknown>();
  f.state.extra[`/conversations/${cid}/deletion-state`] = { status: 'ACTIVE', conversation_id: cid, content_version: 17, content_digest: 'a'.repeat(64), eligibility: { status: 'ELIGIBLE', blockers: [] }, checked_at: at };
  f.state.extra[`/conversations/${cid}/delete-preparations`] = { id: preparationId, status: 'PREPARED', scope_policy: 'CONVERSATION_SOURCE_V1', conversation: { id: cid, title: 'Feedback conversation', project_id: null, created_at: at }, expected_version: 17, expected_digest: 'a'.repeat(64), source_counts: { messages: 2, runs: 2, run_results: 2, action_payloads: 0, inference_contexts: 2, study_sessions: 1, study_answers: 2, feedback_requests: 1, feedback_results: 1 }, payload_hash: 'b'.repeat(64), prepared_at: at, expires_at: '2026-10-11T00:05:00Z' };
  f.state.extra[`/conversation-delete-preparations/${preparationId}/confirm`] = hold.promise;
  click('대화 원문 삭제 열기'); click('선택 대화 삭제 검토'); fireEvent.click(await screen.findByRole('checkbox', { name: '이 제한을 이해했으며 정확히 이 대화의 영구 삭제를 확인합니다' })); click('이 대화 영구 삭제');
  await screen.findByText(/삭제 결과 확인 중에는 선택한 대화의 원문/); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe(''); expect(screen.queryByRole('region', { name: 'Source read when copied' })).toBeNull(); expect(screen.queryByRole('button', { name: 'Apply server-bound task change' })).toBeNull();
  await act(async () => hold.reject(new ApiError('UNKNOWN', true))); expect(writes(f).filter(([path]) => path === '/tasks')).toHaveLength(0); expect(button('New personal task').disabled).toBe(true);
});

it('same-source rediscovery preserves imported intent and choosing a distinct lesson disposes only copied editor authority', async () => {
  const f = await open(); await importCopy(); await intent(); await selectStudy(); expect(button('Apply server-bound task change').disabled).toBe(false); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe('Review study question 1');
  const runB = 'e1111111-1111-4111-8111-111111111111', studyB = 'e2222222-2222-4222-8222-222222222222';
  f.state.extra[`/conversations/${cid}/study-index`] = { conversation_id: cid, conversation_content_version: 17, checked_at: at, next_cursor: null, items: [{ lesson_run_id: runB, lesson_run_version: 4, created_at: at, study_session_id: studyB, study_version: 3, lifecycle: 'OPEN' }] };
  f.state.extra[`/study-sessions/${studyB}`] = { ...f.state.current, id: studyB, lesson_run_id: runB }; f.state.extra[`/study-sessions/${studyB}/feedback`] = { ...f.state.feedback, study_session_id: studyB };
  click('Find saved study lessons'); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe('Review study question 1'); fireEvent.click(await screen.findByRole('button', { name: `Resume lesson ${runB}` })); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe(''); expect(screen.queryByRole('button', { name: 'Apply server-bound task change' })).toBeNull(); expect(writes(f)).toHaveLength(1);
});
it.each(['api', 'project'])('direct StudyPanel held copy is retired by %s context replacement before late results', async boundary => {
  const f = fixture(), copied = vi.fn(), unauthorized = vi.fn(), hold = deferred<unknown>(); const props = { api: f.api, sessionKey: 1, conversationId: cid, projectId: null as string | null, snapshotKey: 1, onUnauthorized: unauthorized, onDeleted: vi.fn(), onReviewCopy: copied };
  const view = render(<StudyPanel {...props}/>); await selectStudy(); f.taskState.copyStudy = hold.promise; fireEvent.click(screen.getByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' })); click('Copy selected items to task draft');
  const next = fixture(); view.rerender(<StudyPanel {...props} api={boundary === 'api' ? next.api : f.api} projectId={boundary === 'project' ? sourceProject : null}/>);
  await act(async () => hold.resolve(f.state.current)); expect(copied).not.toHaveBeenCalled(); expect(unauthorized).not.toHaveBeenCalled(); expect(writes(f)).toHaveLength(0);
});
it('retired copy read cannot paint after snapshot rediscovery chooses a distinct stored study', async () => {
  const f = await open(), hold = deferred<unknown>(); f.taskState.copyStudy = hold.promise; fireEvent.click(screen.getByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' })); click('Copy selected items to task draft'); click('Refresh server state'); await waitFor(() => expect(screen.queryByRole('region', { name: 'Saved answer feedback' })).toBeNull());
  const runB = 'e3333333-3333-4333-8333-333333333333', studyB = 'e4444444-4444-4444-8444-444444444444'; f.taskState.copyStudy = null;
  f.state.extra[`/conversations/${cid}/study-index`] = { conversation_id: cid, conversation_content_version: 17, checked_at: at, next_cursor: null, items: [{ lesson_run_id: runB, lesson_run_version: 4, created_at: at, study_session_id: studyB, study_version: 3, lifecycle: 'OPEN' }] };
  f.state.extra[`/study-sessions/${studyB}`] = { ...f.state.current, id: studyB, lesson_run_id: runB }; f.state.extra[`/study-sessions/${studyB}/feedback`] = { ...f.state.feedback, study_session_id: studyB };
  await waitFor(() => expect(button('Find saved study lessons').disabled).toBe(false)); click('Find saved study lessons'); fireEvent.click(await screen.findByRole('button', { name: `Resume lesson ${runB}` })); await screen.findByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' }); await act(async () => hold.resolve(f.state.current)); expect(screen.queryByRole('region', { name: 'Incoming study review copy' })).toBeNull(); expect(writes(f)).toHaveLength(0);
});
it('real execution boundary retires incoming copy and blocks retained controls without task or model requests', async () => {
  const f = await open(); await copy(); const oldImport = button('Use this review draft'); fireEvent.click(screen.getByRole('checkbox', { name: 'I reviewed the account-wide impact for this checked version' })); act(() => { button('Pause account execution').click(); oldImport.click(); });
  await screen.findByText('Account state: PAUSED'); expect(screen.queryByRole('region', { name: 'Incoming study review copy' })).toBeNull(); expect(writes(f).map(([path]) => path)).toEqual(['/execution/pause']); expect(screen.queryByRole('region', { name: 'Source read when copied' })).toBeNull();
});

it('a newly started lesson adopts its verified persisted study identity before copy and same-source rediscovery', async () => {
  const f = fixture(), original = f.request.getMockImplementation()!; f.state.extra[`/conversations/${cid}/study-index`] = index(false); f.state.extra[`/runs/${run}/study-lesson`] = accepted;
  f.request.mockImplementation((async (path: string, body?: unknown, key?: string, method?: 'POST' | 'PATCH') => { if (path === `/conversations/${cid}/study-sessions` && body) return studyOutcome({ ...start(), key: key! }); return original(path, body, key, method); }) as Api['request']);
  render(<App api={f.api}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); await screen.findByText('No saved messages.'); await waitFor(() => expect(button('Find saved study lessons').disabled).toBe(false)); click('Find saved study lessons'); fireEvent.click(await screen.findByRole('button', { name: `View lesson ${run}` })); fireEvent.click(await screen.findByRole('button', { name: 'Start study session' }));
  // The canonical read is intentionally newer than START: a valid concurrent saved-answer/feedback advance.
  await screen.findByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' }); await importCopy(); await intent();
  f.state.extra[`/conversations/${cid}/study-index`] = { ...index(true), conversation_content_version: 17, items: [{ ...index(true).items[0], study_version: 3 }] };
  await selectStudy(); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe('Review study question 1'); expect(button('Apply server-bound task change').disabled).toBe(false); expect(writes(f).filter(([path]) => path === '/user-intents')).toHaveLength(1); expect(writes(f).filter(([path]) => path === '/tasks')).toHaveLength(0);
});
