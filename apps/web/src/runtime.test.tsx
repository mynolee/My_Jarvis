import { configure, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api, ApiError } from './api';
import { read_runtime } from './runtime';
configure({ asyncUtilTimeout: 5000 });
const conversation_id = '11111111-1111-4111-8111-111111111111';
const run_id = '22222222-2222-4222-8222-222222222222';
const ready = { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: true, status: 'READY' };
class Source { onopen = null; onerror = null; addEventListener() {} close() {} }
beforeEach(() => { vi.stubGlobal('EventSource', Source); vi.stubGlobal('fetch', vi.fn(async () => new Response('retry: 2000\n\n', { headers: { 'Content-Type': 'text/event-stream' } }))); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
function fixture(initial: unknown = ready) {
  const state = { runtime: initial, status: '', result: null as string | null, reject_send: false, unknown_send: false, hold_send: null as Promise<void> | null, hold_cancel: null as Promise<void> | null, hold_status: null as Promise<void> | null, cancel_status: 'CANCELLED' };
  const calls: Array<{ path: string; body?: unknown }> = [];
  const api = new Api(); vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  vi.spyOn(api, 'request').mockImplementation(async <T,>(path: string, body?: unknown): Promise<T> => {
    calls.push({ path, body }); path = path.split('?')[0]; let result: unknown = {};
    if (path === '/me') result = {};
    else if (path === '/local-model/status') { if (state.hold_status) await state.hold_status; if (state.runtime instanceof Error) throw state.runtime; result = state.runtime; }
    else if (path === '/conversations') result = { items: [{ id: conversation_id, title: 'Fixture', project_id: null, created_at: '2026-10-10T00:00:00Z' }], next_cursor: null };
    else if (path.endsWith('/local-inference')) { if (state.hold_send) await state.hold_send; if (state.unknown_send) throw new ApiError('UNKNOWN', true); if (state.reject_send) throw new ApiError('INFERENCE_CAPACITY_RESERVED', false, 429); state.status = 'QUEUED'; result = { run_id, status: state.status }; }
    else if (path.endsWith('/messages')) result = { items: state.status ? [{ id: run_id, text: 'Question', role: 'USER', run_id, execution_mode: 'LOCAL_INFERENCE', created_at: '2026-10-10T00:00:00Z' }] : [], next_cursor: null, stream_cursor: null, current_run: state.status ? { id: run_id, status: state.status, version: 3, execution_mode: 'LOCAL_INFERENCE', result: state.result, approval_id: null, job_id: null } : null };
    else if (path === `/runs/${run_id}`) result = { id: run_id, status: state.status, version: 3, execution_mode: 'LOCAL_INFERENCE', result: state.result, approval_id: null, job_id: null };
    else if (path.endsWith('/cancel')) { if (state.hold_cancel) await state.hold_cancel; state.status = state.cancel_status; }
    else if (path === '/auth/logout') result = {};
    else throw new Error(`Unexpected ${path}`);
    return result as T;
  });
  return { api, state, calls };
}
async function open(api: Api) { const view = render(<App api={api}/>); await screen.findByRole('heading', { name: 'Conversation' }); await waitFor(() => expect(within(screen.getByRole('region', { name: 'Local model runtime' })).getByRole('status').textContent).not.toBe('UNKNOWN')); return view; }
function enter() { fireEvent.change(screen.getByLabelText('Message'), { target: { value: '한국어로 답해 주세요.' } }); }
async function send() { enter(); fireEvent.click(screen.getByRole('button', { name: 'Send to local model' })); await screen.findByText('QUEUED'); }
for (const status of ['DISABLED', 'UNAVAILABLE', 'LOADING']) it(`disables local sending for server ${status} without hiding the explicit mock boundary`, async () => {
  const { api, calls } = fixture({ ...ready, ready: false, status }); await open(api); enter(); const button = screen.getByRole('button', { name: 'Send to local model' }) as HTMLButtonElement; expect(button.disabled).toBe(true); fireEvent.click(button); expect(calls.some(call => call.path.endsWith('/local-inference'))).toBe(false); expect((screen.getByRole('button', { name: 'Send mock message' }) as HTMLButtonElement).disabled).toBe(false);
});
for (const value of [null, {}, { ...ready, ready: 'true' }, { ...ready, status: 'LOADING' }, { ...ready, model_id: '/private/model' }, { ...ready, status: '<img src=x>' }]) it('fails closed on malformed or inconsistent runtime status', () => { expect(() => read_runtime(value)).toThrow('INVALID_RUNTIME'); });
it('renders no raw runtime exception or unknown configuration fields', async () => {
  const { api } = fixture(new ApiError('/private/model/config.json', false, 503)); const { container } = render(<App api={api}/>); await screen.findByText('Runtime state could not be confirmed. Local sending is disabled.'); expect(container.textContent).not.toContain('/private/model'); enter(); expect((screen.getByRole('button', { name: 'Send to local model' }) as HTMLButtonElement).disabled).toBe(true);
  expect(() => read_runtime({ ...ready, model_path: '/private/model', secret: 'hidden' })).toThrow('INVALID_RUNTIME');
});
it('sends only text and client identity once, then displays authoritative queued/running/result state', async () => {
  const { api, state, calls } = fixture(); const { container } = await open(api); enter(); const button = screen.getByRole('button', { name: 'Send to local model' }); fireEvent.click(button); fireEvent.click(button); await screen.findByText('QUEUED');
  const writes = calls.filter(call => call.path.endsWith('/local-inference')); expect(writes).toHaveLength(1); expect(Object.keys(writes[0].body as object).sort()).toEqual(['client_message_id', 'text']); expect(screen.queryByText('SUCCEEDED')).toBeNull();
  state.status = 'RUNNING'; fireEvent.click(screen.getByRole('button', { name: 'Refresh server state' })); await screen.findByText('RUNNING');
  state.status = 'SUCCEEDED'; state.result = '<img src="https://example.invalid/steal">안녕하세요'; fireEvent.click(screen.getByRole('button', { name: 'Refresh server state' })); await screen.findByText(state.result); expect(container.querySelector('img,a,iframe')).toBeNull();
});
it('reports backpressure without fake completion, fallback, or automatic retries', async () => {
  const { api, state, calls } = fixture(); state.reject_send = true; await open(api); enter(); fireEvent.click(screen.getByRole('button', { name: 'Send to local model' })); await screen.findByRole('alert'); expect(screen.getByRole('alert').textContent).toBe('INFERENCE_CAPACITY_RESERVED'); expect(screen.queryByText('SUCCEEDED')).toBeNull(); fireEvent.click(screen.getByRole('button', { name: 'Refresh server state' })); expect(calls.filter(call => call.path.endsWith('/local-inference'))).toHaveLength(1); expect(calls.filter(call => call.path.endsWith('/messages') && call.body)).toHaveLength(0);
});
it('unknown local submission pauses writes across read-only refresh without replay', async () => {
  const { api, state, calls } = fixture(); state.unknown_send = true; await open(api); enter(); fireEvent.click(screen.getByRole('button', { name: 'Send to local model' })); await screen.findByText('Writes paused'); fireEvent.click(screen.getByRole('button', { name: 'Refresh server state' })); expect((screen.getByRole('button', { name: 'Send to local model' }) as HTMLButtonElement).disabled).toBe(true); expect(calls.filter(call => call.path.endsWith('/local-inference'))).toHaveLength(1);
});
it('does not optimistically finish cancellation and never offers mock reconciliation for local UNKNOWN', async () => {
  const { api, state, calls } = fixture(); await open(api); await send(); let finish!: () => void; state.hold_cancel = new Promise(resolve => { finish = resolve; }); state.cancel_status = 'UNKNOWN'; const button = screen.getByRole('button', { name: 'Cancel run' }); fireEvent.click(button); fireEvent.click(button); expect(screen.queryByText('CANCELLED')).toBeNull(); expect(screen.getByText('QUEUED')).toBeTruthy(); finish(); await screen.findByText('Writes paused'); expect(screen.queryByRole('button', { name: 'Reconcile mock outcome' })).toBeNull(); const writes = calls.filter(call => call.path.endsWith('/cancel')); expect(writes).toHaveLength(1); expect(writes[0].body).toEqual({ expected_version: 3 });
});
it('reload recovers local UNKNOWN and remains fail closed rather than replaying', async () => {
  const { api, state, calls } = fixture(); state.status = 'UNKNOWN'; await open(api); await screen.findByText('Writes paused'); expect(screen.queryByRole('button', { name: 'Reconcile mock outcome' })).toBeNull(); expect(calls.some(call => call.body !== undefined)).toBe(false);
});
it('server-confirmed queued cancellation is shown only after acknowledgment', async () => { const { api } = fixture(); await open(api); await send(); fireEvent.click(screen.getByRole('button', { name: 'Cancel run' })); await screen.findByText('The server reports this run cancelled.'); expect(screen.queryByRole('button', { name: 'Cancel run' })).toBeNull(); });
it('pagehide discards a late readiness response and never restores private conversation data', async () => {
  const { api, state } = fixture(); let finish!: () => void; state.hold_status = new Promise(resolve => { finish = resolve; }); render(<App api={api}/>); await screen.findByRole('heading', { name: 'Conversation' }); fireEvent(window, new Event('pagehide')); finish(); await screen.findByRole('heading', { name: 'Owner sign in' }); await new Promise(resolve => setTimeout(resolve, 0)); expect(screen.queryByRole('heading', { name: 'Conversation' })).toBeNull(); expect(screen.queryByRole('status')).toBeNull();
});
it('runtime failure after readiness disables new local requests on refresh', async () => { const { api, state } = fixture(); await open(api); enter(); expect((screen.getByRole('button', { name: 'Send to local model' }) as HTMLButtonElement).disabled).toBe(false); state.runtime = new ApiError('CONNECTION_UNAVAILABLE'); fireEvent.click(screen.getByRole('button', { name: 'Refresh server state' })); await screen.findByText('Runtime state could not be confirmed. Local sending is disabled.'); expect((screen.getByRole('button', { name: 'Send to local model' }) as HTMLButtonElement).disabled).toBe(true); });

it('shows an explicit input limit and accepts multibyte text within the code-point budget', async () => { const { api, calls } = fixture(); await open(api); fireEvent.change(screen.getByLabelText('Message'), { target: { value: '가'.repeat(8001) } }); fireEvent.click(screen.getByRole('button', { name: 'Send to local model' })); await screen.findByText('Message exceeds the 8000-character local input limit.'); expect(calls.filter(call => call.path.endsWith('/local-inference'))).toHaveLength(0); fireEvent.change(screen.getByLabelText('Message'), { target: { value: '가'.repeat(8000) } }); fireEvent.click(screen.getByRole('button', { name: 'Send to local model' })); await screen.findByText('QUEUED'); expect(calls.filter(call => call.path.endsWith('/local-inference'))).toHaveLength(1); });

it('blocks a stale ready snapshot even before the next polling render', async () => { const { api, calls } = fixture(); await open(api); enter(); const later = Date.now() + 10001; vi.spyOn(Date, 'now').mockReturnValue(later); fireEvent.click(screen.getByRole('button', { name: 'Send to local model' })); await screen.findByText('Runtime status is stale. Refresh server state before sending.'); expect(calls.filter(call => call.path.endsWith('/local-inference'))).toHaveLength(0); });

it('pagehide during a local send discards late acknowledgment without claiming cancellation or replaying', async () => { const { api, state, calls } = fixture(); await open(api); let finish!: () => void; state.hold_send = new Promise(resolve => { finish = resolve; }); enter(); fireEvent.click(screen.getByRole('button', { name: 'Send to local model' })); fireEvent(window, new Event('pagehide')); finish(); await screen.findByRole('heading', { name: 'Owner sign in' }); await new Promise(resolve => setTimeout(resolve, 0)); expect(screen.queryByText('QUEUED')).toBeNull(); expect(screen.queryByText('CANCELLED')).toBeNull(); expect(calls.filter(call => call.path.endsWith('/local-inference'))).toHaveLength(1); expect(calls.filter(call => call.path.endsWith('/cancel'))).toHaveLength(0); });

it('blocks conversation switching during a pending write and retains an uncertain outcome', async () => {
  const { api, state, calls } = fixture();
  const original = vi.mocked(api.request).getMockImplementation()!;
  vi.spyOn(api, 'request').mockImplementation(async <T,>(path: string, body?: unknown): Promise<T> => {
    const result = await original(path, body);
    if (path.split('?')[0] === '/conversations' && body === undefined) return {
      ...(result as object), items: [
        { id: conversation_id, title: 'Fixture', project_id: null, created_at: '2026-10-10T00:00:00Z' },
        { id: '44444444-4444-4444-8444-444444444444', title: 'Other conversation', project_id: null, created_at: '2026-10-09T00:00:00Z' }
      ]
    } as T;
    return result as T;
  });
  await open(api);
  let finish!: () => void;
  state.hold_send = new Promise(resolve => { finish = resolve; }); state.unknown_send = true;
  enter(); fireEvent.click(screen.getByRole('button', { name: 'Send to local model' }));
  const other = await screen.findByRole('button', { name: 'Other conversation' });
  expect((other as HTMLButtonElement).disabled).toBe(true); fireEvent.click(other);
  expect(calls.some(call => call.path.includes('44444444-4444-4444-8444-444444444444'))).toBe(false);
  finish(); await screen.findByText('Writes paused');
  expect((screen.getByRole('button', { name: 'Send to local model' }) as HTMLButtonElement).disabled).toBe(true);
  expect(calls.filter(call => call.path.endsWith('/local-inference'))).toHaveLength(1);
});
