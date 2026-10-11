import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api } from './api';

// App integration probes only; endpoint and panel behavior are tested separately.
type UsageProps = { sessionKey: number; onUnauthorized: () => void };
type ScheduleProps = { onPendingChange: (value: boolean) => void; onAcquirePending: () => boolean; onReauthenticate: () => void };
vi.mock('./UsagePanel', () => ({ UsagePanel: (p: UsageProps) => <section aria-label="Usage wiring probe"><span>Usage session {p.sessionKey}</span><button onClick={p.onUnauthorized}>Expire usage session</button></section> }));
vi.mock('./TodayPanel', () => ({ TodayPanel: () => <section aria-label="Today stub"/> }));
vi.mock('./SchedulesPanel', () => ({ SchedulesPanel: (p: ScheduleProps) => <section><button onClick={() => { if (p.onAcquirePending()) p.onPendingChange(true); }}>Begin reminder probe</button><button onClick={() => p.onPendingChange(false)}>Resolve reminder probe</button><button onClick={p.onReauthenticate}>Request identity probe</button></section> }));
vi.mock('./conversation-history-events', () => ({ watch_history_events: vi.fn(() => vi.fn()) }));
function fixture(authenticated = true) {
  const api = new Api(); vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation(async path => {
    if (path === '/execution') return { execution_paused: false, version: 1, checked_at: '2026-10-10T00:00:00Z' }; if (path === '/me') { if (!authenticated) throw new Error('Unavailable'); return {}; }
    if (path === '/auth/logout') return { logged_out: true };
    if (path === '/auth/reauthenticate') return { csrf_token: 'synthetic-test-token', authenticated: true };
    if (path === '/local-model/status') return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: false, status: 'DISABLED' };
    if (path.startsWith('/conversations?')) return { items: [], next_cursor: null };
    throw new Error('Unexpected test route');
  });
  return { api, request };
}
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('does not expose usage summary outside the authenticated workspace', async () => {
  const { api } = fixture(false); render(<App api={api}/>);
  await screen.findByRole('button', { name: 'Sign in' });
  expect(screen.queryByRole('button', { name: '사용량 요약 열기' })).toBeNull();
  expect(screen.queryByRole('region', { name: 'Usage wiring probe' })).toBeNull();
});
it('requires explicit opening and removes private panel on close', async () => {
  const { api, request } = fixture(); render(<App api={api}/>);
  await screen.findByRole('button', { name: '사용량 요약 열기' });
  expect(screen.queryByRole('region', { name: 'Usage wiring probe' })).toBeNull();
  click('사용량 요약 열기'); await screen.findByText('Usage session 0');
  click('사용량 요약 닫기'); expect(screen.queryByRole('region', { name: 'Usage wiring probe' })).toBeNull();
  click('사용량 요약 열기'); await screen.findByText('Usage session 0');
  expect(request.mock.calls.some(call => /approve|cancel|reconcile|resume/.test(call[0]))).toBe(false);
});
it('allows read-only inspection during pending work without taking over run controls', async () => {
  const { api, request } = fixture(); render(<App api={api}/>);
  await screen.findByRole('button', { name: '사용량 요약 열기' });
  await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); click('Open internal reminders'); click('Begin reminder probe');
  const start = screen.getByRole<HTMLButtonElement>('button', { name: 'Start new conversation' });
  expect(start.disabled).toBe(true); click('사용량 요약 열기');
  await screen.findByRole('region', { name: 'Usage wiring probe' }); expect(start.disabled).toBe(true);
  click('사용량 요약 닫기'); expect(start.disabled).toBe(true);
  expect(request.mock.calls.some(call => /approve|cancel|reconcile|resume/.test(call[0]))).toBe(false);
});
it('clears the workspace when usage reads report session expiry', async () => {
  const { api } = fixture(); render(<App api={api}/>);
  await screen.findByRole('button', { name: '사용량 요약 열기' }); click('사용량 요약 열기');
  click('Expire usage session'); await screen.findByRole('heading', { name: 'Owner sign in' });
  expect(screen.queryByRole('region', { name: 'Usage wiring probe' })).toBeNull();
});
it('remounts an open usage panel for a new authenticated generation', async () => {
  const { api } = fixture(); render(<App api={api}/>);
  await screen.findByRole('button', { name: '사용량 요약 열기' }); click('사용량 요약 열기');
  await screen.findByText('Usage session 0'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); click('Open internal reminders'); click('Request identity probe');
  fireEvent.change(await screen.findByLabelText('Confirm password'), { target: { value: 'synthetic-password' } }); click('Confirm identity');
  await screen.findByText('Usage session 1'); expect(screen.queryByText('Usage session 0')).toBeNull();
});
it('clears usage summary on sign out', async () => {
  const { api } = fixture(); render(<App api={api}/>);
  await screen.findByRole('button', { name: '사용량 요약 열기' }); click('사용량 요약 열기'); click('Sign out');
  await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByRole('region', { name: 'Usage wiring probe' })).toBeNull();
});
it('clears usage summary on page disposal without a business action', async () => {
  const { api, request } = fixture(); render(<App api={api}/>);
  await screen.findByRole('button', { name: '사용량 요약 열기' }); click('사용량 요약 열기');
  fireEvent(window, new Event('pagehide')); await screen.findByRole('heading', { name: 'Owner sign in' });
  expect(screen.queryByRole('region', { name: 'Usage wiring probe' })).toBeNull();
  expect(request.mock.calls.some(call => /approve|cancel|reconcile|resume/.test(call[0]))).toBe(false);
});
