import { readFileSync } from 'node:fs';
import { useEffect } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api, ApiError } from './api';
import type { Conversation } from './domain';
import type { HistoryPage } from './conversation-history';

// Real App, deletion panel, execution controls, and Today. Only history and API are synthetic.
interface HistoryProps {
  conversationId: string | null; refreshKey: number; selectionDisabled: boolean;
  onSelect: (conversation: Conversation) => void;
  onSnapshot: (id: string, page: HistoryPage) => void;
}
const observed = vi.hoisted(() => ({ mounts: 0, unmounts: 0, snapshots: 0, deleted: false, latest: null as HistoryProps | null }));
const fixture = (name: string) => JSON.parse(readFileSync(`../../packages/contracts/fixtures/conversation-deletion/${name}.json`, 'utf8'));
const state = fixture('state-eligible'), preparation = fixture('preparation'), outcome = fixture('outcome-committed');
const target = state.conversation_id as string, other = '81000000-0000-4000-8000-000000000002';
const stamp = state.checked_at as string;
const runId = '85000000-0000-4000-8000-000000000001', approvalId = '86000000-0000-4000-8000-000000000001';
const targetResult = 'SYNTHETIC_TARGET_RESPONSE_COPY', otherResult = 'SYNTHETIC_OTHER_RESPONSE_COPY', targetBody = 'SYNTHETIC_TARGET_MESSAGE_BODY';
const targetConversation: Conversation = { ...preparation.conversation };
const otherConversation: Conversation = { id: other, title: 'Synthetic other conversation', project_id: null, created_at: preparation.conversation.created_at };
function snapshot(id: string): HistoryPage {
  return { items: [], next_cursor: null, stream_cursor: null, current_run: { id: id === target ? runId : '85000000-0000-4000-8000-000000000002', status: 'SUCCEEDED', version: 3, result: id === target ? targetResult : otherResult, approval_id: id === target ? approvalId : null, job_id: null, execution_mode: 'MOCK' } };
}
function SyntheticHistory(p: HistoryProps) {
  observed.latest = p;
  useEffect(() => { observed.mounts++; return () => { observed.unmounts++; }; }, []);
  // Remounting source history must produce a fresh snapshot just as a real canonical read does.
  useEffect(() => { if (p.conversationId) { observed.snapshots++; p.onSnapshot(p.conversationId, snapshot(p.conversationId)); } }, [p.conversationId, p.refreshKey, p.onSnapshot]);
  return <section aria-label="Synthetic source history">
    {!observed.deleted && <button disabled={p.selectionDisabled} onClick={() => p.onSelect(targetConversation)}>Select synthetic target</button>}
    <button disabled={p.selectionDisabled} onClick={() => p.onSelect(otherConversation)}>Select synthetic other</button>
    <span>History selection: {p.conversationId ?? 'none'}</span>
    {p.conversationId === target && <><p>{targetConversation.title}</p><p>{targetBody}</p></>}
    {p.conversationId === other && <p>{otherConversation.title}</p>}
  </section>;
}
vi.mock('./ConversationHistoryPanel', () => ({ ConversationHistoryPanel: (p: HistoryProps) => <SyntheticHistory {...p}/> }));

function setup(override?: (path: string, body?: unknown) => unknown) {
  const api = new Api(); let authenticated = true;
  vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation((async (path: string, body?: unknown) => {
    const result = override?.(path, body); if (result !== undefined) return await result;
    if (path === '/me') { if (!authenticated) throw new ApiError('UNAUTHENTICATED', false, 401); return {}; }
    if (path === '/execution') return { execution_paused: false, version: 1, checked_at: stamp };
    if (path === '/today') return JSON.parse(readFileSync('../../packages/contracts/fixtures/today/empty.json', 'utf8'));
    if (path === '/local-model/status') return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: false, status: 'DISABLED' };
    if (path === `/approvals/${approvalId}`) return { id: approvalId, action_id: '87000000-0000-4000-8000-000000000001', action_version: 1, payload_hash: 'c'.repeat(64), status: 'CONSUMED', expires_at: '2026-10-10T17:45:00Z', payload: { note: 'SYNTHETIC_TARGET_ACTION_COPY' } };
    if (path === `/conversations/${target}/deletion-state`) return state;
    if (path === `/conversations/${target}/delete-preparations`) return preparation;
    if (path.endsWith('/confirm') || path.startsWith('/conversation-deletions/requests/')) { observed.deleted = true; return outcome; }
    if (path === '/auth/logout') { authenticated = false; return { logged_out: true }; }
    if (path === '/auth/login') { authenticated = true; return { csrf_token: 'synthetic-csrf-only' }; }
    throw new Error(`Unexpected synthetic route: ${path}`);
  }) as Api['request']);
  return { api, request };
}
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name });
const click = (name: string) => fireEvent.click(button(name));
const confirmCalls = (request: ReturnType<typeof setup>['request']) => request.mock.calls.filter(call => call[0].endsWith('/confirm'));
const prepareCalls = (request: ReturnType<typeof setup>['request']) => request.mock.calls.filter(call => call[0].endsWith('/delete-preparations'));
async function open() {
  await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); click('Select synthetic target');
  await screen.findByText(targetResult); await screen.findByLabelText('Frozen approval payload');
  click('대화 원문 삭제 열기');
}
async function review() { click('선택 대화 삭제 검토'); await screen.findByRole('dialog', { name: '이 대화의 영구 삭제 검토' }); }
function confirmReview() { fireEvent.click(screen.getByLabelText('이 제한을 이해했으며 정확히 이 대화의 영구 삭제를 확인합니다')); click('이 대화 영구 삭제'); }
async function login() {
  await screen.findByRole('heading', { name: 'Owner sign in' }); await waitFor(() => expect(button('Sign in').disabled).toBe(false));
  fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'synthetic-owner' } }); fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-test-password' } });
  fireEvent.submit(screen.getByLabelText('Login name').closest('form')!); await screen.findByText('Account state: AVAILABLE FOR NEW WORK');
}
let network = vi.fn();
beforeEach(() => { observed.mounts = 0; observed.unmounts = 0; observed.snapshots = 0; observed.deleted = false; observed.latest = null; network = vi.fn(() => Promise.reject(new Error('No underlying fetch allowed'))); vi.stubGlobal('fetch', network); });
afterEach(() => { cleanup(); expect(network).not.toHaveBeenCalled(); vi.unstubAllGlobals(); });

describe('real App and deletion panel phase integration (synthetic DOM)', () => {
  it('keeps source history mounted throughout inert preparation pending/release and preserves the reviewed snapshot', async () => {
    let resolve!: (value: unknown) => void;
    const { api, request } = setup(path => path.endsWith('/delete-preparations') ? new Promise(r => { resolve = r; }) : undefined);
    render(<App api={api}/>); await open(); const mounts = observed.mounts, snapshots = observed.snapshots; click('선택 대화 삭제 검토');
    await waitFor(() => expect(prepareCalls(request)).toHaveLength(1));
    expect(screen.getByRole('region', { name: 'Synthetic source history' })).toBeTruthy(); expect(screen.getByText(targetBody)).toBeTruthy(); expect(screen.getByText(targetResult)).toBeTruthy();
    expect(button('Start new conversation').disabled).toBe(true); expect(button('Select synthetic other').disabled).toBe(true); expect(button('대화 원문 삭제 닫기').disabled).toBe(true);
    expect(observed.mounts).toBe(mounts); expect(observed.unmounts).toBe(0);
    await act(async () => resolve(preparation)); await screen.findByRole('dialog', { name: '이 대화의 영구 삭제 검토' });
    expect(observed.mounts).toBe(mounts); expect(observed.snapshots).toBe(snapshots); expect(button('Start new conversation').disabled).toBe(false);
    fireEvent.click(screen.getByLabelText('이 제한을 이해했으며 정확히 이 대화의 영구 삭제를 확인합니다'));
    expect(button('이 대화 영구 삭제').disabled).toBe(false); expect(confirmCalls(request)).toHaveLength(0);
  });
  it('hides source only on explicit confirm, keeps UNKNOWN and NOT_OBSERVED fenced, then clears exact target on a matching GET receipt', async () => {
    let confirmReject!: (error: unknown) => void, committed = false;
    const { api, request } = setup(path => {
      if (path.endsWith('/confirm')) return new Promise((_resolve, reject) => { confirmReject = reject; });
      if (path.startsWith('/conversation-deletions/requests/')) { if (committed) { observed.deleted = true; return outcome; } return fixture('outcome-not-observed'); }
    });
    render(<App api={api}/>); await open(); const staleHistory = observed.latest!;
    fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'SYNTHETIC_TARGET_COMPOSER' } }); await review(); confirmReview();
    expect(confirmCalls(request)).toHaveLength(1); expect(screen.queryByRole('region', { name: 'Synthetic source history' })).toBeNull(); expect(screen.queryByText(targetResult)).toBeNull(); expect(screen.queryByLabelText('Frozen approval payload')).toBeNull(); expect(screen.queryByLabelText('Message')).toBeNull();
    expect(button('Start new conversation').disabled).toBe(true); expect(button('대화 원문 삭제 닫기').disabled).toBe(true);
    await act(async () => confirmReject(new ApiError('UNKNOWN', true))); await screen.findByText(/^UNKNOWN: 삭제 결과/);
    // Refresh temporarily changes writesPaused; await the account read and then the republished recovery view.
    click('Refresh server state'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); await waitFor(() => expect(button('Start new conversation').disabled).toBe(true));
    act(() => staleHistory.onSnapshot(target, snapshot(target))); expect(screen.queryByText(targetResult)).toBeNull();
    fireEvent.click(await screen.findByRole('button', { name: '원래 삭제 요청 결과 확인' })); await screen.findByText(/^NOT_OBSERVED:/); expect(button('Start new conversation').disabled).toBe(true); expect(screen.queryByRole('region', { name: 'Synthetic source history' })).toBeNull();
    committed = true; click('원래 삭제 요청 결과 확인'); await screen.findByText('History selection: none'); await screen.findByText('No run selected.');
    expect(screen.queryByText(targetResult)).toBeNull(); expect(screen.queryByText(targetBody)).toBeNull(); expect(screen.queryByLabelText('Frozen approval payload')).toBeNull(); expect((screen.getByLabelText('Message') as HTMLTextAreaElement).value).toBe(''); expect(button('Start new conversation').disabled).toBe(false); expect(button('Select synthetic other').disabled).toBe(false);
    act(() => staleHistory.onSnapshot(target, snapshot(target))); expect(screen.queryByText(targetResult)).toBeNull();
    const receipts = request.mock.calls.filter(call => call[0].startsWith('/conversation-deletions/requests/')); expect(receipts).toHaveLength(2); expect(receipts.every(call => call[1] === undefined && call[2] === undefined)).toBe(true); expect(receipts[0][0]).toBe(`/conversation-deletions/requests/${confirmCalls(request)[0][2]}`);
    expect(prepareCalls(request)).toHaveLength(1); expect(confirmCalls(request)).toHaveLength(1);
  });
  it('remounts canonical history after a conclusive confirm refusal without restoring consent or repeating the request', async () => {
    let reject!: (error: unknown) => void;
    const { api, request } = setup(path => path.endsWith('/confirm') ? new Promise((_r, j) => { reject = j; }) : undefined);
    render(<App api={api}/>); await open(); const mounts = observed.mounts, snapshots = observed.snapshots; await review(); confirmReview(); expect(screen.queryByText(targetResult)).toBeNull();
    await act(async () => reject(new ApiError('CONVERSATION_CONTENT_CHANGED', false, 409))); await screen.findByText(targetResult);
    expect(observed.mounts).toBe(mounts + 1); expect(observed.snapshots).toBeGreaterThan(snapshots); expect(screen.getByText(`History selection: ${target}`)).toBeTruthy(); expect(button('Start new conversation').disabled).toBe(false); expect(screen.queryByRole('dialog')).toBeNull(); expect(screen.queryByRole('button', { name: '원래 삭제 요청 결과 확인' })).toBeNull();
    expect(confirmCalls(request)).toHaveLength(1); await review(); expect((screen.getByLabelText('이 제한을 이해했으며 정확히 이 대화의 영구 삭제를 확인합니다') as HTMLInputElement).checked).toBe(false); expect(confirmCalls(request)).toHaveLength(1);
  });
  it.each(['committed', 'unauthorized'])('does not let an old %s confirm result poison a new login or clear its different selected source', async result => {
    let resolve!: (value: unknown) => void, reject!: (error: unknown) => void;
    const { api, request } = setup(path => path.endsWith('/confirm') ? new Promise((r, j) => { resolve = r; reject = j; }) : undefined);
    render(<App api={api}/>); await open(); const staleHistory = observed.latest!; await review(); confirmReview();
    click('Sign out'); await login(); click('Select synthetic other'); await screen.findByText(otherResult);
    expect(button('Start new conversation').disabled).toBe(false); expect(screen.queryByText(/A conversation deletion request is pending/)).toBeNull();
    await act(async () => { if (result === 'committed') resolve(outcome); else reject(new ApiError('UNAUTHENTICATED', false, 401)); });
    act(() => staleHistory.onSnapshot(target, snapshot(target)));
    expect(screen.getByText('Session active')).toBeTruthy(); expect(screen.getByText(`History selection: ${other}`)).toBeTruthy(); expect(screen.getByText(otherResult)).toBeTruthy(); expect(screen.queryByText(targetResult)).toBeNull(); expect(button('Start new conversation').disabled).toBe(false);
    click('대화 원문 삭제 열기'); expect(button('선택 대화 삭제 검토').disabled).toBe(false); expect(screen.queryByRole('button', { name: '원래 삭제 요청 결과 확인' })).toBeNull(); expect(confirmCalls(request)).toHaveLength(1);
  });
});
