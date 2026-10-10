import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api } from './api';

// App integration probes only; endpoint and panel behavior are tested separately.
type AuditProps = { sessionKey: number; onUnauthorized: () => void };
type ScheduleProps = { onPendingChange: (value: boolean) => void; onAcquirePending: () => boolean; onReauthenticate: () => void };
vi.mock('./AuditHistoryPanel', () => ({ AuditHistoryPanel: (p: AuditProps) => <section aria-label="Audit wiring probe"><span>Audit session {p.sessionKey}</span><button onClick={p.onUnauthorized}>Expire audit session</button></section> }));
vi.mock('./TodayPanel', () => ({ TodayPanel: () => <section aria-label="Today stub"/> }));
vi.mock('./SchedulesPanel', () => ({ SchedulesPanel: (p: ScheduleProps) => <section><button onClick={() => { if (p.onAcquirePending()) p.onPendingChange(true); }}>Begin reminder probe</button><button onClick={() => p.onPendingChange(false)}>Resolve reminder probe</button><button onClick={p.onReauthenticate}>Request identity probe</button></section> }));
vi.mock('./conversation-history-events', () => ({ watch_history_events: vi.fn(() => vi.fn()) }));
function fixture(authenticated = true) {
  const api = new Api(); vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation(async path => {
    if (path === '/me') { if (!authenticated) throw new Error('Unavailable'); return {}; }
    if (path === '/auth/logout') return {};
    if (path === '/auth/reauthenticate') return { csrf_token: 'synthetic-test-token' };
    if (path === '/local-model/status') return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: false, status: 'DISABLED' };
    if (path.startsWith('/conversations?')) return { items: [], next_cursor: null };
    throw new Error('Unexpected test route');
  });
  return { api, request };
}
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('does not expose audit history outside the authenticated workspace', async () => {
  const { api } = fixture(false); render(<App api={api}/>);
  await screen.findByRole('button', { name: 'Sign in' });
  expect(screen.queryByRole('button', { name: 'Open execution history' })).toBeNull();
  expect(screen.queryByRole('region', { name: 'Audit wiring probe' })).toBeNull();
});
it('requires explicit opening and removes private panel on close', async () => {
  const { api, request } = fixture(); render(<App api={api}/>);
  await screen.findByRole('button', { name: 'Open execution history' });
  expect(screen.queryByRole('region', { name: 'Audit wiring probe' })).toBeNull();
  click('Open execution history'); await screen.findByText('Audit session 0');
  click('Close execution history'); expect(screen.queryByRole('region', { name: 'Audit wiring probe' })).toBeNull();
  click('Open execution history'); await screen.findByText('Audit session 0');
  expect(request.mock.calls.some(call => /approve|cancel|reconcile|resume/.test(call[0]))).toBe(false);
});
it('allows read-only inspection during pending work without taking over run controls', async () => {
  const { api, request } = fixture(); render(<App api={api}/>);
  await screen.findByRole('button', { name: 'Open execution history' });
  click('Open internal reminders'); click('Begin reminder probe');
  const start = screen.getByRole<HTMLButtonElement>('button', { name: 'Start new conversation' });
  expect(start.disabled).toBe(true); click('Open execution history');
  await screen.findByRole('region', { name: 'Audit wiring probe' }); expect(start.disabled).toBe(true);
  click('Close execution history'); expect(start.disabled).toBe(true);
  expect(request.mock.calls.some(call => /approve|cancel|reconcile|resume/.test(call[0]))).toBe(false);
});
it('clears the workspace when audit reads report session expiry', async () => {
  const { api } = fixture(); render(<App api={api}/>);
  await screen.findByRole('button', { name: 'Open execution history' }); click('Open execution history');
  click('Expire audit session'); await screen.findByRole('heading', { name: 'Owner sign in' });
  expect(screen.queryByRole('region', { name: 'Audit wiring probe' })).toBeNull();
});
it('remounts an open history panel for a new authenticated generation', async () => {
  const { api } = fixture(); render(<App api={api}/>);
  await screen.findByRole('button', { name: 'Open execution history' }); click('Open execution history');
  await screen.findByText('Audit session 0'); click('Open internal reminders'); click('Request identity probe');
  fireEvent.change(await screen.findByLabelText('Confirm password'), { target: { value: 'synthetic-password' } }); click('Confirm identity');
  await screen.findByText('Audit session 1'); expect(screen.queryByText('Audit session 0')).toBeNull();
});
it('clears audit history on sign out', async () => {
  const { api } = fixture(); render(<App api={api}/>);
  await screen.findByRole('button', { name: 'Open execution history' }); click('Open execution history'); click('Sign out');
  await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByRole('region', { name: 'Audit wiring probe' })).toBeNull();
});
it('clears audit history on page disposal without a business action', async () => {
  const { api, request } = fixture(); render(<App api={api}/>);
  await screen.findByRole('button', { name: 'Open execution history' }); click('Open execution history');
  fireEvent(window, new Event('pagehide')); await screen.findByRole('heading', { name: 'Owner sign in' });
  expect(screen.queryByRole('region', { name: 'Audit wiring probe' })).toBeNull();
  expect(request.mock.calls.some(call => /approve|cancel|reconcile|resume/.test(call[0]))).toBe(false);
});
