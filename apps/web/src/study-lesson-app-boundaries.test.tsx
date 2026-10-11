import { act, cleanup, configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api, ApiError } from './api';
import { UUID, type Run } from './domain';

// Synthetic coordinator/history tests. No model, browser, provider, or backend execution.
configure({ asyncUtilTimeout: 5000 });
vi.mock('./TodayPanel', () => ({ TodayPanel: () => <section aria-label="Synthetic Today"/> }));
vi.mock('./conversation-history-events', () => ({ watch_history_events: () => () => {} }));
type PendingProps = { writesPaused: boolean; onAcquirePending: () => boolean; onPendingChange: (pending: boolean) => void };
vi.mock('./TasksPanel', () => ({ TasksPanel: (p: PendingProps) => <><button disabled={p.writesPaused} onClick={() => { if (p.onAcquirePending()) p.onPendingChange(true); }}>Begin peer task</button><button onClick={() => p.onPendingChange(false)}>Resolve peer task</button></> }));
type DeletionProps = PendingProps & { onContentHiddenChange: (hidden: boolean) => void; onDeleted: (id: string) => void };
vi.mock('./ConversationDeletionPanel', () => ({ ConversationDeletionPanel: (p: DeletionProps) => <><button disabled={p.writesPaused} onClick={() => { if (p.onAcquirePending()) { p.onPendingChange(true); p.onContentHiddenChange(true); } }}>Begin synthetic deletion</button><button onClick={() => { p.onDeleted(target); p.onContentHiddenChange(false); p.onPendingChange(false); }}>Confirm synthetic deletion</button></> }));
const target = '11111111-1111-4111-8111-111111111111';
const other = '22222222-2222-4222-8222-222222222222';
const runId = '33333333-3333-4333-8333-333333333333';
const messageId = '44444444-4444-4444-8444-444444444444';
const stamp = '2026-10-10T00:00:00Z';
const ready = { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: true, status: 'READY' };
const available = { execution_paused: false, version: 7, checked_at: stamp };
const paused = { ...available, execution_paused: true, version: 8 };
const deferred = <T,>() => { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function fixture(authenticated = true) {
  const state = {
    authenticated, runtime: ready as unknown, execution: available, run: null as Run | null,
    submit: null as Promise<unknown> | null, create: null as Promise<unknown> | null,
    login: null as Promise<unknown> | null, cancel: null as Promise<unknown> | null,
    history: null as Promise<unknown> | null, rejection: null as ApiError | null,
  };
  const history = () => ({ items: state.run?.result ? [{ id: messageId, role: 'ASSISTANT', text: state.run.result, run_id: runId, execution_mode: 'LOCAL_INFERENCE', created_at: stamp }] : [], next_cursor: null, stream_cursor: null, current_run: state.run });
  const api = new Api(); vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation(async (path, body) => {
    const route = path.split('?')[0];
    if (route === '/me') { if (!state.authenticated) throw new ApiError('UNAUTHENTICATED', false, 401); return {}; }
    if (route === '/auth/login') { if (state.login) return state.login; state.authenticated = true; return { csrf_token: 'synthetic-session-token' }; }
    if (route === '/auth/logout') { state.authenticated = false; return { logged_out: true }; }
    if (route === '/auth/reauthenticate') return { csrf_token: 'synthetic-rotated-token', authenticated: true };
    if (route === '/execution') return state.execution;
    if (route === '/execution/pause') { state.execution = paused; return state.execution; }
    if (route === '/execution/resume') { state.execution = { ...available, version: 9 }; return state.execution; }
    if (route === '/local-model/status') { if (state.runtime instanceof Error) throw state.runtime; return state.runtime; }
    if (route === '/conversations') {
      if (body !== undefined) return state.create ?? { id: target, title: 'New lesson conversation', project_id: null, created_at: stamp };
      return { items: [{ id: target, title: 'Lesson conversation', project_id: null, created_at: stamp }, { id: other, title: 'Other conversation', project_id: null, created_at: stamp }], next_cursor: null };
    }
    if (body !== undefined && /\/(study-lessons|local-inference|messages)$/.test(route)) {
      if (state.submit) return state.submit;
      if (state.rejection) throw state.rejection;
      state.run = { id: runId, status: 'QUEUED', version: 3, execution_mode: route.endsWith('/messages') ? 'MOCK' : 'LOCAL_INFERENCE', result: null, approval_id: null, job_id: null };
      return { run_id: runId, status: 'QUEUED' };
    }
    if (route === `/conversations/${target}/messages`) return state.history ?? history();
    if (route === `/conversations/${other}/messages`) return { items: [], next_cursor: null, stream_cursor: null, current_run: null };
    if (route === `/runs/${runId}/cancel`) { if (state.cancel) return state.cancel; state.run = { ...state.run!, status: 'CANCELLED', version: 4 }; return {}; }
    throw new Error(`Unexpected synthetic route: ${route}`);
  });
  return { api, state, request, history };
}
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name });
const click = (name: string) => fireEvent.click(button(name));
const lesson = () => button('Generate study lesson');
const enter = (text = 'Explain a synthetic topic with a short exercise.') => fireEvent.change(screen.getByLabelText('Message'), { target: { value: text } });
const review = () => fireEvent.click(screen.getByRole('checkbox', { name: 'I reviewed the account-wide impact for this checked version' }));
const lessonWrites = (request: ReturnType<typeof fixture>['request']) => request.mock.calls.filter(([path, body]) => path.endsWith('/study-lessons') && body !== undefined);
const allGenerationWrites = (request: ReturnType<typeof fixture>['request']) => request.mock.calls.filter(([path, body]) => /\/(study-lessons|local-inference|messages)$/.test(path) && body !== undefined);
async function open(api: Api) {
  const view = render(<App api={api}/>);
  await screen.findByText('Account state: AVAILABLE FOR NEW WORK');
  await screen.findByText('No saved messages.');
  await waitFor(() => expect(within(screen.getByRole('region', { name: 'Local model runtime' })).getByRole('status').textContent).toBe('READY'));
  return view;
}
async function login() {
  await waitFor(() => expect(button('Sign in').disabled).toBe(false));
  fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'synthetic-owner' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } });
  click('Sign in');
}
async function sendLesson() { enter(); click('Generate study lesson'); await screen.findByText('QUEUED'); await waitFor(() => expect(button('Refresh server state').disabled).toBe(false)); }
beforeEach(() => { vi.stubGlobal('fetch', vi.fn(() => { throw new Error('Unexpected network access in synthetic lesson test.'); })); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it('requires login and explicit lesson action without generating on login or refresh', async () => {
  const { api, request } = fixture(false); render(<App api={api}/>);
  await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByRole('button', { name: 'Generate study lesson' })).toBeNull();
  await login(); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); await screen.findByText('No saved messages.');
  enter(); click('Refresh server state'); await waitFor(() => expect(lesson().disabled).toBe(false));
  expect(allGenerationWrites(request)).toHaveLength(0); expect(screen.getByText(/Runtime READY alone does not confirm that support/)).toBeTruthy();
});

it('posts only text and one canonical client identity to the dedicated route with the matching idempotency header', async () => {
  const { api, request } = fixture(); await open(api); await sendLesson();
  const writes = lessonWrites(request); expect(writes).toHaveLength(1);
  const [path, body, key] = writes[0];
  expect(path).toBe(`/conversations/${target}/study-lessons`);
  expect(Object.keys(body as object).sort()).toEqual(['client_message_id', 'text']);
  expect(UUID.test(key!)).toBe(true); expect(body).toEqual({ client_message_id: key, text: 'Explain a synthetic topic with a short exercise.' });
  expect(allGenerationWrites(request)).toHaveLength(1); expect(screen.queryByText('SUCCEEDED')).toBeNull();
  // Exercise the real shared transport with a synthetic HTTP response, never a server.
  const fetch = vi.fn(async () => new Response(JSON.stringify({ data: { run_id: runId, status: 'QUEUED' } }), { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetch); const transport = new Api(); transport.set_csrf('synthetic-csrf'); await transport.request(path, body, key);
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(fetch.mock.calls[0]).toEqual([`/api/v1${path}`, expect.objectContaining({ method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error', headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-CSRF-Token': 'synthetic-csrf', 'Idempotency-Key': key }, body: JSON.stringify(body) })]);
});

it.each(['Generate study lesson', 'Send to local model', 'Send mock message'])('atomically lets only the first %s action acquire the shared submit lock', async first => {
  const hold = deferred<unknown>(); const { api, state, request } = fixture(); state.submit = hold.promise; await open(api); enter();
  act(() => { button(first).click(); lesson().click(); button('Send to local model').click(); button('Send mock message').click(); });
  expect(allGenerationWrites(request)).toHaveLength(1);
  expect(allGenerationWrites(request)[0][0].split('/').at(-1)).toBe(first === 'Generate study lesson' ? 'study-lessons' : first === 'Send to local model' ? 'local-inference' : 'messages');
  for (const name of ['Generate study lesson', 'Send to local model', 'Send mock message', 'Sign out', 'Start new conversation', 'Other conversation']) expect(button(name).disabled).toBe(true);
  await act(async () => hold.resolve({ run_id: runId, status: 'QUEUED' }));
});

it.each(['local-inference', 'messages'])('preserves the existing %s payload and request invocation', async route => {
  const { api, request } = fixture(); await open(api); enter('Synthetic ordinary input'); click(route === 'local-inference' ? 'Send to local model' : 'Send mock message'); await screen.findByText('QUEUED');
  const writes = allGenerationWrites(request); expect(writes).toHaveLength(1); expect(writes[0]).toHaveLength(2);
  expect(writes[0][0]).toBe(`/conversations/${target}/${route}`);
  expect(writes[0][1]).toEqual({ client_message_id: expect.any(String), text: 'Synthetic ordinary input', ...(route === 'messages' ? { attachment_ids: [] } : {}) });
  expect(lessonWrites(request)).toHaveLength(0);
});

it.each(['DISABLED', 'UNAVAILABLE', 'LOADING', 'malformed'])('fails closed for study generation when local runtime is %s', async status => {
  const { api, state, request } = fixture(); await open(api);
  state.runtime = status === 'malformed' ? { ...ready, purpose: 'untrusted' } : { ...ready, ready: false, status };
  click('Refresh server state'); enter(); await waitFor(() => expect(lesson().disabled).toBe(true)); click('Generate study lesson');
  expect(lessonWrites(request)).toHaveLength(0); expect(button('Send mock message').disabled).toBe(false);
});

it('blocks stale readiness and over-limit Unicode text, while allowing 8000 code points', async () => {
  const { api, request } = fixture(); await open(api); enter('😀'.repeat(8001)); click('Generate study lesson');
  await screen.findByText('Message exceeds the 8000-character local input limit.'); expect(lessonWrites(request)).toHaveLength(0);
  enter('가'.repeat(8000)); const clock = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 10001); click('Generate study lesson');
  await screen.findByText('Runtime status is stale. Refresh server state before sending.'); expect(lessonWrites(request)).toHaveLength(0);
  clock.mockRestore(); click('Refresh server state'); await waitFor(() => expect(lesson().disabled).toBe(false)); click('Generate study lesson'); await screen.findByText('QUEUED'); expect(lessonWrites(request)).toHaveLength(1);
});

it('shows canonical lesson completion as literal text without turning ordinary prose into typed lesson authority', async () => {
  const { api, state, request } = fixture(); const { container } = await open(api); await sendLesson();
  state.run = { ...state.run!, status: 'SUCCEEDED', result: '<img src="https://example.invalid/leak"> Synthetic lesson\nReview: 2 + 2 = 4' };
  click('Refresh server state'); await screen.findByText('SUCCEEDED');
  expect(screen.getByRole('region', { name: 'Saved transcript' }).querySelector('li p')?.textContent).toBe(state.run.result);
  expect(container.querySelector('img,a,iframe')).toBeNull(); expect(allGenerationWrites(request)).toHaveLength(1);
  expect(screen.queryByRole('button', { name: /submit answer|save lesson|verify lesson/i })).toBeNull();
});

it('shows an unavailable canonical failure without claiming success, fabricating a lesson, or falling back', async () => {
  const { api, state, request } = fixture(); await open(api); await sendLesson(); state.run = { ...state.run!, status: 'FAILED', result: 'MODEL_UNAVAILABLE' };
  click('Refresh server state'); await screen.findByText('FAILED'); expect(screen.getAllByText('MODEL_UNAVAILABLE').length).toBeGreaterThan(0);
  expect(screen.queryByText('SUCCEEDED')).toBeNull(); expect(allGenerationWrites(request)).toHaveLength(1);
  click('Refresh server state'); expect(allGenerationWrites(request)).toHaveLength(1);
});

it.each(['INFERENCE_CAPACITY_RESERVED', 'EXECUTION_PAUSED', 'IDEMPOTENCY_KEY_REUSED'])('reports %s without any automatic generation retry or fallback', async code => {
  const { api, state, request } = fixture(); state.rejection = new ApiError(code, false, code === 'INFERENCE_CAPACITY_RESERVED' ? 429 : 409); await open(api); enter(); click('Generate study lesson');
  await screen.findByText(code); click('Refresh server state'); await waitFor(() => expect(button('Refresh server state').disabled).toBe(false)); expect(allGenerationWrites(request)).toHaveLength(1); expect(screen.queryByText('SUCCEEDED')).toBeNull();
});

it('preserves lost-response UNKNOWN across refresh, navigation, and repeated study clicks', async () => {
  const { api, state, request } = fixture(); state.rejection = new ApiError('UNKNOWN', true); await open(api); enter(); click('Generate study lesson'); await screen.findByText('Writes paused');
  click('Refresh server state'); await waitFor(() => expect(button('Refresh server state').disabled).toBe(false));
  for (const name of ['Generate study lesson', 'Send to local model', 'Send mock message', 'Other conversation', 'Start new conversation']) { expect(button(name).disabled).toBe(true); click(name); }
  expect(allGenerationWrites(request)).toHaveLength(1); expect(screen.queryByRole('button', { name: 'Reconcile mock outcome' })).toBeNull();
});

it('clears the private workspace on a current study 401', async () => {
  const { api, state, request } = fixture(); state.rejection = new ApiError('UNAUTHENTICATED', false, 401); await open(api); enter('Private synthetic lesson input'); click('Generate study lesson');
  await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByLabelText('Message')).toBeNull(); expect(screen.queryByText('Private synthetic lesson input')).toBeNull(); expect(allGenerationWrites(request)).toHaveLength(1);
});

it('blocks study while a peer or deletion owns the mutation fence, including a same-turn attempt', async () => {
  const { api, request } = fixture(); await open(api); enter(); click('Open internal tasks');
  act(() => { button('Begin peer task').click(); lesson().click(); }); expect(lesson().disabled).toBe(true); expect(lessonWrites(request)).toHaveLength(0);
  click('Resolve peer task'); click('대화 원문 삭제 열기');
  act(() => { button('Begin synthetic deletion').click(); lesson().click(); });
  expect(screen.queryByRole('button', { name: 'Generate study lesson' })).toBeNull(); expect(lessonWrites(request)).toHaveLength(0);
  click('Confirm synthetic deletion'); await screen.findByText(/대화 원문 삭제가 확인/); expect(screen.queryByText('SUCCEEDED')).toBeNull(); expect(lessonWrites(request)).toHaveLength(0);
});

it('blocks peer/deletion acquisition while a study submit is pending', async () => {
  const hold = deferred<unknown>(); const { api, state, request } = fixture(); state.submit = hold.promise; await open(api); enter(); click('Open internal tasks'); click('대화 원문 삭제 열기');
  act(() => { lesson().click(); button('Begin peer task').click(); button('Begin synthetic deletion').click(); });
  expect(button('Begin peer task').disabled).toBe(true); expect(button('Begin synthetic deletion').disabled).toBe(true); expect(screen.getByRole('region', { name: 'Saved transcript' })).toBeTruthy();
  await act(async () => hold.reject(new ApiError('UNKNOWN', true))); await screen.findByText('Writes paused'); expect(allGenerationWrites(request)).toHaveLength(1);
});

it('pause and resume do not automatically submit staged study input', async () => {
  const { api, request } = fixture(); await open(api); enter(); review(); click('Pause account execution'); await screen.findByText('Account state: PAUSED');
  expect(lesson().disabled).toBe(true); click('Generate study lesson'); review(); click('Resume new execution'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK');
  expect(lesson().disabled).toBe(false); expect(lessonWrites(request)).toHaveLength(0); expect((screen.getByLabelText('Message') as HTMLTextAreaElement).value).not.toBe('');
});

it.each(['success', '401'])('ignores a late study %s after emergency pause and keeps interrupted work fenced after resume', async outcome => {
  const hold = deferred<unknown>(); const { api, state, request } = fixture(); state.submit = hold.promise; await open(api); enter(); click('Generate study lesson'); review(); click('Pause account execution'); await screen.findByText('Account state: PAUSED');
  review(); click('Resume new execution'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK');
  await act(async () => { if (outcome === 'success') hold.resolve({ run_id: runId, status: 'QUEUED' }); else hold.reject(new ApiError('UNAUTHENTICATED', false, 401)); });
  expect(lesson().disabled).toBe(true); expect(screen.getByText(/Earlier work remains unresolved across this safety boundary/)).toBeTruthy(); expect(screen.queryByText('QUEUED')).toBeNull(); expect(allGenerationWrites(request)).toHaveLength(1);
});

it.each(['success', '401'])('discards an old study %s after page disposal and a newer login without unlocking or expiring that login', async outcome => {
  const hold = deferred<unknown>(), signIn = deferred<unknown>(); const { api, state, request } = fixture(); state.submit = hold.promise; await open(api); enter(); click('Generate study lesson');
  fireEvent(window, new Event('pagehide')); await screen.findByRole('heading', { name: 'Owner sign in' }); state.login = signIn.promise; await login(); expect(button('Sign in').disabled).toBe(true);
  await act(async () => { if (outcome === 'success') hold.resolve({ run_id: runId, status: 'QUEUED' }); else hold.reject(new ApiError('UNAUTHENTICATED', false, 401)); });
  expect(button('Sign in').disabled).toBe(true); expect(screen.queryByText('QUEUED')).toBeNull();
  await act(async () => signIn.resolve({ csrf_token: 'synthetic-next-session' })); await screen.findByText('Account state: AVAILABLE FOR NEW WORK');
  expect(screen.getByText(/A previous request may still have committed/)).toBeTruthy(); expect((screen.getByLabelText('Message') as HTMLTextAreaElement).value).toBe(''); expect(allGenerationWrites(request)).toHaveLength(1);
});

it('does not chain study submission from a late conversation-creation response after page disposal', async () => {
  const hold = deferred<unknown>(); const { api, state, request } = fixture(); await open(api); click('Start new conversation'); enter(); state.create = hold.promise; click('Generate study lesson');
  fireEvent(window, new Event('pagehide')); await act(async () => hold.resolve({ id: target, title: 'Late private title', project_id: null, created_at: stamp }));
  await screen.findByRole('heading', { name: 'Owner sign in' }); expect(lessonWrites(request)).toHaveLength(0); expect(screen.queryByText('Late private title')).toBeNull();
});

it('ignores stale lesson history after selecting another conversation', async () => {
  const { api, state } = fixture(); await open(api); await sendLesson(); const hold = deferred<unknown>(); state.history = hold.promise; click('Refresh server state');
  await waitFor(() => expect(button('Other conversation').disabled).toBe(false)); click('Other conversation'); await screen.findByText('No saved messages.');
  await act(async () => hold.resolve({ items: [{ id: messageId, role: 'ASSISTANT', text: 'Stale synthetic lesson', run_id: runId, execution_mode: 'LOCAL_INFERENCE', created_at: stamp }], next_cursor: null, stream_cursor: null, current_run: { ...state.run!, status: 'SUCCEEDED', result: 'Stale synthetic lesson' } }));
  expect(screen.queryByText('Stale synthetic lesson')).toBeNull(); expect(screen.queryByText('SUCCEEDED')).toBeNull(); expect(button('Other conversation').getAttribute('aria-current')).toBe('true');
});

it.each(['CANCELLED', 'UNKNOWN'])('uses canonical cancellation version once and waits for server %s', async outcome => {
  const hold = deferred<unknown>(); const { api, state, request } = fixture(); await open(api); await sendLesson(); state.cancel = hold.promise;
  act(() => { button('Cancel run').click(); button('Cancel run').click(); }); expect(screen.queryByText('CANCELLED')).toBeNull(); expect(screen.getByText('QUEUED')).toBeTruthy();
  const cancellations = request.mock.calls.filter(([path]) => path.endsWith('/cancel')); expect(cancellations).toHaveLength(1); expect(cancellations[0][1]).toEqual({ expected_version: 3 }); expect(UUID.test(cancellations[0][2]!)).toBe(true);
  state.run = { ...state.run!, status: outcome }; await act(async () => hold.resolve({}));
  if (outcome === 'CANCELLED') await screen.findByText('The server reports this run cancelled.');
  else { await screen.findByText('Writes paused'); expect(lesson().disabled).toBe(true); expect(screen.queryByRole('button', { name: 'Reconcile mock outcome' })).toBeNull(); }
  expect(allGenerationWrites(request)).toHaveLength(1);
});


it.each(['success', '401'])('keeps late lesson history %s private after sign-out and sign-in', async outcome => {
  const { api, state } = fixture(); await open(api); await sendLesson(); const hold = deferred<unknown>(); state.history = hold.promise; click('Refresh server state');
  await waitFor(() => expect(button('Sign out').disabled).toBe(false)); click('Sign out'); await screen.findByRole('heading', { name: 'Owner sign in' });
  state.history = null; state.run = null; await login(); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); await screen.findByText('No saved messages.');
  await act(async () => { if (outcome === '401') hold.reject(new ApiError('UNAUTHENTICATED', false, 401)); else hold.resolve({ items: [], next_cursor: null, stream_cursor: null, current_run: { id: runId, status: 'SUCCEEDED', version: 4, execution_mode: 'LOCAL_INFERENCE', result: 'Old session lesson', approval_id: null, job_id: null } }); });
  expect(screen.queryByText('Old session lesson')).toBeNull(); expect(screen.queryByText('SUCCEEDED')).toBeNull(); expect(screen.queryByRole('heading', { name: 'Owner sign in' })).toBeNull();
});

it('drops late lesson history after confirmed deletion hides and clears its source', async () => {
  const { api, state } = fixture(); await open(api); await sendLesson(); const hold = deferred<unknown>(); state.history = hold.promise; click('Refresh server state');
  click('대화 원문 삭제 열기'); await waitFor(() => expect(button('Begin synthetic deletion').disabled).toBe(false)); click('Begin synthetic deletion'); expect(screen.queryByRole('region', { name: 'Saved transcript' })).toBeNull();
  state.history = null; state.run = null; click('Confirm synthetic deletion'); await screen.findByText(/대화 원문 삭제가 확인/);
  await act(async () => hold.resolve({ items: [], next_cursor: null, stream_cursor: null, current_run: { id: runId, status: 'SUCCEEDED', version: 4, execution_mode: 'LOCAL_INFERENCE', result: 'Deleted source lesson', approval_id: null, job_id: null } }));
  expect(screen.queryByText('Deleted source lesson')).toBeNull(); expect(screen.queryByText('SUCCEEDED')).toBeNull();
});


it.each([false, true])('exposes actual study unavailability while preserving uncertain=%s and never falling back', async uncertain => {
  const { api, state, request } = fixture(); state.rejection = new ApiError('LOCAL_MODEL_UNAVAILABLE', uncertain, 503); await open(api); enter(); click('Generate study lesson');
  await screen.findByRole('alert'); expect(screen.getByRole('alert').textContent).toContain('LOCAL_MODEL_UNAVAILABLE:');
  if (uncertain) { await screen.findByText('Writes paused'); expect(screen.getByRole('alert').textContent).toContain('UNKNOWN'); }
  expect(lesson().disabled).toBe(true); expect(allGenerationWrites(request)).toHaveLength(1);
  click('Refresh server state'); await waitFor(() => expect(button('Refresh server state').disabled).toBe(false));
  expect(allGenerationWrites(request)).toHaveLength(1); if (uncertain) expect(lesson().disabled).toBe(true);
});

it.each(['Generate study lesson', 'Send to local model', 'Send mock message'])('clears a current-target deletion without retaining the obsolete submit lock for %s', async action => {
  const { api, state, request } = fixture(); state.rejection = new ApiError('CONVERSATION_DELETED', false, 410); await open(api); enter(); click(action);
  await screen.findByText(/대화 원문 삭제가 확인/); expect(screen.queryByText('QUEUED')).toBeNull();
  for (const name of ['Sign out', 'Refresh server state', 'Start new conversation']) expect(button(name).disabled).toBe(false);
  click('Refresh server state'); await waitFor(() => expect(button('Refresh server state').disabled).toBe(false)); expect(allGenerationWrites(request)).toHaveLength(1);
});

it('does not let a stale study 410 delete the newer selection or unlock its pending operation', async () => {
  const old = deferred<unknown>(), fresh = deferred<unknown>(); const { api, state, request } = fixture(); state.submit = old.promise; await open(api); enter(); click('Generate study lesson');
  fireEvent(window, new Event('pagehide')); await screen.findByRole('heading', { name: 'Owner sign in' }); await login(); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); await screen.findByText('No saved messages.');
  click('Other conversation'); state.submit = fresh.promise; enter('New session input'); click('Generate study lesson');
  await act(async () => old.reject(new ApiError('CONVERSATION_DELETED', false, 410)));
  expect(button('Other conversation').getAttribute('aria-current')).toBe('true'); expect(button('Sign out').disabled).toBe(true); expect(lesson().disabled).toBe(true); expect(screen.queryByText(/대화 원문 삭제가 확인/)).toBeNull();
  expect(lessonWrites(request)).toHaveLength(2); await act(async () => fresh.reject(new ApiError('UNKNOWN', true))); await screen.findByText('Writes paused');
});


it.each(['ambiguous submit', 'unknown run'])('keeps %s fenced when a later tombstone clears the deleted lesson source', async outcome => {
  const { api, state, request } = fixture();
  if (outcome === 'unknown run') state.run = { id: runId, status: 'UNKNOWN', version: 3, execution_mode: 'LOCAL_INFERENCE', result: null, approval_id: null, job_id: null };
  else state.rejection = new ApiError('UNKNOWN', true);
  await open(api);
  if (outcome === 'ambiguous submit') { enter('Private pending lesson input'); click('Generate study lesson'); }
  await screen.findByText('Writes paused');
  const hold = deferred<unknown>(); state.history = hold.promise;
  const before = request.mock.calls.filter(([path]) => path.startsWith(`/conversations/${target}/messages`)).length;
  click('Refresh server state');
  await waitFor(() => expect(request.mock.calls.filter(([path]) => path.startsWith(`/conversations/${target}/messages`)).length).toBeGreaterThan(before));
  await act(async () => hold.reject(new ApiError('CONVERSATION_DELETED', false, 410)));
  await screen.findByText(/대화 원문 삭제가 확인/);
  expect(screen.queryByRole('region', { name: 'Saved transcript' })).toBeNull(); expect(screen.queryByText('Private pending lesson input')).toBeNull();
  expect(screen.getByText('Writes paused')).toBeTruthy();
  for (const name of ['Generate study lesson', 'Send to local model', 'Send mock message', 'Start new conversation', 'Other conversation']) expect(button(name).disabled).toBe(true);
  expect(button('Refresh server state').disabled).toBe(false); expect(button('Sign out').disabled).toBe(false);
  expect(allGenerationWrites(request)).toHaveLength(outcome === 'ambiguous submit' ? 1 : 0);
});
