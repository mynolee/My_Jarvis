import { configure, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
    else if (path === '/auth/logout') { authenticated = false; }
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
async function login() { await waitFor(() => expect((screen.getByRole('button', { name: 'Sign in' }) as HTMLButtonElement).disabled).toBe(false)); fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'owner' } }); fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } }); fireEvent.click(screen.getByRole('button', { name: 'Sign in' })); await screen.findByRole('heading', { name: 'Conversation' }); }
async function send() { fireEvent.change(screen.getByLabelText('Message'), { target: { value: '/calendar' } }); fireEvent.click(screen.getByRole('button', { name: 'Send mock message' })); await screen.findByRole('heading', { name: 'Review exact server snapshot' }); }
it('executes login → chat → frozen approval → mock result; repeated clicks write once; logout erases DOM and closes stream', async () => {
  const { api, calls } = fixture(); const { container } = render(<App api={api}/>); await login(); await send();
  expect(container.querySelector('img')).toBeNull(); const button = screen.getByRole('button', { name: 'Approve exact action' }); fireEvent.click(button); fireEvent.click(button);
  await screen.findByText('MOCK verified synthetic result'); const decisions = calls.filter(call => call.path.endsWith('/decision')); expect(decisions).toHaveLength(1); expect(decisions[0].body).toEqual({ decision: 'APPROVE', expected_action_version: 1, payload_hash: 'a'.repeat(64) });
  fireEvent.click(screen.getByRole('button', { name: 'Sign out' })); await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByText('MOCK verified synthetic result')).toBeNull(); await waitFor(() => expect(FakeSource.instances.every(source => source.closed)).toBe(true));
  expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0);
});
it('reload obtains current snapshot from server instead of browser storage', async () => { const { api } = fixture(); const first = render(<App api={api}/>); await login(); await send(); first.unmount(); render(<App api={api}/>); await screen.findByRole('heading', { name: 'Review exact server snapshot' }); expect((screen.getByRole('button', { name: 'Approve exact action' }) as HTMLButtonElement).disabled).toBe(false); });
it('rejects and cancels through separate explicit actions', async () => { const { api, calls } = fixture(); render(<App api={api}/>); await login(); await send(); fireEvent.click(screen.getByRole('button', { name: 'Reject action' })); await screen.findByText('CANCELLED'); expect(calls.find(call => call.path.endsWith('/decision'))?.body).toMatchObject({ decision: 'REJECT' }); });
it('cancel sends the server run version', async () => { const { api, calls } = fixture(); render(<App api={api}/>); await login(); await send(); fireEvent.click(screen.getByRole('button', { name: 'Cancel run' })); await screen.findByText('CANCELLED'); expect(calls.find(call => call.path.endsWith('/cancel'))?.body).toEqual({ expected_version: 1 }); });
it('uncertain approval disables writes and does not replay during read-only refresh', async () => {
  const { api, calls } = fixture(); const request = vi.mocked(api.request).getMockImplementation()!; vi.spyOn(api, 'request').mockImplementation(async <T,>(path: string, body?: unknown): Promise<T> => { if (path.endsWith('/decision')) { calls.push({ path, body }); throw new ApiError('UNKNOWN', true); } return await request(path, body) as T; });
  render(<App api={api}/>); await login(); await send(); fireEvent.click(screen.getByRole('button', { name: 'Approve exact action' })); await screen.findByText('Writes paused'); expect((screen.getByRole('button', { name: 'Approve exact action' }) as HTMLButtonElement).disabled).toBe(true); fireEvent.click(screen.getByRole('button', { name: 'Refresh server state' })); expect(calls.filter(call => call.path.endsWith('/decision'))).toHaveLength(1);
});

it('uses a verified project only for a new conversation and never changes an existing conversation', async () => {
  const { api, calls } = fixture(); render(<App api={api}/>); await login(); await send();
  const before = calls.filter(call => call.path === '/conversations' && call.body).length;
  fireEvent.click(screen.getByRole('button', { name: 'Choose project for a new conversation' }));
  await screen.findByRole('option', { name: 'Project One' }); fireEvent.change(screen.getByLabelText('New conversation project'), { target: { value: '55555555-5555-4555-8555-555555555555' } });
  await waitFor(() => expect(screen.getByText(/Next new conversation project:/).textContent).toContain('Project One'));
  expect(calls.filter(call => call.path === '/conversations' && call.body)).toHaveLength(before);
  expect(calls.some(call => /conversations\/[^/]+$/.test(call.path) && call.body)).toBe(false);
  fireEvent.click(screen.getByRole('button', { name: 'Start new conversation' }));
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'New project conversation' } });
  fireEvent.click(screen.getByRole('button', { name: 'Send mock message' }));
  await waitFor(() => expect(calls.filter(call => call.path === '/conversations' && call.body)).toHaveLength(before + 1));
  expect(calls.filter(call => call.path === '/conversations' && call.body).at(-1)?.body).toEqual({ title: 'Operator session', project_id: '55555555-5555-4555-8555-555555555555' });
});
