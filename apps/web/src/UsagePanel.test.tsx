import { StrictMode, useState } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { UsagePanel } from './UsagePanel';
import { usage_count_keys, type UsagePeriod, type UsageSnapshot } from './usage';

function period(multiplier = 1): UsagePeriod {
  return {
    local_key: '2026-10-11', start_at: '2026-10-10T15:00:00Z', end_at: '2026-10-11T15:00:00Z', request_count: 6 * multiplier,
    counts: { measured: multiplier, pending: multiplier, in_progress: multiplier, unstarted: multiplier, uncertain: multiplier, legacy_unavailable: multiplier },
    measured: { request_count: multiplier, input_tokens: 80 * multiplier, output_tokens: 40 * multiplier, compute_ms: 1234 * multiplier, peak_memory_bytes: 4096 * multiplier, external_cost_usd_micro: null },
    conservative_reserved: { request_count: 5 * multiplier, max_compute_ms: 300000 * multiplier, max_output_tokens: 2560 * multiplier },
  };
}
function snapshot(inputTokens = 80): UsageSnapshot {
  const day = period(), month = period(2); day.measured.input_tokens = inputTokens; month.measured.output_tokens = 100;
  return { checked_at: '2026-10-10T18:00:00.000001Z', timezone: 'Asia/Seoul', attribution: 'RESERVATION_CREATED_AT', quota_timezone: 'UTC', day, month: { ...month, local_key: '2026-10', start_at: '2026-09-30T15:00:00Z', end_at: '2026-10-31T15:00:00Z' } };
}
function emptySnapshot(): UsageSnapshot {
  const value = snapshot();
  for (const p of [value.day, value.month]) {
    p.request_count = 0; for (const key of usage_count_keys) p.counts[key] = 0;
    p.measured = { request_count: 0, input_tokens: 0, output_tokens: 0, compute_ms: 0, peak_memory_bytes: null, external_cost_usd_micro: null };
    p.conservative_reserved = { request_count: 0, max_compute_ms: 0, max_output_tokens: 0 };
  }
  return value;
}
const deferred = () => { let resolve!: (v: unknown) => void, reject!: (e: unknown) => void; const promise = new Promise((r, j) => { resolve = r; reject = j; }); return { promise, resolve, reject }; };
const refresh = () => fireEvent.click(screen.getByRole('button', { name: '사용량 새로고침' }));
const today = () => within(screen.getByRole('region', { name: '오늘 사용량' }));
const amount = (value: number) => `${value.toLocaleString('ko-KR')}토큰`;
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });

describe('owner read-only usage interface', () => {
  it('shows exact periods, KST check, disjoint counts, measured totals/MAX and separate reservations in Korean', async () => {
    const api = new Api(), request = vi.spyOn(api, 'request').mockResolvedValue(snapshot());
    const { container } = render(<UsagePanel api={api} sessionKey={1} selectionDisabled onUnauthorized={vi.fn()} />);
    await screen.findByRole('region', { name: '오늘 사용량' });
    expect(today().getByRole('heading', { name: '오늘 사용량 · 2026-10-11' })).toBeTruthy();
    expect(screen.getByText(/기간 집계 시간대: Asia\/Seoul \(프로필 설정\)/)).toBeTruthy();
    expect(screen.getByText(/할당량 기준 시간대: UTC/)).toBeTruthy();
    expect(screen.getByText(/RESERVATION_CREATED_AT/)).toBeTruthy();
    const checked = container.querySelector('time[datetime="2026-10-10T18:00:00.000001Z"]');
    expect(checked?.textContent).toContain('2026. 10. 11.'); expect(checked?.textContent).toContain('03:00:00 KST');
    expect(today().getByText('기간 시작 (포함, 한국시간)')).toBeTruthy(); expect(today().getByText('기간 종료 (제외, 한국시간)')).toBeTruthy();
    expect(today().getByText('2026. 10. 11. 00:00:00 KST')).toBeTruthy(); expect(today().getByText('2026. 10. 12. 00:00:00 KST')).toBeTruthy();
    expect(container.textContent).not.toMatch(/2026-\d{2}-\d{2}T\d{2}:\d{2}/);
    expect(container.querySelector('time[datetime="2026-10-10T15:00:00Z"]')).toBeTruthy();
    for (const label of ['계측 기록 있음', '시작 대기', '진행 중', '시작하지 않고 종료', '결과 불확실', '과거 계측 자료 없음']) expect(today().getByText(label)).toBeTruthy();
    const actual = within(screen.getByRole('region', { name: '오늘 사용량 계측된 사용량' }));
    expect(actual.getByText('80토큰')).toBeTruthy(); expect(actual.getByText('40토큰')).toBeTruthy(); expect(actual.getByText('1,234ms')).toBeTruthy(); expect(actual.getByText('4,096바이트')).toBeTruthy();
    expect(actual.getByText('관측 최고 메모리 (최댓값)')).toBeTruthy(); expect(actual.getByText('확인할 수 없음 (비용 자료 미제공)')).toBeTruthy();
    const reserved = within(screen.getByRole('region', { name: '오늘 사용량 보수적 예약 한도' }));
    expect(reserved.getByText('300,000ms')).toBeTruthy(); expect(reserved.getByText('2,560토큰')).toBeTruthy();
    expect(reserved.getByText(/실제 사용량과 합산하지 않으며, 남은 할당량을 뜻하지 않습니다/)).toBeTruthy();
    expect(screen.getByText(/계측 자료가 없는 과거 요청/)).toBeTruthy();
    expect(container.querySelector('a,img,script,iframe,input,textarea,select')).toBeNull();
    expect(screen.getAllByRole('button').map(button => button.textContent)).toEqual(['사용량 새로고침']);
    refresh(); await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
    expect(request.mock.calls.every(call => call.length === 1 && call[0] === '/usage')).toBe(true);
  });
  it('reads only while explicitly opened, discards close results, and makes a fresh read on reopen', async () => {
    const api = new Api(), pending = deferred(), onUnauthorized = vi.fn();
    const request = vi.spyOn(api, 'request').mockReturnValueOnce(pending.promise).mockResolvedValueOnce(snapshot(99));
    function Host() { const [open, setOpen] = useState(false); return <><button onClick={() => setOpen(value => !value)}>{open ? '사용량 닫기' : '사용량 열기'}</button>{open && <UsagePanel api={api} sessionKey={1} onUnauthorized={onUnauthorized} />}</>; }
    render(<Host />); expect(request).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '사용량 열기' })); expect(request).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: '사용량 닫기' })); expect(screen.queryByRole('region', { name: '사용량 요약' })).toBeNull();
    await act(async () => pending.resolve(snapshot())); expect(screen.queryByText(amount(80))).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '사용량 열기' })); await screen.findByText(amount(99)); expect(request).toHaveBeenCalledTimes(2);
  });
  it('does not call failed reads empty or display exception contents and never retries automatically', async () => {
    const api = new Api(), request = vi.spyOn(api, 'request').mockRejectedValue(new Error('<script>private error</script>'));
    render(<UsagePanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByRole('alert');
    expect(screen.getByText(/기록의 유무와 실제 사용량은 알 수 없습니다/)).toBeTruthy();
    expect(screen.queryByRole('region', { name: '오늘 사용량' })).toBeNull(); expect(screen.queryByText(/예약 요청 기록이 없습니다/)).toBeNull();
    expect(screen.queryByText(/private error/)).toBeNull();
    vi.useFakeTimers(); await act(async () => { vi.advanceTimersByTime(60000); }); expect(request).toHaveBeenCalledTimes(1);
    vi.useRealTimers(); request.mockResolvedValueOnce(snapshot()); refresh(); await screen.findByText(amount(80)); expect(request).toHaveBeenCalledTimes(2);
  });
  it('labels stale previous data after refresh failure and suppresses previous empty claims', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValueOnce(emptySnapshot()).mockRejectedValueOnce(new ApiError('CONNECTION_UNAVAILABLE'));
    render(<UsagePanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findAllByText(/해당 기간의 예약 요청 기록이 없습니다/);
    refresh(); await screen.findByRole('alert');
    expect(screen.getByText(/오래된 정보이며 재검증되지 않았습니다/)).toBeTruthy(); expect(screen.queryByText(/해당 기간의 예약 요청 기록이 없습니다/)).toBeNull();
    expect(screen.getByRole('region', { name: '오늘 사용량' })).toBeTruthy();
  });
  it('rejects malformed initial success and retains only a visibly stale last validated snapshot after malformed refresh', async () => {
    const api = new Api(), invalid = { ...snapshot(90), monetary_total: '$1' };
    const request = vi.spyOn(api, 'request').mockResolvedValueOnce(invalid).mockResolvedValueOnce(snapshot(99)).mockResolvedValueOnce(invalid);
    render(<UsagePanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByRole('alert'); expect(screen.queryByText(amount(90))).toBeNull();
    refresh(); await screen.findByText(amount(99)); refresh(); await screen.findByRole('alert');
    expect(screen.getByText(amount(99))).toBeTruthy(); expect(screen.queryByText(amount(90))).toBeNull(); expect(screen.queryByText('$1')).toBeNull(); expect(request).toHaveBeenCalledTimes(3);
  });
  it('fails closed on fabricated calendar windows without rendering invalid periods or replacing the last checked data', async () => {
    const api = new Api(), invalid = snapshot(90);
    Object.assign(invalid.day, { local_key: '2020-01-01', start_at: '2019-01-01T00:00:00Z', end_at: '2027-01-01T00:00:00Z' });
    Object.assign(invalid.month, { local_key: '2020-01', start_at: '2019-01-01T00:00:00Z', end_at: '2027-01-01T00:00:00Z' });
    vi.spyOn(api, 'request').mockResolvedValueOnce(invalid).mockResolvedValueOnce(snapshot(99)).mockResolvedValueOnce(invalid);
    render(<UsagePanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByRole('alert');
    expect(screen.queryByRole('region', { name: '오늘 사용량' })).toBeNull(); expect(screen.queryByText(/2020-01/)).toBeNull();
    refresh(); await screen.findByText(amount(99)); refresh(); await screen.findByRole('alert');
    expect(screen.getByText(/오래된 정보이며 재검증되지 않았습니다/)).toBeTruthy(); expect(screen.getByText(amount(99))).toBeTruthy();
    expect(screen.queryByText(/2020-01/)).toBeNull(); expect(screen.queryByText(amount(90))).toBeNull();
  });
  it('distinguishes a confirmed empty record set from unknown actual usage and unknown cost', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValue(emptySnapshot());
    render(<UsagePanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findAllByText(/해당 기간의 예약 요청 기록이 없습니다/);
    expect(screen.getAllByText('계측 자료 없음')).toHaveLength(2); expect(screen.getAllByText('확인할 수 없음 (비용 자료 미제공)')).toHaveLength(2);
    expect(screen.getAllByText(/미계측 요청의 실제 사용량이 0이라는 뜻은 아닙니다/)).toHaveLength(2); expect(screen.queryByRole('alert')).toBeNull();
  });
  it('makes unsupported browser timezone fallback visible without silently replacing the saved zone', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValue({ ...snapshot(), timezone: 'Server/New_Zone' });
    render(<UsagePanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByText(/브라우저에서 이 프로필 시간대의 현지 날짜를 변환할 수 없습니다/);
    expect(screen.getByText(/기간 집계 시간대: Server\/New_Zone/)).toBeTruthy(); expect(today().getByText('2026. 10. 11. 00:00:00 KST')).toBeTruthy(); expect(screen.queryByRole('alert')).toBeNull();
  });
  it('displays every timestamp in KST while retaining the different profile accounting date and UTC quota basis', async () => {
    const api = new Api(), value = snapshot(); value.timezone = 'America/New_York';
    Object.assign(value.day, { local_key: '2026-10-10', start_at: '2026-10-10T04:00:00Z', end_at: '2026-10-11T04:00:00Z' });
    Object.assign(value.month, { start_at: '2026-10-01T04:00:00Z', end_at: '2026-11-01T04:00:00Z' });
    vi.spyOn(api, 'request').mockResolvedValue(value);
    const { container } = render(<UsagePanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); await screen.findByRole('region', { name: '오늘 사용량' });
    expect(today().getByRole('heading', { name: '오늘 사용량 · 2026-10-10' })).toBeTruthy();
    expect(screen.getByText(/기간 집계 시간대: America\/New_York/)).toBeTruthy(); expect(screen.getByText(/모든 시각은 한국시간\(KST\)으로 표시합니다/)).toBeTruthy();
    expect(today().getByText('2026. 10. 10. 13:00:00 KST')).toBeTruthy(); expect(today().getByText('2026. 10. 11. 13:00:00 KST')).toBeTruthy();
    expect(screen.getByText('2026. 10. 11. 03:00:00 KST')).toBeTruthy(); expect(screen.getByText(/할당량 기준 시간대: UTC/)).toBeTruthy();
    expect([...container.querySelectorAll('time')].every(time => time.textContent?.endsWith(' KST') && time.dateTime.endsWith('Z'))).toBe(true);
    expect(container.textContent).not.toMatch(/2026-\d{2}-\d{2}T\d{2}:\d{2}/);
  });
});

describe('usage stale-response and private-session fences', () => {
  it('allows repeated explicit refresh but ignores older successes and ordinary failures', async () => {
    const api = new Api(), olderSuccess = deferred(), olderFailure = deferred(), current = deferred();
    const request = vi.spyOn(api, 'request').mockReturnValueOnce(olderSuccess.promise).mockReturnValueOnce(olderFailure.promise).mockReturnValueOnce(current.promise);
    render(<UsagePanel api={api} sessionKey={1} onUnauthorized={vi.fn()} />); refresh(); refresh(); expect(request).toHaveBeenCalledTimes(3);
    await act(async () => current.resolve(snapshot(99))); await screen.findByText(amount(99));
    await act(async () => { olderSuccess.resolve(snapshot(88)); olderFailure.reject(new ApiError('CONNECTION_UNAVAILABLE')); });
    expect(screen.queryByText(amount(88))).toBeNull(); expect(screen.getByText(amount(99))).toBeTruthy(); expect(screen.queryByRole('alert')).toBeNull();
  });
  it('clears private data on session rotation and ignores former-session success/401', async () => {
    const api = new Api(), success = deferred(), failure = deferred(), next = deferred(), onUnauthorized = vi.fn();
    vi.spyOn(api, 'request').mockResolvedValueOnce(snapshot(80)).mockReturnValueOnce(success.promise).mockReturnValueOnce(failure.promise).mockReturnValueOnce(next.promise);
    const clear = vi.spyOn(api, 'clear'), { rerender } = render(<UsagePanel api={api} sessionKey={1} onUnauthorized={onUnauthorized} />);
    await screen.findByText(amount(80)); refresh(); refresh(); rerender(<UsagePanel api={api} sessionKey={2} onUnauthorized={onUnauthorized} />);
    expect(screen.queryByText(amount(80))).toBeNull();
    await act(async () => { success.resolve(snapshot(88)); failure.reject(new ApiError('UNAUTHENTICATED', false, 401)); });
    expect(screen.queryByText(amount(88))).toBeNull(); expect(onUnauthorized).not.toHaveBeenCalled(); expect(clear).not.toHaveBeenCalled();
    await act(async () => next.resolve(snapshot(99))); await screen.findByText(amount(99));
  });
  it('fences API replacement even when the supplied session key remains the same', async () => {
    const api = new Api(), replacement = new Api(), pending = deferred(), onUnauthorized = vi.fn(), clear = vi.spyOn(api, 'clear');
    vi.spyOn(api, 'request').mockResolvedValueOnce(snapshot(80)).mockReturnValueOnce(pending.promise); vi.spyOn(replacement, 'request').mockResolvedValue(snapshot(99));
    const { rerender } = render(<UsagePanel api={api} sessionKey={1} onUnauthorized={onUnauthorized} />); await screen.findByText(amount(80)); refresh();
    rerender(<UsagePanel api={replacement} sessionKey={1} onUnauthorized={onUnauthorized} />); expect(screen.queryByText(amount(80))).toBeNull(); await screen.findByText(amount(99));
    await act(async () => pending.reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(onUnauthorized).not.toHaveBeenCalled(); expect(clear).not.toHaveBeenCalled(); expect(screen.getByText(amount(99))).toBeTruthy();
  });
  it('a superseded same-session 401 clears newer data and blocks late repopulation and further refresh', async () => {
    const api = new Api(), old = deferred(), late = deferred(), onUnauthorized = vi.fn();
    const request = vi.spyOn(api, 'request').mockReturnValueOnce(old.promise).mockResolvedValueOnce(snapshot(99)).mockReturnValueOnce(late.promise), clear = vi.spyOn(api, 'clear');
    render(<UsagePanel api={api} sessionKey={1} onUnauthorized={onUnauthorized} />); refresh(); await screen.findByText(amount(99)); refresh();
    await act(async () => old.reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(screen.queryByText(amount(99))).toBeNull(); expect(onUnauthorized).toHaveBeenCalledTimes(1); expect(clear).toHaveBeenCalledTimes(1);
    await act(async () => late.resolve(snapshot(88))); expect(screen.queryByText(amount(88))).toBeNull();
    expect((screen.getByRole('button', { name: '사용량 새로고침' }) as HTMLButtonElement).disabled).toBe(true); refresh(); expect(request).toHaveBeenCalledTimes(3);
  });
  it('handles multiple unauthorized replies once and uses the latest callback without refetching', async () => {
    const api = new Api(), first = deferred(), second = deferred(), previous = vi.fn(), current = vi.fn(), clear = vi.spyOn(api, 'clear');
    const request = vi.spyOn(api, 'request').mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const { rerender } = render(<UsagePanel api={api} sessionKey={1} onUnauthorized={previous} />); refresh();
    rerender(<UsagePanel api={api} sessionKey={1} onUnauthorized={current} />); expect(request).toHaveBeenCalledTimes(2);
    await act(async () => { first.reject(new ApiError('UNAUTHENTICATED', false, 401)); second.reject(new ApiError('UNAUTHENTICATED', false, 401)); });
    expect(previous).not.toHaveBeenCalled(); expect(current).toHaveBeenCalledTimes(1); expect(clear).toHaveBeenCalledTimes(1);
  });
  it('clears on logout/unmount and does not let old responses affect a reopened panel', async () => {
    const api = new Api(), oldSuccess = deferred(), oldFailure = deferred(), onUnauthorized = vi.fn(), clear = vi.spyOn(api, 'clear');
    vi.spyOn(api, 'request').mockResolvedValueOnce(snapshot(80)).mockReturnValueOnce(oldSuccess.promise).mockReturnValueOnce(oldFailure.promise).mockResolvedValueOnce(snapshot(99));
    const { unmount } = render(<UsagePanel api={api} sessionKey={1} onUnauthorized={onUnauthorized} />); await screen.findByText(amount(80)); refresh(); refresh(); unmount();
    expect(screen.queryByText(amount(80))).toBeNull(); render(<UsagePanel api={api} sessionKey={2} onUnauthorized={onUnauthorized} />); await screen.findByText(amount(99));
    await act(async () => { oldSuccess.resolve(snapshot(88)); oldFailure.reject(new ApiError('UNAUTHENTICATED', false, 401)); });
    expect(screen.queryByText(amount(88))).toBeNull(); expect(screen.getByText(amount(99))).toBeTruthy(); expect(onUnauthorized).not.toHaveBeenCalled(); expect(clear).not.toHaveBeenCalled();
  });
  it('fences a StrictMode disposed mount without treating the replay as another authorized session', async () => {
    const api = new Api(), disposed = deferred(), onUnauthorized = vi.fn(), clear = vi.spyOn(api, 'clear');
    const request = vi.spyOn(api, 'request').mockReturnValueOnce(disposed.promise).mockResolvedValueOnce(snapshot(99));
    render(<StrictMode><UsagePanel api={api} sessionKey={1} onUnauthorized={onUnauthorized} /></StrictMode>); await screen.findByText(amount(99));
    await act(async () => disposed.reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(onUnauthorized).not.toHaveBeenCalled(); expect(clear).not.toHaveBeenCalled(); expect(request).toHaveBeenCalledTimes(2);
  });
});
