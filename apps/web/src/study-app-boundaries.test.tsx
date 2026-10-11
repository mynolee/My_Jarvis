import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api, ApiError } from './api';
import { at, cid, index, other, run, sid, study } from '../tests/study-fixtures';
vi.mock('./TodayPanel', () => ({ TodayPanel: () => <section aria-label="Synthetic Today"/> }));
vi.mock('./conversation-history-events', () => ({ watch_history_events: () => () => {} }));
type Peer = { writesPaused: boolean; onAcquirePending: () => boolean; onPendingChange: (value: boolean) => void };
vi.mock('./TasksPanel', () => ({ TasksPanel: (p: Peer) => <><button disabled={p.writesPaused} onClick={() => { if (p.onAcquirePending()) p.onPendingChange(true); }}>Begin peer change</button><button onClick={() => p.onPendingChange(false)}>Finish peer change</button></> }));
type Delete = Peer & { onContentHiddenChange: (value: boolean) => void; onDeleted: (id: string) => void };
vi.mock('./ConversationDeletionPanel', () => ({ ConversationDeletionPanel: (p: Delete) => <><button disabled={p.writesPaused} onClick={() => { if (p.onAcquirePending()) { p.onPendingChange(true); p.onContentHiddenChange(true); } }}>Begin reviewed deletion</button><button onClick={() => { p.onDeleted(cid); p.onContentHiddenChange(false); p.onPendingChange(false); }}>Confirm reviewed deletion</button></> }));
const deferred = <T,>() => { let resolve!: (v: T) => void, reject!: (e: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name }), click = (name: string) => fireEvent.click(button(name));
function fixture() {
  const api = new Api(), state = { authenticated: true, runtime: { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', status: 'DISABLED', ready: false }, history: null as Promise<unknown> | null, read: null as Promise<unknown> | null, mutation: null as Promise<unknown> | null, deleted: false, index: null as Promise<unknown> | null };
  vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation(async (path, body) => {
    const route = path.split('?')[0];
    if (route === '/me') { if (!state.authenticated) throw new ApiError('UNAUTHENTICATED', false, 401); return {}; }
    if (route === '/auth/logout') { state.authenticated = false; return { logged_out: true }; }
    if (route === '/auth/login') { state.authenticated = true; return { csrf_token: 'synthetic-new-session' }; }
    if (route === '/execution') return { execution_paused: false, version: 1, checked_at: at };
    if (route === '/execution/pause') return { execution_paused: true, version: 2, checked_at: at };
    if (route === '/local-model/status') return state.runtime;
    if (route === '/conversations') return body ? { id: other, title: 'New conversation', project_id: null, created_at: at } : { items: [{ id: cid, title: 'Study conversation', project_id: null, created_at: at }, { id: other, title: 'Other conversation', project_id: null, created_at: at }], next_cursor: null };
    if (route.endsWith('/messages') && body === undefined) return state.history ?? { items: [], next_cursor: null, stream_cursor: null, current_run: null };
    if (route.endsWith('/study-index')) return state.index ?? (route.includes(cid) ? index(true) : { ...index(), conversation_id: other, items: [] });
    if (route === `/study-sessions/${sid}` && body === undefined) { if (state.deleted) throw new ApiError('CONVERSATION_DELETED', false, 410); return state.read ?? study(); }
    if (route === `/study-sessions/${sid}/answers`) return state.mutation;
    if (route.startsWith('/study-mutations/')) return { status: 'NOT_OBSERVED', receipt: null, checked_at: at };
    throw new Error('Unexpected synthetic route');
  });
  return { api, state, request };
}
async function open(f = fixture()) { const rendered = render(<App api={f.api}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); await screen.findByText('No saved messages.'); await waitFor(() => expect(button('Find saved study lessons').disabled).toBe(false)); return { ...f, ...rendered }; }
async function resume() {
  await waitFor(() => {
    expect(button('Study conversation').getAttribute('aria-current')).toBe('true');
    expect(screen.getByRole('region', { name: 'Saved transcript' }).getAttribute('aria-busy')).toBe('false');
    expect(button('Find saved study lessons').disabled).toBe(false);
  });
  click('Find saved study lessons'); await screen.findByRole('button', { name: `Resume lesson ${run}` }); click(`Resume lesson ${run}`); await screen.findByLabelText('Answer 1'); await waitFor(() => expect(screen.getByLabelText<HTMLTextAreaElement>('Answer 1').disabled).toBe(false)); }
function enter() { fireEvent.change(screen.getByLabelText('Answer 1'), { target: { value: 'Private app answer' } }); }
const writes = (request: ReturnType<typeof fixture>['request']) => request.mock.calls.filter(([, body]) => body !== undefined);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('reads and records study input with local inference disabled and never uses a generation or ordinary-message route', async () => {
  const f = await open(), hold = deferred<unknown>(); f.state.mutation = hold.promise; await resume(); enter(); expect(button('Generate study lesson').disabled).toBe(true); click('Record answer 1'); expect(writes(f.request)).toHaveLength(1); expect(writes(f.request)[0][0]).toBe(`/study-sessions/${sid}/answers`); await act(async () => hold.reject(new ApiError('UNKNOWN', true)));
});
it('atomically locks peer mutations, conversation and project navigation while an answer is in flight', async () => {
  const f = await open(), hold = deferred<unknown>(); f.state.mutation = hold.promise; click('Open internal tasks'); await resume(); enter(); act(() => { button('Record answer 1').click(); button('Begin peer change').click(); button('Other conversation').click(); });
  expect(writes(f.request)).toHaveLength(1); for (const name of ['Begin peer change', 'Other conversation', 'Start new conversation', 'Generate study lesson', 'Send mock message']) expect(button(name).disabled).toBe(true); await act(async () => hold.reject(new ApiError('UNKNOWN', true))); expect(button('Other conversation').disabled).toBe(true); click('Check original study outcome'); await screen.findByText(/NOT_OBSERVED:/); expect(button('Other conversation').disabled).toBe(true); expect(writes(f.request)).toHaveLength(1);
});
it('rejects the answer synchronously when a peer wins the same-event acquisition race', async () => {
  const f = await open(); click('Open internal tasks'); await resume(); enter(); const record = button('Record answer 1'); act(() => { button('Begin peer change').click(); record.click(); }); expect(writes(f.request)).toHaveLength(0); expect(screen.queryByLabelText('Answer 1')).toBeNull(); click('Finish peer change'); await waitFor(() => expect(button('Find saved study lessons').disabled).toBe(false));
});
it('clears lesson and raw drafts on navigation, including new conversation', async () => {
  await open(); await resume(); enter(); click('Other conversation'); expect(screen.queryByText('Synthetic first question?')).toBeNull(); expect(screen.queryByDisplayValue('Private app answer')).toBeNull(); click('Study conversation'); await screen.findByText('No saved messages.'); await resume(); enter(); click('Start new conversation'); expect(screen.queryByLabelText('Answer 1')).toBeNull();
});
it('hides the study with transcript deletion and clears drafts permanently when deletion is confirmed', async () => {
  await open(); await resume(); enter(); click('대화 원문 삭제 열기'); click('Begin reviewed deletion'); expect(screen.queryByRole('region', { name: 'Durable study' })).toBeNull(); expect(screen.queryByDisplayValue('Private app answer')).toBeNull(); click('Confirm reviewed deletion'); await screen.findByText(/대화 원문 삭제가 확인되었습니다/); expect(screen.queryByText('Synthetic first question?')).toBeNull();
});
it('lets sign-out clear an unknown study, then requires canonical discovery under the new login', async () => {
  const f = await open(); await resume(); enter(); f.state.mutation = Promise.reject(new ApiError('UNKNOWN', true)); f.state.mutation.catch(() => {}); click('Record answer 1'); await screen.findByRole('region', { name: 'Study outcome unknown' }); click('Sign out'); await screen.findByRole('heading', { name: 'Owner sign in' }); await waitFor(() => expect(button('Sign in').disabled).toBe(false)); fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'synthetic-owner' } }); fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } }); click('Sign in'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); expect(screen.queryByRole('button', { name: 'Check original study outcome' })).toBeNull(); expect(screen.queryByDisplayValue('Private app answer')).toBeNull(); expect(f.request.mock.calls.filter(([path]) => path.endsWith('/answers'))).toHaveLength(1);
});
it.each(['navigation', 'new conversation', 'logout', 'deletion', 'execution boundary'])('does not paint a held study read after %s', async boundary => {
  const f = await open(), hold = deferred<unknown>(); f.state.read = hold.promise; click('Find saved study lessons'); await screen.findByRole('button', { name: `Resume lesson ${run}` }); click(`Resume lesson ${run}`);
  if (boundary === 'navigation') click('Other conversation');
  else if (boundary === 'new conversation') click('Start new conversation');
  else if (boundary === 'logout') click('Sign out');
  else if (boundary === 'deletion') { click('대화 원문 삭제 열기'); click('Begin reviewed deletion'); }
  else { fireEvent.click(screen.getByRole('checkbox', { name: 'I reviewed the account-wide impact for this checked version' })); click('Pause account execution'); }
  await act(async () => hold.resolve(study())); expect(screen.queryByText('Synthetic first question?')).toBeNull(); expect(screen.queryByLabelText('Answer 1')).toBeNull();
});

it('waits for the selected transcript before fresh study discovery after navigation', async () => {
  const f = await open(); await resume(); enter();
  const history = deferred<unknown>(); f.state.history = history.promise;
  click('Other conversation'); expect(screen.queryByDisplayValue('Private app answer')).toBeNull();
  click('Study conversation'); expect(screen.getByRole('region', { name: 'Saved transcript' }).getAttribute('aria-busy')).toBe('true');
  const discoveries = () => f.request.mock.calls.filter(([path]) => path.startsWith(`/conversations/${cid}/study-index`));
  const before = discoveries().length, resuming = resume().then(() => null, error => error);
  try { expect(discoveries()).toHaveLength(before); }
  finally { await act(async () => history.resolve({ items: [], next_cursor: null, stream_cursor: null, current_run: null })); }
  expect(await resuming).toBeNull();
  expect(discoveries()).toHaveLength(before + 1); expect(screen.getByLabelText('Answer 1')).toHaveProperty('value', '');
  expect(writes(f.request)).toHaveLength(0);
});
