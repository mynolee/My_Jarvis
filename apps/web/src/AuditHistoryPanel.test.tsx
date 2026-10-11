import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { AuditHistoryPanel } from './AuditHistoryPanel';
import { type AuditEvent, type AuditPage, type AuditScope } from './auditHistory';

const id = (n: number) => `${String(n).padStart(8, '0')}-aaaa-4aaa-8aaa-aaaaaaaaaaaa`;
const cursor = (n = 1) => `cursor${n}.${'a'.repeat(43)}`;
const event = (n = 1): AuditEvent => ({ id: id(n), event_type: 'RUN_ACCEPTED', happened_at: '2026-10-10T18:00:00.000001Z', resource_kind: 'RUN', resource_id: id(999), run: { id: id(999), current_status: 'UNKNOWN' } });
const page = (items: AuditEvent[] = [event()], scope: AuditScope = 'ALL', next_cursor: string | null = null): AuditPage => ({ checked_at: '2026-10-10T18:05:00Z', scope, items, next_cursor });
const full = (start = 100, next: string | null = cursor(), scope: AuditScope = 'ALL') => page(Array.from({ length: 20 }, (_, i) => event(start - i)), scope, next);
const deferred = () => { let resolve!: (v: unknown) => void, reject!: (e: unknown) => void; const promise = new Promise((r, j) => { resolve = r; reject = j; }); return { promise, resolve, reject }; };
const refresh = () => fireEvent.click(screen.getByRole('button', { name: 'Refresh execution history' }));
const older = () => fireEvent.click(screen.getByRole('button', { name: 'Load older events' }));
const changeScope = (scope: AuditScope) => fireEvent.click(screen.getByRole('button', { name: scope === 'ALL' ? 'All owner events' : 'Run events' }));
const rows = () => within(screen.getByRole('list', { name: 'Audit events' })).queryAllByRole('listitem');
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('read-only execution history', () => {
  it('shows explicit event/resource and current run metadata with KST rollover and preserved UTC attributes', async () => {
    const api = new Api(), request = vi.spyOn(api, 'request').mockResolvedValue(page());
    const { container } = render(<AuditHistoryPanel api={api} sessionKey={1} selectionDisabled onUnauthorized={vi.fn()} />);
    await screen.findByText('Event: RUN_ACCEPTED');
    expect(screen.getByText('한국시간(KST)', { exact: false })).toBeTruthy();
    expect(screen.getByText('11 Oct 2026, 03:00:00 KST')).toBeTruthy();
    expect(container.querySelector(`time[datetime="${event().happened_at}"]`)).toBeTruthy();
    expect(screen.getByText('Resource')).toBeTruthy(); expect(screen.getByText('Run', { exact: true })).toBeTruthy();
    expect(screen.getByText('Current run state')).toBeTruthy(); expect(screen.getByText('Run state checked')).toBeTruthy();
    expect(screen.getByText('UNKNOWN: the run outcome is not confirmed.')).toBeTruthy();
    expect(screen.getByText(/do not establish an execution mode/)).toBeTruthy();
    expect(container.querySelector('a,form,input,textarea,img,iframe,script')).toBeNull();
    expect(request).toHaveBeenCalledExactlyOnceWith('/audit?scope=ALL&limit=20');
    expect(screen.queryByRole('button', { name: /approve|retry|reconcile|cancel|send|select/i })).toBeNull();
  });
  it('labels simulated events and does not call SUCCEEDED real verification', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValue(page([{ ...event(), event_type: 'MOCK_RECONCILED', run: { id: id(999), current_status: 'SUCCEEDED' } }]));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />);
    await screen.findByText('Event: MOCK_RECONCILED');
    expect(screen.getByText('Simulated mock event; no real external execution is established.')).toBeTruthy();
    expect(screen.getByText('SUCCEEDED is a server state, not verification of a real external action.')).toBeTruthy();
  });
  it('does not fabricate current state for a missing historical run', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValue(page([{ ...event(), run: null }]));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />);
    await screen.findByText('Current run state is unavailable.');
    expect(screen.queryByText('Current run state', { exact: true })).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
  });
  it('distinguishes intent and preparation resources and sanitized unknown events', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValue(page([
      { ...event(3), event_type: 'TASK_INTENT_ISSUED', resource_kind: 'TASK_INTENT', run: null },
      { ...event(2), event_type: 'MEMORY_DELETE_PREPARED', resource_kind: 'MEMORY_DELETE_PREPARATION', run: null },
      { ...event(1), event_type: 'OTHER', resource_kind: 'OTHER', resource_id: null, run: null },
    ]));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />);
    await screen.findByText('Task intent'); expect(screen.getByText('Memory deletion preparation')).toBeTruthy();
    expect(screen.getByText('Unrecognized event metadata is withheld.')).toBeTruthy();
  });
  it('shows verified empty reads without suggesting execution succeeded or all clear', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValue(page([]));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />);
    await screen.findByText('No matching audit events in this read.');
    expect(screen.queryByRole('alert')).toBeNull(); expect(screen.queryByText(/all clear/i)).toBeNull();
  });
  it('does not turn initial failures into an empty state or expose error details', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockRejectedValue(new ApiError('PRIVATE_PAYLOAD', false, 500, { secret: '<img src="https://example.invalid/secret">' }));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />);
    await screen.findByText('Execution history could not be read. Whether matching events exist is unknown.');
    expect(screen.queryByText(/No matching|No older|PRIVATE_PAYLOAD|example.invalid/)).toBeNull();
  });
  it('rejects unexpected nested content without rendering it or creating URLs', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValue({ ...page(), items: [{ ...event(), payload: '<img src="https://example.invalid/private">' }] });
    const { container } = render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />);
    await screen.findByRole('alert'); expect(screen.queryByText('Event: RUN_ACCEPTED')).toBeNull();
    expect(container.querySelector('img,script,a,iframe')).toBeNull(); expect(screen.queryByText(/example.invalid/)).toBeNull();
  });
  it('retains old rows explicitly stale after refresh failure and suppresses empty/all-finished copy', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValueOnce(page()).mockRejectedValueOnce(new Error('private failure'));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />);
    await screen.findByText('Event: RUN_ACCEPTED'); refresh();
    await screen.findByText(/Previously loaded metadata is stale/); expect(screen.getByText('Event: RUN_ACCEPTED')).toBeTruthy();
    expect(screen.queryByText('No older events in this read.')).toBeNull(); expect(screen.queryByText(/private failure/)).toBeNull();
  });
  it('suppresses a previous empty-state claim when refresh fails', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValueOnce(page([])).mockRejectedValueOnce(new ApiError('CONNECTION_UNAVAILABLE'));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />);
    await screen.findByText('No matching audit events in this read.'); refresh(); await screen.findByRole('alert');
    expect(screen.queryByText('No matching audit events in this read.')).toBeNull();
  });
  it('uses only explicit GET reads for refresh/scope/pagination even under an active navigation lock', async () => {
    const api = new Api(), request = vi.spyOn(api, 'request').mockResolvedValueOnce(full()).mockResolvedValueOnce(page([event(80)])).mockResolvedValueOnce(page([event(300)], 'RUNS')).mockResolvedValueOnce(page([event(400)], 'RUNS'));
    render(<AuditHistoryPanel api={api} sessionKey={1} selectionDisabled onUnauthorized={vi.fn()} />);
    await screen.findByRole('button', { name: 'Load older events' }); older(); await waitFor(() => expect(rows()).toHaveLength(21));
    changeScope('RUNS'); await waitFor(() => expect(rows()).toHaveLength(1)); refresh(); await screen.findByText(id(400));
    expect(request.mock.calls.map(call => call[0])).toEqual(['/audit?scope=ALL&limit=20', `/audit?scope=ALL&limit=20&cursor=${cursor()}`, '/audit?scope=RUNS&limit=20', '/audit?scope=RUNS&limit=20']);
    expect(request.mock.calls.every(call => call.length === 1)).toBe(true);
  });
  it('never writes private history into browser storage', async () => {
    const set = vi.spyOn(Storage.prototype, 'setItem');
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValue(page());
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByText('Event: RUN_ACCEPTED');
    expect(set).not.toHaveBeenCalled();
  });
});

describe('execution history pagination and generation fences', () => {
  it('fences repeated out-of-order refreshes and stale ordinary failures', async () => {
    const api = new Api(), first = deferred(), second = deferred();
    const request = vi.spyOn(api, 'request').mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise).mockResolvedValueOnce(page([event(3)]));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); refresh(); refresh(); await screen.findByText(id(3));
    await act(async () => { first.resolve(page([event(1)])); second.reject(new ApiError('CONNECTION_UNAVAILABLE')); });
    expect(screen.queryByText(id(1))).toBeNull(); expect(screen.getByText(id(3))).toBeTruthy(); expect(screen.queryByRole('alert')).toBeNull(); expect(request).toHaveBeenCalledTimes(3);
  });
  it('clears rows immediately during scope changes and fences repeated toggles', async () => {
    const api = new Api(), firstRun = deferred(), nextAll = deferred();
    vi.spyOn(api, 'request').mockResolvedValueOnce(page([event(1)])).mockReturnValueOnce(firstRun.promise).mockReturnValueOnce(nextAll.promise).mockResolvedValueOnce(page([event(4)], 'RUNS'));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByText(id(1));
    changeScope('RUNS'); expect(screen.queryByText(id(1))).toBeNull(); changeScope('ALL'); changeScope('RUNS'); await screen.findByText(id(4));
    await act(async () => { firstRun.resolve(page([event(2)], 'RUNS')); nextAll.resolve(page([event(3)])); });
    expect(screen.queryByText(id(2))).toBeNull(); expect(screen.queryByText(id(3))).toBeNull(); expect(screen.getByText(id(4))).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Run events' }).getAttribute('aria-pressed')).toBe('true');
  });
  it('does not refetch a repeated same-scope click', async () => {
    const api = new Api(), request = vi.spyOn(api, 'request').mockResolvedValue(page());
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByText('Event: RUN_ACCEPTED'); changeScope('ALL'); changeScope('ALL');
    expect(request).toHaveBeenCalledTimes(1);
  });
  it('discards late pagination when refreshed and prevents repeated load clicks', async () => {
    const api = new Api(), pending = deferred(), request = vi.spyOn(api, 'request').mockResolvedValueOnce(full()).mockReturnValueOnce(pending.promise).mockResolvedValueOnce(page([event(200)]));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByRole('button', { name: 'Load older events' }); older(); older();
    expect(request).toHaveBeenCalledTimes(2); refresh(); await screen.findByText(id(200));
    await act(async () => pending.resolve(page([event(80)]))); expect(rows()).toHaveLength(1); expect(screen.queryByText(id(80))).toBeNull();
  });
  it('discards late pagination after switching scope', async () => {
    const api = new Api(), pending = deferred(); vi.spyOn(api, 'request').mockResolvedValueOnce(full()).mockReturnValueOnce(pending.promise).mockResolvedValueOnce(page([event(200)], 'RUNS'));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByRole('button', { name: 'Load older events' }); older(); changeScope('RUNS'); await screen.findByText(id(200));
    await act(async () => pending.resolve(page([event(80)]))); expect(rows()).toHaveLength(1); expect(screen.queryByText(id(80))).toBeNull();
  });
  it('rejects duplicate event ids across pages without duplicate rows', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValueOnce(full()).mockResolvedValueOnce(page([event(81), event(80)]));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByRole('button', { name: 'Load older events' }); older(); await screen.findByRole('alert');
    expect(rows()).toHaveLength(20); expect(screen.queryByText(id(80))).toBeNull(); expect((screen.getByRole('button', { name: 'Load older events' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('rejects a repeated cursor and does not retry cursor expiry automatically', async () => {
    const api = new Api(), request = vi.spyOn(api, 'request').mockResolvedValueOnce(full()).mockResolvedValueOnce(full(80, cursor())).mockResolvedValueOnce(full(200)).mockRejectedValueOnce(new ApiError('AUDIT_CURSOR_EXPIRED', false, 410));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByRole('button', { name: 'Load older events' }); older(); await screen.findByRole('alert'); expect(rows()).toHaveLength(20);
    refresh(); await screen.findByText(id(200)); older(); await screen.findByRole('alert'); expect(request).toHaveBeenCalledTimes(4); expect(rows()).toHaveLength(20);
  });
  it('bounds the rendered window to 100 rows while reaching older pages, and refresh starts over', async () => {
    const api = new Api(), request = vi.spyOn(api, 'request');
    for (let i = 0; i < 6; i++) request.mockResolvedValueOnce(full(200 - i * 20, i === 5 ? null : cursor(i + 1)));
    request.mockResolvedValueOnce(page([event(300)]));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByText(id(200));
    for (let i = 1; i < 6; i++) { older(); await screen.findByText(id(200 - i * 20)); expect(rows().length).toBeLessThanOrEqual(100); }
    expect(rows()).toHaveLength(100); expect(screen.queryByText(id(200))).toBeNull(); expect(screen.getByText(id(81))).toBeTruthy();
    expect(screen.getByText(/Newer rows were removed/)).toBeTruthy(); expect(screen.queryByRole('button', { name: 'Load older events' })).toBeNull();
    refresh(); await screen.findByText(id(300)); expect(rows()).toHaveLength(1); expect(screen.queryByText(id(81))).toBeNull(); expect(screen.queryByText(/Newer rows were removed/)).toBeNull();
  });
});

describe('execution history private-session boundaries', () => {
  it('clears content immediately on session rotation and ignores old-session success/401', async () => {
    const api = new Api(), oldSuccess = deferred(), oldFailure = deferred(), next = deferred();
    vi.spyOn(api, 'request').mockResolvedValueOnce(page([event(1)])).mockReturnValueOnce(oldSuccess.promise).mockReturnValueOnce(oldFailure.promise).mockReturnValueOnce(next.promise);
    const onUnauthorized = vi.fn(), clear = vi.spyOn(api, 'clear');
    const { rerender } = render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={onUnauthorized} />); await screen.findByText(id(1)); refresh(); refresh();
    rerender(<AuditHistoryPanel api={api} sessionKey={2} onUnauthorized={onUnauthorized} />); expect(screen.queryByText(id(1))).toBeNull();
    await act(async () => { oldSuccess.resolve(page([event(2)])); oldFailure.reject(new ApiError('UNAUTHENTICATED', false, 401)); });
    expect(screen.queryByText(id(2))).toBeNull(); expect(onUnauthorized).not.toHaveBeenCalled(); expect(clear).not.toHaveBeenCalled();
    await act(async () => next.resolve(page([event(3)]))); await screen.findByText(id(3));
  });
  it('fences API replacement even when session key is unchanged', async () => {
    const oldApi = new Api(), nextApi = new Api(), pending = deferred(), onUnauthorized = vi.fn();
    vi.spyOn(oldApi, 'request').mockResolvedValueOnce(page([event(1)])).mockReturnValueOnce(pending.promise); vi.spyOn(nextApi, 'request').mockResolvedValue(page([event(2)]));
    const clear = vi.spyOn(oldApi, 'clear'), { rerender } = render(<AuditHistoryPanel api={oldApi} sessionKey={1} onUnauthorized={onUnauthorized} />);
    await screen.findByText(id(1)); refresh(); rerender(<AuditHistoryPanel api={nextApi} sessionKey={1} onUnauthorized={onUnauthorized} />); expect(screen.queryByText(id(1))).toBeNull(); await screen.findByText(id(2));
    await act(async () => pending.reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(onUnauthorized).not.toHaveBeenCalled(); expect(clear).not.toHaveBeenCalled();
  });
  it('treats a superseded same-session 401 as authoritative and blocks late repopulation', async () => {
    const api = new Api(), old = deferred(), late = deferred();
    vi.spyOn(api, 'request').mockReturnValueOnce(old.promise).mockResolvedValueOnce(page([event(2)])).mockReturnValueOnce(late.promise);
    const onUnauthorized = vi.fn(), clear = vi.spyOn(api, 'clear');
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={onUnauthorized} />); refresh(); await screen.findByText(id(2)); refresh();
    await act(async () => old.reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(screen.queryByText(id(2))).toBeNull(); expect(onUnauthorized).toHaveBeenCalledTimes(1); expect(clear).toHaveBeenCalledTimes(1);
    await act(async () => late.resolve(page([event(3)]))); expect(screen.queryByText(id(3))).toBeNull();
    expect((screen.getByRole('button', { name: 'Refresh execution history' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('treats an old-scope 401 as authoritative for the same session', async () => {
    const api = new Api(), old = deferred(), onUnauthorized = vi.fn(); vi.spyOn(api, 'request').mockReturnValueOnce(old.promise).mockResolvedValueOnce(page([event(2)], 'RUNS'));
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={onUnauthorized} />); changeScope('RUNS'); await screen.findByText(id(2));
    await act(async () => old.reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(onUnauthorized).toHaveBeenCalledTimes(1); expect(screen.queryByText(id(2))).toBeNull();
  });
  it('notifies unauthorized once even when multiple pending reads reject', async () => {
    const api = new Api(), first = deferred(), second = deferred(), onUnauthorized = vi.fn(); vi.spyOn(api, 'request').mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={onUnauthorized} />); refresh();
    await act(async () => { first.reject(new ApiError('UNAUTHENTICATED', false, 401)); second.reject(new ApiError('UNAUTHENTICATED', false, 401)); });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });
  it('uses the current unauthorized callback without replaying reads', async () => {
    const api = new Api(), pending = deferred(), oldCallback = vi.fn(), nextCallback = vi.fn(), request = vi.spyOn(api, 'request').mockReturnValue(pending.promise);
    const { rerender } = render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={oldCallback} />);
    rerender(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={nextCallback} />);
    await act(async () => pending.reject(new ApiError('UNAUTHENTICATED', false, 401)));
    expect(oldCallback).not.toHaveBeenCalled(); expect(nextCallback).toHaveBeenCalledTimes(1); expect(request).toHaveBeenCalledTimes(1);
  });
  it('clears on close/unmount and starts a new read on reopen, ignoring disposed success/401', async () => {
    const api = new Api(), oldSuccess = deferred(), oldFailure = deferred(), onUnauthorized = vi.fn(), clear = vi.spyOn(api, 'clear');
    const request = vi.spyOn(api, 'request').mockResolvedValueOnce(page([event(1)])).mockReturnValueOnce(oldSuccess.promise).mockReturnValueOnce(oldFailure.promise).mockResolvedValueOnce(page([event(4)]));
    const { unmount } = render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={onUnauthorized} />); await screen.findByText(id(1)); refresh(); refresh(); unmount();
    expect(screen.queryByText(id(1))).toBeNull(); render(<AuditHistoryPanel api={api} sessionKey={1} onUnauthorized={onUnauthorized} />); await screen.findByText(id(4));
    await act(async () => { oldSuccess.resolve(page([event(2)])); oldFailure.reject(new ApiError('UNAUTHENTICATED', false, 401)); });
    expect(screen.queryByText(id(2))).toBeNull(); expect(screen.getByText(id(4))).toBeTruthy(); expect(onUnauthorized).not.toHaveBeenCalled(); expect(clear).not.toHaveBeenCalled(); expect(request).toHaveBeenCalledTimes(4);
  });
});
