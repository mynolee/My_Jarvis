import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { ProjectsPanel } from './ProjectsPanel';
import { read_project, read_project_page, read_project_receipt, valid_project_fields } from './projects';
const id = '11111111-1111-4111-8111-111111111111';
const project = { id, name: 'Research', description: '', active: true, version: 1, created_at: '2026-10-10T00:00:00Z' };
const page = { items: [project], next_cursor: null };
const props = (api: Api) => ({ api, sessionKey: 1, selectedProjectId: null, onSelect: vi.fn(), onUnauthorized: vi.fn() });
function fill(name = 'Research') { fireEvent.change(screen.getByLabelText('Project name'), { target: { value: name } }); }
function submit() { fireEvent.click(screen.getByRole('button', { name: 'Create project' })); }
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
describe('project selection and explicit creation', () => {
  it('validates wire data and input limits before use', () => {
    expect(valid_project_fields({ name: '\u0085', description: '' })).toBe(false);
    expect(valid_project_fields({ name: 'bad\u0000name', description: '' })).toBe(false);
    expect(valid_project_fields({ name: '😀'.repeat(200), description: 'a'.repeat(4000) })).toBe(true);
    expect(valid_project_fields({ name: 'a'.repeat(201), description: '' })).toBe(false);
    expect(() => read_project({ ...project, active: false })).toThrow();
    expect(() => read_project_page({ ...page, items: [project, project] })).toThrow();
  });
  it('rejects lone surrogate fields while accepting complete Unicode pairs', () => {
    for (const invalid of ['\uD800', '\uDC00', 'prefix\uD800suffix', '\uD800\uD800']) {
      expect(valid_project_fields({ name: invalid, description: '' })).toBe(false);
      expect(valid_project_fields({ name: 'Valid', description: invalid })).toBe(false);
      expect(() => read_project({ ...project, name: invalid })).toThrow();
      expect(() => read_project({ ...project, description: invalid })).toThrow();
    }
    expect(valid_project_fields({ name: '\uD83D\uDE00', description: '\uD83D\uDE00' })).toBe(true);
    expect(read_project({ ...project, name: '😀', description: '😀' }).name).toBe('😀');
  });
  it('rejects extra or missing response fields against the closed wire contract', () => {
    expect(() => read_project({ ...project, owner_id: id })).toThrow();
    expect(() => read_project({ ...project, extra: 'ignored' })).toThrow();
    expect(() => read_project_page({ ...page, extra: true })).toThrow();
    expect(() => read_project_page({ items: [] })).toThrow();
    expect(() => read_project_receipt({ status: 'COMMITTED', project, extra: true }, project)).toThrow();
  });
  it('selects verified project only for a new conversation, without any mutation', async () => {
    const api = new Api(), p = props(api); const request = vi.spyOn(api, 'request').mockImplementation(async path => path.startsWith('/projects?') ? page : project);
    render(<ProjectsPanel {...p}/>); await screen.findByRole('option', { name: 'Research' });
    fireEvent.change(screen.getByLabelText('New conversation project'), { target: { value: id } });
    await waitFor(() => expect(p.onSelect).toHaveBeenCalledWith(project)); expect(request).toHaveBeenCalledWith(`/projects/${id}`); expect(request.mock.calls.every(call => call[1] === undefined)).toBe(true);
  });
  it('uses one immutable create key and locks duplicate clicks until completion', async () => {
    const api = new Api(); let resolve!: (data: unknown) => void;
    const request = vi.spyOn(api, 'request').mockImplementation(async (_path, body) => body ? new Promise(r => { resolve = r; }) : page);
    render(<ProjectsPanel {...props(api)}/>); fill(); submit(); submit();
    await waitFor(() => expect(request.mock.calls.filter(call => call[1] !== undefined)).toHaveLength(1));
    const write = request.mock.calls.find(call => call[1] !== undefined)!; expect(write[1]).toEqual({ name: 'Research', description: '' }); expect(write[2]).toMatch(/^[a-f0-9-]{36}$/);
    await act(async () => resolve(project)); await screen.findByText('Project created. Select it for a new conversation.');
  });
  it('keeps an uncertain create paused after absent receipt, then reconciles read-only', async () => {
    const api = new Api(); let committed = false;
    const request = vi.spyOn(api, 'request').mockImplementation(async (path, body) => { if (body) throw new ApiError('UNKNOWN', true); if (path.startsWith('/project-creations/')) return committed ? { status: 'COMMITTED', project } : { status: 'NOT_OBSERVED', project: null }; return page; });
    render(<ProjectsPanel {...props(api)}/>); fill(); submit();
    fireEvent.click(await screen.findByRole('button', { name: 'Check project outcome' })); await screen.findByText(/This does not prove failure/);
    expect((screen.getByLabelText('Project name') as HTMLInputElement).value).toBe('Research'); expect(screen.getByLabelText('Project name').closest('fieldset')!.disabled).toBe(true);
    committed = true; fireEvent.click(screen.getByRole('button', { name: 'Check project outcome' })); await screen.findByText(/Server receipt confirms/);
    expect(request.mock.calls.filter(call => call[1] !== undefined)).toHaveLength(1);
  });
  it('treats mismatched success payload as unknown and rejects mismatched receipts', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockImplementation(async (path, body) => body ? { ...project, name: 'Wrong' } : path.startsWith('/project-creations/') ? { status: 'COMMITTED', project: { ...project, name: 'Wrong' } } : page);
    render(<ProjectsPanel {...props(api)}/>); fill(); submit(); fireEvent.click(await screen.findByRole('button', { name: 'Check project outcome' })); await screen.findByText('INVALID_PROJECT_RESPONSE'); expect(screen.getByLabelText('Project name').closest('fieldset')!.disabled).toBe(true);
  });
  it('preserves the draft on conflict and obeys global write and selection locks', async () => {
    const api = new Api(), p = props(api); vi.spyOn(api, 'request').mockImplementation(async (_path, body) => { if (body) throw new ApiError('IDEMPOTENCY_CONFLICT', false, 409); return page; });
    const { rerender } = render(<ProjectsPanel {...p}/>); fill(); submit(); await screen.findByText(/Your draft is preserved/); expect((screen.getByLabelText('Project name') as HTMLInputElement).value).toBe('Research');
    rerender(<ProjectsPanel {...p} writesPaused selectionDisabled/>); expect(screen.getByLabelText('Project name').closest('fieldset')!.disabled).toBe(true); expect((screen.getByLabelText('New conversation project') as HTMLSelectElement).disabled).toBe(true);
  });
  it('fences stale success and stale 401 across session changes', async () => {
    const api = new Api(), p = props(api); let reject!: (error: unknown) => void;
    vi.spyOn(api, 'request').mockImplementationOnce(() => new Promise((_, r) => { reject = r; })).mockResolvedValue({ items: [], next_cursor: null });
    const { rerender } = render(<ProjectsPanel {...p}/>); rerender(<ProjectsPanel {...p} sessionKey={2}/>);
    await act(async () => reject(new ApiError('UNAUTHENTICATED', false, 401))); expect(p.onUnauthorized).not.toHaveBeenCalled();
  });
  it('ignores a prior session create success after the owner session changes', async () => {
    const api = new Api(), p = props(api); let resolve!: (value: unknown) => void;
    vi.spyOn(api, 'request').mockImplementation(async (_path, body) => body ? new Promise(r => { resolve = r; }) : { items: [], next_cursor: null });
    const { rerender } = render(<ProjectsPanel {...p}/>); fill(); submit(); await waitFor(() => expect(resolve).toBeTruthy());
    rerender(<ProjectsPanel {...p} sessionKey={2}/>); await act(async () => resolve(project));
    expect(screen.queryByRole('option', { name: 'Research' })).toBeNull(); expect(screen.queryByText('Project created. Select it for a new conversation.')).toBeNull(); expect(p.onSelect).not.toHaveBeenCalled();
  });
  it('clears private drafts and fences later work on current session 401', async () => {
    const api = new Api(), p = props(api); vi.spyOn(api, 'request').mockImplementation(async (_path, body) => { if (body) throw new ApiError('UNAUTHENTICATED', false, 401); return page; });
    render(<ProjectsPanel {...p}/>); await screen.findByRole('option', { name: 'Research' }); fill('Private draft'); submit(); await waitFor(() => expect(p.onUnauthorized).toHaveBeenCalledTimes(1)); expect((screen.getByLabelText('Project name') as HTMLInputElement).value).toBe(''); expect(screen.queryByRole('option', { name: 'Research' })).toBeNull();
  });
  it('escapes project names and has no automatic selection or storage persistence', async () => {
    const api = new Api(), p = props(api); vi.spyOn(api, 'request').mockResolvedValue({ items: [{ ...project, name: '<img src=x>' }], next_cursor: null }); const storage = vi.spyOn(Storage.prototype, 'setItem');
    const { container } = render(<ProjectsPanel {...p}/>); await screen.findByRole('option', { name: '<img src=x>' }); expect(container.querySelector('img')).toBeNull(); expect(p.onSelect).not.toHaveBeenCalled(); expect(storage).not.toHaveBeenCalled();
  });
});
