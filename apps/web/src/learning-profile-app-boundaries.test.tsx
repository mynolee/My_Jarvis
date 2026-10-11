import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { App } from './App';
import type { Memory, MemoryIdentity } from './memories';
vi.mock('./conversation-history-events', () => ({ watch_history_events: vi.fn(() => vi.fn()) }));
const id = '11111111-1111-4111-8111-111111111111', second = '22222222-2222-4222-8222-222222222222', project = '33333333-3333-4333-8333-333333333333', nextProject = '44444444-4444-4444-8444-444444444444', time = '2026-10-10T00:00:00Z';
const original: Memory = { id, scope: 'GLOBAL', project_id: null, key: 'learning.level', kind: 'PROFILE', value: 'Original synthetic learning value', change_reason: 'Synthetic reason', source: { type: 'EXPLICIT_OWNER_FORM', submission_id: second, submitted_at: time, source_hash: 'a'.repeat(64) }, version: 1, generation: 1, confirmed_at: time, created_at: time, updated_at: time };
function fixture(override: (path: string, body?: unknown) => unknown = () => undefined) {
  const api = new Api(); let memory = { ...original }; vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation(async (path, body) => {
    const result = override(path, body); if (result !== undefined) return result;
    if (path === '/execution') return { execution_paused: false, version: 1, checked_at: time };
    if (path === '/me') return {};
    if (path === '/auth/logout') return { logged_out: true };
    if (path === '/auth/reauthenticate') return { csrf_token: 'synthetic-rotated-token', authenticated: true };
    if (path === '/local-model/status') return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: false, status: 'DISABLED' };
    if (path.startsWith('/conversations?')) return { items: [{ id, title: 'Project conversation', project_id: project, created_at: time }, { id: second, title: 'Personal conversation', project_id: null, created_at: time }], next_cursor: null };
    if (path.includes('/messages?')) return { items: [], next_cursor: null, stream_cursor: null, current_run: null };
    if (path.startsWith('/projects?')) return { items: [{ id: nextProject, name: 'Next project', description: '', active: true, version: 1, created_at: time }], next_cursor: null };
    if (path === `/projects/${nextProject}`) return { id: nextProject, name: 'Next project', description: '', active: true, version: 1, created_at: time };
    if (path.startsWith('/tasks?')) return { items: [], next_cursor: null };
    if (path.startsWith('/memories?')) return { items: path.includes('scope=PROJECT') ? [] : [memory], next_cursor: null };
    if (path === '/memories/key-state') { const identity = body as MemoryIdentity; return identity.scope === memory.scope && identity.key === memory.key ? { status: 'ACTIVE', memory_id: id, version: memory.version, generation: memory.generation } : { status: 'ABSENT', generation: 0, memory_id: null, version: null }; }
    if (path === `/memories/${id}` && !body) return memory;
    if (path === '/memory-intents') { const issue = body as { operation: string; arguments: { expected_generation: number } }; return { id: second, tool_id: 'memory.write', operation: issue.operation, target_id: id, expected_generation: issue.arguments.expected_generation, payload_hash: 'b'.repeat(64), expires_at: '2099-01-01T00:00:00Z', status: 'ISSUED' }; }
    if (path === `/memories/${id}` && body) { const args = (body as { arguments: { value: string; change_reason: string } }).arguments; memory = { ...memory, value: args.value, change_reason: args.change_reason, version: memory.version + 1 }; return { status: 'COMMITTED', memory_id: id, version: memory.version, generation: memory.generation, operation: 'REVISE' }; }
    if (path.startsWith('/memory-mutations/')) return { status: 'NOT_OBSERVED', memory_id: null, version: null, generation: null, operation: null };
    throw new Error(`Unexpected ${path}`);
  }); return { api, request };
}
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
async function openLearning() { await screen.findByRole('button', { name: 'Open learning profile' }); click('Open learning profile'); await screen.findByText('Saved value: Original synthetic learning value'); }
async function stageLearning() { fireEvent.change(screen.getByLabelText('Learning value'), { target: { value: 'Updated synthetic learning value' } }); fireEvent.change(screen.getByLabelText('Learning change reason'), { target: { value: 'Explicit update' } }); click('Review learning field save'); await screen.findByRole('button', { name: 'Confirm exact learning field save' }); await waitFor(() => expect((screen.getByRole('button', { name: 'Open explicit memories' }) as HTMLButtonElement).disabled).toBe(false)); }
async function stageGeneric() { click('Open explicit memories'); await screen.findByText(original.value); click('learning.level · PROFILE · v1'); await screen.findByText(`Current saved value: ${original.value}`); fireEvent.change(screen.getByLabelText('Memory value'), { target: { value: 'Updated through generic editor' } }); fireEvent.change(screen.getByLabelText('Change reason'), { target: { value: 'Explicit generic update' } }); click('Review memory save'); await screen.findByRole('button', { name: 'Confirm exact memory save' }); await waitFor(() => expect((screen.getByRole('button', { name: 'Open learning profile' }) as HTMLButtonElement).disabled).toBe(false)); }
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('switches between mutually exclusive editors, discards staged forms and reads the same updated records', async () => {
  let committed = false;
  let releaseRefresh!: (value: unknown) => void;
  const refreshed = new Promise(resolve => { releaseRefresh = resolve; });
  const { api, request } = fixture((path, body) => {
    if (path === `/memories/${id}` && body) committed = true;
    if (committed && path.startsWith('/memories?')) return refreshed;
  });
  render(<App api={api}/>);
  await openLearning();
  await stageLearning();
  await stageGeneric();
  expect(screen.queryByRole('region', { name: 'Learning profile' })).toBeNull();
  expect(screen.queryByRole('region', { name: 'Review exact learning field save' })).toBeNull();
  click('Confirm exact memory save');
  await screen.findByText(/Save confirmed/);
  const learningToggle = screen.getByRole('button', { name: 'Open learning profile' }) as HTMLButtonElement;
  expect(learningToggle.disabled).toBe(true);
  // A committed receipt can precede list refresh; navigation remains fenced.
  click('Open learning profile');
  expect(screen.queryByRole('region', { name: 'Learning profile' })).toBeNull();
  expect(screen.getByRole('region', { name: 'Explicit memories' })).toBeTruthy();
  await act(async () => releaseRefresh({ items: [{ ...original, value: 'Updated through generic editor', change_reason: 'Explicit generic update', version: 2 }], next_cursor: null }));
  await waitFor(() => expect(learningToggle.disabled).toBe(false));
  click('Open learning profile');
  await screen.findByText('Saved value: Updated through generic editor');
  expect(screen.queryByRole('region', { name: 'Explicit memories' })).toBeNull();
  expect((screen.getByLabelText('Learning value') as HTMLTextAreaElement).value).toBe('');
  expect(request.mock.calls.filter(c => c[0] === `/memories/${id}` && c[1])).toHaveLength(1);
});
it('blocks editor switching and every existing memory-domain navigation fence during UNKNOWN', async () => {
  const { api, request } = fixture((path, body) => { if (path === `/memories/${id}` && body) throw new ApiError('UNKNOWN', true); }); render(<App api={api}/>); await openLearning(); click('Open internal tasks'); click('Choose project for a new conversation'); await stageLearning(); click('Confirm exact learning field save'); await screen.findByRole('button', { name: 'Check learning save outcome' });
  for (const name of ['Open explicit memories', 'Start new conversation', 'Personal conversation', 'Send mock message']) expect((screen.getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(true);
  expect(screen.getByLabelText('Task title').closest('fieldset')!.disabled).toBe(true); expect(screen.getByLabelText('Project name').closest('fieldset')!.disabled).toBe(true); click('Check learning save outcome'); await screen.findByText(/NOT_OBSERVED is inconclusive/); expect((screen.getByRole('button', { name: 'Check learning save outcome' }) as HTMLButtonElement).disabled).toBe(false); expect(request.mock.calls.filter(c => c[0] === `/memories/${id}` && c[1])).toHaveLength(1);
});
it('keeps learning ownership when confirmation and switch occur in the same event batch', async () => {
  let resolve!: (v: unknown) => void; const { api, request } = fixture((path, body) => path === `/memories/${id}` && body ? new Promise(r => { resolve = r; }) : undefined); render(<App api={api}/>); await openLearning(); await stageLearning(); const confirm = screen.getByRole('button', { name: 'Confirm exact learning field save' }), toggle = screen.getByRole('button', { name: 'Open explicit memories' }); act(() => { confirm.click(); toggle.click(); }); await waitFor(() => expect(resolve).toBeDefined()); expect(screen.getByRole('region', { name: 'Learning profile' })).toBeTruthy(); expect(screen.queryByRole('region', { name: 'Explicit memories' })).toBeNull(); await act(async () => resolve({})); await screen.findByRole('button', { name: 'Check learning save outcome' }); expect(request.mock.calls.filter(c => c[0] === `/memories/${id}` && c[1])).toHaveLength(1);
});
it('rejects a captured outgoing learning confirmation after a same-tick editor switch', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await openLearning(); await stageLearning(); const confirm = screen.getByRole('button', { name: 'Confirm exact learning field save' }), toggle = screen.getByRole('button', { name: 'Open explicit memories' }); act(() => { toggle.click(); confirm.click(); }); await screen.findByRole('region', { name: 'Explicit memories' }); expect(request.mock.calls.filter(c => c[0] === `/memories/${id}` && c[1])).toHaveLength(0); expect(screen.queryByRole('region', { name: 'Learning profile' })).toBeNull();
});
it('rejects a captured outgoing generic confirmation after a same-tick editor switch', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await screen.findByRole('button', { name: 'Open explicit memories' }); await stageGeneric(); const confirm = screen.getByRole('button', { name: 'Confirm exact memory save' }), toggle = screen.getByRole('button', { name: 'Open learning profile' }); act(() => { toggle.click(); confirm.click(); }); await screen.findByRole('region', { name: 'Learning profile' }); expect(request.mock.calls.filter(c => c[0] === `/memories/${id}` && c[1])).toHaveLength(0);
});
it('keeps generic ownership when its confirmation precedes a same-tick learning switch', async () => {
  let resolve!: (v: unknown) => void; const { api, request } = fixture((path, body) => path === `/memories/${id}` && body ? new Promise(r => { resolve = r; }) : undefined); render(<App api={api}/>); await screen.findByRole('button', { name: 'Open explicit memories' }); await stageGeneric(); const confirm = screen.getByRole('button', { name: 'Confirm exact memory save' }), toggle = screen.getByRole('button', { name: 'Open learning profile' }); act(() => { confirm.click(); toggle.click(); }); await waitFor(() => expect(resolve).toBeDefined()); expect(screen.getByRole('region', { name: 'Explicit memories' })).toBeTruthy(); expect(screen.queryByRole('region', { name: 'Learning profile' })).toBeNull(); await act(async () => resolve({})); await screen.findByRole('button', { name: 'Check memory outcome' }); expect(request.mock.calls.filter(c => c[0] === `/memories/${id}` && c[1])).toHaveLength(1);
});
it('clears private learning data on logout and ignores the late write response', async () => {
  let resolve!: (v: unknown) => void; const { api } = fixture((path, body) => path === `/memories/${id}` && body ? new Promise(r => { resolve = r; }) : undefined); render(<App api={api}/>); await openLearning(); await stageLearning(); click('Confirm exact learning field save'); await waitFor(() => expect(resolve).toBeDefined()); click('Sign out'); await screen.findByRole('heading', { name: 'Owner sign in' }); await act(async () => resolve({ status: 'COMMITTED', memory_id: id, version: 2, generation: 1, operation: 'REVISE' })); expect(screen.queryByText(original.value)).toBeNull(); expect(screen.queryByRole('region', { name: 'Learning profile' })).toBeNull(); expect(screen.queryByText(/This field save is confirmed/)).toBeNull();
});
it('uses the current conversation project and switches to the next project only for a new conversation', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await openLearning(); click('Choose project for a new conversation'); await screen.findByRole('option', { name: 'Next project' }); fireEvent.change(screen.getByLabelText('New conversation project'), { target: { value: nextProject } }); await screen.findByText(/Next new conversation project: Next project/); fireEvent.change(screen.getByLabelText('Learning scope'), { target: { value: 'PROJECT' } }); expect(screen.getByText(`Selected project: ${project}`)).toBeTruthy(); click('Start new conversation'); await screen.findByText(`Selected project: ${nextProject}`); await waitFor(() => expect(request.mock.calls.some(c => c[0] === '/memories/key-state' && (c[1] as MemoryIdentity).project_id === nextProject)).toBe(true));
});
