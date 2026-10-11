import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api, ApiError } from './api';
vi.mock('./conversation-history-events', () => ({ watch_history_events: vi.fn(() => vi.fn()) }));
const id = '11111111-1111-4111-8111-111111111111', second = '22222222-2222-4222-8222-222222222222', project = '33333333-3333-4333-8333-333333333333', nextProject = '44444444-4444-4444-8444-444444444444';
const time = '2026-10-10T00:00:00Z';
const memory = { id, scope: 'GLOBAL', project_id: null, key: 'answer.style', kind: 'PREFERENCE', value: 'Private explicit preference', change_reason: 'Owner choice', source: { type: 'EXPLICIT_OWNER_FORM', submission_id: second, submitted_at: time, source_hash: 'a'.repeat(64) }, version: 1, generation: 1, confirmed_at: time, created_at: time, updated_at: time };
const intent = { id: second, tool_id: 'memory.write', operation: 'CREATE', target_id: id, expected_generation: 0, payload_hash: 'b'.repeat(64), expires_at: '2099-01-01T00:00:00Z', status: 'ISSUED' };
const preparation = { id: second, memory, payload_hash: 'c'.repeat(64), expected_version: 1, expected_generation: 1, expires_at: intent.expires_at, status: 'PREPARED' };
const receipt = { status: 'COMMITTED', memory_id: id, version: 1, generation: 1, operation: 'CREATE' };
function fixture(override: (path: string, body?: unknown) => unknown = () => undefined) {
  const api = new Api(); vi.spyOn(api, 'bootstrap_csrf').mockResolvedValue();
  const request = vi.spyOn(api, 'request').mockImplementation(async (path, body) => {
    const overridden = override(path, body); if (overridden !== undefined) return overridden;
    if (path === '/execution') return { execution_paused: false, version: 1, checked_at: '2026-10-10T00:00:00Z' }; if (path === '/me' || path === '/auth/logout') return {};
    if (path === '/auth/reauthenticate') return { csrf_token: 'fresh-session-token', authenticated: true };
    if (path === '/local-model/status') return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: false, status: 'DISABLED' };
    if (path.startsWith('/conversations?')) return { items: [{ id, title: 'Project conversation', project_id: project, created_at: time }, { id: second, title: 'Personal conversation', project_id: null, created_at: time }], next_cursor: null };
    if (path.includes('/messages?')) return { items: [], next_cursor: null, stream_cursor: null, current_run: null };
    if (path.startsWith('/projects?')) return { items: [{ id: nextProject, name: 'Next project', description: '', active: true, version: 1, created_at: time }], next_cursor: null };
    if (path === `/projects/${nextProject}`) return { id: nextProject, name: 'Next project', description: '', active: true, version: 1, created_at: time };
    if (path.startsWith('/tasks?')) return { items: [], next_cursor: null };
    if (path.startsWith('/memories?')) return { items: path.includes('scope=PROJECT') ? [] : [memory], next_cursor: null };
    if (path === `/memories/${id}`) return memory;
    if (path === '/memories/key-state') return { status: 'ABSENT', generation: 0, memory_id: null, version: null };
    if (path === '/memory-intents') return intent;
    if (path === '/memories') return receipt;
    if (path.endsWith('/delete-preparations')) return preparation;
    if (path.startsWith('/memory-mutations/')) return { status: 'NOT_OBSERVED', memory_id: null, version: null, generation: null, operation: null };
    throw new Error(`Unexpected ${path}`);
  }); return { api, request };
}
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
async function openMemory() { await screen.findByRole('button', { name: 'Open explicit memories' }); click('Open explicit memories'); await screen.findByText(memory.value); }
async function selectMemory() { click('answer.style · PREFERENCE · v1'); await screen.findByRole('button', { name: 'Prepare memory deletion' }); }
async function openReauth() { await selectMemory(); await waitFor(() => expect((screen.getByRole('button', { name: 'Reauthenticate for deletion' }) as HTMLButtonElement).disabled).toBe(false)); click('Reauthenticate for deletion'); await screen.findByLabelText('Confirm password'); fireEvent.change(await screen.findByLabelText('Confirm password'), { target: { value: 'synthetic' } }); }
function fillMemory() { click('New memory'); fireEvent.change(screen.getByLabelText('Memory key'), { target: { value: 'answer.style' } }); fireEvent.change(screen.getByLabelText('Memory value'), { target: { value: 'Fresh value' } }); fireEvent.change(screen.getByLabelText('Change reason'), { target: { value: 'Explicit choice' } }); }
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('uses the selected conversation project, then the next-project selection only for a new conversation', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await openMemory(); await waitFor(() => expect(screen.getByRole('button', { name: 'Project conversation' }).getAttribute('aria-current')).toBe('true')); click('Choose project for a new conversation'); await screen.findByRole('option', { name: 'Next project' }); fireEvent.change(screen.getByLabelText('New conversation project'), { target: { value: nextProject } }); await screen.findByText(/Next new conversation project: Next project/);
  fireEvent.change(screen.getByLabelText('Memory scope'), { target: { value: 'PROJECT' } }); await waitFor(() => expect(request.mock.calls.some(c => c[0] === `/memories?scope=PROJECT&limit=20&project_id=${project}`)).toBe(true)); expect(request.mock.calls.some(c => c[0] === `/memories?scope=PROJECT&limit=20&project_id=${nextProject}`)).toBe(false);
  click('Start new conversation'); await waitFor(() => expect(request.mock.calls.some(c => c[0] === `/memories?scope=PROJECT&limit=20&project_id=${nextProject}`)).toBe(true)); expect((screen.getByRole('button', { name: 'Open explicit memories' }) as HTMLButtonElement).disabled).toBe(true);
});
it('synchronously blocks same-tick reauthentication after task acquisition and preserves unknown-task recovery', async () => {
  const { api, request } = fixture(path => { if (path === '/user-intents') throw new ApiError('UNKNOWN', true); }); render(<App api={api}/>); await openMemory(); await openReauth(); click('Open internal tasks'); fireEvent.change(screen.getByLabelText('Task title'), { target: { value: 'Private task' } }); click('Review task change');
  const reauthForm = screen.getByLabelText('Confirm password').closest('form')!;
  act(() => { screen.getByRole('button', { name: 'Request task intent' }).click(); reauthForm.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  await screen.findByRole('button', { name: 'Check task outcome' }); expect(request.mock.calls.some(c => c[0] === '/auth/reauthenticate')).toBe(false); expect((screen.getByRole('button', { name: 'Start new conversation' }) as HTMLButtonElement).disabled).toBe(true); expect((screen.getByRole('button', { name: 'Confirm identity' }) as HTMLButtonElement).disabled).toBe(true);
  click('Check task outcome'); await screen.findByText(/No task mutation was sent by this page/); expect(screen.getByRole('button', { name: 'Check task outcome' })).toBeTruthy(); expect(screen.getByRole('region', { name: 'Explicit memories' })).toBeTruthy();
});
it('synchronously fences project creation and retains its receipt key across blocked reauth', async () => {
  const { api, request } = fixture(path => { if (path === '/projects') throw new ApiError('UNKNOWN', true); if (path.startsWith('/project-creations/')) return { status: 'NOT_OBSERVED', project: null }; }); render(<App api={api}/>); await openMemory(); await openReauth(); click('Choose project for a new conversation'); fireEvent.change(screen.getByLabelText('Project name'), { target: { value: 'New project' } });
  const reauthForm = screen.getByLabelText('Confirm password').closest('form')!;
  act(() => { screen.getByRole('button', { name: 'Create project' }).click(); reauthForm.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  await screen.findByRole('button', { name: 'Check project outcome' }); click('Check project outcome'); await screen.findByText(/No committed receipt is visible yet/); expect(request.mock.calls.some(c => c[0] === '/auth/reauthenticate')).toBe(false); expect(request.mock.calls.filter(c => c[0] === '/projects')).toHaveLength(1); expect(screen.getByLabelText('Memory value').closest('fieldset')!.disabled).toBe(true);
});
it('locks other domains and navigation for uncertain memory saves without blocking receipt reconciliation', async () => {
  const { api, request } = fixture(path => { if (path === '/memories') throw new ApiError('UNKNOWN', true); }); render(<App api={api}/>); await openMemory(); click('Open internal tasks'); click('Choose project for a new conversation'); fillMemory(); click('Review memory save'); await screen.findByRole('button', { name: 'Confirm exact memory save' }); click('Confirm exact memory save'); await screen.findByRole('button', { name: 'Check memory outcome' });
  for (const name of ['Start new conversation', 'Personal conversation', 'Send mock message']) expect((screen.getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(true);
  expect(screen.getByLabelText('Task title').closest('fieldset')!.disabled).toBe(true); expect(screen.getByLabelText('Project name').closest('fieldset')!.disabled).toBe(true); click('Check memory outcome'); await screen.findByText(/This does not prove failure/); expect(request.mock.calls.filter(c => c[0] === '/memories')).toHaveLength(1); expect((screen.getByRole('button', { name: 'Check memory outcome' }) as HTMLButtonElement).disabled).toBe(false);
});
it('rotates session on explicit reauth, clears old preview, and never auto-prepares or deletes', async () => {
  const { api, request } = fixture(); render(<App api={api}/>); await openMemory(); await selectMemory(); click('Prepare memory deletion'); await screen.findByRole('region', { name: 'Confirm memory deletion' }); await waitFor(() => expect((screen.getByRole('button', { name: 'Reauthenticate for deletion' }) as HTMLButtonElement).disabled).toBe(false)); click('Reauthenticate for deletion'); fireEvent.change(await screen.findByLabelText('Confirm password'), { target: { value: 'synthetic' } }); click('Confirm identity'); await screen.findByText(/Identity confirmed/);
  expect(screen.queryByRole('region', { name: 'Confirm memory deletion' })).toBeNull(); await waitFor(() => expect(screen.queryByRole('button', { name: 'Prepare memory deletion' })).toBeNull()); expect(request.mock.calls.filter(c => c[0].endsWith('/delete-preparations'))).toHaveLength(1); expect(request.mock.calls.some(c => c[0].endsWith('/confirm'))).toBe(false); expect((screen.getByLabelText('Memory value') as HTMLTextAreaElement).value).toBe('');
  await screen.findByText(memory.value); await selectMemory(); click('Prepare memory deletion'); await screen.findByRole('region', { name: 'Confirm memory deletion' }); expect(request.mock.calls.filter(c => c[0].endsWith('/delete-preparations'))).toHaveLength(2);
});
it('distinguishes an in-flight request from an unknown outcome and allows signout privacy clearing', async () => {
  let resolve!: (v: unknown) => void; const { api, request } = fixture(path => path === '/memory-intents' ? new Promise(r => { resolve = r; }) : undefined); render(<App api={api}/>); await openMemory(); fillMemory(); click('Review memory save'); await waitFor(() => expect(resolve).toBeDefined()); await screen.findByText(/A task, project, or memory request is pending/); expect(screen.queryByText('Writes paused')).toBeNull(); expect(screen.queryByRole('button', { name: 'Check memory outcome' })).toBeNull(); click('Sign out'); await screen.findByRole('heading', { name: 'Owner sign in' }); await act(async () => resolve(intent)); expect(screen.queryByRole('region', { name: 'Review exact memory save' })).toBeNull(); expect(screen.queryByText(memory.value)).toBeNull(); expect(request.mock.calls.filter(c => c[0] === '/memory-intents')).toHaveLength(1);
});

it('synchronously blocks session rotation when a memory save is acquired in the same event tick', async () => {
  const { api, request } = fixture(path => { if (path === '/memories') throw new ApiError('UNKNOWN', true); }); render(<App api={api}/>); await openMemory(); await openReauth(); fillMemory(); click('Review memory save'); await screen.findByRole('button', { name: 'Confirm exact memory save' }); const reauthForm = screen.getByLabelText('Confirm password').closest('form')!;
  act(() => { screen.getByRole('button', { name: 'Confirm exact memory save' }).click(); reauthForm.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  await screen.findByRole('button', { name: 'Check memory outcome' }); expect(request.mock.calls.some(c => c[0] === '/auth/reauthenticate')).toBe(false); click('Check memory outcome'); await screen.findByText(/This does not prove failure/); expect(request.mock.calls.filter(c => c[0] === '/memories')).toHaveLength(1);
});
