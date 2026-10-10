import { readFileSync } from 'node:fs';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { NotificationsPanel } from './NotificationsPanel';
const cases = JSON.parse(readFileSync('../../packages/contracts/fixtures/schedules-inbox/cases.json', 'utf8')) as Array<{ name: string; instance: any }>;
const sample = (name: string): any => structuredClone(cases.find(c => c.name === name)!.instance);
const item = sample('notification-created'), opened = sample('notification-opened'), emptyPage = { items: [], next_cursor: null };
const props = (api: Api) => ({ api, sessionKey: 1, onUnauthorized: vi.fn(), onPendingChange: vi.fn() });
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
function standard(api: Api, overrides: (path: string, body?: unknown) => unknown = () => undefined) { return vi.spyOn(api, 'request').mockImplementation(async (path, body) => { const result = overrides(path, body); if (result !== undefined) return result; if (path.startsWith('/notifications?')) return { items: [item], next_cursor: null }; if (path === `/notifications/${item.id}`) return item; if (path === `/notifications/${item.id}/open`) return opened; throw new Error(`Unexpected ${path}`); }); }
async function select() { await screen.findByRole('button', { name: `View details: ${item.title}` }); click(`View details: ${item.title}`); await screen.findByRole('region', { name: 'Inbox item details' }); }
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
describe('internal inbox read/open boundaries', () => {
  it('loads and views by GET without marking opened or writing browser storage', async () => {
    const api = new Api(), request = standard(api), local = vi.spyOn(Storage.prototype, 'setItem'); render(<NotificationsPanel {...props(api)}/>); await select();
    expect(screen.getByText(item.text)).toBeTruthy(); expect(screen.getByText('Not explicitly opened')).toBeTruthy(); expect(request.mock.calls.every(c => c[1] === undefined)).toBe(true); expect(local).not.toHaveBeenCalled();
    click('Refresh internal inbox'); await waitFor(() => expect(request.mock.calls.filter(c => c[0].startsWith('/notifications?'))).toHaveLength(2)); expect(request.mock.calls.every(c => c[1] === undefined)).toBe(true);
  });
  it('renders hostile HTML and URLs literally without external links', async () => {
    const api = new Api(), poison = '<img src="https://example.invalid/exfil" onerror="alert(1)"><script>bad()</script> https://example.invalid/';
    standard(api, path => path === `/notifications/${item.id}` ? { ...item, text: poison } : undefined); const { container } = render(<NotificationsPanel {...props(api)}/>); await select();
    expect(screen.getByText(poison)).toBeTruthy(); expect(container.querySelector('img, iframe, script, a')).toBeNull();
  });
  it('opens once explicitly with exact empty body and a synchronous pending lock', async () => {
    const api = new Api(), p = props(api); let resolve!: (v: unknown) => void;
    const request = standard(api, path => path.endsWith('/open') ? new Promise(r => { expect(p.onPendingChange).toHaveBeenLastCalledWith(true); resolve = r; }) : undefined);
    render(<NotificationsPanel {...p}/>); await select(); click('Open this inbox item'); click('Open this inbox item'); expect(request.mock.calls.filter(c => c[0].endsWith('/open'))).toHaveLength(1);
    expect(request.mock.calls.find(c => c[0].endsWith('/open'))!.slice(1)).toEqual([{}]);
    await act(async () => resolve(opened)); await screen.findByText('OPENED recorded in your internal inbox.'); expect(screen.queryByRole('button', { name: 'Open this inbox item' })).toBeNull(); expect(p.onPendingChange).toHaveBeenLastCalledWith(false);
  });
  it.each(['transport', '5xx', 'malformed', 'wrong item', 'changed content'])('freezes an uncertain %s response without auto-retry', async kind => {
    const api = new Api(); const request = standard(api, path => { if (!path.endsWith('/open')) return; if (kind === 'transport') throw new ApiError('UNKNOWN', true); if (kind === '5xx') throw new ApiError('INTERNAL_ERROR', true, 500); if (kind === 'wrong item') return { ...opened, id: '11111111-1111-4111-8111-111111111111' }; if (kind === 'changed content') return { ...opened, text: 'Different' }; return { ...opened, provider: 'unexpected' }; });
    render(<NotificationsPanel {...props(api)}/>); await select(); click('Open this inbox item'); await screen.findByRole('button', { name: 'Check inbox open outcome' });
    expect((screen.getByRole('button', { name: 'Open this inbox item' }) as HTMLButtonElement).disabled).toBe(true); click('Open this inbox item'); expect(request.mock.calls.filter(c => c[0].endsWith('/open'))).toHaveLength(1);
  });
  it('keeps CREATED inconclusive and resolves only a read-only OPENED outcome', async () => {
    const api = new Api(); let committed = false; const request = standard(api, path => { if (path.endsWith('/open')) throw new ApiError('UNKNOWN', true); if (path === `/notifications/${item.id}`) return committed ? opened : item; });
    render(<NotificationsPanel {...props(api)}/>); await select(); click('Open this inbox item'); await screen.findByRole('button', { name: 'Check inbox open outcome' }); click('Check inbox open outcome'); await screen.findByText(/CREATED is inconclusive/);
    committed = true; click('Check inbox open outcome'); await screen.findByText('OPENED recorded in your internal inbox.'); expect(request.mock.calls.filter(c => c[0].endsWith('/open'))).toHaveLength(1); expect(request.mock.calls.filter(c => c[0] === `/notifications/${item.id}`).every(c => c[1] === undefined)).toBe(true);
  });
  it('retains recovery when reconciliation identifies a different item', async () => {
    const api = new Api(); let reads = 0; standard(api, path => { if (path.endsWith('/open')) throw new ApiError('UNKNOWN', true); if (path === `/notifications/${item.id}` && ++reads > 1) return { ...opened, id: '11111111-1111-4111-8111-111111111111' }; });
    render(<NotificationsPanel {...props(api)}/>); await select(); click('Open this inbox item'); await screen.findByRole('button', { name: 'Check inbox open outcome' }); click('Check inbox open outcome'); await screen.findByText('INVALID_REMINDER_RESPONSE'); expect(screen.getByRole('button', { name: 'Check inbox open outcome' })).toBeTruthy();
  });
  it('purges content and ignores a late old-session selection response', async () => {
    const api = new Api(), p = props(api); let resolve!: (v: unknown) => void, newSession = false;
    standard(api, path => path === `/notifications/${item.id}` ? new Promise(r => { resolve = r; }) : newSession && path.startsWith('/notifications?') ? emptyPage : undefined);
    const { rerender } = render(<NotificationsPanel {...p}/>); await screen.findByRole('button', { name: `View details: ${item.title}` }); click(`View details: ${item.title}`); await waitFor(() => expect(resolve).toBeDefined()); newSession = true; rerender(<NotificationsPanel {...p} sessionKey={2}/>); await act(async () => resolve(item));
    expect(screen.queryByText(item.text)).toBeNull(); expect(screen.queryByRole('region', { name: 'Inbox item details' })).toBeNull();
  });
  it('keeps only an item identifier across session rotation during an open', async () => {
    const api = new Api(), p = props(api); let resolve!: (v: unknown) => void, rotated = false;
    const request = standard(api, path => path.endsWith('/open') ? new Promise(r => { resolve = r; }) : rotated && path.startsWith('/notifications?') ? emptyPage : rotated && path === `/notifications/${item.id}` ? opened : undefined);
    const { rerender } = render(<NotificationsPanel {...p}/>); await select(); click('Open this inbox item'); await waitFor(() => expect(resolve).toBeDefined()); rotated = true; rerender(<NotificationsPanel {...p} sessionKey={2}/>); await screen.findByRole('button', { name: 'Check inbox open outcome' }); expect(screen.queryByText(item.text)).toBeNull(); await act(async () => resolve(opened)); expect(screen.queryByText(item.text)).toBeNull();
    click('Check inbox open outcome'); await screen.findByText('OPENED recorded in your internal inbox.'); expect(request.mock.calls.filter(c => c[0].endsWith('/open'))).toHaveLength(1);
  });
  it('clears private state on current-session 401 and ignores a stale-session 401', async () => {
    const api = new Api(), p = props(api); let reject!: (v: unknown) => void;
    standard(api, path => path === `/notifications/${item.id}` ? new Promise((_r, j) => { reject = j; }) : undefined);
    const { rerender } = render(<NotificationsPanel {...p}/>); await screen.findByRole('button', { name: `View details: ${item.title}` }); click(`View details: ${item.title}`); await waitFor(() => expect(reject).toBeDefined()); const oldReject = reject;
    rerender(<NotificationsPanel {...p} sessionKey={2}/>); await act(async () => oldReject(new ApiError('UNAUTHENTICATED', false, 401))); expect(p.onUnauthorized).not.toHaveBeenCalled();
    await screen.findByRole('button', { name: `View details: ${item.title}` }); click(`View details: ${item.title}`); await act(async () => reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(p.onUnauthorized).toHaveBeenCalledOnce(); expect(screen.queryByText(item.title)).toBeNull();
  });
  it('purges an unknown open identifier and content on reconciliation 401', async () => {
    const api = new Api(), p = props(api); let recovering = false;
    standard(api, path => { if (path.endsWith('/open')) { recovering = true; throw new ApiError('UNKNOWN', true); } if (recovering && path === `/notifications/${item.id}`) throw new ApiError('UNAUTHENTICATED', false, 401); });
    render(<NotificationsPanel {...p}/>); await select(); click('Open this inbox item'); await screen.findByRole('button', { name: 'Check inbox open outcome' }); click('Check inbox open outcome'); await waitFor(() => expect(p.onUnauthorized).toHaveBeenCalledOnce()); expect(screen.queryByRole('region', { name: 'Unknown inbox open outcome' })).toBeNull(); expect(screen.queryByText(item.text)).toBeNull();
  });
  it('requires synchronous parent acquisition for both a new open and read-only recovery', async () => {
    const api = new Api(), p = props(api); let allow = true; const acquire = vi.fn(() => allow), request = standard(api, path => { if (path.endsWith('/open')) throw new ApiError('UNKNOWN', true); });
    render(<NotificationsPanel {...p} onAcquirePending={acquire}/>); await select(); allow = false; click('Open this inbox item'); expect(request.mock.calls.some(c => c[0].endsWith('/open'))).toBe(false); allow = true; click('Open this inbox item'); await screen.findByRole('button', { name: 'Check inbox open outcome' }); const before = request.mock.calls.length; allow = false; click('Check inbox open outcome'); expect(request.mock.calls).toHaveLength(before);
  });
  it('honors cross-panel write/selection exclusion and keeps outcome checks available', async () => {
    const api = new Api(), p = props(api), request = standard(api); const { rerender } = render(<NotificationsPanel {...p}/>); await select(); rerender(<NotificationsPanel {...p} writesPaused selectionDisabled/>);
    expect((screen.getByRole('button', { name: 'Open this inbox item' }) as HTMLButtonElement).disabled).toBe(true); expect((screen.getByRole('button', { name: 'Close inbox details' }) as HTMLButtonElement).disabled).toBe(true); click('Open this inbox item'); expect(request.mock.calls.some(c => c[1] !== undefined)).toBe(false);
  });
  it('pages with opaque cursors and never marks list results opened', async () => {
    const api = new Api(), request = standard(api, path => path === '/notifications?limit=20' ? { items: [item], next_cursor: 'opaque-token' } : path.includes('cursor=') ? emptyPage : undefined);
    render(<NotificationsPanel {...props(api)}/>); await screen.findByRole('button', { name: 'Load more inbox items' }); click('Load more inbox items'); await waitFor(() => expect(request).toHaveBeenCalledWith('/notifications?limit=20&cursor=opaque-token')); expect(request.mock.calls.every(c => c[1] === undefined)).toBe(true);
  });
  it('releases the parent pending indicator on disposal without interpreting server completion', async () => {
    const api = new Api(), p = props(api); standard(api); const { unmount } = render(<NotificationsPanel {...p}/>); await select(); unmount(); expect(p.onPendingChange).toHaveBeenLastCalledWith(false);
  });
});
