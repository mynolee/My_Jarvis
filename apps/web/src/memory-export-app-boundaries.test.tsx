import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useEffect, useRef, useState } from 'react';
import { App } from './App';
import { Api, ApiError } from './api';
import type { Project } from './projects';
import type { Conversation } from './domain';

// App wiring only: transport, document parsing, and browser save behavior have separate tests.
type ProbeProps = {
  writesPaused: boolean; selectionDisabled?: boolean; sessionKey?: number; selectedProjectId?: string | null;
  onPendingChange: (value: boolean) => void; onAcquirePending: () => boolean;
  onUnauthorized: () => void; onReauthenticate?: () => void; onOutcomeUnknown?: () => void;
};
const probes = vi.hoisted(() => ({ acquired: vi.fn(), inspect: vi.fn(), discarded: vi.fn() }));
const project_id = '11111111-1111-4111-8111-111111111111';
const conversation_project_id = '22222222-2222-4222-8222-222222222222';
const conversation_id = '33333333-3333-4333-8333-333333333333';
const global_conversation_id = '44444444-4444-4444-8444-444444444444';
const time = '2026-10-10T00:00:00Z';
function begin(p: ProbeProps, domain: string) { if (!p.onAcquirePending()) return false; probes.acquired(domain); p.onPendingChange(true); return true; }
function peer(p: ProbeProps, domain: string) {
  return <section aria-label={`${domain} wiring probe`}>
    <button disabled={p.writesPaused} onClick={() => begin(p, domain)}>Begin {domain} probe</button>
    <button onClick={() => p.onPendingChange(false)}>Resolve {domain} probe</button>
  </section>;
}
function ExportProbe(p: ProbeProps) {
  const pending = useRef(false), live = useRef(p); live.current = p;
  const [unknown, setUnknown] = useState(false);
  // Mirror real panel cleanup ordering: this can run after App has cleared its own private state.
  useEffect(() => () => {
    if (pending.current) { probes.discarded(); live.current.onOutcomeUnknown?.(); }
    pending.current = false; live.current.onPendingChange(false);
  }, []);
  return <section aria-label="Export wiring probe">
  <span>Export session {p.sessionKey}</span><span>Export selected project {p.selectedProjectId ?? 'none'}</span>
  {unknown && <span>Export result UNKNOWN</span>}
  <input aria-label="Private export probe marker" defaultValue=""/>
  <button disabled={p.writesPaused} onClick={() => { if (begin(p, 'export')) pending.current = true; }}>Begin export probe</button>
  <button onClick={() => { if (pending.current) setUnknown(true); }}>Mark export UNKNOWN probe</button>
  <button onClick={() => { pending.current = false; setUnknown(false); p.onPendingChange(false); }}>Resolve export probe</button>
  <button disabled={p.selectionDisabled} onClick={() => probes.inspect('scope')}>Inspect export scope probe</button>
  <button onClick={() => probes.inspect('status')}>Read export status probe</button>
  <button onClick={p.onReauthenticate}>Request export identity probe</button>
  <button onClick={p.onUnauthorized}>Expire export session</button>
</section>;
}
vi.mock('./MemoryExportPanel', () => ({ MemoryExportPanel: (p: ProbeProps) => <ExportProbe {...p}/> }));
vi.mock('./TasksPanel', () => ({ TasksPanel: (p: ProbeProps) => peer(p, 'task') }));
vi.mock('./MemoriesPanel', () => ({ MemoriesPanel: (p: ProbeProps) => peer(p, 'memory') }));
vi.mock('./ProfilePanel', () => ({ ProfilePanel: (p: ProbeProps) => peer(p, 'profile') }));
vi.mock('./SchedulesPanel', () => ({ SchedulesPanel: (p: ProbeProps) => peer(p, 'reminder') }));
vi.mock('./NotificationsPanel', () => ({ NotificationsPanel: (p: ProbeProps) => peer(p, 'inbox') }));
vi.mock('./MailDraftsPanel', () => ({ MailDraftsPanel: (p: ProbeProps) => peer(p, 'draft') }));
vi.mock('./ProjectsPanel', () => ({ ProjectsPanel: (p: ProbeProps & { onSelect: (value: Project | null) => void }) => <>
  {peer(p, 'project')}
  <button disabled={p.selectionDisabled} onClick={() => p.onSelect({ id: project_id, name: 'Synthetic project', description: '', active: true, version: 1, created_at: time })}>Choose synthetic project probe</button>
  <button disabled={p.selectionDisabled} onClick={() => p.onSelect(null)}>Clear synthetic project probe</button>
</> }));
vi.mock('./ConversationHistoryPanel', () => ({ ConversationHistoryPanel: (p: { selectionDisabled: boolean; onSelect: (value: Conversation) => void }) => <section aria-label="History wiring probe">
  <button disabled={p.selectionDisabled} onClick={() => p.onSelect({ id: conversation_id, title: 'Synthetic project conversation', project_id: conversation_project_id, created_at: time })}>Select project conversation probe</button>
  <button disabled={p.selectionDisabled} onClick={() => p.onSelect({ id: global_conversation_id, title: 'Synthetic global conversation', project_id: null, created_at: time })}>Select global conversation probe</button>
</section> }));
vi.mock('./TodayPanel', () => ({ TodayPanel: () => <section aria-label="Today stub"/> }));
vi.mock('./conversation-history-events', () => ({ watch_history_events: vi.fn(() => vi.fn()) }));

function fixture({ authenticated = true, paused = false }: { authenticated?: boolean; paused?: boolean } = {}) {
  const api = new Api(); vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  let execution_paused = paused, version = 1;
  const request = vi.spyOn(api, 'request').mockImplementation(async path => {
    if (path === '/execution') return { execution_paused, version, checked_at: time };
    if (path === '/execution/pause' || path === '/execution/resume') { execution_paused = path.endsWith('/pause'); version++; return { execution_paused, version, checked_at: time }; }
    if (path === '/me') { if (!authenticated) throw new ApiError('UNAUTHENTICATED', false, 401); return {}; }
    if (path === '/auth/logout') return { logged_out: true };
    if (path === '/auth/reauthenticate' || path === '/auth/login') return { csrf_token: 'synthetic-test-token', authenticated: true };
    if (path === '/local-model/status') return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: false, status: 'DISABLED' };
    throw new Error(`Unexpected synthetic route: ${path}`);
  });
  const download = vi.spyOn(api, 'download_export').mockRejectedValue(new Error('Unexpected download from an App wiring probe'));
  return { api, request, download };
}
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name });
async function account_ready(paused = false) { await screen.findByText(`Account state: ${paused ? 'PAUSED' : 'AVAILABLE FOR NEW WORK'}`); }
async function open_export(paused = false) { await account_ready(paused); click('기억 내보내기 열기'); await screen.findByRole('region', { name: 'Export wiring probe' }); }
function open_peers() {
  for (const name of ['Open internal tasks', 'Choose project for a new conversation', 'Open owner settings', 'Open explicit memories', 'Open internal reminders', 'Open internal inbox', 'Open internal mail drafts']) click(name);
}
afterEach(() => { cleanup(); vi.restoreAllMocks(); probes.acquired.mockClear(); probes.inspect.mockClear(); probes.discarded.mockClear(); });

it('never exposes export settings before authentication', async () => {
  const { api, request, download } = fixture({ authenticated: false }); render(<App api={api}/>);
  await screen.findByRole('button', { name: 'Sign in' });
  expect(screen.queryByRole('button', { name: '기억 내보내기 열기' })).toBeNull();
  expect(screen.queryByRole('region', { name: 'Export wiring probe' })).toBeNull();
  expect(request.mock.calls.some(([path]) => path.startsWith('/exports'))).toBe(false); expect(download).not.toHaveBeenCalled();
});

it('requires explicit opening and discards private panel state when explicitly closed', async () => {
  const { api, request, download } = fixture(); render(<App api={api}/>); await account_ready();
  expect(screen.queryByRole('region', { name: 'Export wiring probe' })).toBeNull(); click('기억 내보내기 열기');
  fireEvent.change(screen.getByLabelText('Private export probe marker'), { target: { value: 'synthetic-private-state' } });
  click('기억 내보내기 닫기'); expect(screen.queryByRole('region', { name: 'Export wiring probe' })).toBeNull();
  click('기억 내보내기 열기'); expect((screen.getByLabelText('Private export probe marker') as HTMLInputElement).value).toBe('');
  expect(request.mock.calls.some(([, body]) => body !== undefined)).toBe(false); expect(download).not.toHaveBeenCalled();
});

it('holds every peer write, navigation, project selection, and export close until export resolves', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await open_export(); open_peers();
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'synthetic unsent text' } });
  click('Begin export probe');
  for (const name of ['Begin task probe', 'Begin project probe', 'Begin profile probe', 'Begin memory probe', 'Begin reminder probe', 'Begin inbox probe', 'Begin draft probe',
    'Start new conversation', 'Send mock message', 'Choose synthetic project probe', 'Select project conversation probe', '기억 내보내기 닫기']) expect(button(name).disabled).toBe(true);
  expect(button('Resolve export probe').disabled).toBe(false); expect(button('Read export status probe').disabled).toBe(false);
  click('Refresh server state'); await account_ready(); click('Read export status probe'); click('기억 내보내기 닫기');
  expect(screen.getByRole('region', { name: 'Export wiring probe' })).toBeTruthy(); expect(button('Start new conversation').disabled).toBe(true);
  expect(screen.getByText(/An explicit-memory export request is pending or unresolved/)).toBeTruthy();
  click('Resolve export probe');
  for (const name of ['Begin reminder probe', 'Begin draft probe', 'Start new conversation', 'Choose synthetic project probe', '기억 내보내기 닫기']) expect(button(name).disabled).toBe(false);
  expect(probes.acquired).toHaveBeenCalledExactlyOnceWith('export'); expect(request.mock.calls.some(([, body]) => body !== undefined)).toBe(false);
});

it.each(['task', 'project', 'profile', 'memory', 'reminder', 'inbox', 'draft'])('keeps export creation blocked while the %s domain is pending', async domain => {
  const { api } = fixture(); render(<App api={api}/>); await open_export(); open_peers(); click(`Begin ${domain} probe`);
  expect(button('Begin export probe').disabled).toBe(true); expect(button('Inspect export scope probe').disabled).toBe(true);
  click('Begin export probe'); expect(probes.acquired).toHaveBeenCalledExactlyOnceWith(domain);
  expect(button('Read export status probe').disabled).toBe(false); click(`Resolve ${domain} probe`);
  expect(button('Begin export probe').disabled).toBe(false); expect(button('Inspect export scope probe').disabled).toBe(false);
});

it.each(['export', 'reminder'])('atomically fences the second same-turn acquisition when %s acquires first', async first => {
  const { api } = fixture(); render(<App api={api}/>); await open_export(); click('Open internal reminders');
  const second = first === 'export' ? 'reminder' : 'export';
  act(() => { button(`Begin ${first} probe`).click(); button(`Begin ${second} probe`).click(); });
  expect(probes.acquired).toHaveBeenCalledExactlyOnceWith(first); click(`Resolve ${first} probe`);
  expect(button('Start new conversation').disabled).toBe(false);
});

it('fences a same-turn close before export pending state has rendered', async () => {
  const { api } = fixture(); render(<App api={api}/>); await open_export();
  act(() => { button('Begin export probe').click(); button('기억 내보내기 닫기').click(); });
  expect(screen.getByRole('region', { name: 'Export wiring probe' })).toBeTruthy(); expect(button('기억 내보내기 닫기').disabled).toBe(true);
  click('Resolve export probe'); click('기억 내보내기 닫기'); expect(screen.queryByRole('region', { name: 'Export wiring probe' })).toBeNull();
});

it('allows export panel and read inspection while account execution is paused', async () => {
  const { api, request, download } = fixture({ paused: true }); render(<App api={api}/>); await open_export(true);
  expect(button('Begin export probe').disabled).toBe(true); expect(button('Inspect export scope probe').disabled).toBe(false);
  expect(button('Read export status probe').disabled).toBe(false); click('Inspect export scope probe'); click('Read export status probe');
  expect(probes.inspect.mock.calls).toEqual([['scope'], ['status']]); expect(probes.acquired).not.toHaveBeenCalled();
  click('기억 내보내기 닫기'); expect(screen.queryByRole('region', { name: 'Export wiring probe' })).toBeNull();
  expect(request.mock.calls.some(([, body]) => body !== undefined)).toBe(false); expect(download).not.toHaveBeenCalled();
});

it('clears the private workspace when export reports session expiry', async () => {
  const { api } = fixture(); render(<App api={api}/>); await open_export(); click('Expire export session');
  await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByRole('region', { name: 'Export wiring probe' })).toBeNull();
});

it.each(['pending', 'UNKNOWN'])('clears a %s export on sign out without stale cleanup poisoning the next sign in', async outcome => {
  const { api, request, download } = fixture(); render(<App api={api}/>); await open_export(); click('Begin export probe');
  if (outcome === 'UNKNOWN') { click('Mark export UNKNOWN probe'); expect(screen.getByText('Export result UNKNOWN')).toBeTruthy(); }
  click('Sign out');
  await screen.findByRole('heading', { name: 'Owner sign in' }); await waitFor(() => expect(button('Sign in').disabled).toBe(false));
  expect(screen.queryByRole('region', { name: 'Export wiring probe' })).toBeNull();
  fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'synthetic-owner' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } }); click('Sign in'); await account_ready();
  expect(screen.getByText(/Private recovery identifiers were cleared at sign-out/)).toBeTruthy();
  expect(screen.queryByRole('region', { name: 'Export wiring probe' })).toBeNull();
  expect(probes.discarded).toHaveBeenCalledOnce(); expect(screen.queryByText('Writes paused')).toBeNull();
  expect(button('Start new conversation').disabled).toBe(false); click('기억 내보내기 열기');
  expect(button('Begin export probe').disabled).toBe(false); expect(screen.queryByText('Export result UNKNOWN')).toBeNull();
  expect(request.mock.calls.some(([path]) => path.startsWith('/exports'))).toBe(false); expect(download).not.toHaveBeenCalled();
});

it('keeps a genuinely unresolved export fenced across emergency pause and resume despite panel cleanup', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await open_export(); click('Begin export probe'); click('Mark export UNKNOWN probe');
  fireEvent.click(screen.getByRole('checkbox', { name: 'I reviewed the account-wide impact for this checked version' }));
  expect(button('Pause account execution').disabled).toBe(false); click('Pause account execution'); await account_ready(true);
  await screen.findByText('Export session 1'); expect(probes.discarded).toHaveBeenCalledOnce();
  expect(screen.getByText(/Earlier work remains unresolved across this safety boundary/)).toBeTruthy();
  fireEvent.click(screen.getByRole('checkbox', { name: 'I reviewed the account-wide impact for this checked version' }));
  click('Resume new execution'); await account_ready(); await screen.findByText('Export session 2');
  expect(button('Begin export probe').disabled).toBe(true); expect(button('Start new conversation').disabled).toBe(true);
  expect(screen.getByText('Writes paused')).toBeTruthy();
  expect(request.mock.calls.filter(([, body]) => body !== undefined)).toEqual([
    ['/execution/pause', { expected_version: 1 }], ['/execution/resume', { expected_version: 2 }],
  ]);
});

it('clears an in-progress export on page disposal without submitting any business action', async () => {
  const { api, request, download } = fixture(); const clear = vi.spyOn(api, 'clear'); render(<App api={api}/>); await open_export(); click('Begin export probe');
  fireEvent(window, new Event('pagehide')); await screen.findByRole('heading', { name: 'Owner sign in' });
  expect(screen.queryByRole('region', { name: 'Export wiring probe' })).toBeNull(); expect(clear).toHaveBeenCalled();
  expect(request.mock.calls.some(([, body]) => body !== undefined)).toBe(false); expect(download).not.toHaveBeenCalled();
});

it('requires explicit identity confirmation and remounts the open export panel without replaying a job', async () => {
  const { api, request, download } = fixture(); const fence = vi.spyOn(api, 'fence_requests'); render(<App api={api}/>); await open_export();
  expect(screen.getByText('Export session 0')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Private export probe marker'), { target: { value: 'old synthetic job state' } });
  click('Request export identity probe'); expect(request.mock.calls.some(([path]) => path === '/auth/reauthenticate')).toBe(false);
  expect(screen.getByText(/explicitly create a fresh export job/)).toBeTruthy();
  fireEvent.change(await screen.findByLabelText('Confirm password'), { target: { value: 'synthetic-password' } }); click('Confirm identity');
  await screen.findByText('Export session 1'); await account_ready(); expect(screen.queryByText('Export session 0')).toBeNull();
  expect((screen.getByLabelText('Private export probe marker') as HTMLInputElement).value).toBe(''); expect(fence).toHaveBeenCalledOnce();
  expect(request.mock.calls.filter(([path]) => path === '/auth/reauthenticate')).toHaveLength(1);
  expect(request.mock.calls.some(([path]) => path.startsWith('/exports'))).toBe(false); expect(download).not.toHaveBeenCalled();
});

it('blocks session rotation acquired in the same turn as export pending work', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await open_export(); click('Request export identity probe');
  const password = await screen.findByLabelText('Confirm password'); fireEvent.change(password, { target: { value: 'synthetic-password' } });
  act(() => { button('Begin export probe').click(); password.closest('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  expect(request.mock.calls.some(([path]) => path === '/auth/reauthenticate')).toBe(false); expect(button('Confirm identity').disabled).toBe(true);
  click('Resolve export probe'); expect(button('Confirm identity').disabled).toBe(false);
});

it('derives export project context from the selected conversation before the next-conversation project', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await open_export(); click('Choose project for a new conversation');
  expect(screen.getByText('Export selected project none')).toBeTruthy(); click('Choose synthetic project probe');
  expect(screen.getByText(`Export selected project ${project_id}`)).toBeTruthy(); click('Select project conversation probe');
  expect(screen.getByText(`Export selected project ${conversation_project_id}`)).toBeTruthy(); click('Clear synthetic project probe');
  expect(screen.getByText(`Export selected project ${conversation_project_id}`)).toBeTruthy(); click('Choose synthetic project probe');
  click('Select global conversation probe'); expect(screen.getByText('Export selected project none')).toBeTruthy();
  click('Start new conversation'); expect(screen.getByText(`Export selected project ${project_id}`)).toBeTruthy();
  expect(request.mock.calls.some(([, body]) => body !== undefined)).toBe(false);
});
