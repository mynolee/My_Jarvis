import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { TasksPanel } from './TasksPanel';
import { read_task, valid_date, valid_fields, empty_task, type Task } from './tasks';
const id = '11111111-1111-4111-8111-111111111111', intentId = '22222222-2222-4222-8222-222222222222';
const task: Task = { ...empty_task(), id, title: 'Synthetic task', version: 1, created_at: '2026-10-10T09:00:00Z', updated_at: '2026-10-10T09:00:00Z' };
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
function fixture(mode: 'success' | 'conflict' | 'unknown' | 'unauthorized' = 'success', existing = false) {
  const api = new Api(), unauthorized = vi.fn();
  const calls: {path: string; body?: unknown; key?: string; method?: string}[] = [];
  vi.spyOn(api, 'request').mockImplementation(async <T,>(path: string, body?: unknown, key?: string, method?: string): Promise<T> => {
    calls.push({path, body, key, method});
    if (path.startsWith('/tasks?')) return { items: existing ? [task] : [], next_cursor: null } as T;
    if (path === '/user-intents') { const b = body as { tool_id: string; arguments: unknown }; return { id: intentId, tool_id: b.tool_id, target_id: id, arguments: b.arguments, status: 'ISSUED', payload_hash: 'a'.repeat(64), expires_at: '2099-01-01T00:00:00Z' } as T; }
    if (body && (path === '/tasks' || path === `/tasks/${id}`)) {
      if (mode === 'conflict') throw new ApiError('PRECONDITION_CHANGED', false, 409, { current_task: { ...task, title: 'Other device title', version: 2 } });
      if (mode === 'unknown') throw new ApiError('UNKNOWN', true);
      if (mode === 'unauthorized') throw new ApiError('UNAUTHENTICATED', false, 401);
      return { ...task, ...(body as object), version: existing ? 2 : 1 } as T;
    }
    if (path.startsWith('/task-mutations/')) return { status: 'NOT_OBSERVED', task: null } as T;
    if (path === `/tasks/${id}`) throw new ApiError('NOT_FOUND', false, 404);
    throw new Error(`Unexpected ${path}`);
  });
  return {api, calls, unauthorized};
}
async function review() { fireEvent.change(screen.getByLabelText('Task title'), { target: { value: 'My unsaved title' } }); fireEvent.click(screen.getByRole('button', {name: 'Review task change'})); fireEvent.click(screen.getByRole('button', {name: 'Request task intent'})); return await screen.findByRole('button', {name: 'Apply server-bound task change'}); }
it('validates Unicode limits, real calendar dates and offset deadlines', () => {
  expect(valid_fields({...empty_task(), title: '😀'.repeat(200)})).toBe(true);
  expect(valid_fields({...empty_task(), title: '😀'.repeat(201)})).toBe(false);
  expect(valid_date('2026-02-30T09:00:00Z')).toBe(false);
  expect(valid_date('2026-02-28T09:00:00+09:00')).toBe(true);
  expect(() => read_task({...task, status: 'RUNNING'})).toThrow();
});
it('shows exact issued intent; cancellation does not mutate; repeated apply writes once', async () => {
  const f = fixture(); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>);
  await review(); expect(screen.getByRole('heading', {name: 'Review server-bound task intent'})).toBeTruthy();
  expect(f.calls.filter(c => c.path === '/tasks')).toHaveLength(0);
  fireEvent.click(screen.getByRole('button', {name: 'Back to draft'}));
  const apply = await review(); fireEvent.click(apply); fireEvent.click(apply);
  await screen.findByText('Server confirmed task TODO, version 1.');
  const mutation = f.calls.filter(c => c.path === '/tasks'); expect(mutation).toHaveLength(1);
  expect(mutation[0].body).toEqual({user_intent_id: intentId, title: 'My unsaved title', description: '', project_id: null, due_at: null, priority: 'NORMAL', estimated_minutes: null});
  expect(mutation[0].key).not.toBe(f.calls.find(c => c.path === '/user-intents')?.key);
  expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0);
});
it('PATCH conflict preserves draft beside canonical task and requires explicit review and fresh intent', async () => {
  const f = fixture('conflict', true); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>);
  fireEvent.click(await screen.findByRole('button', {name: 'Edit Synthetic task'})); fireEvent.click(await review());
  const conflict = await screen.findByRole('region', {name: 'Task version conflict'});
  expect(within(conflict).getByText(/Other device title/)).toBeTruthy(); expect(within(conflict).getByText(/My unsaved title/)).toBeTruthy();
  expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe('My unsaved title');
  expect(f.calls.filter(c => c.method === 'PATCH')).toHaveLength(1);
  expect(f.calls.find(c => c.method === 'PATCH')?.body).toMatchObject({expected_version: 1});
  expect((screen.getByRole('button', {name: 'Review task change'}) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByRole('button', {name: 'I reviewed this version; keep my draft'}));
  await review(); const intents = f.calls.filter(c => c.path === '/user-intents'); expect(intents).toHaveLength(2); expect(intents[1].body).toMatchObject({arguments: {expected_version: 2}});
});
it('unknown mutation retains key, reads receipt and canonical UUID; NOT_OBSERVED plus 404 never unlocks', async () => {
  const f = fixture('unknown'); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>); fireEvent.click(await review());
  await screen.findByRole('heading', {name: 'Task writes paused'});
  fireEvent.click(screen.getByRole('button', {name: 'Check task outcome'})); await screen.findByText(/A missing task is not proof of failure/);
  expect(f.calls.find(c => c.path.startsWith('/task-mutations/'))?.path).toBe(`/task-mutations/${f.calls.find(c => c.path === '/tasks')?.key}`);
  expect(f.calls.some(c => c.path === `/tasks/${id}` && !c.body)).toBe(true);
  expect(f.calls.filter(c => c.path === '/tasks')).toHaveLength(1);
  expect((screen.getByRole('button', {name: 'New personal task'}) as HTMLButtonElement).disabled).toBe(true);
});
it('401 clears private task state and invalidates parent session', async () => {
  const f = fixture('unauthorized', true); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>); fireEvent.click(await review());
  await waitFor(() => expect(f.unauthorized).toHaveBeenCalledOnce());
  expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe(''); expect(screen.queryByText('Synthetic task')).toBeNull();
});
it('sends RFC3339 filters encoded; PATCH retains CSRF and exposes conflict details', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({error: {code: 'PRECONDITION_CHANGED', details: {current_task: task}}}), {status: 409, headers: {'content-type': 'application/json'}})); vi.stubGlobal('fetch', fetcher);
  const api = new Api(); api.set_csrf('test-token');
  await expect(api.request(`/tasks/${id}`, {expected_version: 1}, intentId, 'PATCH')).rejects.toMatchObject({status: 409, details: {current_task: task}});
  expect(fetcher).toHaveBeenCalledWith(`/api/v1/tasks/${id}`, expect.objectContaining({method: 'PATCH', headers: expect.objectContaining({'X-CSRF-Token': 'test-token'})}));
  fetcher.mockResolvedValue(new Response(JSON.stringify({data: {items: [], next_cursor: null}}), {headers: {'content-type': 'application/json'}}));
  await expect(api.request('/tasks?from=2026-10-10T09%3A00%3A00%2B09%3A00&limit=20')).resolves.toMatchObject({items: []});
});
it.each(['DONE', 'CANCELLED', 'TODO', 'IN_PROGRESS'])('explicitly applies %s with the canonical version, without any model endpoint', async status => {
  const f = fixture('success', true); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>);
  fireEvent.click(await screen.findByRole('button', {name: 'Edit Synthetic task'}));
  fireEvent.change(screen.getByLabelText('Task status'), {target: {value: status}}); fireEvent.click(await review());
  await screen.findByText(`Server confirmed task ${status}, version 2.`);
  expect(f.calls.find(c => c.method === 'PATCH')?.body).toMatchObject({status, expected_version: 1});
  expect(f.calls.every(c => !/runs|messages|inference/.test(c.path))).toBe(true);
});
it('committed receipt reads current task and only then allows new explicit changes', async () => {
  const f = fixture('unknown'); const original = vi.mocked(f.api.request).getMockImplementation()!;
  vi.mocked(f.api.request).mockImplementation(async <T,>(path: string, body?: unknown, key?: string, method?: 'POST' | 'PATCH'): Promise<T> => {
    if (path.startsWith('/task-mutations/')) return {status: 'COMMITTED', task} as T;
    if (path === `/tasks/${id}` && !body) return {...task, title: 'Current server title', version: 3} as T;
    return original(path, body, key, method) as Promise<T>;
  });
  render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>); fireEvent.click(await review()); await screen.findByRole('heading', {name: 'Task writes paused'});
  fireEvent.click(screen.getByRole('button', {name: 'Check task outcome'})); await screen.findByText(/Current task version is 3/);
  expect(screen.queryByRole('heading', {name: 'Task writes paused'})).toBeNull(); expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe('Current server title');
  expect(f.calls.filter(c => c.path === '/tasks')).toHaveLength(1);
});
it('does not mutate after intent issuance resolves following unmount', async () => {
  const f = fixture(); let resolve: (value: unknown) => void = () => {};
  const original = vi.mocked(f.api.request).getMockImplementation()!;
  vi.mocked(f.api.request).mockImplementation(async <T,>(path: string, body?: unknown, key?: string): Promise<T> => path === '/user-intents' ? await new Promise<unknown>(r => {resolve = r;}) as T : original(path, body, key) as Promise<T>);
  const view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>);
  fireEvent.change(screen.getByLabelText('Task title'), {target: {value: 'Private draft'}}); fireEvent.click(screen.getByRole('button', {name: 'Review task change'})); fireEvent.click(screen.getByRole('button', {name: 'Request task intent'})); view.unmount(); resolve({});
  await Promise.resolve(); expect(f.calls.filter(c => c.path === '/tasks')).toHaveLength(0);
});
it('switching drafts discards an issued intent and requires a new server intent', async () => {
  const f = fixture('success', true); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>); await review();
  fireEvent.click(screen.getByRole('button', {name: 'Edit Synthetic task'}));
  fireEvent.click(screen.getByRole('button', {name: 'Review task change'}));
  expect(screen.queryByRole('button', {name: 'Apply server-bound task change'})).toBeNull();
  expect(screen.getByRole('button', {name: 'Request task intent'})).toBeTruthy();
});
it('a subsequent edit sends only allowed task fields, never server metadata', async () => {
  const f = fixture(); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>); fireEvent.click(await review()); await screen.findByText('Server confirmed task TODO, version 1.');
  await review(); const intents = f.calls.filter(c => c.path === '/user-intents');
  expect(intents[1].body).toEqual({tool_id: 'task.update', target_id: id, arguments: {title: 'My unsaved title', description: '', project_id: null, due_at: null, priority: 'NORMAL', estimated_minutes: null, status: 'TODO', expected_version: 1}});
});
it.each(['\u0085', '\u00A0', '\uFEFF', '\t\n\r \u1680\u2000\u200A\u2028\u2029\u202F\u205F\u3000'])('rejects a title containing only the shared blank set: %j', title => {
  expect(valid_fields({...empty_task(), title})).toBe(false);
});
it.each(['\u200B', '\u180E', ' \u0085x\uFEFF '])('preserves valid nonblank title exactly: %j', title => {
  const value = {...task, title}; expect(read_task(value).title).toBe(title);
});
it('requires task-specific canonical lowercase UUIDs without changing unrelated domain IDs', () => {
  expect(() => read_task({...task, id: 'AAAAAAAA-1111-4111-8111-111111111111'})).toThrow();
  expect(valid_fields({...task, project_id: 'AAAAAAAA-1111-4111-8111-111111111111'})).toBe(false);
  expect(() => read_task({...task, id: 'aaaaaaaa-1111-4111-8111-111111111111'})).not.toThrow();
});
it.each(['0000-01-01T00:00:00Z', '0001-01-01T00:00:00+00:01', '9999-12-31T23:59:59-00:01', '2026-01-01T00:00:00.1234567Z'])('rejects unrepresentable task timestamp %s', value => { expect(valid_date(value)).toBe(false); });
it.each(['0001-01-01T00:00:00Z', '9999-12-31T23:59:59.999999Z', '2026-01-01t00:00:00.123456z'])('accepts bounded microsecond timestamp %s', value => { expect(valid_date(value)).toBe(true); });
it.each([
  {body: '<html>Unauthorized</html>', contentType: 'text/html'},
  {body: '{bad json', contentType: 'application/json'},
  {body: new Uint8Array([0xff, 0xfe]), contentType: 'application/json'},
])('all HTTP401 responses invalidate CSRF before body parsing: $contentType', async ({body, contentType}) => {
  const fetcher = vi.fn().mockResolvedValue(new Response(body, {status: 401, headers: {'content-type': contentType}})); vi.stubGlobal('fetch', fetcher);
  const api = new Api(); api.set_csrf('private-token');
  await expect(api.request('/tasks', {})).rejects.toMatchObject({status: 401, uncertain: false});
  await expect(api.request('/tasks', {})).rejects.toMatchObject({code: 'CSRF_REQUIRED'}); expect(fetcher).toHaveBeenCalledOnce();
});
it('malformed non401 parsing errors retain observed HTTP status', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new Uint8Array([0xff]), {status: 503, headers: {'content-type': 'application/json'}})));
  const api = new Api(); api.set_csrf('token'); await expect(api.request('/tasks', {})).rejects.toMatchObject({status: 503, uncertain: true});
});
it('an uncertain ApiError with HTTP401 still erases private state instead of entering reconciliation', async () => {
  const f = fixture('success', true); const original = vi.mocked(f.api.request).getMockImplementation()!;
  vi.mocked(f.api.request).mockImplementation(async <T,>(path: string, body?: unknown, key?: string, method?: 'POST' | 'PATCH'): Promise<T> => { if (body && path === '/tasks') throw new ApiError('INVALID_RESPONSE', true, 401); return original(path, body, key, method) as Promise<T>; });
  render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>); fireEvent.click(await review());
  await waitFor(() => expect(f.unauthorized).toHaveBeenCalledOnce());
  expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe(''); expect(screen.queryByRole('heading', {name: 'Task writes paused'})).toBeNull(); expect(screen.queryByText('Synthetic task')).toBeNull();
});
it('rejects title NUL before issuing an intent while leaving encrypted description unchanged', async () => {
  expect(valid_fields({...empty_task(), title: 'Title\u0000text'})).toBe(false);
  expect(valid_fields({...empty_task(), title: 'Title', description: 'Encrypted\u0000description'})).toBe(true);
  const f = fixture(); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>);
  fireEvent.change(screen.getByLabelText('Task title'), {target: {value: 'Title\u0000text'}});
  fireEvent.click(screen.getByRole('button', {name: 'Review task change'}));
  await screen.findByText(/Check title/); expect(f.calls.some(c => c.path === '/user-intents')).toBe(false);
});
it('accepts distinct microsecond range bounds that share the same millisecond', async () => {
  const f = fixture(); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>);
  fireEvent.change(screen.getByLabelText('Due from (inclusive RFC3339)'), {target: {value: '2026-10-10T09:00:00.000001Z'}});
  fireEvent.change(screen.getByLabelText('Due to (exclusive RFC3339)'), {target: {value: '2026-10-10T18:00:00.000002+09:00'}});
  fireEvent.click(screen.getByRole('button', {name: 'Apply task filters'}));
  await waitFor(() => expect(f.calls.some(c => c.path.includes('from=2026-10-10T09%3A00%3A00.000001Z') && c.path.includes('to=2026-10-10T18%3A00%3A00.000002%2B09%3A00'))).toBe(true));
  expect(screen.queryByText(/from must precede to/)).toBeNull();
});
