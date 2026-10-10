import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { can_decide, decision_body, type Approval } from './domain';
import { read_event } from './events';
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const id = '11111111-1111-4111-8111-111111111111';
const snapshot: Approval = { id, action_id: id, action_version: 1, payload_hash: 'a'.repeat(64), status: 'PENDING', expires_at: '2099-01-01T00:00:00Z', payload: { tool_id: 'calendar.event.create', arguments: { title: 'Synthetic' } } };
describe('frozen approvals', () => {
  it('sends only exact decision/version/hash, never client policy or arguments', () => { expect(decision_body(snapshot, 'APPROVE')).toEqual({ decision: 'APPROVE', expected_action_version: 1, payload_hash: 'a'.repeat(64) }); });
  it('blocks expired and consumed approvals', () => { expect(can_decide({ ...snapshot, expires_at: '2000-01-01T00:00:00Z' })).toBe(false); expect(can_decide({ ...snapshot, status: 'APPROVED' })).toBe(false); });
  it('blocks invalid snapshot hashes', () => { expect(can_decide({ ...snapshot, payload_hash: 'bad' })).toBe(false); });
});
describe('SEC-041 text-only output', () => {
  it('does not create HTML, links, or network-triggering images from untrusted strings', () => { const payload = '<script>window.stolen=true</script><img src="https://example.invalid/steal" onerror="alert(1)">[open](javascript:alert(1))'; const { container } = render(<p>{payload}</p>); expect(screen.getByText(payload)).toBeTruthy(); expect(container.querySelector('script,img,a,iframe')).toBeNull(); });
});
describe('bounded cookie-session requests', () => {
  it('requires CSRF before any mutation', async () => { const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher); await expect(new Api().request('/auth/login', {})).rejects.toMatchObject({ code: 'CSRF_REQUIRED' }); expect(fetcher).not.toHaveBeenCalled(); });
  it('uses cookie credentials, no-store, redirect error and CSRF', async () => { const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { ok: true } }), { headers: { 'content-type': 'application/json' } })); vi.stubGlobal('fetch', fetcher); const api = new Api(); api.set_csrf('test-csrf'); await api.request('/auth/logout', {}); expect(fetcher).toHaveBeenCalledWith('/api/v1/auth/logout', expect.objectContaining({ credentials: 'same-origin', cache: 'no-store', redirect: 'error', headers: expect.objectContaining({ 'X-CSRF-Token': 'test-csrf' }) })); });
  it('does not retry uncertain writes', async () => { const fetcher = vi.fn().mockRejectedValue(new TypeError('network')); vi.stubGlobal('fetch', fetcher); const api = new Api(); api.set_csrf('token'); await expect(api.request('/auth/logout', {})).rejects.toMatchObject({ code: 'UNKNOWN', uncertain: true }); expect(fetcher).toHaveBeenCalledTimes(1); });
  it('rejects path injection before fetch', async () => { await expect(new Api().request('//evil.invalid')).rejects.toBeInstanceOf(ApiError); });
  it('clears in-memory CSRF on logout cleanup', async () => { const api = new Api(); api.set_csrf('token'); api.clear(); await expect(api.request('/auth/logout', {})).rejects.toMatchObject({ code: 'CSRF_REQUIRED' }); });
});
describe('SEC-043/044 event hints', () => {
  const event = { event_id: id, conversation_id: id, run_id: id, sequence: 1, created_at: '2026-10-09T00:00:00Z' };
  it('rejects other conversations, unknown names, replay and oversized messages', () => { expect(read_event(JSON.stringify(event), 'run.status', id, null)).toEqual({ event_id: id, sequence: 1 }); expect(read_event(JSON.stringify(event), 'run.status', '22222222-2222-4222-8222-222222222222', null)).toBeNull(); expect(read_event(JSON.stringify(event), 'execute.command', id, null)).toBeNull(); expect(read_event(JSON.stringify(event), 'run.status', id, { event_id: id, sequence: 1 })).toBeNull(); expect(read_event('x'.repeat(65537), 'run.status', id, null)).toBeNull(); });
});
