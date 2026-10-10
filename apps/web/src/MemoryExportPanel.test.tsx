import { readFileSync } from 'node:fs';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { MemoryExportPanel, type MemoryExportPanelProps } from './MemoryExportPanel';
import { EXPORT_FILENAME } from './memory-export';

const fixture = (name: string) => JSON.parse(readFileSync(`../../packages/contracts/fixtures/exports/${name}.json`, 'utf8'));
const job = fixture('job-available'), doc = fixture('document-current');
const project = '72000000-0000-4000-8000-000000000001', otherProject = '72000000-0000-4000-8000-000000000002';
const encode = (value: unknown) => new TextEncoder().encode(JSON.stringify(value));
const props = (api: Api): MemoryExportPanelProps => ({ api, sessionKey: 1, selectedProjectId: null, onUnauthorized: vi.fn(), onReauthenticate: vi.fn(), onAcquirePending: vi.fn(() => true), onPendingChange: vi.fn(), onOutcomeUnknown: vi.fn() });
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
const createLabel = '선택 범위 내보내기 작업 생성', downloadLabel = '현재 기억 JSON 다운로드', reconcileLabel = '원래 생성 요청 결과 확인';
const review = () => fireEvent.click(screen.getByLabelText('선택한 범위와 개인정보 포함 가능성을 확인했습니다'));
function standard(api: Api, override?: (path: string, body?: unknown) => unknown) {
  const request = vi.spyOn(api, 'request').mockImplementation((async (path: string, body?: unknown) => {
    const result = override?.(path, body); if (result !== undefined) return await result;
    if (path === '/exports') return body && (body as { scope: string }).scope === 'PROJECT' ? { ...job, scope: 'PROJECT', project_id: (body as { project_id: string }).project_id } : job;
    if (path.startsWith('/exports/requests/')) return { status: 'COMMITTED', job };
    if (path === `/exports/${job.id}`) return job;
    throw new Error(`Unexpected route ${path}`);
  }) as Api['request']);
  const download = vi.spyOn(api, 'download_export').mockImplementation(async () => encode(doc));
  return { request, download };
}
async function createJob() { review(); click(createLabel); await screen.findByRole('region', { name: '내보내기 작업 메타데이터' }); }
let createURL = vi.fn(), revokeURL = vi.fn(), anchorClick = vi.fn();
beforeEach(() => {
  createURL = vi.fn(() => 'blob:synthetic-export'); revokeURL = vi.fn(); anchorClick = vi.fn();
  const OriginalURL = URL;
  vi.stubGlobal('URL', class extends OriginalURL { static createObjectURL = createURL; static revokeObjectURL = revokeURL; });
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { anchorClick(this); });
});
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('explicit memory export panel (synthetic DOM only)', () => {
  it('does nothing on open and explains exact scope, plaintext risks, current snapshot and save uncertainty', () => {
    const api = new Api(), { request, download } = standard(api); render(<MemoryExportPanel {...props(api)}/>);
    expect(request).not.toHaveBeenCalled(); expect(download).not.toHaveBeenCalled(); expect(createURL).not.toHaveBeenCalled();
    expect((screen.getByRole('button', { name: createLabel }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/애플리케이션 비밀정보 열/)).toBeTruthy(); expect(screen.getByText(/개인정보·비밀 문구는 수정 없이/)).toBeTruthy();
    expect(screen.getByText(/이미 생성·전송 중인 다운로드/)).toBeTruthy(); expect(screen.getByText(/실제 파일 저장과 모바일 동작은 검증되지/)).toBeTruthy();
  });
  it('creates exactly the explicitly reviewed scope with one UUID key and never downloads automatically', async () => {
    const api = new Api(), p = props(api), { request, download } = standard(api); render(<MemoryExportPanel {...p}/>); await createJob();
    expect(request).toHaveBeenCalledOnce(); expect(request.mock.calls[0][0]).toBe('/exports'); expect(request.mock.calls[0][1]).toEqual(fixture('create-global'));
    expect(request.mock.calls[0][2]).toMatch(/^[a-f0-9-]{36}$/); expect(download).not.toHaveBeenCalled(); expect(p.onPendingChange).toHaveBeenLastCalledWith(false);
    expect(screen.getByText(/AVAILABLE은 범위 작업의 상태/)).toBeTruthy();
    expect(document.querySelector('time')?.getAttribute('datetime')).toBe(job.created_at); expect(document.querySelector('time')?.textContent).toContain('KST');
  });
  it('exports only the explicitly selected project and clears review/job on scope change', async () => {
    const api = new Api(), p = props(api), { request } = standard(api); render(<MemoryExportPanel {...p} selectedProjectId={project}/>);
    fireEvent.change(screen.getByLabelText('내보내기 범위'), { target: { value: 'PROJECT' } }); await createJob();
    expect(request.mock.calls[0][1]).toEqual({ kind: 'EXPLICIT_MEMORIES', scope: 'PROJECT', project_id: project, format: 'JSON_UTF8' });
    fireEvent.change(screen.getByLabelText('내보내기 범위'), { target: { value: 'GLOBAL' } });
    expect(screen.queryByRole('region', { name: '내보내기 작업 메타데이터' })).toBeNull(); expect((screen.getByRole('button', { name: createLabel }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('locks duplicate submits synchronously while creation is pending', async () => {
    const api = new Api(); let resolve!: (value: unknown) => void; const { request } = standard(api, path => path === '/exports' ? new Promise(r => { resolve = r; }) : undefined);
    render(<MemoryExportPanel {...props(api)}/>); review(); click(createLabel); click(createLabel); expect(request).toHaveBeenCalledOnce(); await act(async () => resolve(job));
  });
  it.each([new ApiError('UNKNOWN', true), new Error('lost response')])('keeps UNKNOWN fenced after uncertain create and NOT_OBSERVED without replay', async (error) => {
    const api = new Api(), p = props(api); let committed = false;
    const { request, download } = standard(api, path => { if (path === '/exports') throw error; if (path.startsWith('/exports/requests/')) return committed ? { status: 'COMMITTED', job } : { status: 'NOT_OBSERVED', job: null }; });
    render(<MemoryExportPanel {...p}/>); review(); click(createLabel); await screen.findByRole('button', { name: reconcileLabel });
    expect(p.onPendingChange).toHaveBeenLastCalledWith(true); expect((screen.getByLabelText('내보내기 범위') as HTMLSelectElement).disabled).toBe(true);
    click(reconcileLabel); await screen.findByText(/실패했다는 증거가 아니므로/); expect(p.onPendingChange).toHaveBeenLastCalledWith(true);
    committed = true; click(reconcileLabel); await screen.findByRole('region', { name: '내보내기 작업 메타데이터' });
    expect(request.mock.calls.filter(c => c[0] === '/exports')).toHaveLength(1); expect(request.mock.calls.filter(c => c[0].startsWith('/exports/requests/')).every(c => c[1] === undefined && c[2] === undefined)).toBe(true);
    expect(download).not.toHaveBeenCalled(); expect(p.onPendingChange).toHaveBeenLastCalledWith(false);
  });
  it('makes malformed create success UNKNOWN and rejects a wrong-scope recovery job', async () => {
    const api = new Api(), p = props(api), { request } = standard(api, path => path === '/exports' ? { ...job, download_url: 'https://evil.invalid' } : path.startsWith('/exports/requests/') ? { status: 'COMMITTED', job: { ...job, scope: 'PROJECT', project_id: project } } : undefined);
    render(<MemoryExportPanel {...p}/>); review(); click(createLabel); await screen.findByRole('button', { name: reconcileLabel }); click(reconcileLabel); await screen.findByText(/INVALID_EXPORT_RESPONSE/);
    expect(p.onPendingChange).toHaveBeenLastCalledWith(true); expect(request.mock.calls.filter(c => c[0] === '/exports')).toHaveLength(1); expect(screen.queryByRole('button', { name: downloadLabel })).toBeNull();
  });
  it('keeps original recovery scope across a forced project change and discards late create result', async () => {
    const api = new Api(), p = props(api); let resolve!: (value: unknown) => void;
    const { request } = standard(api, path => path === '/exports' ? new Promise(r => { resolve = r; }) : path.startsWith('/exports/requests/') ? { status: 'COMMITTED', job: { ...job, scope: 'PROJECT', project_id: project } } : undefined);
    const { rerender } = render(<MemoryExportPanel {...p} selectedProjectId={project}/>); fireEvent.change(screen.getByLabelText('내보내기 범위'), { target: { value: 'PROJECT' } }); review(); click(createLabel);
    rerender(<MemoryExportPanel {...p} selectedProjectId={otherProject}/>); await screen.findByRole('button', { name: reconcileLabel }); await act(async () => resolve({ ...job, scope: 'PROJECT', project_id: project }));
    expect(screen.queryByRole('button', { name: downloadLabel })).toBeNull(); click(reconcileLabel); await screen.findByText(/원래 생성 요청의 처리를 메타데이터로/);
    expect(screen.queryByRole('button', { name: downloadLabel })).toBeNull(); expect(request.mock.calls.filter(c => c[0] === '/exports')).toHaveLength(1); expect(p.onPendingChange).toHaveBeenLastCalledWith(false);
  });
  it('refreshes only metadata and disables download after a failed or expired status read', async () => {
    const api = new Api(); let expires = false; const { request, download } = standard(api, path => { if (path === `/exports/${job.id}`) { if (expires) return fixture('job-expired'); throw new ApiError('CONNECTION_UNAVAILABLE'); } });
    render(<MemoryExportPanel {...props(api)}/>); await createJob(); click('작업 상태만 새로고침'); await screen.findByText(/이전 상태 · 재검증 안 됨/); expect((screen.getByRole('button', { name: downloadLabel }) as HTMLButtonElement).disabled).toBe(true);
    expires = true; click('작업 상태만 새로고침'); await screen.findByText('확인된 상태: EXPIRED'); expect(download).not.toHaveBeenCalled(); expect(request.mock.calls.filter(c => c[0] !== '/exports').every(c => c[1] === undefined)).toBe(true);
  });
  it('allows explicit metadata/download GET while writes are paused without acquiring a mutation lock', async () => {
    const api = new Api(), p = props(api), { download } = standard(api); const { rerender } = render(<MemoryExportPanel {...p}/>); await createJob();
    rerender(<MemoryExportPanel {...p} writesPaused selectionDisabled/>); click('작업 상태만 새로고침'); await screen.findByText(/범위 작업의 상태만 확인/); click(downloadLabel); await waitFor(() => expect(download).toHaveBeenCalledOnce());
    expect(p.onAcquirePending).toHaveBeenCalledOnce(); expect(p.onPendingChange).toHaveBeenLastCalledWith(false);
  });
  it('creates a fixed JSON Blob only after validation, never renders content, and revokes its temporary URL', async () => {
    const api = new Api(), p = props(api), { download } = standard(api);
    const payload = { ...doc, items: [{ ...doc.items[0], value: '<script>private literal</script>' }] }; let raw: Uint8Array | undefined;
    download.mockImplementation(async () => raw = encode(payload)); const { container } = render(<MemoryExportPanel {...p}/>); await createJob(); click(downloadLabel);
    await screen.findByText(/브라우저 다운로드를 시작했습니다/); expect(download).toHaveBeenCalledWith(job.id); expect(createURL).toHaveBeenCalledOnce(); expect(anchorClick).toHaveBeenCalledOnce();
    const anchor = anchorClick.mock.calls[0][0] as HTMLAnchorElement; expect(anchor.download).toBe(EXPORT_FILENAME); expect(anchor.href).toBe('blob:synthetic-export'); expect(anchor.isConnected).toBe(false);
    expect((createURL.mock.calls[0] as unknown as [Blob])[0].type).toBe('application/json;charset=utf-8'); expect(container.textContent).not.toContain('private literal'); expect(container.querySelector('script')).toBeNull();
    expect(raw?.every(value => value === 0)).toBe(true); await waitFor(() => expect(revokeURL).toHaveBeenCalledWith('blob:synthetic-export'));
  });
  it.each([{ ...doc, export_job_id: project }, { ...doc, secret: 'leak' }, { ...doc, scope: 'PROJECT', project_id: project }])('never creates Blob for malformed or foreign document %#', async (payload) => {
    const api = new Api(), { download } = standard(api); download.mockResolvedValue(encode(payload)); render(<MemoryExportPanel {...props(api)}/>); await createJob(); click(downloadLabel); await screen.findByText(/INVALID_EXPORT_RESPONSE/); expect(createURL).not.toHaveBeenCalled(); expect(anchorClick).not.toHaveBeenCalled();
  });
  it('revokes URL and does not claim download initiation when browser activation throws', async () => {
    const api = new Api(); standard(api); anchorClick.mockImplementation(() => { throw new Error('blocked'); }); render(<MemoryExportPanel {...props(api)}/>); await createJob(); click(downloadLabel); await screen.findByText(/DOWNLOAD_NOT_VERIFIED/);
    expect(revokeURL).toHaveBeenCalledOnce(); expect(screen.queryByText(/브라우저 다운로드를 시작했습니다/)).toBeNull(); expect(document.querySelector('a[download]')).toBeNull();
  });
  it('discards a late download on project change and scrubs the raw buffer', async () => {
    const api = new Api(), p = props(api), { download } = standard(api); let resolve!: (value: Uint8Array) => void; download.mockImplementation(() => new Promise(r => { resolve = r; }));
    const { rerender } = render(<MemoryExportPanel {...p} selectedProjectId={project}/>); await createJob(); click(downloadLabel); rerender(<MemoryExportPanel {...p} selectedProjectId={otherProject}/>);
    const raw = encode(doc); await act(async () => resolve(raw)); expect(createURL).not.toHaveBeenCalled(); expect(raw.every(value => value === 0)).toBe(true); expect(screen.queryByRole('button', { name: downloadLabel })).toBeNull();
  });
  it('discards pending download after close, logout/session rotation and pagehide', async () => {
    for (const boundary of ['close', 'rotate', 'pagehide']) {
      const api = new Api(), p = props(api), { download } = standard(api); let resolve!: (value: Uint8Array) => void; download.mockImplementation(() => new Promise(r => { resolve = r; }));
      const { rerender, unmount } = render(<MemoryExportPanel {...p}/>); await createJob(); click(downloadLabel);
      if (boundary === 'close') unmount(); else if (boundary === 'rotate') rerender(<MemoryExportPanel {...p} sessionKey={2}/>); else fireEvent(window, new Event('pagehide'));
      await act(async () => resolve(encode(doc))); expect(createURL).not.toHaveBeenCalled(); unmount();
    }
  });
  it('revokes active temporary URLs on every privacy boundary before its completion timer', async () => {
    for (const boundary of ['close', 'rotate', 'scope', 'project', 'pagehide']) {
      const api = new Api(), p = props(api); standard(api); const { rerender, unmount } = render(<MemoryExportPanel {...p} selectedProjectId={project}/>); await createJob(); vi.useFakeTimers();
      await act(async () => click(downloadLabel)); expect(createURL).toHaveBeenCalled(); const prior = revokeURL.mock.calls.length;
      if (boundary === 'close') unmount(); else if (boundary === 'rotate') rerender(<MemoryExportPanel {...p} sessionKey={2} selectedProjectId={project}/>); else if (boundary === 'scope') fireEvent.change(screen.getByLabelText('내보내기 범위'), { target: { value: 'PROJECT' } }); else if (boundary === 'project') rerender(<MemoryExportPanel {...p} selectedProjectId={otherProject}/>); else fireEvent(window, new Event('pagehide'));
      expect(revokeURL.mock.calls.length).toBe(prior + 1); unmount(); vi.useRealTimers();
    }
  });
  it('requires a separate fresh job after reauthentication and clears private state on rotation', async () => {
    const api = new Api(), p = props(api), { request, download } = standard(api); const { rerender } = render(<MemoryExportPanel {...p}/>); await createJob(); click('내보내기 본인 확인'); expect(p.onReauthenticate).toHaveBeenCalledOnce(); expect(screen.queryByRole('button', { name: downloadLabel })).toBeNull();
    rerender(<MemoryExportPanel {...p} sessionKey={2}/>); expect(request).toHaveBeenCalledOnce(); expect(download).not.toHaveBeenCalled(); expect((screen.getByRole('button', { name: createLabel }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('does not reauthenticate or create new work while the prior outcome is UNKNOWN', async () => {
    const api = new Api(), p = props(api), { request } = standard(api, path => { if (path === '/exports') throw new ApiError('UNKNOWN', true); }); render(<MemoryExportPanel {...p}/>); review(); click(createLabel); await screen.findByRole('button', { name: reconcileLabel });
    click('내보내기 본인 확인'); click(createLabel); expect(p.onReauthenticate).not.toHaveBeenCalled(); expect(request).toHaveBeenCalledOnce();
  });
  it('warns generically when unresolved metadata is discarded at a session boundary', async () => {
    const api = new Api(), p = props(api); standard(api, path => { if (path === '/exports') throw new ApiError('UNKNOWN', true); }); const { rerender } = render(<MemoryExportPanel {...p}/>); review(); click(createLabel); await screen.findByRole('button', { name: reconcileLabel });
    rerender(<MemoryExportPanel {...p} sessionKey={2}/>); expect(p.onOutcomeUnknown).toHaveBeenCalledOnce(); expect(p.onOutcomeUnknown).toHaveBeenCalledWith(); expect(screen.queryByRole('button', { name: reconcileLabel })).toBeNull();
  });
  it('handles same-session superseded 401, but ignores old-session unauthorized results', async () => {
    for (const rotate of [false, true]) {
      const api = new Api(), p = props(api), { download } = standard(api); let reject!: (reason: unknown) => void; download.mockImplementation(() => new Promise((_r, j) => { reject = j; }));
      const { rerender, unmount } = render(<MemoryExportPanel {...p}/>); await createJob(); click(downloadLabel); rerender(<MemoryExportPanel {...p} sessionKey={rotate ? 2 : 1} selectedProjectId={project}/>);
      await act(async () => reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(p.onUnauthorized).toHaveBeenCalledTimes(rotate ? 0 : 1); expect(createURL).not.toHaveBeenCalled(); unmount();
    }
  });
  it('honors a rejected parent acquisition and leaves the reviewed scope unsubmitted', () => {
    const api = new Api(), p = props(api), { request } = standard(api); p.onAcquirePending = vi.fn(() => false); render(<MemoryExportPanel {...p}/>); review(); click(createLabel); expect(request).not.toHaveBeenCalled(); expect(p.onPendingChange).not.toHaveBeenCalledWith(true);
  });
  it('does not use browser persistence for private data or recovery metadata', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'), api = new Api(); standard(api); render(<MemoryExportPanel {...props(api)}/>); await createJob(); click(downloadLabel); await screen.findByText(/브라우저 다운로드를 시작했습니다/); expect(storage).not.toHaveBeenCalled();
  });
});
