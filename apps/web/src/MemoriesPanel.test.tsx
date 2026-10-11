import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { MemoriesPanel } from './MemoriesPanel';
import { read_memory, read_memory_intent, read_memory_key_state, read_memory_page, read_memory_preparation, read_memory_receipt, read_memory_revision, valid_memory_body, valid_memory_fields, valid_memory_identity } from './memories';
const id = '11111111-1111-4111-8111-111111111111', intentId = '22222222-2222-4222-8222-222222222222', projectId = '33333333-3333-4333-8333-333333333333';
const source = { type: 'EXPLICIT_OWNER_FORM', submission_id: intentId, submitted_at: '2026-10-10T00:00:00Z', source_hash: 'a'.repeat(64) };
const memory = { id, scope: 'GLOBAL', project_id: null, key: 'answer.style', kind: 'PREFERENCE', value: 'Concise answers <script>alert(1)</script>', change_reason: 'Owner choice', source, version: 1, generation: 1, confirmed_at: source.submitted_at, created_at: source.submitted_at, updated_at: source.submitted_at };
const page = { items: [memory], next_cursor: null }, emptyPage = { items: [], next_cursor: null };
const absent = { status: 'ABSENT', generation: 0, memory_id: null, version: null };
const active = { status: 'ACTIVE', generation: 1, memory_id: id, version: 1 };
const intent = { id: intentId, tool_id: 'memory.write', operation: 'CREATE', target_id: id, expected_generation: 0, payload_hash: 'b'.repeat(64), expires_at: '2099-10-10T00:00:00Z', status: 'ISSUED' };
const receipt = { status: 'COMMITTED', memory_id: id, version: 1, generation: 1, operation: 'CREATE' };
const preparation = { id: intentId, memory, payload_hash: 'c'.repeat(64), expected_version: 1, expected_generation: 1, expires_at: intent.expires_at, status: 'PREPARED' };
const notObserved = { status: 'NOT_OBSERVED', memory_id: null, version: null, generation: null, operation: null };
const deletedReceipt = { ...receipt, status: 'REDACTED_DELETED', operation: 'DELETE', generation: 1 };
const revision = { memory_id: id, version: 1, value: 'Original value', change_reason: 'Original reason', source, confirmed_at: source.submitted_at };
const props = (api: Api) => ({ api, sessionKey: 1, selectedProjectId: null, onUnauthorized: vi.fn(), onReauthenticate: vi.fn(), onPendingChange: vi.fn() });
function fill(value = 'Short answers') { fireEvent.change(screen.getByLabelText('Memory key'), { target: { value: 'answer.style' } }); fireEvent.change(screen.getByLabelText('Memory value'), { target: { value } }); fireEvent.change(screen.getByLabelText('Change reason'), { target: { value: 'My explicit choice' } }); }
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
async function select() { click('answer.style · PREFERENCE · v1'); await screen.findByRole('button', { name: 'Prepare memory deletion' }); }
function standard(api: Api, overrides: (path: string, body?: unknown) => unknown = () => undefined) { return vi.spyOn(api, 'request').mockImplementation(async (path, body) => { const override = overrides(path, body); if (override !== undefined) return override; if (path.startsWith('/memories?')) return page; if (path === `/memories/${id}` && !body) return memory; if (path === '/memories/key-state') return absent; if (path === '/memory-intents') return intent; if (path === '/memories') return receipt; if (path.endsWith('/delete-preparations')) return preparation; if (path.endsWith('/confirm')) return deletedReceipt; if (path.includes('/revisions?')) return { items: [revision], next_cursor: null }; if (path.startsWith('/memory-mutations/')) return notObserved; throw new Error(`Unexpected ${path}`); }); }
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
describe('explicit memory wire validation', () => {
  it('enforces Unicode scalar, codepoint, UTF-8 byte, key, scope, and body bounds', () => {
    expect(valid_memory_fields({ value: '😀'.repeat(2000), change_reason: '😀'.repeat(300) })).toBe(true);
    for (const value of ['\uD800', '\uDC00', 'x\0', ' ', '\u0085', '\uFEFF', '😀'.repeat(2001)]) expect(valid_memory_fields({ value, change_reason: 'Reason' })).toBe(false);
    expect(valid_memory_fields({ value: 'v', change_reason: 'x'.repeat(301) })).toBe(false);
    expect(valid_memory_identity({ scope: 'GLOBAL', project_id: null, key: 'a.b_c-9' })).toBe(true);
    expect(valid_memory_identity({ scope: 'GLOBAL', project_id: id, key: 'key' })).toBe(false);
    expect(valid_memory_identity({ scope: 'PROJECT', project_id: null, key: 'key' })).toBe(false);
    expect(valid_memory_identity({ scope: 'PROJECT', project_id: id, key: 'KEY' })).toBe(false);
    expect(valid_memory_body({ value: 'x'.repeat(16384) })).toBe(false);
  });
  it('rejects extra fields, unsafe counters, invalid source and inconsistent preparation', () => {
    expect(read_memory(memory).value).toBe(memory.value);
    expect(() => read_memory({ ...memory, confirmed_at: '2026-10-10T00:00:00.1234567Z' })).toThrow();
    expect(() => read_memory({ ...memory, id: id + '\n' })).toThrow();
    expect(() => read_memory({ ...memory, key: memory.key + '\n' })).toThrow();
    expect(() => read_memory_intent({ ...intent, payload_hash: intent.payload_hash + '\n' })).toThrow();
    for (const extra of [{ owner_id: id }, { version: Number.MAX_SAFE_INTEGER + 1 }, { generation: 0 }, { source: { ...source, type: 'CHAT' } }, { value: '\uD800' }]) expect(() => read_memory({ ...memory, ...extra })).toThrow();
    expect(() => read_memory_page({ items: [memory, memory], next_cursor: null }, read_memory)).toThrow();
    expect(() => read_memory_revision({ ...revision, owner_id: id })).toThrow();
    expect(() => read_memory_intent({ ...intent, arguments: {} })).toThrow();
    expect(() => read_memory_preparation({ ...preparation, expected_version: 2 })).toThrow();
    expect(() => read_memory_key_state({ ...absent, generation: 1 })).toThrow();
    expect(() => read_memory_key_state({ ...active, memory_id: null })).toThrow();
    expect(() => read_memory_receipt({ ...receipt, value: 'leak' })).toThrow();
    expect(() => read_memory_receipt({ ...notObserved, memory_id: id })).toThrow();
    expect(() => read_memory_receipt({ ...receipt, operation: 'DELETE' })).toThrow();
  });
});
describe('explicit memory forms and approvals', () => {
  it('renders values as plaintext and scopes reads without implicit global inclusion', async () => {
    const api = new Api(), request = standard(api, path => path.includes('scope=PROJECT') ? emptyPage : undefined);
    const { container } = render(<MemoriesPanel {...props(api)} selectedProjectId={projectId}/>); await screen.findByText(memory.value); expect(container.querySelector('script')).toBeNull();
    fireEvent.change(screen.getByLabelText('Memory scope'), { target: { value: 'PROJECT' } }); await waitFor(() => expect(request.mock.calls.some(c => c[0] === `/memories?scope=PROJECT&limit=20&project_id=${projectId}`)).toBe(true)); await waitFor(() => expect(screen.queryByText(memory.value)).toBeNull());
  });
  it('requires key-state and R2 review before consuming exactly bound arguments', async () => {
    const api = new Api(), request = standard(api); render(<MemoriesPanel {...props(api)}/>); fill(); click('Review memory save'); await screen.findByRole('region', { name: 'Review exact memory save' });
    expect(request.mock.calls.some(c => c[0] === '/memories')).toBe(false); const issue = request.mock.calls.find(c => c[0] === '/memory-intents')!;
    expect(issue[1]).toEqual({ tool_id: 'memory.write', operation: 'CREATE', target_id: null, arguments: { scope: 'GLOBAL', project_id: null, key: 'answer.style', kind: 'PREFERENCE', value: 'Short answers', change_reason: 'My explicit choice', expected_generation: 0, revive_deleted: false } });
    expect(request.mock.calls.find(c => c[0] === '/memories/key-state')![2]).toBeUndefined();
    click('Confirm exact memory save'); await screen.findByText(/Save confirmed/); const save = request.mock.calls.find(c => c[0] === '/memories')!; expect(save[1]).toEqual({ intent_id: intentId, arguments: (issue[1] as { arguments: unknown }).arguments }); expect(save[2]).not.toBe(issue[2]);
  });
  it('preserves drafts on version conflicts and never resends consumed arguments', async () => {
    const api = new Api(), request = standard(api, path => { if (path === '/memories/key-state') return active; if (path === '/memory-intents') throw new ApiError('PRECONDITION_CHANGED', false, 409); });
    render(<MemoriesPanel {...props(api)}/>); await screen.findByText(memory.value); await select(); fireEvent.change(screen.getByLabelText('Memory value'), { target: { value: 'Updated' } }); fireEvent.change(screen.getByLabelText('Change reason'), { target: { value: 'Revision choice' } }); click('Review memory save');
    await screen.findByText(/Your draft is preserved/); expect((screen.getByLabelText('Memory value') as HTMLTextAreaElement).value).toBe('Updated'); expect(request.mock.calls.filter(c => c[0] === '/memory-intents')).toHaveLength(1);
  });
  it('requires explicit fresh-save choice for a tombstone and binds observed generation', async () => {
    const api = new Api(), request = standard(api, path => path === '/memories/key-state' ? { status: 'DELETED', generation: 4, memory_id: null, version: null } : path === '/memory-intents' ? { ...intent, expected_generation: 4 } : undefined);
    render(<MemoriesPanel {...props(api)}/>); fill(); click('Review memory save'); await screen.findByText(/This key was deleted/); expect(request.mock.calls.some(c => c[0] === '/memory-intents')).toBe(false);
    fireEvent.click(screen.getByLabelText(/Save a fresh memory/)); click('Review memory save'); await screen.findByRole('region', { name: 'Review exact memory save' }); expect((request.mock.calls.find(c => c[0] === '/memory-intents')![1] as { arguments: unknown }).arguments).toMatchObject({ expected_generation: 4, revive_deleted: true });
  });
  it('locks duplicate submissions until intent issuance resolves', async () => {
    const api = new Api(); let resolve!: (value: unknown) => void; const request = standard(api, path => path === '/memory-intents' ? new Promise(r => { resolve = r; }) : undefined);
    render(<MemoriesPanel {...props(api)}/>); fill(); click('Review memory save'); await waitFor(() => expect(resolve).toBeDefined()); click('Review memory save'); expect(request.mock.calls.filter(c => c[0] === '/memory-intents')).toHaveLength(1); await act(async () => resolve(intent));
  });
  it('uses receipt-only reconciliation after uncertain save and does not treat NOT_OBSERVED as failure', async () => {
    const api = new Api(); let committed = false; const request = standard(api, path => { if (path === '/memories') throw new ApiError('UNKNOWN', true); if (path.startsWith('/memory-mutations/')) return committed ? receipt : notObserved; });
    render(<MemoriesPanel {...props(api)}/>); fill(); click('Review memory save'); await screen.findByRole('button', { name: 'Confirm exact memory save' }); click('Confirm exact memory save'); await screen.findByRole('button', { name: 'Check memory outcome' });
    expect(screen.queryByRole('button', { name: 'Abandon unconsumed approval' })).toBeNull(); click('Check memory outcome'); await screen.findByText(/does not prove failure/); expect(screen.getByLabelText('Memory value').closest('fieldset')!.disabled).toBe(true);
    committed = true; click('Check memory outcome'); await screen.findByText(/Save confirmed/); expect(request.mock.calls.filter(c => c[0] === '/memories')).toHaveLength(1); expect(request.mock.calls.filter(c => c[0].startsWith('/memory-mutations/')).every(c => c[1] === undefined && c[2] === undefined)).toBe(true);
  });
  it('treats malformed mutation success as uncertain instead of automatic replay', async () => {
    const api = new Api(), request = standard(api, path => path === '/memories' ? { ...receipt, value: 'unexpected' } : undefined); render(<MemoriesPanel {...props(api)}/>); fill(); click('Review memory save'); await screen.findByRole('button', { name: 'Confirm exact memory save' }); click('Confirm exact memory save'); await screen.findByRole('button', { name: 'Check memory outcome' }); expect(request.mock.calls.filter(c => c[0] === '/memories')).toHaveLength(1);
  });
  it('can abandon only an uncertain unconsumed approval', async () => {
    const api = new Api(), request = standard(api, path => { if (path === '/memory-intents') throw new ApiError('UNKNOWN', true); }); render(<MemoriesPanel {...props(api)}/>); fill(); click('Review memory save'); await screen.findByRole('button', { name: 'Abandon unconsumed approval' }); click('Abandon unconsumed approval'); await screen.findByText(/No save or deletion was submitted/); expect(screen.getByLabelText('Memory value').closest('fieldset')!.disabled).toBe(false); expect(request.mock.calls.some(c => c[0] === '/memories')).toBe(false);
  });
  it('requires recent reauth flow, fresh preview, checkbox, and exact R3 confirmation', async () => {
    const api = new Api(), p = props(api), request = standard(api); const { rerender } = render(<MemoriesPanel {...p}/>); await screen.findByText(memory.value); await select(); click('Reauthenticate for deletion'); expect(p.onReauthenticate).toHaveBeenCalledOnce(); expect(request.mock.calls.some(c => c[0].endsWith('/confirm'))).toBe(false);
    rerender(<MemoriesPanel {...p} sessionKey={2}/>); await screen.findByText(memory.value); await select(); click('Prepare memory deletion'); await screen.findByRole('region', { name: 'Confirm memory deletion' }); expect((screen.getByRole('button', { name: 'Delete this memory and revisions' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByLabelText(/I confirm deletion/)); click('Delete this memory and revisions'); await screen.findByText(/Deletion is confirmed/); expect(request.mock.calls.find(c => c[0].endsWith('/confirm'))![1]).toEqual({ payload_hash: preparation.payload_hash, expected_version: 1, expected_generation: 1, confirm: true }); expect(screen.queryByRole('region', { name: 'Confirm memory deletion' })).toBeNull();
  });
  it('purges old draft, revisions and intents when an old write receipt reports deletion', async () => {
    const api = new Api(); let gone = false; const request = standard(api, path => { if (path === '/memories') throw new ApiError('UNKNOWN', true); if (path.startsWith('/memory-mutations/')) { gone = true; return { ...receipt, status: 'REDACTED_DELETED' }; } if (gone && path.startsWith('/memories?')) return emptyPage; }); render(<MemoriesPanel {...props(api)}/>); fill('Secret draft'); click('Review memory save'); await screen.findByRole('button', { name: 'Confirm exact memory save' }); click('Confirm exact memory save'); await screen.findByRole('button', { name: 'Check memory outcome' }); click('Check memory outcome'); await screen.findByText(/Deletion is confirmed/); expect((screen.getByLabelText('Memory value') as HTMLTextAreaElement).value).toBe(''); expect(screen.queryByText(memory.value)).toBeNull(); expect(request.mock.calls.filter(c => c[0] === '/memories')).toHaveLength(1);
  });
  it('clears revisions on deleted-parent reads and never retains stale history', async () => {
    const api = new Api(); let gone = false; standard(api, path => { if (path.includes('/revisions?') && gone) throw new ApiError('NOT_FOUND', false, 404); }); render(<MemoriesPanel {...props(api)}/>); await screen.findByText(memory.value); await select(); click('View active memory revisions'); await screen.findByText('Version 1: Original value'); gone = true; click('View active memory revisions'); await screen.findByText(/Revisions were cleared/); expect(screen.queryByText('Version 1: Original value')).toBeNull(); expect((screen.getByLabelText('Memory value') as HTMLTextAreaElement).value).toBe('');
  });
  it('fences late selection responses after project changes', async () => {
    const api = new Api(); let resolve!: (v: unknown) => void; standard(api, (path, body) => path === `/memories/${id}` && !body ? new Promise(r => { resolve = r; }) : undefined); const p = props(api), { rerender } = render(<MemoriesPanel {...p}/>); await screen.findByText(memory.value); click('answer.style · PREFERENCE · v1'); await waitFor(() => expect(resolve).toBeDefined()); rerender(<MemoriesPanel {...p} selectedProjectId={projectId}/>); await act(async () => resolve(memory)); expect((screen.getByLabelText('Memory value') as HTMLTextAreaElement).value).toBe(''); expect(screen.queryByRole('button', { name: 'Prepare memory deletion' })).toBeNull();
  });
  it('clears private state on 401 and ignores stale sessions', async () => {
    const api = new Api(), p = props(api); standard(api, path => { if (path === '/memory-intents') throw new ApiError('UNAUTHENTICATED', false, 401); }); render(<MemoriesPanel {...p}/>); fill(); click('Review memory save'); await waitFor(() => expect(p.onUnauthorized).toHaveBeenCalledOnce()); expect((screen.getByLabelText('Memory value') as HTMLTextAreaElement).value).toBe(''); expect(screen.queryByText(memory.value)).toBeNull();
  });
  it('clears preview on session rotation and obeys external write pause', async () => {
    const api = new Api(), p = props(api); standard(api); const { rerender } = render(<MemoriesPanel {...p}/>); fill(); click('Review memory save'); await screen.findByRole('region', { name: 'Review exact memory save' }); rerender(<MemoriesPanel {...p} sessionKey={2} writesPaused/>); await waitFor(() => expect(screen.queryByRole('region', { name: 'Review exact memory save' })).toBeNull()); expect(screen.getByLabelText('Memory value').closest('fieldset')!.disabled).toBe(true);
  });
  it('retains only a receipt recovery path when selection changes during an actual save', async () => {
    const api = new Api(); let resolve!: (v: unknown) => void;
    const request = standard(api, path => path === '/memories' ? new Promise(r => { resolve = r; }) : path.startsWith('/memory-mutations/') ? receipt : undefined);
    const p = props(api), { rerender } = render(<MemoriesPanel {...p}/>); fill(); click('Review memory save'); await screen.findByRole('button', { name: 'Confirm exact memory save' }); click('Confirm exact memory save'); await waitFor(() => expect(resolve).toBeDefined());
    rerender(<MemoriesPanel {...p} selectedProjectId={projectId}/>); await screen.findByRole('button', { name: 'Check memory outcome' }); await act(async () => resolve(receipt)); expect(screen.getByRole('button', { name: 'Check memory outcome' })).toBeTruthy(); expect((screen.getByLabelText('Memory value') as HTMLTextAreaElement).value).toBe('');
    click('Check memory outcome'); await screen.findByText(/Save confirmed/); expect(request.mock.calls.filter(c => c[0] === '/memories')).toHaveLength(1);
  });
  it('does not permit reauthentication or a second destructive submit after uncertain deletion', async () => {
    const api = new Api(), p = props(api); const request = standard(api, path => { if (path.endsWith('/confirm')) throw new ApiError('UNKNOWN', true); }); render(<MemoriesPanel {...p}/>); await screen.findByText(memory.value); await select(); click('Prepare memory deletion'); await screen.findByRole('region', { name: 'Confirm memory deletion' }); fireEvent.click(screen.getByLabelText(/I confirm deletion/)); click('Delete this memory and revisions'); await screen.findByRole('button', { name: 'Check memory outcome' });
    expect((screen.getByRole('button', { name: 'Reauthenticate for deletion' }) as HTMLButtonElement).disabled).toBe(true); expect(screen.queryByRole('button', { name: 'Delete this memory and revisions' })).toBeNull(); expect(screen.queryByRole('button', { name: 'Abandon unconsumed approval' })).toBeNull(); expect(request.mock.calls.filter(c => c[0].endsWith('/confirm'))).toHaveLength(1);
  });
  it('clears old values when preparation reports deletion and does not issue a delete', async () => {
    const api = new Api(); const request = standard(api, path => { if (path.endsWith('/delete-preparations')) throw new ApiError('MEMORY_DELETED', false, 410); }); render(<MemoriesPanel {...props(api)}/>); await screen.findByText(memory.value); await select(); click('View active memory revisions'); await screen.findByText('Version 1: Original value'); click('Prepare memory deletion'); await screen.findByText(/This memory was deleted/); expect(screen.queryByText('Version 1: Original value')).toBeNull(); expect((screen.getByLabelText('Memory value') as HTMLTextAreaElement).value).toBe(''); expect(request.mock.calls.some(c => c[0].endsWith('/confirm'))).toBe(false);
  });
  it('refreshes the conflict baseline while preserving the user draft for a fresh review', async () => {
    const api = new Api(); let reads = 0; standard(api, (path, body) => path === `/memories/${id}` && !body ? { ...memory, version: ++reads, value: reads === 1 ? memory.value : 'New concurrent value' } : undefined); render(<MemoriesPanel {...props(api)}/>); await screen.findByText(memory.value); await select(); fireEvent.change(screen.getByLabelText('Memory value'), { target: { value: 'My unsaved draft' } }); click('Refresh selected memory version'); await screen.findByText(/Current saved value: New concurrent value/); expect((screen.getByLabelText('Memory value') as HTMLTextAreaElement).value).toBe('My unsaved draft'); await screen.findByText(/Current version loaded/);
  });
  it('honors same-session 401 even from a superseded selection but ignores old-session 401', async () => {
    const api = new Api(), p = props(api); let reject!: (reason: unknown) => void; standard(api, (path, body) => path === `/memories/${id}` && !body ? new Promise((_r, j) => { reject = j; }) : undefined); const { rerender } = render(<MemoriesPanel {...p}/>); await screen.findByText(memory.value); click('answer.style · PREFERENCE · v1'); await waitFor(() => expect(reject).toBeDefined()); rerender(<MemoriesPanel {...p} selectedProjectId={projectId}/>); await act(async () => reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(p.onUnauthorized).toHaveBeenCalledOnce();
    rerender(<MemoriesPanel {...p} sessionKey={2}/>); await screen.findByText(memory.value); click('answer.style · PREFERENCE · v1'); await waitFor(() => expect(screen.getByRole('button', { name: 'New memory' }).getAttribute('disabled')).not.toBeNull()); const oldReject = reject; rerender(<MemoriesPanel {...p} sessionKey={3}/>); await act(async () => oldReject(new ApiError('UNAUTHENTICATED', false, 401))); expect(p.onUnauthorized).toHaveBeenCalledOnce();
  });

  it('purges a selected old generation when key-state reveals deletion or replacement', async () => {
    const api = new Api(), request = standard(api, path => path === '/memories/key-state' ? { status: 'DELETED', generation: 2, memory_id: null, version: null } : undefined); render(<MemoriesPanel {...props(api)}/>); await screen.findByText(memory.value); await select(); fireEvent.change(screen.getByLabelText('Change reason'), { target: { value: 'Revise old memory' } }); click('Review memory save'); await screen.findByText(/This memory was deleted/); expect((screen.getByLabelText('Memory value') as HTMLTextAreaElement).value).toBe(''); expect(request.mock.calls.some(c => c[0] === '/memory-intents')).toBe(false);
  });

  it('keeps recovery paused for valid-looking receipts from the wrong version or generation', async () => {
    const api = new Api(); standard(api, path => path === '/memories' || path.startsWith('/memory-mutations/') ? { ...receipt, generation: 7 } : undefined); render(<MemoriesPanel {...props(api)}/>); fill(); click('Review memory save'); await screen.findByRole('button', { name: 'Confirm exact memory save' }); click('Confirm exact memory save'); await screen.findByRole('button', { name: 'Check memory outcome' }); click('Check memory outcome'); await screen.findByText('INVALID_MEMORY_RESPONSE'); expect(screen.getByRole('button', { name: 'Check memory outcome' })).toBeTruthy(); expect(screen.getByLabelText('Memory value').closest('fieldset')!.disabled).toBe(true);
  });

  it('purges old content when delete preparation returns deleted-parent 404', async () => {
    const api = new Api(), request = standard(api, path => { if (path.endsWith('/delete-preparations')) throw new ApiError('NOT_FOUND', false, 404); }); render(<MemoriesPanel {...props(api)}/>); await screen.findByText(memory.value); await select(); click('View active memory revisions'); await screen.findByText('Version 1: Original value'); click('Prepare memory deletion'); await screen.findByText(/This memory was deleted/); expect(screen.queryByText('Version 1: Original value')).toBeNull(); expect((screen.getByLabelText('Memory value') as HTMLTextAreaElement).value).toBe(''); expect(request.mock.calls.some(c => c[0].endsWith('/confirm'))).toBe(false);
  });
  it('rejects a deletion preview whose full snapshot differs from the selected memory', async () => {
    const api = new Api(), request = standard(api, path => path.endsWith('/delete-preparations') ? { ...preparation, memory: { ...memory, scope: 'PROJECT', project_id: projectId, kind: 'PROFILE' } } : undefined); render(<MemoriesPanel {...props(api)}/>); await screen.findByText(memory.value); await select(); click('Prepare memory deletion'); await screen.findByRole('button', { name: 'Abandon unconsumed approval' }); expect(screen.queryByRole('region', { name: 'Confirm memory deletion' })).toBeNull(); expect(request.mock.calls.some(c => c[0].endsWith('/confirm'))).toBe(false);
  });

});

it('describes eligible context as data with explicit-only writes and unverified native quality', async () => {
  const api = new Api(); standard(api); render(<MemoriesPanel {...props(api)}/>); await screen.findByText(memory.value);
  expect(screen.getByText(/Eligible global and selected-project memories can be included as data/)).toBeTruthy();
  expect(screen.getByText(/Chats do not automatically write memories/)).toBeTruthy();
  expect(screen.getByText(/Older history without that ordering remains visible in the transcript/)).toBeTruthy();
  expect(screen.getByText(/late output is discarded and may be UNKNOWN while computation may continue/)).toBeTruthy();
});

// Synthetic same-batch dispatch regression: this does not assert an exploit using
// ordinary physical clicks separated by React's discrete-event commit.
describe('synchronous generic memory approval revocation', () => {
  it.each(['cancel', 'new', 'scope'])('rejects retained save confirmation after same-batch %s', async boundary => {
    const api = new Api(), request = standard(api), acquire = vi.fn(() => true);
    render(<MemoriesPanel {...props(api)} selectedProjectId={projectId} onAcquirePending={acquire}/>);
    fill(); click('Review memory save');
    const confirm = await screen.findByRole('button', { name: 'Confirm exact memory save' });
    acquire.mockClear();
    act(() => {
      if (boundary === 'cancel') screen.getByRole('button', { name: 'Cancel save review' }).click();
      if (boundary === 'new') screen.getByRole('button', { name: 'New memory' }).click();
      if (boundary === 'scope') fireEvent.change(screen.getByLabelText('Memory scope'), { target: { value: 'PROJECT' } });
      confirm.click();
    });
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Review exact memory save' })).toBeNull());
    expect(request.mock.calls.some(c => c[0] === '/memories' || c[3] === 'PATCH')).toBe(false);
    expect(acquire).not.toHaveBeenCalled();
  });

  it.each(['cancel', 'uncheck', 'new', 'scope'])('rejects retained deletion confirmation after same-batch %s', async boundary => {
    const api = new Api(), request = standard(api), acquire = vi.fn(() => true);
    render(<MemoriesPanel {...props(api)} selectedProjectId={projectId} onAcquirePending={acquire}/>);
    await screen.findByText(memory.value); await select(); click('Prepare memory deletion');
    await screen.findByRole('region', { name: 'Confirm memory deletion' });
    fireEvent.click(screen.getByLabelText(/I confirm deletion/));
    const confirm = screen.getByRole('button', { name: 'Delete this memory and revisions' });
    acquire.mockClear();
    act(() => {
      if (boundary === 'cancel') screen.getByRole('button', { name: 'Cancel deletion' }).click();
      if (boundary === 'uncheck') (screen.getByLabelText(/I confirm deletion/) as HTMLInputElement).click();
      if (boundary === 'new') screen.getByRole('button', { name: 'New memory' }).click();
      if (boundary === 'scope') fireEvent.change(screen.getByLabelText('Memory scope'), { target: { value: 'PROJECT' } });
      confirm.click();
    });
    expect(request.mock.calls.some(c => c[0].endsWith('/confirm'))).toBe(false);
    expect(acquire).not.toHaveBeenCalled();
    if (boundary === 'uncheck') {
      expect((confirm as HTMLButtonElement).disabled).toBe(true);
      fireEvent.click(screen.getByLabelText(/I confirm deletion/));
      click('Delete this memory and revisions');
      await screen.findByText(/Deletion is confirmed/);
      expect(request.mock.calls.filter(c => c[0].endsWith('/confirm'))).toHaveLength(1);
    }
  });

  it('allows a fresh save review after cancellation and submits it only once', async () => {
    const api = new Api(), request = standard(api);
    render(<MemoriesPanel {...props(api)}/>); fill(); click('Review memory save');
    await screen.findByRole('button', { name: 'Confirm exact memory save' }); click('Cancel save review');
    click('Review memory save'); const confirm = await screen.findByRole('button', { name: 'Confirm exact memory save' });
    act(() => { confirm.click(); confirm.click(); });
    await screen.findByText(/Save confirmed/);
    expect(request.mock.calls.filter(c => c[0] === '/memories')).toHaveLength(1);
  });
});
