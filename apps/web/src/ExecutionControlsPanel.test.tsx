import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { ExecutionControlsPanel } from './ExecutionControlsPanel';
import type { ExecutionState } from './execution-controls';
const time = '2026-10-10T15:00:00Z';
const initial = { execution_paused: false, version: 7, checked_at: time };
const paused = { execution_paused: true, version: 8, checked_at: time };
const deferred = <T,>() => { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
const review = () => fireEvent.click(screen.getByRole('checkbox', { name: 'I reviewed the account-wide impact for this checked version' }));
const disabled = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name }).disabled;
function fixture(override: (path: string, body?: unknown) => unknown = () => undefined) {
  const api = new Api(); api.set_csrf('synthetic-initial-token');
  const request = vi.spyOn(api, 'request').mockImplementation(async (path, body) => {
    const value = override(path, body); if (value !== undefined) return value;
    if (path === '/execution') return initial;
    if (path === '/execution/pause') return paused;
    if (path === '/execution/resume') return { ...initial, version: 9 };
    if (path === '/auth/reauthenticate') return { csrf_token: 'synthetic-fresh-token', authenticated: true };
    throw new Error('Unexpected route');
  });
  const props = { api, sessionKey: 1, onUnauthorized: vi.fn(), onStateChange: vi.fn<(state: ExecutionState | null) => void>(), onBoundaryStart: vi.fn(), onBoundaryEnd: vi.fn() };
  return { api, request, props };
}
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
describe('owner-driven execution controls', () => {
  it('loads only a read, fails closed initially, and shows checked version/time in KST', async () => {
    const read = deferred<unknown>(); const { request, props } = fixture(path => path === '/execution' ? read.promise : undefined); render(<ExecutionControlsPanel {...props}/>);
    expect(disabled('Pause account execution')).toBe(true); expect(screen.getByText('Account state: UNKNOWN')).toBeTruthy();
    await act(async () => read.resolve(initial)); expect(screen.getByText('Account state: AVAILABLE FOR NEW WORK')).toBeTruthy(); expect(screen.getByText('11 Oct 2026, 00:00:00 KST')).toBeTruthy();
    expect(disabled('Pause account execution')).toBe(true); expect(request.mock.calls).toEqual([['/execution']]); review(); expect(disabled('Pause account execution')).toBe(false);
  });
  it('issues exactly one explicit CAS pause and never reports success optimistically', async () => {
    const write = deferred<unknown>(); const { request, props } = fixture(path => path === '/execution/pause' ? write.promise : undefined); render(<ExecutionControlsPanel {...props}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); review();
    act(() => { screen.getByRole('button', { name: 'Pause account execution' }).click(); screen.getByRole('button', { name: 'Pause account execution' }).click(); });
    expect(request.mock.calls.filter(c => c[0] === '/execution/pause')).toEqual([['/execution/pause', { expected_version: 7 }]]);
    expect(screen.getByText('Account state: UNKNOWN')).toBeTruthy(); expect(screen.queryByText('Account state: PAUSED')).toBeNull(); expect(props.onBoundaryStart).toHaveBeenCalledTimes(1);
    await act(async () => write.resolve(paused)); expect(screen.getByText('Account state: PAUSED')).toBeTruthy(); expect(props.onBoundaryEnd).toHaveBeenCalledTimes(1); expect(disabled('Resume new execution')).toBe(true);
  });
  it('keeps UNKNOWN original outcome after current-state refresh, and requires another review for a new request', async () => {
    let reads = 0; const { request, props } = fixture(path => { if (path === '/execution') return ++reads === 1 ? initial : paused; if (path === '/execution/pause') throw new ApiError('UNKNOWN', true); });
    render(<ExecutionControlsPanel {...props}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); review(); click('Pause account execution'); await screen.findByText(/UNKNOWN: the execution-control request may have committed/);
    expect(disabled('Pause account execution')).toBe(true); click('Refresh account execution state'); await screen.findByText('Account state: PAUSED');
    expect(screen.getByText(/An earlier execution-control request has an UNKNOWN outcome/)).toBeTruthy(); expect(disabled('Resume new execution')).toBe(true); expect(request.mock.calls.filter(c => c[1] !== undefined)).toHaveLength(1);
    review(); click('Resume new execution'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); expect(request.mock.calls.at(-1)).toEqual(['/execution/resume', { expected_version: 8 }]); expect(screen.getByText(/An earlier execution-control request has an UNKNOWN outcome/)).toBeTruthy();
  });
  it.each([{ ...paused, version: 7 }, { ...paused, execution_paused: false }, { ...paused, extra: 'rejected' }, null])('treats malformed/inconsistent success as UNKNOWN %#', async value => {
    const { props } = fixture(path => path === '/execution/pause' ? Promise.resolve(value) : undefined); render(<ExecutionControlsPanel {...props}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); review(); click('Pause account execution'); await screen.findByText(/UNKNOWN: the execution-control request may have committed/); expect(screen.getByText('Account state: UNKNOWN')).toBeTruthy();
  });
  it('shows safe conflict and requires fresh read rather than replaying', async () => {
    const { request, props } = fixture(path => { if (path === '/execution/pause') throw new ApiError('PRECONDITION_CHANGED', false, 409); }); render(<ExecutionControlsPanel {...props}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); review(); click('Pause account execution'); await screen.findByText(/Account version changed/); expect(disabled('Pause account execution')).toBe(true); expect(request.mock.calls.filter(c => c[1] !== undefined)).toHaveLength(1);
  });
  it('does not transmit a password or chain a pause after successful production-shaped reauth', async () => {
    const { api, request, props } = fixture(); const csrf = vi.spyOn(api, 'set_csrf'); render(<ExecutionControlsPanel {...props}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); click('Confirm identity for execution controls');
    const input = screen.getByLabelText<HTMLInputElement>('Execution control password'); fireEvent.change(input, { target: { value: 'synthetic-password' } }); expect(request.mock.calls.filter(c => c[1] !== undefined)).toHaveLength(0);
    click('Confirm execution identity'); await screen.findByText(/Identity confirmed. Refresh account execution state/); expect(input.value).toBe(''); expect(csrf).toHaveBeenCalledWith('synthetic-fresh-token'); expect(request.mock.calls.filter(c => c[1] !== undefined)).toEqual([['/auth/reauthenticate', { password: 'synthetic-password' }]]); expect(disabled('Pause account execution')).toBe(true);
    click('Refresh account execution state'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); expect(disabled('Pause account execution')).toBe(true); review(); click('Pause account execution'); await screen.findByText('Account state: PAUSED');
  });
  it('does not use current state to clear ambiguous identity confirmation or restore mutation authority', async () => {
    const { request, props } = fixture(path => { if (path === '/auth/reauthenticate') throw new ApiError('UNKNOWN', true); }); render(<ExecutionControlsPanel {...props}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); click('Confirm identity for execution controls'); fireEvent.change(screen.getByLabelText('Execution control password'), { target: { value: 'synthetic-password' } }); click('Confirm execution identity'); await screen.findByText(/UNKNOWN: identity confirmation may have rotated/);
    click('Refresh account execution state'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); expect(disabled('Pause account execution')).toBe(true); expect(disabled('Confirm identity for execution controls')).toBe(true); expect(props.onStateChange.mock.calls.at(-1)).toEqual([null]); expect(request.mock.calls.filter(c => c[1] !== undefined)).toHaveLength(1);
  });
  it('handles required recent identity without sending or retrying automatically', async () => {
    const { request, props } = fixture(path => { if (path === '/execution/pause') throw new ApiError('REAUTHENTICATION_REQUIRED', false, 403); }); render(<ExecutionControlsPanel {...props}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); review(); click('Pause account execution'); await screen.findByLabelText('Execution control password'); expect(request.mock.calls.filter(c => c[1] !== undefined)).toHaveLength(1); expect(disabled('Pause account execution')).toBe(true);
  });
  it('reconciles malformed reads fail-closed with stale snapshot and no automatic retry', async () => {
    let reads = 0; const { request, props } = fixture(path => path === '/execution' ? ++reads === 1 ? initial : { ...initial, execution_paused: 'false' } : undefined); render(<ExecutionControlsPanel {...props}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); review(); click('Refresh account execution state'); await screen.findByText(/Account execution state could not be confirmed/); expect(screen.getByText('Account state: UNKNOWN')).toBeTruthy(); expect(disabled('Pause account execution')).toBe(true); expect(request.mock.calls).toHaveLength(2); expect(props.onStateChange.mock.calls.at(-1)).toEqual([null]);
  });
});
describe('session and response fences', () => {
  it('ignores late success from a superseded status read', async () => {
    const old = deferred<unknown>(); let reads = 0; const { props } = fixture(path => path === '/execution' ? ++reads === 1 ? old.promise : paused : undefined); render(<ExecutionControlsPanel {...props}/>); click('Refresh account execution state'); await screen.findByText('Account state: PAUSED'); await act(async () => old.resolve(initial)); expect(screen.getByText('Account state: PAUSED')).toBeTruthy();
  });
  it('treats a superseded same-session 401 as revocation', async () => {
    const old = deferred<unknown>(); let reads = 0; const { props } = fixture(path => path === '/execution' ? ++reads === 1 ? old.promise : paused : undefined); render(<ExecutionControlsPanel {...props}/>); click('Refresh account execution state'); await screen.findByText('Account state: PAUSED'); await act(async () => old.reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(props.onUnauthorized).toHaveBeenCalledTimes(1); expect(screen.queryByText('Account state: PAUSED')).toBeNull();
  });
  it.each(['success', '401'] as const)('ignores %s from an old session after replacement', async result => {
    const old = deferred<unknown>(); let reads = 0; const { props } = fixture(path => path === '/execution' ? ++reads === 1 ? old.promise : paused : undefined); const ui = render(<ExecutionControlsPanel {...props}/>); ui.rerender(<ExecutionControlsPanel {...props} sessionKey={2}/>); await screen.findByText('Account state: PAUSED'); await act(async () => { if (result === 'success') old.resolve(initial); else old.reject(new ApiError('UNAUTHENTICATED', false, 401)); }); expect(props.onUnauthorized).not.toHaveBeenCalled(); expect(screen.getByText('Account state: PAUSED')).toBeTruthy();
  });
  it('discards a late mutation result after unmount and never releases a new boundary', async () => {
    const write = deferred<unknown>(); const { props } = fixture(path => path === '/execution/pause' ? write.promise : undefined); const ui = render(<ExecutionControlsPanel {...props}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); review(); click('Pause account execution'); ui.unmount(); await act(async () => write.resolve(paused)); expect(props.onBoundaryEnd).not.toHaveBeenCalled(); expect(props.onStateChange.mock.calls.at(-1)).toEqual([null]);
  });
  it('keeps unrelated UNKNOWN warning during confirmed pause and resume', async () => {
    const { props } = fixture(); render(<ExecutionControlsPanel {...props} unresolvedWork/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); expect(screen.getByText(/Earlier work remains unresolved across this safety boundary/)).toBeTruthy(); review(); click('Pause account execution'); await screen.findByText('Account state: PAUSED'); expect(screen.getByText(/Earlier work remains unresolved across this safety boundary/)).toBeTruthy();
  });
});
it.each(['pause', 'reauth'] as const)('ignores a pre-boundary superseded status 401 during %s', async action => {
  const old = deferred<unknown>(), operation = deferred<unknown>(); let reads = 0;
  const { props } = fixture(path => path === '/execution' ? ++reads === 1 ? old.promise : initial : path === (action === 'pause' ? '/execution/pause' : '/auth/reauthenticate') ? operation.promise : undefined);
  render(<ExecutionControlsPanel {...props}/>); click('Refresh account execution state'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK');
  if (action === 'pause') { review(); click('Pause account execution'); } else { click('Confirm identity for execution controls'); fireEvent.change(screen.getByLabelText('Execution control password'), { target: { value: 'synthetic-password' } }); click('Confirm execution identity'); }
  await act(async () => old.reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(props.onUnauthorized).not.toHaveBeenCalled();
  await act(async () => operation.resolve(action === 'pause' ? paused : { csrf_token: 'synthetic-fresh-token', authenticated: true })); expect(props.onBoundaryEnd).toHaveBeenCalledTimes(1);
});
it('closing the execution identity form drops the unsent password and performs no mutation', async () => {
  const { request, props } = fixture(); render(<ExecutionControlsPanel {...props}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); click('Confirm identity for execution controls'); fireEvent.change(screen.getByLabelText('Execution control password'), { target: { value: 'synthetic-password' } }); click('Close execution identity form'); expect(screen.queryByLabelText('Execution control password')).toBeNull(); click('Confirm identity for execution controls'); expect(screen.getByLabelText<HTMLInputElement>('Execution control password').value).toBe(''); expect(request.mock.calls.filter(c => c[1] !== undefined)).toHaveLength(0);
});
