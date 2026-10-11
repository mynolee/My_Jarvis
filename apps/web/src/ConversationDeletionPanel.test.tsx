import { readFileSync } from 'node:fs';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { ConversationDeletionPanel, type ConversationDeletionPanelProps } from './ConversationDeletionPanel';

const fixture = (name: string) => JSON.parse(readFileSync(`../../packages/contracts/fixtures/conversation-deletion/${name}.json`, 'utf8'));
const state = fixture('state-eligible'), preparation = fixture('preparation'), committed = fixture('outcome-committed');
const target = state.conversation_id, other = '81000000-0000-4000-8000-000000000002';
const reviewLabel = '선택 대화 삭제 검토', consentLabel = '이 제한을 이해했으며 정확히 이 대화의 영구 삭제를 확인합니다', confirmLabel = '이 대화 영구 삭제', receiptLabel = '원래 삭제 요청 결과 확인';
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
const props = (api: Api): ConversationDeletionPanelProps => ({ api, sessionKey: 1, selectedConversationId: target, onUnauthorized: vi.fn(), onReauthenticate: vi.fn(), onAcquirePending: vi.fn(() => true), onPendingChange: vi.fn(), onContentHiddenChange: vi.fn(), onDeleted: vi.fn(), onOutcomeUnknown: vi.fn() });
function standard(api: Api, override?: (path: string, body?: unknown) => unknown) {
  return vi.spyOn(api, 'request').mockImplementation((async (path: string, body?: unknown) => {
    const value = override?.(path, body); if (value !== undefined) return await value;
    if (path.endsWith('/deletion-state')) return state;
    if (path.endsWith('/delete-preparations')) return preparation;
    if (path.endsWith('/confirm') || path.startsWith('/conversation-deletions/requests/')) return committed;
    throw new Error('Unexpected synthetic route');
  }) as Api['request']);
}
async function review() { click(reviewLabel); await screen.findByRole('dialog', { name: '이 대화의 영구 삭제 검토' }); }
async function confirm() { await review(); fireEvent.click(screen.getByLabelText(consentLabel)); click(confirmLabel); }
const mutations = (request: ReturnType<typeof standard>) => request.mock.calls.filter(call => call[1] !== undefined);
const confirmations = (request: ReturnType<typeof standard>) => request.mock.calls.filter(call => call[0].endsWith('/confirm'));
afterEach(() => { cleanup(); vi.useRealTimers(); });

describe('conversation source deletion owner form (synthetic DOM only)', () => {
  it('opens inertly and requires a selected canonical target', () => {
    const api = new Api(), p = props(api), request = standard(api); const { rerender } = render(<ConversationDeletionPanel {...p} selectedConversationId={null}/>);
    expect(request).not.toHaveBeenCalled(); expect((screen.getByRole('button', { name: reviewLabel }) as HTMLButtonElement).disabled).toBe(true);
    rerender(<ConversationDeletionPanel {...p}/>); expect(request).not.toHaveBeenCalled(); expect(screen.queryByRole('dialog')).toBeNull();
  });
  it('reviews exact target and counts with visible retained-scope limits, unchecked consent and non-destructive focus', async () => {
    const api = new Api(), p = props(api), request = standard(api); render(<ConversationDeletionPanel {...p}/>); await review();
    expect(request.mock.calls.map(call => call[0])).toEqual([`/conversations/${target}/deletion-state`, `/conversations/${target}/delete-preparations`]);
    expect(request.mock.calls[1][1]).toEqual(fixture('prepare-request')); expect(request.mock.calls[1][2]).toMatch(/^[0-9a-f-]{36}$/);
    expect(screen.getByText(`대화 제목: ${preparation.conversation.title}`)).toBeTruthy(); expect(screen.getByText(`대화 ID: ${target}`)).toBeTruthy();
    expect(screen.getByText(/메시지 2개 · 실행 1개 · 응답 복사본 1개/)).toBeTruthy(); expect(screen.getByText(/별도로 저장한 기억, 작업, 초안, 알림/)).toBeTruthy();
    expect(screen.getByText(/내용 유래 키 기반 바인딩/)).toBeTruthy(); expect(screen.getByText(/기존 백업과 데이터베이스 저장 잔여물/)).toBeTruthy(); expect(screen.getByText(/전송 중인 응답을 회수할 수 없습니다/)).toBeTruthy();
    expect((screen.getByLabelText(consentLabel) as HTMLInputElement).checked).toBe(false); expect((screen.getByRole('button', { name: confirmLabel }) as HTMLButtonElement).disabled).toBe(true);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '대화 유지' })); expect(p.onPendingChange).toHaveBeenLastCalledWith(false); expect(p.onContentHiddenChange).not.toHaveBeenCalled();
    const times = [...document.querySelectorAll('time')]; expect(times.every(time => time.textContent?.endsWith('KST'))).toBe(true); expect(times[1].dateTime).toBe(preparation.prepared_at); expect(times[1].textContent).toBe('2026-10-11 02:40:00 KST');
  });
  it('sends one separately keyed exact confirmation only after both explicit actions, clears preview and reports target', async () => {
    const api = new Api(), p = props(api), request = standard(api); render(<ConversationDeletionPanel {...p}/>); await review();
    click(confirmLabel); expect(confirmations(request)).toHaveLength(0); fireEvent.click(screen.getByLabelText(consentLabel)); const button = screen.getByRole('button', { name: confirmLabel }); act(() => { fireEvent.click(button); fireEvent.click(button); });
    await waitFor(() => expect(p.onDeleted).toHaveBeenCalledWith(target)); expect(confirmations(request)).toHaveLength(1); expect(confirmations(request)[0][1]).toEqual(fixture('confirm-request'));
    expect(confirmations(request)[0][2]).not.toBe(mutations(request)[0][2]); expect(screen.queryByRole('dialog')).toBeNull(); expect(screen.queryByText(preparation.conversation.title, { exact: false })).toBeNull();
    expect(p.onContentHiddenChange).toHaveBeenNthCalledWith(1, true); expect(p.onContentHiddenChange).toHaveBeenLastCalledWith(false); expect(p.onPendingChange).toHaveBeenLastCalledWith(false);
  });
  it.each(['keep', 'escape'])('dismisses review by %s without confirming and restores safe focus', async boundary => {
    const api = new Api(), request = standard(api); render(<ConversationDeletionPanel {...props(api)}/>); await review(); fireEvent.click(screen.getByLabelText(consentLabel));
    if (boundary === 'keep') click('대화 유지'); else fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull(); expect(confirmations(request)).toHaveLength(0); expect(document.activeElement).toBe(screen.getByRole('button', { name: reviewLabel }));
    await review(); expect((screen.getByLabelText(consentLabel) as HTMLInputElement).checked).toBe(false);
  });
  it('does not make destructive confirmation the submit action of a surrounding form', async () => {
    const api = new Api(), request = standard(api); const submit = vi.fn(event => event.preventDefault()); render(<form onSubmit={submit}><input aria-label="unrelated"/><ConversationDeletionPanel {...props(api)}/></form>); await review(); fireEvent.click(screen.getByLabelText(consentLabel));
    fireEvent.keyDown(screen.getByLabelText('unrelated'), { key: 'Enter' }); fireEvent.submit(screen.getByLabelText('unrelated').closest('form')!);
    expect(confirmations(request)).toHaveLength(0); expect(screen.getByRole('button', { name: confirmLabel }).getAttribute('type')).toBe('button');
  });
  it.each(['ACTIVE_WORK', 'UNKNOWN_WORK', 'PENDING_APPROVAL', 'UNSUPPORTED_LEGACY_INFERENCE', 'INTEGRITY_UNVERIFIED'])('refuses %s before preparation and never resolves/cancels work automatically', async blocker => {
    const api = new Api(), request = standard(api, path => path.endsWith('/deletion-state') ? { ...state, content_digest: null, eligibility: { status: 'BLOCKED', blockers: [blocker] } } : undefined);
    render(<ConversationDeletionPanel {...props(api)}/>); click(reviewLabel); await screen.findByRole('alert'); expect(request).toHaveBeenCalledOnce(); expect(mutations(request)).toHaveLength(0); expect(screen.queryByRole('dialog')).toBeNull();
    if (blocker === 'UNSUPPORTED_LEGACY_INFERENCE') expect(screen.getByText(/실행 전에 취소되어 기록이 없는 요청도 포함/)).toBeTruthy();
  });
  it('disables duplicate preparation synchronously while allowing Escape to discard an inert delayed preparation', async () => {
    const api = new Api(), p = props(api); let resolve!: (v: unknown) => void; const request = standard(api, path => path.endsWith('/delete-preparations') ? new Promise(r => { resolve = r; }) : undefined);
    render(<ConversationDeletionPanel {...p}/>); click(reviewLabel); click(reviewLabel); await waitFor(() => expect(mutations(request)).toHaveLength(1));
    fireEvent.keyDown(screen.getByRole('region', { name: '대화 원문 삭제' }), { key: 'Escape' }); await act(async () => resolve(preparation));
    expect(screen.queryByRole('dialog')).toBeNull(); expect(confirmations(request)).toHaveLength(0); expect(p.onPendingChange).toHaveBeenLastCalledWith(false);
  });
  it.each(['selection', 'snapshot', 'pause', 'session'])('invalidates checked review on %s boundary without automatic fresh preparation', async boundary => {
    const api = new Api(), p = props(api), request = standard(api); const { rerender } = render(<ConversationDeletionPanel {...p}/>); await review(); fireEvent.click(screen.getByLabelText(consentLabel));
    rerender(<ConversationDeletionPanel {...p} selectedConversationId={boundary === 'selection' ? other : target} snapshotKey={boundary === 'snapshot' ? 1 : 0} writesPaused={boundary === 'pause'} sessionKey={boundary === 'session' ? 2 : 1}/>);
    expect(screen.queryByRole('dialog')).toBeNull(); expect(request).toHaveBeenCalledTimes(2); expect(confirmations(request)).toHaveLength(0);
  });
  it.each(['selection', 'session', 'close', 'pagehide'])('discards a late preparation after %s', async boundary => {
    const api = new Api(), p = props(api); let resolve!: (v: unknown) => void; const request = standard(api, path => path.endsWith('/delete-preparations') ? new Promise(r => { resolve = r; }) : undefined);
    const { rerender, unmount } = render(<ConversationDeletionPanel {...p}/>); click(reviewLabel); await waitFor(() => expect(mutations(request)).toHaveLength(1));
    if (boundary === 'close') unmount(); else if (boundary === 'pagehide') fireEvent(window, new Event('pagehide')); else rerender(<ConversationDeletionPanel {...p} sessionKey={boundary === 'session' ? 2 : 1} selectedConversationId={boundary === 'selection' ? other : target}/>);
    await act(async () => resolve(preparation)); expect(screen.queryByRole('dialog')).toBeNull(); expect(confirmations(request)).toHaveLength(0); expect(p.onDeleted).not.toHaveBeenCalled();
  });
  it('expires the preview by elapsed monotonic time despite an unrelated wall-clock date', async () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2001-01-01T00:00:00Z'));
    const api = new Api(), request = standard(api); render(<ConversationDeletionPanel {...props(api)}/>);
    await act(async () => click(reviewLabel)); expect(screen.getByRole('dialog')).toBeTruthy(); fireEvent.click(screen.getByLabelText(consentLabel));
    await act(async () => vi.advanceTimersByTime(300000)); expect(screen.queryByRole('dialog')).toBeNull(); expect(screen.getByText(/삭제 준비가 만료/)).toBeTruthy(); expect(confirmations(request)).toHaveLength(0);
  });
  it.each([new ApiError('UNKNOWN', true), new Error('synthetic lost response'), { ...preparation, scope_policy: 'UNKNOWN' }])('lost/malformed preparation requires explicit abandonment and never sends confirmation %#', async result => {
    const api = new Api(), p = props(api), request = standard(api, path => { if (path.endsWith('/delete-preparations')) { if (result instanceof Error) throw result; return result; } }); render(<ConversationDeletionPanel {...p}/>); click(reviewLabel); await screen.findByRole('button', { name: '미확인 준비 포기' });
    expect(screen.queryByRole('dialog')).toBeNull(); expect(p.onPendingChange).toHaveBeenLastCalledWith(false); expect(p.onContentHiddenChange).not.toHaveBeenCalled(); expect(p.onOutcomeUnknown).not.toHaveBeenCalled();
    click(reviewLabel); expect(mutations(request)).toHaveLength(1); click('미확인 준비 포기'); expect((screen.getByRole('button', { name: reviewLabel }) as HTMLButtonElement).disabled).toBe(false); expect(confirmations(request)).toHaveLength(0);
  });
  it.each([new ApiError('UNKNOWN', true), new ApiError('SERVER_ERROR', true, 500), new Error('synthetic connection loss'), { ...committed, extra: true }, { ...committed, receipt: { ...committed.receipt, conversation_id: other } }, { ...committed, receipt: { ...committed.receipt, preparation_id: other } }])('uncertain/malformed confirm keeps content hidden and reconciles GET only without a new key %#', async result => {
    const api = new Api(), p = props(api); let settled = false; const request = standard(api, path => {
      if (path.endsWith('/confirm')) { if (result instanceof Error) throw result; return result; }
      if (path.startsWith('/conversation-deletions/requests/')) return settled ? committed : fixture('outcome-not-observed');
    }); render(<ConversationDeletionPanel {...p}/>); await confirm(); await screen.findByText(/^UNKNOWN:/);
    expect(screen.queryByText(preparation.conversation.title, { exact: false })).toBeNull(); expect(p.onPendingChange).toHaveBeenLastCalledWith(true); expect(p.onContentHiddenChange).toHaveBeenLastCalledWith(true);
    click('삭제 본인 확인'); click(reviewLabel); expect(p.onReauthenticate).not.toHaveBeenCalled(); expect(confirmations(request)).toHaveLength(1);
    click(receiptLabel); await screen.findByText(/^NOT_OBSERVED:/); expect(p.onPendingChange).toHaveBeenLastCalledWith(true); expect(p.onDeleted).not.toHaveBeenCalled();
    settled = true; click(receiptLabel); await waitFor(() => expect(p.onDeleted).toHaveBeenCalledWith(target));
    const recoveries = request.mock.calls.filter(call => call[0].startsWith('/conversation-deletions/requests/')); expect(recoveries).toHaveLength(2); expect(recoveries.every(call => call[1] === undefined && call[2] === undefined)).toBe(true); expect(recoveries[0][0]).toBe(`/conversation-deletions/requests/${confirmations(request)[0][2]}`);
    expect(mutations(request)).toHaveLength(2); expect(p.onPendingChange).toHaveBeenLastCalledWith(false); expect(p.onContentHiddenChange).toHaveBeenLastCalledWith(false);
  });
  it('keeps uncertainty after a malformed receipt, selection/pause changes and a late discarded confirm', async () => {
    const api = new Api(), p = props(api); let resolve!: (v: unknown) => void;
    const request = standard(api, path => path.endsWith('/confirm') ? new Promise(r => { resolve = r; }) : path.startsWith('/conversation-deletions/requests/') ? { ...committed, receipt: { ...committed.receipt, deleted_version: 8 } } : undefined);
    const { rerender } = render(<ConversationDeletionPanel {...p}/>); await confirm(); rerender(<ConversationDeletionPanel {...p} selectedConversationId={other} writesPaused selectionDisabled/>); await act(async () => resolve(committed));
    expect(p.onDeleted).not.toHaveBeenCalled(); expect(p.onPendingChange).toHaveBeenLastCalledWith(true); click(receiptLabel); await screen.findByText(/INVALID_DELETION_RESPONSE/);
    expect(confirmations(request)).toHaveLength(1); expect(p.onPendingChange).toHaveBeenLastCalledWith(true); expect(p.onContentHiddenChange).toHaveBeenLastCalledWith(true);
  });
  it.each(['0001-01-01T00:00:00Z', '2026-10-10T17:39:59.999999Z', '2026-10-10T17:45:00Z', '2026-10-10T17:45:00.000001Z'])('keeps direct and reconciled out-of-lifetime receipt %s UNKNOWN without redispatch', async deleted_at => {
    const api = new Api(), p = props(api), invalid = { ...committed, checked_at: '2026-10-11T00:00:00Z', receipt: { ...committed.receipt, deleted_at } };
    const request = standard(api, path => path.endsWith('/confirm') || path.startsWith('/conversation-deletions/requests/') ? invalid : undefined);
    render(<ConversationDeletionPanel {...p}/>); await confirm(); await screen.findByText(/^UNKNOWN:/);
    expect(p.onDeleted).not.toHaveBeenCalled(); expect(p.onPendingChange).toHaveBeenLastCalledWith(true); expect(p.onContentHiddenChange).toHaveBeenLastCalledWith(true); expect(screen.queryByRole('dialog')).toBeNull();
    click(receiptLabel); await screen.findByText(/INVALID_DELETION_RESPONSE/);
    expect(p.onDeleted).not.toHaveBeenCalled(); expect(p.onPendingChange).toHaveBeenLastCalledWith(true); expect(confirmations(request)).toHaveLength(1);
    expect(request.mock.calls.filter(call => call[0].startsWith('/conversation-deletions/requests/')).every(call => call[1] === undefined && call[2] === undefined)).toBe(true);
  });
  it('binds receipt time to the actual displayed preparation even after its private review was purged', async () => {
    const api = new Api(), p = props(api), shifted = { ...preparation, prepared_at: '2026-10-10T18:00:00Z', expires_at: '2026-10-10T18:05:00Z' };
    standard(api, path => path.endsWith('/delete-preparations') ? shifted : undefined);
    render(<ConversationDeletionPanel {...p}/>); await review(); expect(screen.getByText('2026-10-11 03:00:00 KST')).toBeTruthy(); fireEvent.click(screen.getByLabelText(consentLabel)); click(confirmLabel);
    await screen.findByText(/^UNKNOWN:/); expect(p.onDeleted).not.toHaveBeenCalled(); expect(screen.queryByRole('dialog')).toBeNull(); expect(p.onPendingChange).toHaveBeenLastCalledWith(true);
  });
  it('reconciles a commit inside the reviewed lifetime after local and server observation times pass expiry', async () => {
    const api = new Api(), p = props(api), late = { ...committed, checked_at: '2026-10-11T00:00:00Z' };
    const request = standard(api, path => { if (path.endsWith('/confirm')) throw new ApiError('UNKNOWN', true); if (path.startsWith('/conversation-deletions/requests/')) return late; });
    render(<ConversationDeletionPanel {...p}/>); await confirm(); await screen.findByText(/^UNKNOWN:/);
    vi.useFakeTimers(); await act(async () => vi.advanceTimersByTime(300001));
    await act(async () => click(receiptLabel));
    expect(p.onDeleted).toHaveBeenCalledExactlyOnceWith(target); expect(p.onPendingChange).toHaveBeenLastCalledWith(false); expect(p.onContentHiddenChange).toHaveBeenLastCalledWith(false); expect(confirmations(request)).toHaveLength(1);
    expect(screen.queryByRole('dialog')).toBeNull(); expect(screen.queryByRole('button', { name: receiptLabel })).toBeNull();
  });
  it('does not treat Escape, repeated clicks or unmount as server cancellation during confirm', async () => {
    const api = new Api(), p = props(api); let resolve!: (v: unknown) => void; const request = standard(api, path => path.endsWith('/confirm') ? new Promise(r => { resolve = r; }) : undefined);
    const { unmount } = render(<ConversationDeletionPanel {...p}/>); await confirm(); fireEvent.keyDown(screen.getByRole('region', { name: '대화 원문 삭제' }), { key: 'Escape' });
    click(receiptLabel); expect(confirmations(request)).toHaveLength(1); expect(p.onPendingChange).toHaveBeenLastCalledWith(true); unmount(); expect(p.onOutcomeUnknown).toHaveBeenCalledOnce();
    await act(async () => resolve(committed)); expect(p.onDeleted).not.toHaveBeenCalled(); expect(p.onPendingChange).toHaveBeenLastCalledWith(true);
  });
  it('clears old confirmation authority on session rotation without poisoning new callbacks or logging out a new session', async () => {
    const api = new Api(), p = props(api); let reject!: (v: unknown) => void; standard(api, path => path.endsWith('/confirm') ? new Promise((_r, j) => { reject = j; }) : undefined);
    const { rerender } = render(<ConversationDeletionPanel {...p}/>); await confirm(); const next = { ...props(api), sessionKey: 2 };
    rerender(<ConversationDeletionPanel {...next}/>); expect(p.onOutcomeUnknown).toHaveBeenCalledOnce(); expect(next.onOutcomeUnknown).not.toHaveBeenCalled(); expect(next.onPendingChange).not.toHaveBeenCalled(); expect(next.onContentHiddenChange).not.toHaveBeenCalled();
    await act(async () => reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(next.onUnauthorized).not.toHaveBeenCalled(); expect(p.onUnauthorized).not.toHaveBeenCalled(); expect(screen.queryByRole('button', { name: receiptLabel })).toBeNull();
  });
  it('propagates a current-session 401 and discards private review without replay', async () => {
    const api = new Api(), p = props(api), request = standard(api, path => { if (path.endsWith('/confirm')) throw new ApiError('UNAUTHENTICATED', false, 401); }); render(<ConversationDeletionPanel {...p}/>); await confirm(); await waitFor(() => expect(p.onUnauthorized).toHaveBeenCalledOnce());
    expect(p.onOutcomeUnknown).toHaveBeenCalledOnce(); expect(screen.queryByRole('dialog')).toBeNull(); expect(confirmations(request)).toHaveLength(1); expect(p.onDeleted).not.toHaveBeenCalled();
  });
  it('handles conclusive refusal without claiming deletion and releases only its own fences', async () => {
    const api = new Api(), p = props(api), request = standard(api, path => { if (path.endsWith('/confirm')) throw new ApiError('CONVERSATION_CONTENT_CHANGED', false, 409); }); render(<ConversationDeletionPanel {...p}/>); await confirm(); await screen.findByText(/CONVERSATION_CONTENT_CHANGED/);
    expect(p.onDeleted).not.toHaveBeenCalled(); expect(p.onPendingChange).toHaveBeenLastCalledWith(false); expect(p.onContentHiddenChange).toHaveBeenLastCalledWith(false); expect(screen.queryByRole('button', { name: receiptLabel })).toBeNull(); expect(confirmations(request)).toHaveLength(1);
  });
  it('allows explicit metadata-only state reads while writes are paused and credits deleted state without request attribution', async () => {
    const api = new Api(), p = props(api), request = standard(api, path => path.endsWith('/deletion-state') ? fixture('state-deleted') : undefined); render(<ConversationDeletionPanel {...p} writesPaused selectionDisabled/>);
    click('삭제 상태만 확인'); await waitFor(() => expect(p.onDeleted).toHaveBeenCalledWith(target)); expect(request).toHaveBeenCalledOnce(); expect(mutations(request)).toHaveLength(0); expect(p.onAcquirePending).not.toHaveBeenCalled(); expect(screen.getByText(/어느 요청이 처리되었는지를 뜻하지는 않습니다/)).toBeTruthy();
  });
  it('requires an explicit fresh review after reauthentication; reading ACTIVE does not create a preparation', async () => {
    const api = new Api(), p = props(api), request = standard(api); const { rerender } = render(<ConversationDeletionPanel {...p}/>); await review(); click('삭제 본인 확인'); expect(p.onReauthenticate).toHaveBeenCalledOnce(); expect(screen.queryByRole('dialog')).toBeNull();
    rerender(<ConversationDeletionPanel {...p} sessionKey={2}/>); click('삭제 상태만 확인'); await screen.findByText(/현재 삭제되지 않은 대화/); expect(mutations(request)).toHaveLength(1); expect(confirmations(request)).toHaveLength(0);
  });
  it('does not prepare when parent denies the shared write fence', async () => {
    const api = new Api(), p = props(api), request = standard(api); p.onAcquirePending = vi.fn(() => false); render(<ConversationDeletionPanel {...p}/>); click(reviewLabel); await screen.findByText(/삭제 준비를 시작하지 않았습니다/);
    expect(mutations(request)).toHaveLength(0); expect(p.onPendingChange).not.toHaveBeenCalledWith(true);
  });
  it('never writes private content, commitments or pending keys to persistent storage', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'), api = new Api(); standard(api); render(<ConversationDeletionPanel {...props(api)}/>); await confirm(); await screen.findByText(/저장 원문 삭제를 확인했습니다/); expect(storage).not.toHaveBeenCalled();
    expect(window.location.search).toBe(''); expect(window.location.hash).toBe('');
  });
});
