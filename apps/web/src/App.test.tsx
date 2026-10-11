import { act, configure, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api, ApiError } from './api';
configure({ asyncUtilTimeout: 5000 });
const conversation_id = '11111111-1111-4111-8111-111111111111', run_id = '22222222-2222-4222-8222-222222222222', approval_id = '33333333-3333-4333-8333-333333333333';
class FakeSource { static instances: FakeSource[] = []; closed = false; onopen = null; onerror = null; constructor() { FakeSource.instances.push(this); } addEventListener() {} close() { this.closed = true; } }
beforeEach(() => { FakeSource.instances = []; vi.stubGlobal('EventSource', FakeSource); vi.stubGlobal('fetch', vi.fn(async () => new Response('retry: 2000\n\n', { headers: { 'Content-Type': 'text/event-stream' } }))); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
function fixture() {
  let authenticated = false, sent = false, state = 'WAITING_APPROVAL';
  const api = new Api(); const calls: Array<{ path: string; body?: unknown }> = [];
  vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue(undefined);
  vi.spyOn(api, 'request').mockImplementation(async <T,>(path: string, body?: unknown): Promise<T> => {
    calls.push({ path, body }); path = path.split('?')[0]; let result: unknown = {};
    if (path === '/auth/login') { authenticated = true; result = { csrf_token: 'fixture-token' }; }
    else if (path === '/execution') result = { execution_paused: false, version: 1, checked_at: '2026-10-10T00:00:00Z' };
    else if (path === '/local-model/status') result = { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: false, status: 'DISABLED' };
    else if (path === '/auth/logout') { authenticated = false; result = { logged_out: true }; }
    else if (path === '/me') { if (!authenticated) throw new ApiError('UNAUTHENTICATED', false, 401); }
    else if (path === '/projects') result = { items: [{ id: '55555555-5555-4555-8555-555555555555', name: 'Project One', description: '', active: true, version: 1, created_at: '2026-10-10T00:00:00Z' }], next_cursor: null };
    else if (path === '/projects/55555555-5555-4555-8555-555555555555') result = { id: '55555555-5555-4555-8555-555555555555', name: 'Project One', description: '', active: true, version: 1, created_at: '2026-10-10T00:00:00Z' };
    else if (path === '/conversations' && body) result = { id: conversation_id, title: 'Mock', project_id: null, created_at: '2026-10-09T00:00:00Z' };
    else if (path === '/conversations') result = { items: [{ id: conversation_id, title: 'Mock', project_id: null, created_at: '2026-10-09T00:00:00Z' }], next_cursor: null };
    else if (path.endsWith('/messages')) { if (body) { sent = true; result = { run_id, status: state }; } else result = { items: sent ? [{ id: run_id, text: '<img src="https://example.invalid/steal">', role: 'USER', run_id, execution_mode: 'MOCK', created_at: '2026-10-09T00:00:00Z' }] : [], next_cursor: null, stream_cursor: null, current_run: sent ? { id: run_id, status: state, version: 1, execution_mode: 'MOCK', result: state === 'SUCCEEDED' ? 'MOCK verified synthetic result' : null, approval_id, job_id: null } : null }; }
    else if (path === `/runs/${run_id}`) result = { id: run_id, status: state, version: 1, result: state === 'SUCCEEDED' ? 'MOCK verified synthetic result' : null, approval_id, job_id: null };
    else if (path.endsWith('/decision')) { state = (body as {decision:string}).decision === 'APPROVE' ? 'SUCCEEDED' : 'CANCELLED'; }
    else if (path.endsWith('/cancel')) { state = 'CANCELLED'; }
    else if (path === `/approvals/${approval_id}`) result = { id: approval_id, action_id: run_id, action_version: 1, payload_hash: 'a'.repeat(64), status: state === 'WAITING_APPROVAL' ? 'PENDING' : 'CONSUMED', expires_at: '2099-01-01T00:00:00Z', payload: { tool_id: 'calendar.event.create', arguments: { title: 'Mock event' } } };
    else throw new Error(`Unexpected ${path}`);
    return result as T;
  });
  return { api, calls };
}
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name });
async function enabled(name: string) { await waitFor(() => expect(button(name).disabled).toBe(false)); }
async function composer_ready() { await waitFor(() => expect(screen.getByLabelText<HTMLTextAreaElement>('Message').disabled).toBe(false)); }
async function login() {
  await enabled('Sign in');
  fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'owner' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } });
  fireEvent.click(button('Sign in'));
  await screen.findByRole('heading', { name: 'Conversation' });
  // The workspace can render before the independent execution-state read completes.
  await composer_ready();
}
async function send() {
  await composer_ready();
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: '/calendar' } });
  await enabled('Send mock message');
  fireEvent.click(button('Send mock message'));
  await screen.findByRole('heading', { name: 'Review exact server snapshot' });
  // History can publish the approval before the submit's runtime refresh unlocks writes.
  await enabled('Approve exact action');
}
function hold_response(api: Api, held_path: string) {
  const request = vi.mocked(api.request).getMockImplementation()!;
  let release!: () => void, pending = 0, released = false;
  const gate = new Promise<void>(resolve => { release = resolve; });
  vi.spyOn(api, 'request').mockImplementation(async <T,>(path: string, body?: unknown, key?: string): Promise<T> => {
    const response = await request(path, body, key);
    if (path === held_path && !released) { pending++; await gate; }
    return response as T;
  });
  return { pending: () => pending, release: async () => { await act(async () => { released = true; release(); }); } };
}
it('executes login → chat → frozen approval → mock result; repeated clicks write once; logout erases DOM and closes stream', async () => {
  const { api, calls } = fixture(); const { container } = render(<App api={api}/>); await login(); await send();
  expect(container.querySelector('img')).toBeNull(); const button = screen.getByRole('button', { name: 'Approve exact action' }); fireEvent.click(button); fireEvent.click(button);
  await screen.findByText('MOCK verified synthetic result'); const decisions = calls.filter(call => call.path.endsWith('/decision')); expect(decisions).toHaveLength(1); expect(decisions[0].body).toEqual({ decision: 'APPROVE', expected_action_version: 1, payload_hash: 'a'.repeat(64) });
  await enabled('Sign out'); fireEvent.click(screen.getByRole('button', { name: 'Sign out' })); await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByText('MOCK verified synthetic result')).toBeNull(); await waitFor(() => expect(FakeSource.instances.every(source => source.closed)).toBe(true));
  expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0);
});
it('reload obtains current snapshot from server instead of browser storage', async () => { const { api } = fixture(); const first = render(<App api={api}/>); await login(); await send(); first.unmount(); render(<App api={api}/>); await screen.findByRole('heading', { name: 'Review exact server snapshot' }); await enabled('Approve exact action'); expect((screen.getByRole('button', { name: 'Approve exact action' }) as HTMLButtonElement).disabled).toBe(false); });
it('rejects and cancels through separate explicit actions', async () => { const { api, calls } = fixture(); render(<App api={api}/>); await login(); await send(); fireEvent.click(screen.getByRole('button', { name: 'Reject action' })); await screen.findByText('CANCELLED'); expect(calls.find(call => call.path.endsWith('/decision'))?.body).toMatchObject({ decision: 'REJECT' }); });
it('cancel sends the server run version', async () => { const { api, calls } = fixture(); render(<App api={api}/>); await login(); await send(); fireEvent.click(screen.getByRole('button', { name: 'Cancel run' })); await screen.findByText('CANCELLED'); expect(calls.find(call => call.path.endsWith('/cancel'))?.body).toEqual({ expected_version: 1 }); });
it('uncertain approval disables writes and does not replay during read-only refresh', async () => {
  const { api, calls } = fixture(); const request = vi.mocked(api.request).getMockImplementation()!; vi.spyOn(api, 'request').mockImplementation(async <T,>(path: string, body?: unknown): Promise<T> => { if (path.endsWith('/decision')) { calls.push({ path, body }); throw new ApiError('UNKNOWN', true); } return await request(path, body) as T; });
  render(<App api={api}/>); await login(); await send(); fireEvent.click(screen.getByRole('button', { name: 'Approve exact action' })); await screen.findByText('Writes paused'); expect((screen.getByRole('button', { name: 'Approve exact action' }) as HTMLButtonElement).disabled).toBe(true); await enabled('Refresh server state'); fireEvent.click(screen.getByRole('button', { name: 'Refresh server state' })); expect(calls.filter(call => call.path.endsWith('/decision'))).toHaveLength(1);
});

it('uses a verified project only for a new conversation and never changes an existing conversation', async () => {
  const { api, calls } = fixture(); render(<App api={api}/>); await login(); await send();
  const before = calls.filter(call => call.path === '/conversations' && call.body).length;
  fireEvent.click(screen.getByRole('button', { name: 'Choose project for a new conversation' }));
  await screen.findByRole('option', { name: 'Project One' }); fireEvent.change(screen.getByLabelText('New conversation project'), { target: { value: '55555555-5555-4555-8555-555555555555' } });
  await waitFor(() => expect(screen.getByText(/Next new conversation project:/).textContent).toContain('Project One'));
  expect(calls.filter(call => call.path === '/conversations' && call.body)).toHaveLength(before);
  expect(calls.some(call => /conversations\/[^/]+$/.test(call.path) && call.body)).toBe(false);
  await enabled('Start new conversation'); fireEvent.click(screen.getByRole('button', { name: 'Start new conversation' }));
  await composer_ready(); fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'New project conversation' } });
  await enabled('Send mock message'); fireEvent.click(screen.getByRole('button', { name: 'Send mock message' }));
  await waitFor(() => expect(calls.filter(call => call.path === '/conversations' && call.body)).toHaveLength(before + 1));
  expect(calls.filter(call => call.path === '/conversations' && call.body).at(-1)?.body).toEqual({ title: 'Operator session', project_id: '55555555-5555-4555-8555-555555555555' });
});

it('waits for login execution readiness before a fresh send; disabled clicks never queue a message', async () => {
  const { api, calls } = fixture(); const execution = hold_response(api, '/execution');
  render(<App api={api}/>); let login_ready = false;
  const logging_in = login().then(() => { login_ready = true; });
  await screen.findByRole('heading', { name: 'Conversation' });
  await waitFor(() => expect(execution.pending()).toBeGreaterThan(0));
  await act(async () => {}); expect(login_ready).toBe(false);
  expect(screen.getByLabelText<HTMLTextAreaElement>('Message').disabled).toBe(true);
  // Even a synthetic input change cannot make an unavailable action writable.
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: '/calendar' } });
  expect(button('Send mock message').disabled).toBe(true); fireEvent.click(button('Send mock message'));
  const messages = () => calls.filter(call => call.path.endsWith('/messages') && call.body !== undefined);
  expect(messages()).toHaveLength(0);
  await execution.release(); await logging_in; expect(login_ready).toBe(true);
  await enabled('Send mock message'); expect(messages()).toHaveLength(0);
  fireEvent.click(button('Send mock message')); await enabled('Approve exact action'); expect(messages()).toHaveLength(1);
});

it('waits for post-send readiness after the approval heading; disabled approval clicks never queue a decision', async () => {
  const { api, calls } = fixture(); render(<App api={api}/>); await login();
  const runtime = hold_response(api, '/local-model/status'); let send_ready = false;
  const sending = send().then(() => { send_ready = true; });
  await screen.findByRole('heading', { name: 'Review exact server snapshot' });
  await waitFor(() => expect(runtime.pending()).toBeGreaterThan(0));
  await act(async () => {}); expect(send_ready).toBe(false);
  expect(button('Approve exact action').disabled).toBe(true); fireEvent.click(button('Approve exact action'));
  const decisions = () => calls.filter(call => call.path.endsWith('/decision'));
  expect(decisions()).toHaveLength(0);
  await runtime.release(); await sending; expect(send_ready).toBe(true);
  await enabled('Approve exact action'); expect(decisions()).toHaveLength(0);
  fireEvent.click(button('Approve exact action')); fireEvent.click(button('Approve exact action'));
  await screen.findByText('MOCK verified synthetic result'); expect(decisions()).toHaveLength(1);
  expect(decisions()[0].body).toEqual({ decision: 'APPROVE', expected_action_version: 1, payload_hash: 'a'.repeat(64) });
});

it('waits for post-decision readiness after the result; disabled logout clicks never queue sign-out', async () => {
  const { api, calls } = fixture(); render(<App api={api}/>); await login(); await send();
  const runtime = hold_response(api, '/local-model/status');
  fireEvent.click(button('Approve exact action')); await screen.findByText('MOCK verified synthetic result');
  await waitFor(() => expect(runtime.pending()).toBeGreaterThan(0));
  expect(button('Sign out').disabled).toBe(true); fireEvent.click(button('Sign out'));
  const logouts = () => calls.filter(call => call.path === '/auth/logout');
  expect(logouts()).toHaveLength(0);
  await runtime.release(); await enabled('Sign out'); expect(logouts()).toHaveLength(0);
  fireEvent.click(button('Sign out')); await screen.findByRole('heading', { name: 'Owner sign in' });
  expect(logouts()).toHaveLength(1); expect(screen.queryByText('MOCK verified synthetic result')).toBeNull();
});

it('waits for reload execution readiness after the approval heading before a fresh decision', async () => {
  const { api, calls } = fixture(); const first = render(<App api={api}/>); await login(); await send(); first.unmount();
  const execution = hold_response(api, '/execution'); render(<App api={api}/>);
  await screen.findByRole('heading', { name: 'Review exact server snapshot' });
  await waitFor(() => expect(execution.pending()).toBeGreaterThan(0));
  expect(button('Approve exact action').disabled).toBe(true); fireEvent.click(button('Approve exact action'));
  const decisions = () => calls.filter(call => call.path.endsWith('/decision'));
  expect(decisions()).toHaveLength(0);
  await execution.release(); await enabled('Approve exact action'); expect(decisions()).toHaveLength(0);
  fireEvent.click(button('Approve exact action')); await screen.findByText('MOCK verified synthetic result');
  expect(decisions()).toHaveLength(1);
});

it('waits for sign-out completion after the login heading before a fresh sign-in', async () => {
  const { api, calls } = fixture(); render(<App api={api}/>); await login();
  const logout = hold_response(api, '/auth/logout'); await enabled('Sign out'); fireEvent.click(button('Sign out'));
  await screen.findByRole('heading', { name: 'Owner sign in' });
  await waitFor(() => expect(logout.pending()).toBeGreaterThan(0));
  expect(button('Sign in').disabled).toBe(true);
  fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'owner' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } });
  fireEvent.click(button('Sign in'));
  const logins = () => calls.filter(call => call.path === '/auth/login');
  expect(logins()).toHaveLength(1);
  await logout.release(); await screen.findByText('The server accepted the sign-out request.');
  await enabled('Sign in'); expect(logins()).toHaveLength(1);
  await login(); expect(logins()).toHaveLength(2);
});
