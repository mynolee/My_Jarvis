import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api } from './api';

// These probes test App's cross-domain wiring, not the panels' transport behavior.
type ProbeProps = { writesPaused: boolean; sessionKey: number; onPendingChange: (value: boolean) => void; onUnauthorized: () => void; onReauthenticate?: () => void; onAcquirePending?: () => boolean };
vi.mock('./MailDraftsPanel', () => ({ MailDraftsPanel: (p: ProbeProps) => <section aria-label="Mail draft boundary probe"><span>Mail draft session {p.sessionKey}</span><button disabled={p.writesPaused} onClick={() => { if (p.onAcquirePending?.() !== false) p.onPendingChange(true); }}>Begin mail draft write</button><button onClick={() => p.onPendingChange(false)}>Resolve mail draft outcome</button><button onClick={p.onUnauthorized}>Expire mail draft session</button></section> }));
vi.mock('./SchedulesPanel', () => ({ SchedulesPanel: (p: ProbeProps) => <section aria-label="Reminder boundary probe"><span>Reminder session {p.sessionKey}</span><button disabled={p.writesPaused} onClick={() => { if (p.onAcquirePending?.() !== false) p.onPendingChange(true); }}>Begin reminder write</button><button onClick={() => p.onPendingChange(false)}>Resolve reminder outcome</button><button onClick={p.onReauthenticate}>Ask reminder reauthentication</button></section> }));
vi.mock('./conversation-history-events', () => ({ watch_history_events: vi.fn(() => vi.fn()) }));
const id = '11111111-1111-4111-8111-111111111111';
const time = '2030-01-01T00:00:00Z';
function fixture() {
  const api = new Api(); vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation(async path => {
    if (path === '/me' || path === '/auth/logout') return {};
    if (path === '/auth/reauthenticate' || path === '/auth/login') return { csrf_token: 'synthetic-test-token' };
    if (path === '/local-model/status') return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: false, status: 'DISABLED' };
    if (path.startsWith('/conversations?')) return { items: [{ id, title: 'Synthetic conversation', project_id: null, created_at: time }], next_cursor: null };
    if (path.includes('/messages?')) return { items: [], next_cursor: null, stream_cursor: null, current_run: null };
    if (path.startsWith('/tasks?') || path.startsWith('/projects?')) return { items: [], next_cursor: null };
    throw new Error('Unexpected test route');
  });
  return { api, request };
}
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
async function openPanels() {
  await screen.findByRole('button', { name: 'Open internal mail drafts' });
  click('Open internal mail drafts'); click('Open internal reminders');
  await screen.findByRole('region', { name: 'Mail draft boundary probe' });
}
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('locks old editing domains, reminder writes and navigation during mail draft uncertainty', async () => {
  const { api } = fixture(); render(<App api={api}/>); await openPanels(); click('Open internal tasks'); click('Choose project for a new conversation');
  await screen.findByLabelText('Task title'); click('Begin mail draft write');
  for (const name of ['Start new conversation', 'Send mock message', 'Begin reminder write']) expect(screen.getByRole<HTMLButtonElement>('button', { name }).disabled).toBe(true);
  expect(screen.getByLabelText('Task title').closest('fieldset')!.disabled).toBe(true);
  expect(screen.getByLabelText('Project name').closest('fieldset')!.disabled).toBe(true);
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Resolve mail draft outcome' }).disabled).toBe(false);
  click('Resolve mail draft outcome'); await waitFor(() => expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Begin reminder write' }).disabled).toBe(false));
});
it('locks mail drafts and navigation for reminder uncertainty without locking its read-only recovery', async () => {
  const { api } = fixture(); render(<App api={api}/>); await openPanels(); click('Begin reminder write');
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Begin mail draft write' }).disabled).toBe(true);
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Start new conversation' }).disabled).toBe(true);
  click('Resolve reminder outcome'); expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Begin mail draft write' }).disabled).toBe(false);
});
it('fences same-tick session rotation before React has rerendered mail draft pending state', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await openPanels(); click('Ask reminder reauthentication');
  const password = await screen.findByLabelText('Confirm password'); fireEvent.change(password, { target: { value: 'synthetic-password' } });
  const form = password.closest('form')!;
  act(() => { screen.getByRole('button', { name: 'Begin mail draft write' }).click(); form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  expect(request.mock.calls.some(call => call[0] === '/auth/reauthenticate')).toBe(false);
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Confirm identity' }).disabled).toBe(true);
});
it('allows logout while a mail draft is pending and removes both private panels', async () => {
  const { api } = fixture(); render(<App api={api}/>); await openPanels(); click('Begin mail draft write'); click('Sign out');
  await screen.findByRole('heading', { name: 'Owner sign in' });
  expect(screen.queryByRole('region', { name: 'Mail draft boundary probe' })).toBeNull();
  expect(screen.queryByRole('region', { name: 'Reminder boundary probe' })).toBeNull();
});
it('clears mail draft and reminder state when a child observes an expired session', async () => {
  const { api } = fixture(); render(<App api={api}/>); await openPanels(); click('Expire mail draft session');
  await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByRole('region', { name: 'Reminder boundary probe' })).toBeNull();
});
it('propagates ordinary session rotation to both panels without replaying any domain write', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await openPanels(); click('Ask reminder reauthentication');
  fireEvent.change(await screen.findByLabelText('Confirm password'), { target: { value: 'synthetic-password' } }); click('Confirm identity');
  await screen.findByText('Mail draft session 1'); expect(screen.getByText('Reminder session 1')).toBeTruthy();
  expect(request.mock.calls.filter(call => call[0] === '/auth/reauthenticate')).toHaveLength(1);
  expect(request.mock.calls.some(call => call[0].startsWith('/mail-draft') || call[0].startsWith('/schedule'))).toBe(false);
});

it('atomically admits only one of two same-turn panel acquisitions', async () => {
  const { api } = fixture(); render(<App api={api}/>); await openPanels();
  act(() => { screen.getByRole('button', { name: 'Begin mail draft write' }).click(); screen.getByRole('button', { name: 'Begin reminder write' }).click(); });
  click('Resolve mail draft outcome');
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Start new conversation' }).disabled).toBe(false);
});
it('prevents an old-domain project write acquired in the same turn as a mail draft', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await openPanels(); click('Choose project for a new conversation');
  const name = await screen.findByLabelText('Project name'); fireEvent.change(name, { target: { value: 'Synthetic project' } });
  const form = name.closest('form')!;
  act(() => { screen.getByRole('button', { name: 'Begin mail draft write' }).click(); form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  expect(request.mock.calls.some(call => call[0] === '/projects')).toBe(false);
  click('Resolve mail draft outcome'); expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Start new conversation' }).disabled).toBe(false);
});

it('retains only an uncertainty warning across sign-out, not private recovery identifiers or replay', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await openPanels(); click('Begin mail draft write'); click('Sign out');
  await screen.findByRole('heading', { name: 'Owner sign in' });
  fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'synthetic-owner' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } }); click('Sign in');
  await screen.findByText(/Private recovery identifiers were cleared at sign-out/);
  expect(screen.queryByRole('region', { name: 'Mail draft boundary probe' })).toBeNull();
  expect(request.mock.calls.some(call => call[0].startsWith('/mail-draft') || call[0].startsWith('/schedule'))).toBe(false);
});
