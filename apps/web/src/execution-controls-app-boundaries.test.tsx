import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api, ApiError } from './api';
import type { Conversation } from './domain';
import type { HistoryPage } from './conversation-history';
const id = '11111111-1111-4111-8111-111111111111';
const time = '2026-10-10T15:00:00Z';
const initial = { execution_paused: false, version: 7, checked_at: time };
const paused = { execution_paused: true, version: 8, checked_at: time };
type ProbeProps = { writesPaused: boolean; onAcquirePending?: () => boolean; onPendingChange?: (value: boolean) => void; onReauthenticate?: () => void; onUnauthorized?: () => void };
function Probe({ domain, ...p }: ProbeProps & { domain: string }) { return <section aria-label={`${domain} probe`}><button disabled={p.writesPaused} onClick={() => { if (p.onAcquirePending?.() !== false) p.onPendingChange?.(true); }}>Begin {domain}</button><button onClick={() => p.onPendingChange?.(false)}>Resolve {domain}</button>{p.onReauthenticate && <button onClick={p.onReauthenticate}>Request ordinary identity</button>}</section>; }
vi.mock('./TasksPanel', () => ({ TasksPanel: (p: ProbeProps) => <Probe {...p} domain="task"/> }));
vi.mock('./ProjectsPanel', () => ({ ProjectsPanel: (p: ProbeProps) => <Probe {...p} domain="project"/> }));
vi.mock('./MemoriesPanel', () => ({ MemoriesPanel: (p: ProbeProps) => <Probe {...p} domain="memory"/> }));
vi.mock('./ProfilePanel', () => ({ ProfilePanel: (p: ProbeProps) => <Probe {...p} domain="profile"/> }));
vi.mock('./SchedulesPanel', () => ({ SchedulesPanel: (p: ProbeProps) => <Probe {...p} domain="reminder"/> }));
vi.mock('./NotificationsPanel', () => ({ NotificationsPanel: (p: ProbeProps) => <Probe {...p} domain="inbox"/> }));
vi.mock('./MailDraftsPanel', () => ({ MailDraftsPanel: (p: ProbeProps) => <Probe {...p} domain="mail draft"/> }));
vi.mock('./TodayPanel', () => ({ TodayPanel: () => <section aria-label="Today readable"/> }));
vi.mock('./AuditHistoryPanel', () => ({ AuditHistoryPanel: () => <section aria-label="Audit readable"/> }));
type HistoryProps = { onSelect: (conversation: Conversation) => void; onSnapshot: (id: string, page: HistoryPage) => void; selectionDisabled: boolean };
vi.mock('./ConversationHistoryPanel', () => ({ ConversationHistoryPanel: (p: HistoryProps) => <section aria-label="History readable"><button disabled={p.selectionDisabled} onClick={() => p.onSelect({ id, title: 'Synthetic conversation', project_id: null, created_at: time })}>Select synthetic conversation</button><button onClick={() => p.onSnapshot(id, { items: [], next_cursor: null, stream_cursor: null, current_run: { id, status: 'UNKNOWN', version: 1, execution_mode: 'MOCK', result: null, approval_id: null, job_id: null } })}>Read unknown run</button></section> }));
const deferred = <T,>() => { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function fixture(override: (path: string, body?: unknown) => unknown = () => undefined) {
  const api = new Api(); vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation(async (path, body) => {
    const result = override(path, body); if (result !== undefined) return result;
    if (path === '/me') return {};
    if (path === '/auth/logout') return { logged_out: true };
    if (path === '/auth/reauthenticate' || path === '/auth/login') return { csrf_token: 'synthetic-test-token', authenticated: true };
    if (path === '/execution') return initial;
    if (path === '/execution/pause') return paused;
    if (path === '/execution/resume') return { ...initial, version: 9 };
    if (path === '/local-model/status') return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: true, status: 'READY' };
    if (path.endsWith('/messages')) return {};
    throw new Error('Unexpected test route');
  }); return { api, request };
}
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
const review = () => fireEvent.click(screen.getByRole('checkbox', { name: 'I reviewed the account-wide impact for this checked version' }));
const disabled = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name }).disabled;
async function ready() { await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); }
function openDomains() { for (const name of ['Open internal tasks', 'Choose project for a new conversation', 'Open explicit memories', 'Open owner settings', 'Open internal reminders', 'Open internal inbox', 'Open internal mail drafts']) click(name); }
const domains = ['task', 'project', 'memory', 'profile', 'reminder', 'inbox', 'mail draft'];
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('exposes controls only in the authenticated workspace', async () => {
  const { api } = fixture(path => { if (path === '/me') throw new ApiError('UNAUTHENTICATED', false, 401); }); render(<App api={api}/>); await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByRole('region', { name: 'Account execution controls' })).toBeNull();
});
it('fails closed for missing account state across every new mutation surface while retaining read-only access', async () => {
  const pending = deferred<unknown>(); const { api, request } = fixture(path => path === '/execution' ? pending.promise : undefined); render(<App api={api}/>); await screen.findByRole('region', { name: 'Account execution controls' }); openDomains(); click('Open execution history');
  for (const domain of domains) expect(disabled(`Begin ${domain}`)).toBe(true);
  for (const region of ['Today readable', 'Audit readable', 'History readable']) expect(screen.getByRole('region', { name: region })).toBeTruthy();
  expect(disabled('Send mock message')).toBe(true); await act(async () => pending.resolve(initial)); await ready();
  for (const domain of domains) expect(disabled(`Begin ${domain}`)).toBe(false);
  expect(request.mock.calls.filter(call => call[1] !== undefined)).toHaveLength(0);
});
it('propagates confirmed pause to all writes while preserving read-only Today, audit and history selection', async () => {
  const { api, request } = fixture(path => path === '/execution' ? paused : undefined); render(<App api={api}/>); await screen.findByText('Account state: PAUSED'); openDomains(); click('Open execution history');
  for (const domain of domains) expect(disabled(`Begin ${domain}`)).toBe(true);
  expect(disabled('Select synthetic conversation')).toBe(false); click('Select synthetic conversation');
  for (const region of ['Today readable', 'Audit readable', 'History readable']) expect(screen.getByRole('region', { name: region })).toBeTruthy();
  expect(disabled('Send to local model')).toBe(true); expect(disabled('Send mock message')).toBe(true); expect(request.mock.calls.filter(call => call[1] !== undefined)).toHaveLength(0);
});
it.each(domains)('keeps emergency pause independent of %s pending and preserves its unresolved fence after resume', async domain => {
  const { api, request } = fixture(); render(<App api={api}/>); await ready(); openDomains(); click(`Begin ${domain}`); expect(disabled('Start new conversation')).toBe(true);
  review(); expect(disabled('Pause account execution')).toBe(false); click('Pause account execution'); await screen.findByText('Account state: PAUSED'); await screen.findByRole('region', { name: `${domain} probe` });
  expect(screen.getByText(/Earlier work remains unresolved across this safety boundary/)).toBeTruthy();
  for (const name of domains) expect(disabled(`Begin ${name}`)).toBe(true);
  review(); click('Resume new execution'); await ready(); expect(disabled('Start new conversation')).toBe(true);
  for (const name of domains) expect(disabled(`Begin ${name}`)).toBe(true);
  expect(request.mock.calls.filter(call => call[1] !== undefined)).toEqual([['/execution/pause', { expected_version: 7 }], ['/execution/resume', { expected_version: 8 }]]);
});
it('permits explicit emergency identity confirmation during UNKNOWN but never clears that fence or chains a pause', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await ready(); click('Select synthetic conversation'); click('Read unknown run'); expect(disabled('Start new conversation')).toBe(true);
  click('Confirm identity for execution controls'); fireEvent.change(screen.getByLabelText('Execution control password'), { target: { value: 'synthetic-password' } }); click('Confirm execution identity');
  await screen.findByText(/Identity confirmed. Refresh account execution state/); expect(screen.getByText(/Earlier work remains unresolved across this safety boundary/)).toBeTruthy();
  click('Refresh account execution state'); await ready(); expect(disabled('Start new conversation')).toBe(true); review(); expect(disabled('Pause account execution')).toBe(false);
  expect(request.mock.calls.filter(call => call[1] !== undefined)).toEqual([['/auth/reauthenticate', { password: 'synthetic-password' }]]);
});
it('fences same-tick ordinary writes and identity submission when emergency boundary acquires first', async () => {
  const pause = deferred<unknown>(); const { api, request } = fixture(path => path === '/execution/pause' ? pause.promise : undefined); render(<App api={api}/>); await ready(); click('Open internal reminders'); click('Request ordinary identity'); const form = screen.getByLabelText('Confirm password').closest('form')!; fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'synthetic-password' } }); review();
  act(() => { screen.getByRole('button', { name: 'Pause account execution' }).click(); form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  expect(request.mock.calls.some(c => c[0] === '/auth/reauthenticate')).toBe(false); expect(request.mock.calls.filter(c => c[0] === '/execution/pause')).toHaveLength(1); await act(async () => pause.resolve(paused));
});
it('does not let an old main request finally unlock a newer sign-in request', async () => {
  const old = deferred<unknown>(), login = deferred<unknown>(); const { api } = fixture(path => path.endsWith('/messages') ? old.promise : path === '/auth/login' ? login.promise : undefined); render(<App api={api}/>); await ready(); click('Select synthetic conversation'); fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'synthetic-message' } }); click('Send mock message'); review(); click('Pause account execution'); await screen.findByText('Account state: PAUSED'); click('Sign out');
  await screen.findByRole('heading', { name: 'Owner sign in' }); fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'synthetic-owner' } }); fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } }); click('Sign in');
  await waitFor(() => expect(disabled('Sign in')).toBe(true)); await act(async () => old.resolve({})); expect(disabled('Sign in')).toBe(true);
  await act(async () => login.resolve({ csrf_token: 'synthetic-new-token', authenticated: true })); await ready(); expect(screen.getByText(/A previous request may still have committed/)).toBeTruthy();
});
it.each(['success', '401'] as const)('does not restore private workspace from a late control %s after page disposal', async outcome => {
  const write = deferred<unknown>(); const { api } = fixture(path => path === '/execution/pause' ? write.promise : undefined); render(<App api={api}/>); await ready(); review(); click('Pause account execution'); expect(disabled('Sign out')).toBe(true); fireEvent(window, new Event('pagehide')); await screen.findByRole('heading', { name: 'Owner sign in' });
  await act(async () => { if (outcome === 'success') write.resolve(paused); else write.reject(new ApiError('UNAUTHENTICATED', false, 401)); }); expect(screen.queryByRole('region', { name: 'Account execution controls' })).toBeNull(); expect(disabled('Sign in')).toBe(false);
});
it('clears every execution/private snapshot on pagehide without issuing a control write', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await ready(); openDomains(); fireEvent(window, new Event('pagehide')); await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByRole('region', { name: 'Account execution controls' })).toBeNull(); expect(request.mock.calls.filter(call => call[1] !== undefined)).toHaveLength(0);
});
it('does not let status refresh after ambiguous reauth enable ordinary writes', async () => {
  const { api, request } = fixture(path => { if (path === '/auth/reauthenticate') throw new ApiError('UNKNOWN', true); }); render(<App api={api}/>); await ready(); openDomains(); click('Confirm identity for execution controls'); fireEvent.change(screen.getByLabelText('Execution control password'), { target: { value: 'synthetic-password' } }); click('Confirm execution identity'); await screen.findByText(/UNKNOWN: identity confirmation may have rotated/); click('Refresh account execution state'); await ready();
  for (const domain of domains) expect(disabled(`Begin ${domain}`)).toBe(true); expect(disabled('Pause account execution')).toBe(true); expect(request.mock.calls.filter(call => call[1] !== undefined)).toHaveLength(1);
});
it('clears boundary lock and private data on a current control 401', async () => {
  const { api } = fixture(path => { if (path === '/execution/pause') throw new ApiError('UNAUTHENTICATED', false, 401); }); render(<App api={api}/>); await ready(); review(); click('Pause account execution'); await screen.findByRole('heading', { name: 'Owner sign in' }); expect(disabled('Sign in')).toBe(false); expect(screen.queryByRole('region', { name: 'Account execution controls' })).toBeNull();
});
it('serializes sign-out behind pending emergency reauthentication, including same-turn clicks', async () => {
  const pending = deferred<unknown>(); const { api, request } = fixture(path => path === '/auth/reauthenticate' ? pending.promise : undefined); render(<App api={api}/>); await ready(); click('Confirm identity for execution controls'); fireEvent.change(screen.getByLabelText('Execution control password'), { target: { value: 'synthetic-password' } });
  const signout = screen.getByRole('button', { name: 'Sign out' }); act(() => { screen.getByRole('button', { name: 'Confirm execution identity' }).click(); signout.click(); });
  expect(disabled('Sign out')).toBe(true); expect(request.mock.calls.some(c => c[0] === '/auth/logout')).toBe(false);
  await act(async () => pending.reject(new ApiError('UNKNOWN', true))); await screen.findByText(/UNKNOWN: identity confirmation may have rotated/); expect(disabled('Sign out')).toBe(false); click('Sign out'); await screen.findByRole('heading', { name: 'Owner sign in' });
});
it('keeps sign-in serialized and reports unconfirmed server termination after ambiguous logout', async () => {
  const pending = deferred<unknown>(); const { api, request } = fixture(path => path === '/auth/logout' ? pending.promise : undefined); render(<App api={api}/>); await ready(); click('Sign out'); await screen.findByRole('heading', { name: 'Owner sign in' }); expect(disabled('Sign in')).toBe(true); await act(async () => pending.reject(new ApiError('UNKNOWN', true))); expect(disabled('Sign in')).toBe(false); expect(screen.getByText(/Server session termination is unconfirmed/)).toBeTruthy(); expect(request.mock.calls.filter(c => c[0] === '/auth/logout')).toHaveLength(1);
});
it.each([{}, { logged_out: false }, { logged_out: true, extra: 'unverified' }])('does not claim confirmed termination for malformed logout data %#', async value => {
  const { api } = fixture(path => path === '/auth/logout' ? value : undefined); render(<App api={api}/>); await ready(); click('Sign out'); await screen.findByText(/Server session termination is unconfirmed/); expect(screen.queryByText('The server accepted the sign-out request.')).toBeNull();
});
it('fences existing client requests before issuing the control mutation', async () => {
  const { api } = fixture(); const fence = vi.spyOn(api, 'fence_requests'); const original = vi.mocked(api.request).getMockImplementation()!;
  vi.spyOn(api, 'request').mockImplementation(async (path, body, key, method) => { if (path === '/execution/pause') expect(fence).toHaveBeenCalledTimes(1); return original(path, body, key, method); });
  render(<App api={api}/>); await ready(); review(); click('Pause account execution'); await screen.findByText('Account state: PAUSED');
});
it.each(['pause', 'reauth'] as const)('blocks a same-turn emergency %s while ordinary identity rotation owns the boundary', async action => {
  const pending = deferred<unknown>(); const { api, request } = fixture(path => path === '/auth/reauthenticate' ? pending.promise : undefined); render(<App api={api}/>); await ready(); click('Open internal reminders'); click('Request ordinary identity'); fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'ordinary-synthetic-password' } }); review();
  let emergency: HTMLElement;
  if (action === 'reauth') { click('Confirm identity for execution controls'); fireEvent.change(screen.getByLabelText('Execution control password'), { target: { value: 'emergency-synthetic-password' } }); emergency = screen.getByLabelText('Execution control password').closest('form')!; } else emergency = screen.getByRole('button', { name: 'Pause account execution' });
  act(() => { screen.getByRole('button', { name: 'Confirm identity' }).click(); if (action === 'reauth') emergency.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); else emergency.click(); });
  expect(request.mock.calls.filter(c => c[1] !== undefined)).toEqual([['/auth/reauthenticate', { password: 'ordinary-synthetic-password' }]]); expect(disabled('Sign out')).toBe(true); expect(disabled('Pause account execution')).toBe(true); expect(disabled('Confirm identity for execution controls')).toBe(true);
  await act(async () => pending.resolve({ csrf_token: 'synthetic-rotated-token', authenticated: true })); await ready(); expect(disabled('Sign out')).toBe(false); expect(disabled('Pause account execution')).toBe(true);
});
it.each(['timeout', 'malformed'] as const)('preserves shared identity uncertainty after ordinary reauth %s and fresh status read', async failure => {
  const { api, request } = fixture(path => { if (path === '/auth/reauthenticate') { if (failure === 'timeout') throw new ApiError('UNKNOWN', true); return { csrf_token: 'unverified-without-authenticated' }; } }); render(<App api={api}/>); await ready(); click('Open internal reminders'); click('Request ordinary identity'); fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'synthetic-password' } }); click('Confirm identity'); await screen.findByText(/UNKNOWN: identity confirmation may have rotated this session. Sign out and sign in explicitly before any new write/); click('Refresh account execution state'); await ready();
  expect(disabled('Pause account execution')).toBe(true); expect(disabled('Confirm identity for execution controls')).toBe(true); expect(disabled('Begin reminder')).toBe(true); expect(disabled('Send mock message')).toBe(true); expect(request.mock.calls.filter(c => c[1] !== undefined)).toHaveLength(1);
});
it('retains prior control UNKNOWN through ordinary reauthentication and a new current-state snapshot', async () => {
  const { api, request } = fixture(path => { if (path === '/execution/pause') throw new ApiError('UNKNOWN', true); }); render(<App api={api}/>); await ready(); review(); click('Pause account execution'); await screen.findByText(/An earlier execution-control request has an UNKNOWN outcome/); click('Refresh account execution state'); await ready();
  click('Open internal reminders'); click('Request ordinary identity'); fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'synthetic-password' } }); click('Confirm identity'); await ready(); expect(screen.getByText(/An earlier execution-control request has an UNKNOWN outcome/)).toBeTruthy();
  expect(request.mock.calls.filter(c => c[1] !== undefined)).toEqual([['/execution/pause', { expected_version: 7 }], ['/auth/reauthenticate', { password: 'synthetic-password' }]]);
  click('Sign out'); await waitFor(() => expect(disabled('Sign in')).toBe(false)); fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'synthetic-owner' } }); fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } }); click('Sign in'); await ready(); expect(screen.getByText(/A previous request may still have committed/)).toBeTruthy();
});
