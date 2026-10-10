import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api } from './api';

// App wiring probes; real Today request and rendering tests live with the panel.
type TodayProps = { sessionKey: number; selectionDisabled: boolean; onUnauthorized: () => void };
type ScheduleProps = { onPendingChange: (value: boolean) => void; onAcquirePending: () => boolean; onReauthenticate: () => void };
vi.mock('./TodayPanel', () => ({ TodayPanel: (p: TodayProps) => <section aria-label="Today wiring probe"><span>Today session {p.sessionKey}</span><button disabled={p.selectionDisabled}>Today navigation probe</button><button onClick={p.onUnauthorized}>Expire Today session</button></section> }));
vi.mock('./SchedulesPanel', () => ({ SchedulesPanel: (p: ScheduleProps) => <section><button onClick={() => { if (p.onAcquirePending()) p.onPendingChange(true); }}>Begin reminder probe</button><button onClick={() => p.onPendingChange(false)}>Resolve reminder probe</button><button onClick={p.onReauthenticate}>Request identity probe</button></section> }));
vi.mock('./conversation-history-events', () => ({ watch_history_events: vi.fn(() => vi.fn()) }));
function fixture(authenticated = true) {
  const api = new Api(); vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation(async path => {
    if (path === '/execution') return { execution_paused: false, version: 1, checked_at: '2026-10-10T00:00:00Z' }; if (path === '/me') { if (!authenticated) throw new Error('Unavailable'); return {}; }
    if (path === '/auth/logout') return {};
    if (path === '/auth/reauthenticate') return { csrf_token: 'synthetic-test-token', authenticated: true };
    if (path === '/local-model/status') return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: false, status: 'DISABLED' };
    if (path.startsWith('/conversations?')) return { items: [], next_cursor: null };
    throw new Error('Unexpected test route');
  });
  return { api, request };
}
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('mounts Today only inside the authenticated workspace', async () => {
  const { api } = fixture(false); render(<App api={api}/>);
  await screen.findByRole('button', { name: 'Sign in' });
  expect(screen.queryByRole('region', { name: 'Today wiring probe' })).toBeNull();
});
it('propagates pending navigation boundaries without creating Today mutations', async () => {
  const { api, request } = fixture(); render(<App api={api}/>);
  await screen.findByRole('region', { name: 'Today wiring probe' }); click('Open internal reminders'); click('Begin reminder probe');
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Today navigation probe' }).disabled).toBe(true);
  click('Resolve reminder probe');
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Today navigation probe' }).disabled).toBe(false);
  expect(request.mock.calls.some(call => call[0] === '/today' && call[1] !== undefined)).toBe(false);
});
it('removes Today when its read detects an expired session', async () => {
  const { api } = fixture(); render(<App api={api}/>);
  await screen.findByRole('region', { name: 'Today wiring probe' }); click('Expire Today session');
  await screen.findByRole('heading', { name: 'Owner sign in' });
  expect(screen.queryByRole('region', { name: 'Today wiring probe' })).toBeNull();
});
it('remounts Today with the new authenticated session generation', async () => {
  const { api, request } = fixture(); render(<App api={api}/>);
  await screen.findByText('Today session 0'); click('Open internal reminders'); click('Request identity probe');
  fireEvent.change(await screen.findByLabelText('Confirm password'), { target: { value: 'synthetic-password' } }); click('Confirm identity');
  await screen.findByText('Today session 1');
  expect(request.mock.calls.filter(call => call[0] === '/auth/reauthenticate')).toHaveLength(1);
});
it('clears Today on page disposal without sending a dashboard action', async () => {
  const { api, request } = fixture(); render(<App api={api}/>);
  await screen.findByRole('region', { name: 'Today wiring probe' }); fireEvent(window, new Event('pagehide'));
  await screen.findByRole('heading', { name: 'Owner sign in' });
  expect(screen.queryByRole('region', { name: 'Today wiring probe' })).toBeNull();
  expect(request.mock.calls.some(call => call[0] === '/today' && call[1] !== undefined)).toBe(false);
});
