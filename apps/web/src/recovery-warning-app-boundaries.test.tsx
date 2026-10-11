import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi, type Mock, type MockInstance } from 'vitest';
import { useState } from 'react';
import { App } from './App';
import { Api, ApiError } from './api';
import type { Run } from './domain';
import type { MemoryExportPanelProps } from './MemoryExportPanel';

// Real App, ConversationHistoryPanel and ExecutionControlsPanel in every case.
// The explicitly labelled domain probes test ONLY App's ten-domain signal wiring.
// MemoryExportPanel switches to importOriginal for the real recovery cases below.
// Design group 3 is covered by the four real generic/learning held-intent/write
// BC08c logout cases in memory-study-composition.test.tsx, including old handlers,
// late responses, private purges and exact intent/business request counts.
const controls = vi.hoisted(() => ({ realExport: false, acquired: vi.fn() }));
type Domain = 'task' | 'project' | 'memory' | 'profile' | 'schedule' | 'notification' | 'mail_draft' | 'memory_export' | 'conversation_deletion' | 'study';
type ProbeProps = {
  writesPaused?: boolean; onAcquirePending: () => boolean; onPendingChange: (pending: boolean) => void;
  onUnauthorized: () => void; onReauthenticate?: () => void; onOutcomeUnknown?: () => void;
};
function DomainProbe({ domain, ...p }: ProbeProps & { domain: Domain }) {
  const [unknown, setUnknown] = useState(false);
  return <section aria-label={`${domain} App wiring probe`}>
    <input aria-label={`${domain} private wiring value`} defaultValue=""/>
    <button disabled={p.writesPaused} onClick={() => {
      if (p.onAcquirePending()) { controls.acquired(domain); p.onPendingChange(true); }
    }}>Acquire {domain} wiring probe</button>
    <button onClick={() => setUnknown(true)}>Mark {domain} UNKNOWN wiring probe</button>
    {unknown && <span>{domain} synthetic UNKNOWN</span>}
    <button onClick={() => { p.onPendingChange(false); setUnknown(false); }}>Release {domain} wiring probe</button>
    {p.onReauthenticate && <button onClick={p.onReauthenticate}>Request {domain} identity wiring probe</button>}
    {p.onOutcomeUnknown && <button onClick={p.onOutcomeUnknown}>Report {domain} discarded outcome wiring probe</button>}
  </section>;
}
vi.mock('./TasksPanel', () => ({ TasksPanel: (p: ProbeProps) => <DomainProbe {...p} domain="task"/> }));
vi.mock('./ProjectsPanel', () => ({ ProjectsPanel: (p: ProbeProps) => <DomainProbe {...p} domain="project"/> }));
vi.mock('./MemoriesPanel', () => ({ MemoriesPanel: (p: ProbeProps) => <DomainProbe {...p} domain="memory"/> }));
vi.mock('./LearningProfilePanel', () => ({ LearningProfilePanel: (p: ProbeProps) => <DomainProbe {...p} domain="memory"/> }));
vi.mock('./ProfilePanel', () => ({ ProfilePanel: (p: ProbeProps) => <DomainProbe {...p} domain="profile"/> }));
vi.mock('./SchedulesPanel', () => ({ SchedulesPanel: (p: ProbeProps) => <DomainProbe {...p} domain="schedule"/> }));
vi.mock('./NotificationsPanel', () => ({ NotificationsPanel: (p: ProbeProps) => <DomainProbe {...p} domain="notification"/> }));
vi.mock('./MailDraftsPanel', () => ({ MailDraftsPanel: (p: ProbeProps) => <DomainProbe {...p} domain="mail_draft"/> }));
vi.mock('./ConversationDeletionPanel', () => ({ ConversationDeletionPanel: (p: ProbeProps) => <DomainProbe {...p} domain="conversation_deletion"/> }));
vi.mock('./StudyPanel', () => ({ StudyPanel: (p: ProbeProps) => <DomainProbe {...p} domain="study"/> }));
vi.mock('./MemoryExportPanel', async importOriginal => {
  const actual = await importOriginal<typeof import('./MemoryExportPanel')>();
  return { MemoryExportPanel: (p: MemoryExportPanelProps) => controls.realExport
    ? <actual.MemoryExportPanel {...p}/>
    : <DomainProbe {...p} onAcquirePending={p.onAcquirePending!} onPendingChange={p.onPendingChange!} domain="memory_export"/> };
});
vi.mock('./TodayPanel', () => ({ TodayPanel: () => <section aria-label="Unrelated Today reader stub"/> }));
vi.mock('./conversation-history-events', () => ({ watch_history_events: () => () => {} }));

const cid = 'c1111111-1111-4111-8111-111111111111';
const other = 'c2222222-2222-4222-8222-222222222222';
const rid = 'c3333333-3333-4333-8333-333333333333';
const requestId = 'c4444444-4444-4444-8444-444444444444';
const jobId = 'c5555555-5555-4555-8555-555555555555';
const at = '2026-10-10T12:00:00Z';
const privateText = 'Synthetic private body never belongs in a recovery warning';
const privateOwner = 'synthetic-private-owner';
const privatePassword = 'synthetic-private-password';
const privateCsrf = 'synthetic-private-csrf';
const warningName = 'Previous request outcome unconfirmed';
const warningCopy = 'A previous request may still have committed. A view or session change does not confirm its outcome. Review saved data and execution history before making a new change. A new sign-in does not cancel earlier work. No request has been repeated automatically.';
const job = { id: jobId, kind: 'EXPLICIT_MEMORIES', scope: 'GLOBAL', project_id: null, format: 'JSON_UTF8', semantics: 'CURRENT_AT_DOWNLOAD', status: 'AVAILABLE', created_at: at, expires_at: '2026-10-10T12:15:00Z', checked_at: at };
const exportBody = { kind: 'EXPLICIT_MEMORIES', scope: 'GLOBAL', project_id: null, format: 'JSON_UTF8' };
const domains: Domain[] = ['task', 'project', 'memory', 'profile', 'schedule', 'notification', 'mail_draft', 'memory_export', 'conversation_deletion', 'study'];
const openLabels: Record<Exclude<Domain, 'study'>, string> = {
  task: 'Open internal tasks', project: 'Choose project for a new conversation', memory: 'Open explicit memories',
  profile: 'Open owner settings', schedule: 'Open internal reminders', notification: 'Open internal inbox',
  mail_draft: 'Open internal mail drafts', memory_export: '기억 내보내기 열기', conversation_deletion: '대화 원문 삭제 열기',
};
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name });
const click = (name: string) => fireEvent.click(button(name));
const change = (name: string, value: string) => fireEvent.change(screen.getByLabelText(name), { target: { value } });
const reviewExecution = () => fireEvent.click(screen.getByRole('checkbox', { name: 'I reviewed the account-wide impact for this checked version' }));
function deferred<T = unknown>() {
  let resolve!: (value: T) => void, reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function fixture(options: { authenticated?: boolean } = {}) {
  const api = new Api();
  const state = {
    authenticated: options.authenticated ?? true, paused: false, version: 1,
    listRead: null as Promise<unknown> | null, historyRead: null as Promise<unknown> | null,
    listError: null as Error | null, historyError: null as Error | null, deleted: false,
    run: null as Run | null, loginFailures: 0,
    logout: { logged_out: true } as unknown, reauth: { csrf_token: privateCsrf, authenticated: true } as unknown,
    message: {} as unknown, pause: null as unknown, exportCreate: job as unknown,
    exportReceipt: { status: 'COMMITTED', job } as unknown,
  };
  const conversations = () => ({ items: [
    ...(state.deleted ? [] : [{ id: cid, title: 'Canonical conversation', project_id: null, created_at: at }]),
    { id: other, title: 'Other canonical conversation', project_id: null, created_at: at },
  ], next_cursor: null });
  const history = () => ({ items: [], next_cursor: null, stream_cursor: null, current_run: state.run });
  const result = (value: unknown) => { if (value instanceof Error) throw value; return value; };
  const bootstrap = vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation((async (path: string, body?: unknown) => {
    const route = path.split('?')[0];
    if (route === '/me') { if (!state.authenticated) throw new ApiError('UNAUTHENTICATED', false, 401); return {}; }
    if (route === '/auth/logout') { state.authenticated = false; return result(state.logout); }
    if (route === '/auth/login') {
      if (state.loginFailures > 0) { state.loginFailures--; throw new ApiError('INVALID_CREDENTIALS', false, 403); }
      state.authenticated = true; return { csrf_token: privateCsrf, authenticated: true };
    }
    if (route === '/auth/reauthenticate') return result(state.reauth);
    if (route === '/execution') return { execution_paused: state.paused, version: state.version, checked_at: at };
    if (route === '/execution/pause' || route === '/execution/resume') {
      if (route === '/execution/pause' && state.pause !== null) return result(state.pause);
      state.paused = route.endsWith('/pause'); state.version++;
      return { execution_paused: state.paused, version: state.version, checked_at: at };
    }
    if (route === '/local-model/status') return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: true, status: 'READY' };
    if (route === '/conversations' && body === undefined) {
      if (state.listError) throw state.listError;
      return state.listRead ?? conversations();
    }
    if ((route === `/conversations/${cid}/messages` || route === `/conversations/${other}/messages`) && body === undefined) {
      if (state.historyError) throw state.historyError;
      return state.historyRead ?? history();
    }
    if (route === `/conversations/${cid}/messages` && body !== undefined) return result(state.message);
    if (route === '/exports') return result(state.exportCreate);
    if (route.startsWith('/exports/requests/')) return result(state.exportReceipt);
    throw new Error(`Unexpected synthetic route: ${path}`);
  }) as Api['request']);
  const download = vi.spyOn(api, 'download_export').mockRejectedValue(new Error('No download was requested'));
  return { api, state, request, bootstrap, download, conversations, history };
}
type Fixture = ReturnType<typeof fixture>;
const mutations = (f: Fixture) => f.request.mock.calls.filter(([, body]) => body !== undefined);
const mutationPaths = (f: Fixture) => mutations(f).map(([path]) => path);
const routes = (f: Fixture, path: string) => f.request.mock.calls.filter(([candidate]) => candidate.split('?')[0] === path);
async function accountReady(paused = false) {
  await screen.findByText('Session active');
  await screen.findByText(`Account state: ${paused ? 'PAUSED' : 'AVAILABLE FOR NEW WORK'}`);
  await waitFor(() => expect(button('Sign out').disabled).toBe(false));
}
async function canonicalReady() {
  await screen.findByText('No saved messages.');
  await waitFor(() => expect(button('Canonical conversation').getAttribute('aria-current')).toBe('true'));
}
async function ready() { await accountReady(); await canonicalReady(); }
async function signedOut() {
  await screen.findByRole('heading', { name: 'Owner sign in' });
  await waitFor(() => expect(button('Sign in').disabled).toBe(false));
}
function enterLogin() { change('Login name', privateOwner); change('Password', privatePassword); click('Sign in'); }
async function signIn() { enterLogin(); await ready(); }
function assertWarning() {
  const alerts = screen.getAllByRole('alert', { name: warningName });
  expect(alerts).toHaveLength(1); expect(alerts[0].textContent).toBe(warningCopy);
  for (const secret of [cid, other, rid, requestId, jobId, privateText, privateOwner, privatePassword, privateCsrf]) expect(alerts[0].outerHTML).not.toContain(secret);
  expect(within(alerts[0]).queryByRole('button')).toBeNull(); expect(within(alerts[0]).queryByRole('link')).toBeNull();
  return alerts[0];
}
function assertNoWarning() { expect(screen.queryByRole('alert', { name: warningName })).toBeNull(); }
function openDomain(domain: Domain) { if (domain !== 'study') click(openLabels[domain]); }
async function loseProbe(f: Fixture, domain: Domain = 'task', boundary: 'logout' | '401' = 'logout', unknown = false) {
  openDomain(domain); change(`${domain} private wiring value`, privateText);
  click(`Acquire ${domain} wiring probe`);
  if (unknown) { click(`Mark ${domain} UNKNOWN wiring probe`); expect(screen.getByText(`${domain} synthetic UNKNOWN`)).toBeTruthy(); }
  expect(button('Start new conversation').disabled).toBe(true);
  if (boundary === 'logout') click('Sign out');
  else { f.state.listError = new ApiError('UNAUTHENTICATED', false, 401); click('Refresh conversations'); }
  await signedOut(); f.state.listError = null;
  expect(screen.queryByRole('region', { name: `${domain} App wiring probe` })).toBeNull(); assertWarning();
}
async function olderWarning(f: Fixture) { await loseProbe(f); await signIn(); assertWarning(); }
function requestOrdinaryIdentity() { click('Open internal reminders'); click('Request schedule identity wiring probe'); change('Confirm password', privatePassword); click('Confirm identity'); }
function requestControlIdentity() { click('Confirm identity for execution controls'); change('Execution control password', privatePassword); click('Confirm execution identity'); }
function openRealExport() { click('기억 내보내기 열기'); }
function createExport() { fireEvent.click(screen.getByLabelText('선택한 범위와 개인정보 포함 가능성을 확인했습니다')); click('선택 범위 내보내기 작업 생성'); }
const reconcileExport = () => click('원래 생성 요청 결과 확인');

// Every scenario also checks that display/recovery causes no persistent state or
// hidden fetch. These spies cover both Web Storage objects, IDB, CacheStorage,
// history state and URL changes; transport itself is explicitly synthetic.
let storageWrite: MockInstance<Storage['setItem']>, storageRead: MockInstance<Storage['getItem']>;
let storageRemove: MockInstance<Storage['removeItem']>, storageClear: MockInstance<Storage['clear']>;
let pushState: MockInstance<History['pushState']>, replaceState: MockInstance<History['replaceState']>;
let idbOpen: Mock, idbDelete: Mock, cachePut: Mock, cacheAdd: Mock, cacheAddAll: Mock, cacheDelete: Mock;
let cacheOpen: Mock<() => Promise<{ put: Mock; add: Mock; addAll: Mock; delete: Mock }>>;
let hiddenFetch: Mock<() => Promise<never>>;
beforeEach(() => {
  controls.realExport = false; controls.acquired.mockClear();
  storageWrite = vi.spyOn(Storage.prototype, 'setItem'); storageRead = vi.spyOn(Storage.prototype, 'getItem');
  storageRemove = vi.spyOn(Storage.prototype, 'removeItem'); storageClear = vi.spyOn(Storage.prototype, 'clear');
  pushState = vi.spyOn(history, 'pushState'); replaceState = vi.spyOn(history, 'replaceState');
  idbOpen = vi.fn(); idbDelete = vi.fn(); vi.stubGlobal('indexedDB', { open: idbOpen, deleteDatabase: idbDelete });
  cachePut = vi.fn(); cacheAdd = vi.fn(); cacheAddAll = vi.fn(); cacheDelete = vi.fn();
  cacheOpen = vi.fn(async () => ({ put: cachePut, add: cacheAdd, addAll: cacheAddAll, delete: cacheDelete }));
  vi.stubGlobal('caches', { open: cacheOpen, delete: cacheDelete });
  hiddenFetch = vi.fn(() => Promise.reject(new Error('Unexpected unscripted fetch'))); vi.stubGlobal('fetch', hiddenFetch);
});
afterEach(() => {
  cleanup();
  try {
    for (const spy of [storageWrite, storageRead, storageRemove, storageClear, pushState, replaceState, idbOpen, idbDelete, cacheOpen, cachePut, cacheAdd, cacheAddAll, cacheDelete, hiddenFetch]) expect(spy).not.toHaveBeenCalled();
  } finally { vi.restoreAllMocks(); vi.unstubAllGlobals(); }
});

describe('persistent content-free warning: real App/history/execution boundaries', () => {
  it('G01: starts absent, remains absent through idle sign-out/sign-in and real automatic selection', async () => {
    const f = fixture(); render(<App api={f.api}/>); await ready(); assertNoWarning();
    change('Message', privateText); click('Sign out'); await signedOut(); assertNoWarning();
    expect(screen.queryByLabelText('Message')).toBeNull(); await signIn(); assertNoWarning();
    expect((screen.getByLabelText('Message') as HTMLTextAreaElement).value).toBe('');
    expect(mutationPaths(f)).toEqual(['/auth/logout', '/auth/login']); expect(f.bootstrap).toHaveBeenCalledTimes(2);
    expect(routes(f, '/me')).toHaveLength(1); expect(f.download).not.toHaveBeenCalled();
  });
  it('G01: initial unauthenticated bootstrap and successful explicit login do not invent a loss', async () => {
    const f = fixture({ authenticated: false }); render(<App api={f.api}/>); await signedOut(); assertNoWarning();
    await signIn(); assertNoWarning(); expect(mutationPaths(f)).toEqual(['/auth/login']);
  });
  it.each(['401', 'pagehide'] as const)('G01: idle read-only %s purges without inventing a loss', async boundary => {
    const f = fixture(); render(<App api={f.api}/>); await ready(); change('Message', privateText);
    if (boundary === '401') { f.state.listError = new ApiError('UNAUTHENTICATED', false, 401); click('Refresh conversations'); }
    else fireEvent(window, new Event('pagehide'));
    await signedOut(); assertNoWarning(); expect(screen.queryByLabelText('Message')).toBeNull();
    f.state.listError = null; await signIn(); assertNoWarning(); expect(mutationPaths(f)).toEqual(['/auth/login']);
  });
  it('G05: idle ambiguous logout alone retains its separate inherited termination notice', async () => {
    const f = fixture(); f.state.logout = new ApiError('UNKNOWN', true); render(<App api={f.api}/>); await ready();
    click('Sign out'); await signedOut(); await screen.findByText(/Server session termination is unconfirmed/); assertNoWarning();
    expect(mutationPaths(f)).toEqual(['/auth/logout']);
  });

  describe.each(domains)('G02: %s App signal wiring, not domain transport', domain => {
    it.each([
      ['logout', false], ['logout', true], ['401', false], ['401', true],
    ] as const)('captures %s with synthetic UNKNOWN=%s and survives held then automatic canonical selection', async (boundary, unknown) => {
      const f = fixture(); render(<App api={f.api}/>); await ready(); assertNoWarning();
      await loseProbe(f, domain, boundary, unknown);
      const list = deferred(); f.state.listRead = list.promise; enterLogin(); await accountReady();
      assertWarning(); expect(screen.queryByRole('button', { name: 'Canonical conversation' })).toBeNull();
      f.state.listRead = null; await act(async () => list.resolve(f.conversations())); await canonicalReady(); assertWarning();
      expect(button('Start new conversation').disabled).toBe(false); expect(screen.queryByText(`${domain} synthetic UNKNOWN`)).toBeNull();
      expect(screen.queryByDisplayValue(privateText)).toBeNull(); expect(controls.acquired).toHaveBeenCalledExactlyOnceWith(domain);
      expect(mutationPaths(f)).toEqual(boundary === 'logout' ? ['/auth/logout', '/auth/login'] : ['/auth/login']);
      expect(f.bootstrap).toHaveBeenCalledTimes(2); expect(routes(f, '/me')).toHaveLength(1); expect(f.download).not.toHaveBeenCalled();
    });
  });
  it('G02/G12: same-turn acquisition is captured before pending UI can render', async () => {
    const f = fixture(); render(<App api={f.api}/>); await ready(); openDomain('task');
    act(() => { button('Acquire task wiring probe').click(); button('Sign out').click(); });
    await signedOut(); assertWarning(); await signIn(); assertWarning();
    expect(controls.acquired).toHaveBeenCalledExactlyOnceWith('task'); expect(mutationPaths(f)).toEqual(['/auth/logout', '/auth/login']);
  });
  it.each(['study', 'conversation_deletion', 'memory_export'] as const)('G02: an active-session %s discarded-outcome callback immediately displays content-free loss (App wiring probe)', async domain => {
    const f = fixture(); render(<App api={f.api}/>); await ready(); openDomain(domain);
    click(`Report ${domain} discarded outcome wiring probe`); assertWarning();
    expect(screen.getByText('Session active')).toBeTruthy(); expect(button('Start new conversation').disabled).toBe(false);
    expect(mutationPaths(f)).toEqual([]);
  });

  it('G04: manual selection, new conversation, validation, new operation, deletion and successful identity notices cannot replace the warning', async () => {
    const f = fixture(); render(<App api={f.api}/>); await ready(); await olderWarning(f);
    click('Other canonical conversation'); await waitFor(() => expect(button('Other canonical conversation').getAttribute('aria-current')).toBe('true')); assertWarning();
    click('Start new conversation'); expect(screen.getByText('Select a conversation to view its history.')).toBeTruthy(); assertWarning();
    click('Canonical conversation'); await canonicalReady();
    change('Message', '가'.repeat(6000)); click('Send mock message'); await screen.findByText('Message exceeds the 16000-byte mock input limit.'); assertWarning();
    const pending = deferred(); f.state.message = pending.promise; change('Message', 'A new explicitly requested synthetic message'); click('Send mock message');
    expect(screen.queryByText('Message exceeds the 16000-byte mock input limit.')).toBeNull(); assertWarning();
    await act(async () => pending.resolve({})); await accountReady();
    f.state.deleted = true; f.state.historyError = new ApiError('CONVERSATION_DELETED', false, 410); click('Refresh server state');
    await screen.findByText('대화 원문 삭제가 확인되었습니다. 별도로 저장한 기억과 이미 전달된 사본은 삭제 대상이 아닙니다.'); assertWarning();
    f.state.historyError = null; click('Other canonical conversation'); await screen.findByText('No saved messages.');
    requestOrdinaryIdentity(); await screen.findByText('Identity confirmed. Review the current snapshot before deciding.'); await accountReady(); assertWarning();
    expect(mutationPaths(f)).toEqual(['/auth/logout', '/auth/login', `/conversations/${cid}/messages`, '/auth/reauthenticate']);
    const message = mutations(f)[2]; expect(message[1]).toEqual({ client_message_id: expect.any(String), text: 'A new explicitly requested synthetic message', attachment_ids: [] });
    expect(f.request.mock.calls.filter(([path, body]) => path === '/conversations' && body !== undefined)).toHaveLength(0);
  });
  it.each(['accepted', 'malformed', 'ambiguous'] as const)('G05: a prior warning coexists with waiting and %s logout, with exactly one termination request', async outcome => {
    const f = fixture(); const pending = deferred(); f.state.logout = pending.promise; render(<App api={f.api}/>); await ready();
    click('Report study discarded outcome wiring probe'); assertWarning(); click('Sign out');
    await screen.findByText('Local private view cleared. Waiting for the server sign-out result.'); assertWarning();
    await act(async () => { if (outcome === 'ambiguous') pending.reject(new ApiError('UNKNOWN', true)); else pending.resolve(outcome === 'accepted' ? { logged_out: true } : { logged_out: true, unverified: true }); });
    await signedOut(); await screen.findByText(outcome === 'accepted' ? 'The server accepted the sign-out request.' : /Server session termination is unconfirmed/);
    assertWarning(); expect(mutationPaths(f)).toEqual(['/auth/logout']);
  });
  it('G06: failed login, successful login and another idle logout/login keep exactly one warning without replay', async () => {
    const f = fixture(); render(<App api={f.api}/>); await ready(); await loseProbe(f);
    f.state.loginFailures = 1; enterLogin(); await screen.findByText('INVALID_CREDENTIALS'); await signedOut(); assertWarning();
    await signIn(); assertWarning(); click('Sign out'); await signedOut(); assertWarning(); await signIn(); assertWarning();
    expect(screen.getAllByText(warningCopy)).toHaveLength(1);
    expect(mutationPaths(f)).toEqual(['/auth/logout', '/auth/login', '/auth/login', '/auth/logout', '/auth/login']);
    expect(f.bootstrap).toHaveBeenCalledTimes(4);
  });

  it.each(['transport UNKNOWN', 'canonical UNKNOWN'] as const)('G07/G13: real main %s remains fenced across pause/resume/current-state reads and warning interaction', async mode => {
    const f = fixture();
    if (mode === 'canonical UNKNOWN') f.state.run = { id: rid, status: 'UNKNOWN', version: 1, result: null, approval_id: null, job_id: null, execution_mode: 'MOCK' };
    render(<App api={f.api}/>); await ready();
    if (mode === 'transport UNKNOWN') { f.state.message = new ApiError('UNKNOWN', true); change('Message', privateText); click('Send mock message'); }
    await screen.findByText('Writes paused'); assertNoWarning(); expect(button('Start new conversation').disabled).toBe(true);
    const pending = deferred(); f.state.pause = pending.promise; reviewExecution(); click('Pause account execution');
    await screen.findByText(/Session safety boundary in progress/); assertWarning(); expect(screen.queryByLabelText('Message')).toBeNull();
    f.state.paused = true; f.state.version = 2;
    await act(async () => pending.resolve({ execution_paused: true, version: 2, checked_at: at })); await accountReady(true); assertWarning();
    f.state.run = null; reviewExecution(); click('Resume new execution'); await accountReady();
    click('Refresh server state'); await accountReady(); await screen.findByText('No run selected.');
    fireEvent.focus(assertWarning()); fireEvent.click(assertWarning()); fireEvent.keyDown(window, { key: 'Escape' }); assertWarning();
    expect(screen.getByText(/Earlier work remains unresolved across this safety boundary/)).toBeTruthy();
    expect(button('Start new conversation').disabled).toBe(true); expect(button('Send mock message').disabled).toBe(true);
    click('Start new conversation'); click('Send mock message');
    expect(mutationPaths(f)).toEqual([...(mode === 'transport UNKNOWN' ? [`/conversations/${cid}/messages`] : []), '/execution/pause', '/execution/resume']);
    expect(mutations(f).slice(-2)).toEqual([['/execution/pause', { expected_version: 1 }], ['/execution/resume', { expected_version: 2 }]]);
  });

  it('G08: real verified current export creation releases only its current fence without inventing historical loss', async () => {
    controls.realExport = true; const f = fixture(); render(<App api={f.api}/>); await ready(); openRealExport(); createExport();
    await screen.findByRole('region', { name: '내보내기 작업 메타데이터' }); assertNoWarning(); expect(button('Start new conversation').disabled).toBe(false);
    expect(mutations(f)).toEqual([['/exports', exportBody, expect.any(String)]]); expect(f.download).not.toHaveBeenCalled();
  });
  it.each([false, true])('G08/G12/G13: real NOT_OBSERVED and malformed receipts stay fenced; matching COMMITTED releases only its own fence, older warning=%s', async priorLoss => {
    controls.realExport = true; const f = fixture(); render(<App api={f.api}/>); await ready(); if (priorLoss) await olderWarning(f);
    vi.spyOn(crypto, 'randomUUID').mockReturnValue(requestId); f.state.exportCreate = new ApiError('UNKNOWN', true);
    openRealExport(); createExport(); await screen.findByRole('button', { name: '원래 생성 요청 결과 확인' });
    expect(button('Start new conversation').disabled).toBe(true); priorLoss ? assertWarning() : assertNoWarning();
    f.state.exportReceipt = { status: 'NOT_OBSERVED', job: null }; reconcileExport(); await screen.findByText(/실패했다는 증거가 아니므로/);
    expect(button('Start new conversation').disabled).toBe(true);
    f.state.exportReceipt = { status: 'COMMITTED', job: { ...job, scope: 'PROJECT', project_id: other } }; reconcileExport(); await screen.findByText(/INVALID_EXPORT_RESPONSE/);
    expect(button('Start new conversation').disabled).toBe(true); expect(screen.queryByRole('button', { name: '현재 기억 JSON 다운로드' })).toBeNull();
    click('Refresh server state'); await accountReady(); expect(button('Start new conversation').disabled).toBe(true);
    if (priorLoss) { fireEvent.focus(assertWarning()); fireEvent.click(assertWarning()); fireEvent.keyDown(window, { key: 'Escape' }); assertWarning(); }
    click('내보내기 본인 확인'); expect(routes(f, '/auth/reauthenticate')).toHaveLength(0);
    f.state.exportReceipt = { status: 'COMMITTED', job }; reconcileExport(); await screen.findByRole('region', { name: '내보내기 작업 메타데이터' });
    expect(button('Start new conversation').disabled).toBe(false); priorLoss ? assertWarning() : assertNoWarning();
    expect(routes(f, '/exports')).toEqual([['/exports', exportBody, requestId]]);
    expect(routes(f, `/exports/requests/${requestId}`)).toEqual(Array.from({ length: 3 }, () => [`/exports/requests/${requestId}`]));
    expect(mutationPaths(f)).toEqual([...(priorLoss ? ['/auth/logout', '/auth/login'] : []), '/exports']); expect(f.download).not.toHaveBeenCalled();
  });
  it('G08: real UNKNOWN export is discarded at an active-session execution boundary without claiming session termination or replay', async () => {
    controls.realExport = true; const f = fixture(); f.state.exportCreate = new ApiError('UNKNOWN', true);
    render(<App api={f.api}/>); await ready(); openRealExport(); createExport(); await screen.findByRole('button', { name: '원래 생성 요청 결과 확인' }); assertNoWarning();
    const pause = deferred(); f.state.pause = pause.promise; reviewExecution(); click('Pause account execution');
    await screen.findByText(/Session safety boundary in progress/); assertWarning(); expect(screen.getByText('Session active')).toBeTruthy();
    f.state.paused = true; f.state.version = 2; await act(async () => pause.resolve({ execution_paused: true, version: 2, checked_at: at })); await accountReady(true);
    assertWarning(); expect(screen.queryByRole('button', { name: '원래 생성 요청 결과 확인' })).toBeNull();
    expect(screen.queryByRole('region', { name: '내보내기 작업 메타데이터' })).toBeNull(); expect(button('Start new conversation').disabled).toBe(true);
    expect(screen.queryByText(/Private recovery identifiers were cleared at sign-out/)).toBeNull();
    expect(mutationPaths(f)).toEqual(['/exports', '/execution/pause']); expect(f.request.mock.calls.filter(([path]) => path.startsWith('/exports/requests/'))).toHaveLength(0); expect(f.download).not.toHaveBeenCalled();
  });

  it.each([false, true])('G09: real control UNKNOWN survives current-state read and separately reviewed control; older loss=%s', async priorLoss => {
    const f = fixture(); render(<App api={f.api}/>); await ready(); if (priorLoss) await olderWarning(f);
    f.state.pause = new ApiError('UNKNOWN', true); reviewExecution(); click('Pause account execution');
    await screen.findByText(/An earlier execution-control request has an UNKNOWN outcome/); priorLoss ? assertWarning() : assertNoWarning();
    f.state.paused = true; f.state.version = 2; click('Refresh account execution state'); await accountReady(true);
    expect(screen.getByText(/A current-state read cannot prove what that request did/)).toBeTruthy();
    reviewExecution(); click('Resume new execution'); await accountReady(); expect(screen.getByText(/An earlier execution-control request has an UNKNOWN outcome/)).toBeTruthy();
    click('Sign out'); await signedOut(); assertWarning(); await signIn(); assertWarning();
    expect(mutationPaths(f)).toEqual([...(priorLoss ? ['/auth/logout', '/auth/login'] : []), '/execution/pause', '/execution/resume', '/auth/logout', '/auth/login']);
    expect(routes(f, '/execution/pause')).toEqual([['/execution/pause', { expected_version: 1 }]]);
    expect(routes(f, '/execution/resume')).toEqual([['/execution/resume', { expected_version: 2 }]]);
  });
  describe.each(['ordinary', 'execution'] as const)('G10: real %s identity path', path => {
    it.each([false, true])('clean confirmation preserves older warning=%s and has no implicit action', async priorLoss => {
      const f = fixture(); render(<App api={f.api}/>); await ready(); if (priorLoss) await olderWarning(f);
      if (path === 'ordinary') requestOrdinaryIdentity(); else requestControlIdentity();
      await screen.findByText(path === 'ordinary' ? 'Identity confirmed. Review the current snapshot before deciding.' : 'Identity confirmed. Refresh account execution state, review the impact, then choose a separate action. Nothing was paused or resumed automatically.');
      if (path === 'execution') click('Refresh account execution state'); await accountReady();
      priorLoss ? assertWarning() : assertNoWarning(); expect(button('Start new conversation').disabled).toBe(false);
      expect(screen.queryByDisplayValue(privatePassword)).toBeNull();
      expect(mutationPaths(f)).toEqual([...(priorLoss ? ['/auth/logout', '/auth/login'] : []), '/auth/reauthenticate']);
    });
    it.each(['timeout', 'malformed'] as const)('captures settled %s ambiguity on idle logout, then survives login and real automatic selection', async failure => {
      const f = fixture(); f.state.reauth = failure === 'timeout' ? new ApiError('UNKNOWN', true) : { csrf_token: privateCsrf };
      render(<App api={f.api}/>); await ready();
      if (path === 'ordinary') requestOrdinaryIdentity(); else requestControlIdentity();
      await screen.findByText('Identity confirmation outcome is UNKNOWN. A status read cannot restore or verify the session credentials.');
      // Crucial: let the real finally release execution_locked before logout.
      // The execution path has no ordinary uncertainty or control UNKNOWN, so
      // only clear_private's identity_unknown_ref capture can retain this loss.
      await waitFor(() => expect(button('Sign out').disabled).toBe(false));
      expect(screen.queryByText(/Session safety boundary in progress/)).toBeNull(); assertNoWarning();
      click('Refresh account execution state'); await accountReady();
      expect(button('Confirm identity for execution controls').disabled).toBe(true); expect(button('Pause account execution').disabled).toBe(true);
      expect(button('Start new conversation').disabled).toBe(true); expect(button('Send mock message').disabled).toBe(true);
      if (path === 'execution') expect(screen.queryByText('Writes paused')).toBeNull();
      expect(screen.queryByText(/An earlier execution-control request has an UNKNOWN outcome/)).toBeNull();
      click('Sign out'); await signedOut(); assertWarning();
      const list = deferred(); f.state.listRead = list.promise; enterLogin(); await accountReady(); assertWarning();
      f.state.listRead = null; await act(async () => list.resolve(f.conversations())); await canonicalReady(); assertWarning();
      expect(button('Start new conversation').disabled).toBe(false);
      expect(mutations(f)).toEqual([
        ['/auth/reauthenticate', { password: privatePassword }], ['/auth/logout', {}], ['/auth/login', { login_name: privateOwner, password: privatePassword }],
      ]);
      expect(f.bootstrap).toHaveBeenCalledTimes(2); expect(f.download).not.toHaveBeenCalled();
    });
  });

  it.each(['success', 'error', '401'] as const)('G11/G12: retained App pagehide captures held main work and ignores its late %s after fresh login', async outcome => {
    const f = fixture(); const pending = deferred(); f.state.message = pending.promise; render(<App api={f.api}/>); await ready();
    change('Message', privateText); click('Send mock message'); expect(routes(f, `/conversations/${cid}/messages`).filter(([, body]) => body !== undefined)).toHaveLength(1);
    fireEvent(window, new Event('pagehide')); await signedOut(); assertWarning(); expect(screen.queryByLabelText('Message')).toBeNull();
    await signIn(); assertWarning(); const before = mutationPaths(f);
    await act(async () => {
      if (outcome === 'success') pending.resolve({ text: 'Synthetic retired response body', request_id: requestId });
      else pending.reject(outcome === '401' ? new ApiError('UNAUTHENTICATED', false, 401) : new ApiError('UNKNOWN', true));
    });
    await ready(); assertWarning(); expect(screen.queryByText('Synthetic retired response body')).toBeNull();
    expect(screen.queryByText('Writes paused')).toBeNull(); expect((screen.getByLabelText('Message') as HTMLTextAreaElement).value).toBe('');
    expect(mutationPaths(f)).toEqual(before); expect(before).toEqual([`/conversations/${cid}/messages`, '/auth/login']);
    expect(button('Start new conversation').disabled).toBe(false); expect(routes(f, '/auth/logout')).toHaveLength(0);
  });
  it('G11: full App teardown/remount loses the in-memory display only, without reading or writing persistence', async () => {
    const f = fixture(); const first = render(<App api={f.api}/>); await ready(); await olderWarning(f); first.unmount();
    const fresh = fixture(); render(<App api={fresh.api}/>); await ready(); assertNoWarning();
    // Absence on a new mount is a lifecycle limit, never a server-resolution claim.
    expect(screen.queryByText(/earlier request (?:was resolved|was cancelled|failed)/i)).toBeNull();
    expect(mutationPaths(f)).toEqual(['/auth/logout', '/auth/login']); expect(mutationPaths(fresh)).toEqual([]);
    expect(storageRead).not.toHaveBeenCalled(); expect(storageWrite).not.toHaveBeenCalled();
  });
  it('G12/G13: exact content-free warning has no dismiss action; reading, Escape, refresh, selection and clean identity do not dismiss it', async () => {
    const f = fixture(); render(<App api={f.api}/>); await ready(); await olderWarning(f);
    const warning = assertWarning(); fireEvent.focus(warning); fireEvent.click(warning); fireEvent.keyDown(warning, { key: 'Escape' });
    expect(within(warning).queryByRole('button', { name: /dismiss|acknowledge|hide|resolve|reconcile/i })).toBeNull(); assertWarning();
    click('Refresh conversations'); click('Refresh server state'); await accountReady(); assertWarning();
    click('Other canonical conversation'); await waitFor(() => expect(button('Other canonical conversation').getAttribute('aria-current')).toBe('true')); assertWarning();
    requestOrdinaryIdentity(); await screen.findByText('Identity confirmed. Review the current snapshot before deciding.'); await accountReady(); assertWarning();
    expect(mutationPaths(f)).toEqual(['/auth/logout', '/auth/login', '/auth/reauthenticate']); expect(f.download).not.toHaveBeenCalled();
  });
});
