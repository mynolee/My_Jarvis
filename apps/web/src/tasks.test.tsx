import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { TasksPanel } from './TasksPanel';
import type { StudyReviewCopy } from './study-review-task';
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

const copyProject = '33333333-3333-4333-8333-333333333333';
function reviewCopy(project_id: string | null = copyProject): StudyReviewCopy {
  return { ui_id: '44444444-4444-4444-8444-444444444444', fields: { title: 'Review study question 1', description: '- Synthetic copied checklist\n- Compare the two assumptions', project_id, due_at: null, priority: 'NORMAL', estimated_minutes: null }, origin: { conversation_id: '55555555-5555-4555-8555-555555555555', project_id, study_session_id: '66666666-6666-4666-8666-666666666666', lesson_run_id: '77777777-7777-4777-8777-777777777777', question_id: '88888888-8888-4888-8888-888888888888', input_id: '99999999-9999-4999-8999-999999999999', attempt_id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', feedback_request_id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', feedback_version: 3, study_version: 3, conversation_version: 7, study_checked_at: '2026-10-10T09:00:01.000001Z', feedback_checked_at: '2026-10-10T09:00:01.000002Z', feedback_updated_at: '2026-10-10T09:00:00Z', selected_indexes: [0, 2] } };
}
function deferred<T>() { let resolve!: (value: T) => void, reject!: (reason: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function copyFixture(options: { intent?: (body: unknown) => unknown | Promise<unknown>; mutation?: (body: unknown) => unknown | Promise<unknown>; receipt?: () => unknown | Promise<unknown>; current?: () => unknown | Promise<unknown> } = {}) {
  const f = fixture(), original = vi.mocked(f.api.request).getMockImplementation()!;
  vi.mocked(f.api.request).mockImplementation(async <T,>(path: string, body?: unknown, key?: string, method?: 'POST' | 'PATCH'): Promise<T> => {
    if (path === '/user-intents' && options.intent) { f.calls.push({path, body, key, method}); return await options.intent(body) as T; }
    if (path === '/tasks' && body && options.mutation) { f.calls.push({path, body, key, method}); return await options.mutation(body) as T; }
    if (path.startsWith('/task-mutations/') && options.receipt) { f.calls.push({path, body, key, method}); return await options.receipt() as T; }
    if (path === `/tasks/${id}` && !body && options.current) { f.calls.push({path, body, key, method}); return await options.current() as T; }
    return original(path, body, key, method) as Promise<T>;
  });
  return f;
}
function intentFor(body: unknown) { const b = body as {tool_id: string; arguments: unknown}; return {id: intentId, tool_id: b.tool_id, target_id: id, arguments: b.arguments, status: 'ISSUED', payload_hash: 'a'.repeat(64), expires_at: '2099-01-01T00:00:00Z'}; }
function taskFor(body: unknown): Task { const {user_intent_id: _intent, ...fields} = body as Record<string, unknown>; return {...task, ...fields} as Task; }
async function importReview() { fireEvent.click(await screen.findByRole('button', {name: 'Use this review draft'})); }
async function requestCopyIntent() { fireEvent.click(screen.getByRole('button', {name: 'Review task change'})); fireEvent.click(screen.getByRole('button', {name: 'Request task intent'})); return screen.findByRole('button', {name: 'Apply server-bound task change'}); }
function expectCopyCleared(copy: StudyReviewCopy) {
  expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe('');
  expect((screen.getByLabelText('Task description') as HTMLTextAreaElement).value).toBe('');
  expect(screen.queryByRole('region', {name: 'Source read when copied'})).toBeNull();
  expect(screen.queryByRole('button', {name: 'Apply server-bound task change'})).toBeNull();
  expect(document.body.textContent).not.toContain(copy.fields.description);
  expect(document.body.textContent).not.toContain(copy.origin.feedback_request_id);
}
it.each(['dirty', 'review', 'issued', 'conflict'] as const)('incoming copy preserves an existing %s until deliberate replacement', async state => {
  const f = fixture(state === 'conflict' ? 'conflict' : 'success', true), copy = reviewCopy(), consumed = vi.fn(), dismissed = vi.fn();
  const view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} onConsumeReviewCopy={consumed} onDismissReviewCopy={dismissed}/>);
  fireEvent.click(await screen.findByRole('button', {name: 'Edit Synthetic task'}));
  fireEvent.change(screen.getByLabelText('Task title'), {target: {value: 'Keep my personal edit'}});
  if (state !== 'dirty') fireEvent.click(screen.getByRole('button', {name: 'Review task change'}));
  if (state === 'issued' || state === 'conflict') { fireEvent.click(screen.getByRole('button', {name: 'Request task intent'})); const apply = await screen.findByRole('button', {name: 'Apply server-bound task change'}); if (state === 'conflict') { fireEvent.click(apply); await screen.findByRole('region', {name: 'Task version conflict'}); } }
  const before = f.calls.filter(c => c.body).length;
  view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy} onConsumeReviewCopy={consumed} onDismissReviewCopy={dismissed}/>);
  await screen.findByRole('region', {name: 'Incoming study review copy'});
  expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe('Keep my personal edit');
  if (state === 'issued') expect(screen.getByRole('button', {name: 'Apply server-bound task change'})).toBeTruthy();
  if (state === 'conflict') expect(screen.getByRole('region', {name: 'Task version conflict'})).toBeTruthy();
  expect(f.calls.filter(c => c.body)).toHaveLength(before);
  fireEvent.click(screen.getByRole('button', {name: 'Replace my current unsaved draft with this copy'}));
  expect(consumed).toHaveBeenCalledExactlyOnceWith(copy.ui_id); expect(dismissed).not.toHaveBeenCalled();
  expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe(copy.fields.title);
  expect(screen.queryByRole('region', {name: 'Task version conflict'})).toBeNull();
  expect(screen.queryByRole('button', {name: 'Apply server-bound task change'})).toBeNull();
  expect(screen.queryByLabelText('Task status')).toBeNull(); expect(f.calls.filter(c => c.body)).toHaveLength(before);
  view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={{...copy}} onConsumeReviewCopy={consumed}/>);
  expect(screen.queryByRole('region', {name: 'Incoming study review copy'})).toBeNull(); expect(consumed).toHaveBeenCalledOnce();
});
it('dismissing an incoming copy preserves the current draft and makes no write', async () => {
  const f = copyFixture(), copy = reviewCopy(), dismiss = vi.fn(); const view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized}/>);
  fireEvent.change(screen.getByLabelText('Task title'), {target: {value: 'Personal draft'}});
  view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy} onDismissReviewCopy={dismiss}/>);
  fireEvent.click(await screen.findByRole('button', {name: 'Dismiss review copy'}));
  expect(dismiss).toHaveBeenCalledExactlyOnceWith(copy.ui_id); expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe('Personal draft'); expect(f.calls.filter(c => c.body)).toHaveLength(0);
});
it.each([copyProject, null])('edits all six imported create fields while preserving source project %s and separate intent/apply', async project => {
  const f = copyFixture(), copy = reviewCopy(project); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview();
  expect(screen.getByText(/Requesting an intent stores the draft in task records; Apply creates the task/)).toBeTruthy();
  expect(screen.getByText(/Deleting the study conversation does not erase these task records/)).toBeTruthy();
  expect((screen.getByLabelText('Task deadline (RFC3339, optional)') as HTMLInputElement).value).toBe('');
  expect((screen.getByLabelText('Task priority') as HTMLSelectElement).value).toBe('NORMAL');
  expect((screen.getByLabelText('Estimated minutes (optional)') as HTMLInputElement).value).toBe('');
  fireEvent.change(screen.getByLabelText('Task title'), {target: {value: 'Edited title'}});
  fireEvent.change(screen.getByLabelText('Task description'), {target: {value: 'Edited copied text'}});
  fireEvent.change(screen.getByLabelText('Task deadline (RFC3339, optional)'), {target: {value: '2026-11-01T09:00:00.123456+09:00'}});
  fireEvent.change(screen.getByLabelText('Task priority'), {target: {value: 'HIGH'}});
  fireEvent.change(screen.getByLabelText('Estimated minutes (optional)'), {target: {value: '17'}});
  const apply = await requestCopyIntent(); const args = {title: 'Edited title', description: 'Edited copied text', project_id: project, due_at: '2026-11-01T09:00:00.123456+09:00', priority: 'HIGH', estimated_minutes: 17};
  expect(f.calls.filter(c => c.body)).toHaveLength(1); expect(f.calls.find(c => c.path === '/user-intents')?.body).toEqual({tool_id: 'task.create', target_id: null, arguments: args});
  const source = screen.getByRole('region', {name: 'Source read when copied'}); expect(source.textContent).toContain(copy.origin.feedback_request_id); expect(source.textContent).toContain(copy.origin.feedback_checked_at);
  fireEvent.click(apply); fireEvent.click(apply); await screen.findByText('Server confirmed task TODO, version 1.');
  const writes = f.calls.filter(c => c.body); expect(writes).toHaveLength(2); expect(writes[1].body).toEqual({user_intent_id: intentId, ...args}); expect(writes[1].key).not.toBe(writes[0].key); expect(writes.every(c => c.key !== copy.ui_id)).toBe(true);
  expect(screen.queryByRole('region', {name: 'Source read when copied'})).toBeNull(); expect(writes.some(c => c.method === 'PATCH')).toBe(false);
});
it('cancel/back and explicit discard never claim that an issued copy record was erased', async () => {
  const f = copyFixture(), copy = reviewCopy(); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); await requestCopyIntent();
  fireEvent.click(screen.getByRole('button', {name: 'Back to draft'})); expect(screen.getByRole('alert').textContent).toContain('no task change'); expect(screen.getByRole('alert').textContent).toContain('intent remains in task records');
  fireEvent.click(screen.getByRole('button', {name: 'Discard review task draft'})); expectCopyCleared(copy); expect(f.calls.filter(c => c.body)).toHaveLength(1);
});
it('accepts equivalent deadline instants at microsecond precision for an imported create', async () => {
  const f = copyFixture({mutation: body => ({...taskFor(body), due_at: '2026-11-01T00:00:00.123456Z'})}); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={reviewCopy()}/>); await importReview();
  fireEvent.change(screen.getByLabelText('Task deadline (RFC3339, optional)'), {target: {value: '2026-11-01T09:00:00.123456+09:00'}});
  fireEvent.click(await requestCopyIntent()); await screen.findByText('Server confirmed task TODO, version 1.');
});
it.each([
  ['wrong target', {id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'}], ['wrong title', {title: 'Changed title'}], ['wrong description', {description: 'Changed description'}], ['wrong project', {project_id: null}], ['wrong due instant', {due_at: '2026-11-01T00:00:00.123457Z'}], ['wrong priority', {priority: 'HIGH'}], ['wrong estimate', {estimated_minutes: 18}], ['DONE status', {status: 'DONE'}], ['newer version', {version: 2}], ['malformed body', {created_at: 'not a date'}],
] as const)('unverified imported mutation %s retains original recovery and drops copy prose', async (_name, changed) => {
  const copy = reviewCopy(), f = copyFixture({mutation: body => ({...taskFor(body), ...changed})}); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview();
  fireEvent.change(screen.getByLabelText('Task deadline (RFC3339, optional)'), {target: {value: '2026-11-01T09:00:00.123456+09:00'}}); fireEvent.click(await requestCopyIntent());
  await screen.findByRole('heading', {name: 'Task writes paused'}); expectCopyCleared(copy); expect(screen.queryByText('Server confirmed task TODO, version 1.')).toBeNull();
  fireEvent.click(screen.getByRole('button', {name: 'Check task outcome'})); await screen.findByText(/A missing task is not proof of failure/);
  const writes = f.calls.filter(c => c.path === '/tasks'); expect(writes).toHaveLength(1); expect(f.calls.find(c => c.path.startsWith('/task-mutations/'))?.path).toBe(`/task-mutations/${writes[0].key}`);
});
it.each(['malformed', '503', 'transport'] as const)('imported intent %s is fenced UNKNOWN with no task write or automatic retry', async kind => {
  const copy = reviewCopy(), f = copyFixture({intent: () => { if (kind === 'malformed') return {}; if (kind === '503') throw new ApiError('UNAVAILABLE', false, 503); throw new TypeError('network'); }});
  render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); fireEvent.click(screen.getByRole('button', {name: 'Review task change'})); fireEvent.click(screen.getByRole('button', {name: 'Request task intent'}));
  await screen.findByRole('heading', {name: 'Task writes paused'}); expectCopyCleared(copy); fireEvent.click(screen.getByRole('button', {name: 'Check task outcome'})); await screen.findByText(/No task mutation was sent by this page/);
  expect(f.calls.filter(c => c.body)).toHaveLength(1); expect(f.calls.some(c => c.path.startsWith('/task-mutations/'))).toBe(false); expect((screen.getByRole('button', {name: 'New personal task'}) as HTMLButtonElement).disabled).toBe(true);
});
it.each(['valid', 'unverified'] as const)('late %s imported intent after genuine disposal cannot restore draft or Apply', async outcome => {
  const held = deferred<unknown>(), copy = reviewCopy(), f = copyFixture({intent: () => held.promise}), pending = vi.fn();
  const view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy} reviewCopyGeneration={0} onPendingChange={pending}/>); await importReview();
  fireEvent.click(screen.getByRole('button', {name: 'Review task change'})); fireEvent.click(screen.getByRole('button', {name: 'Request task intent'}));
  expect(pending).toHaveBeenLastCalledWith(true); const intentRequest = f.calls.find(c => c.path === '/user-intents')!;
  view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} reviewCopyGeneration={1} onPendingChange={pending}/>); expectCopyCleared(copy);
  await act(async () => { held.resolve(outcome === 'valid' ? intentFor(intentRequest.body) : {}); await held.promise; });
  expectCopyCleared(copy); expect(f.calls.filter(c => c.body)).toHaveLength(1);
  if (outcome === 'valid') { expect(screen.getByText(/Server confirmed an intent was recorded/)).toBeTruthy(); expect(screen.queryByRole('heading', {name: 'Task writes paused'})).toBeNull(); expect(pending).toHaveBeenLastCalledWith(false); }
  else { expect(screen.getByRole('heading', {name: 'Task writes paused'})).toBeTruthy(); expect(pending).toHaveBeenLastCalledWith(true); expect(screen.getByText(`Request: ${intentRequest.key}`)).toBeTruthy(); }
});
it.each(['valid', 'wrong arguments', 'DONE', 'version 2'] as const)('late imported Apply %s is checked under original operation kind after source marker disposal', async outcome => {
  const held = deferred<unknown>(), copy = reviewCopy(), f = copyFixture({mutation: () => held.promise}); const view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); fireEvent.click(await requestCopyIntent());
  const mutation = f.calls.find(c => c.path === '/tasks')!; view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} reviewCopyGeneration={1}/>); expectCopyCleared(copy);
  const raw = taskFor(mutation.body); if (outcome === 'wrong arguments') raw.description = 'Changed'; if (outcome === 'DONE') raw.status = 'DONE'; if (outcome === 'version 2') raw.version = 2;
  await act(async () => { held.resolve(raw); await held.promise; }); expectCopyCleared(copy); expect(f.calls.filter(c => c.path === '/tasks')).toHaveLength(1);
  if (outcome === 'valid') { expect(screen.getByText(/Server confirmed independent task/)).toBeTruthy(); expect(screen.queryByRole('heading', {name: 'Task writes paused'})).toBeNull(); }
  else { expect(screen.getByRole('heading', {name: 'Task writes paused'})).toBeTruthy(); expect(screen.getByText(`Request: ${mutation.key}`)).toBeTruthy(); }
});
it('same-generation rerenders and own pending transitions preserve held intent and Apply arguments', async () => {
  const intent = deferred<unknown>(), mutation = deferred<unknown>(), copy = reviewCopy(), f = copyFixture({intent: () => intent.promise, mutation: () => mutation.promise});
  const view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy} reviewCopyGeneration={0}/>); await importReview(); fireEvent.click(screen.getByRole('button', {name: 'Review task change'})); fireEvent.click(screen.getByRole('button', {name: 'Request task intent'}));
  view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} reviewCopyGeneration={0} writesPaused/>);
  expect((screen.getByLabelText('Task description') as HTMLTextAreaElement).value).toBe(copy.fields.description);
  await act(async () => { intent.resolve(intentFor(f.calls.find(c => c.path === '/user-intents')!.body)); await intent.promise; });
  view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} reviewCopyGeneration={0}/>); fireEvent.click(screen.getByRole('button', {name: 'Apply server-bound task change'}));
  view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} reviewCopyGeneration={0} writesPaused/>);
  expect((screen.getByLabelText('Task description') as HTMLTextAreaElement).value).toBe(copy.fields.description); expect((screen.getByRole('button', {name: 'New personal task'}) as HTMLButtonElement).disabled).toBe(true);
  await act(async () => { mutation.resolve(taskFor(f.calls.find(c => c.path === '/tasks')!.body)); await mutation.promise; });
  await screen.findByText('Server confirmed task TODO, version 1.'); expect(f.calls.filter(c => c.body)).toHaveLength(2);
});
it.each([
  ['extra envelope field', {status: 'COMMITTED', task, source: 'unexpected'}], ['wrong target', {status: 'COMMITTED', task: {...task, id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'}}], ['DONE receipt', {status: 'COMMITTED', task: {...task, status: 'DONE'}}], ['version 2 receipt', {status: 'COMMITTED', task: {...task, version: 2}}], ['missing task', {status: 'COMMITTED'}], ['array envelope', [{status: 'COMMITTED', task}]], ['NOT_OBSERVED with task', {status: 'NOT_OBSERVED', task}], ['null receipt', null],
] as const)('imported reconciliation rejects %s without current read or unlocking', async (_name, receipt) => {
  const copy = reviewCopy(), f = copyFixture({mutation: () => {throw new ApiError('UNKNOWN', true);}, receipt: () => receipt, current: () => task}); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); fireEvent.click(await requestCopyIntent()); await screen.findByRole('heading', {name: 'Task writes paused'});
  fireEvent.click(screen.getByRole('button', {name: 'Check task outcome'})); await screen.findByText('Task request could not be verified.');
  expectCopyCleared(copy); expect(screen.getByRole('heading', {name: 'Task writes paused'})).toBeTruthy(); expect(f.calls.filter(c => c.path === `/tasks/${id}`)).toHaveLength(0); expect(f.calls.filter(c => c.path === '/tasks')).toHaveLength(1);
});
it('imported original TODO/v1 receipt may reconcile separately to an ordinary newer DONE task', async () => {
  const copy = reviewCopy(), f = copyFixture({mutation: () => {throw new ApiError('UNKNOWN', true);}, receipt: () => ({status: 'COMMITTED', task: {...task, ...copy.fields}}), current: () => ({...task, title: 'Owner edited task', status: 'DONE', version: 4})});
  const view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); fireEvent.click(await requestCopyIntent()); await screen.findByRole('heading', {name: 'Task writes paused'});
  view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} reviewCopyGeneration={1}/>); fireEvent.click(screen.getByRole('button', {name: 'Check task outcome'})); await screen.findByText(/committed version 1. Current task version is 4/);
  expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe('Owner edited task'); expect((screen.getByLabelText('Task status') as HTMLSelectElement).value).toBe('DONE'); expect(screen.queryByRole('heading', {name: 'Task writes paused'})).toBeNull(); expect(screen.queryByRole('region', {name: 'Source read when copied'})).toBeNull();
  expect(f.calls.filter(c => c.body)).toHaveLength(2); expect(f.calls.slice(-3, -1).map(c => c.path)).toEqual([expect.stringMatching(/^\/task-mutations\//), `/tasks/${id}`]);
});
it.each(['404', '503', 'wrong target', 'malformed'])('imported committed receipt with current-task %s remains pending', async kind => {
  const copy = reviewCopy(), f = copyFixture({mutation: () => {throw new ApiError('UNKNOWN', true);}, receipt: () => ({status: 'COMMITTED', task}), current: () => {if (kind === '404' || kind === '503') throw new ApiError('UNAVAILABLE', false, Number(kind)); return kind === 'wrong target' ? {...task, id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'} : {};}});
  render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); fireEvent.click(await requestCopyIntent()); await screen.findByRole('heading', {name: 'Task writes paused'}); fireEvent.click(screen.getByRole('button', {name: 'Check task outcome'}));
  await waitFor(() => expect(f.calls.some(c => c.path === `/tasks/${id}`)).toBe(true)); expect(screen.getByRole('heading', {name: 'Task writes paused'})).toBeTruthy(); expectCopyCleared(copy); expect(f.calls.filter(c => c.path === '/tasks')).toHaveLength(1);
});
it('a genuine boundary while reconciliation waits does not reinstall discarded copy prose', async () => {
  const held = deferred<unknown>(), copy = reviewCopy(), f = copyFixture({mutation: () => {throw new ApiError('UNKNOWN', true);}, receipt: () => ({status: 'COMMITTED', task}), current: () => held.promise});
  const view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); fireEvent.click(await requestCopyIntent()); await screen.findByRole('heading', {name: 'Task writes paused'}); fireEvent.click(screen.getByRole('button', {name: 'Check task outcome'}));
  await waitFor(() => expect(f.calls.some(c => c.path === `/tasks/${id}`)).toBe(true)); view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} reviewCopyGeneration={1}/>);
  await act(async () => { held.resolve({...task, ...copy.fields}); await held.promise; }); expectCopyCleared(copy); expect(screen.queryByRole('heading', {name: 'Task writes paused'})).toBeNull(); expect(screen.queryByRole('region', {name: 'Observed server task'})).toBeNull();
});
it.each(['generation', 'pagehide', 'API replacement'] as const)('%s clears incoming and uncommitted copied text without creating a task', async boundary => {
  const f = copyFixture(), copy = reviewCopy(), other = copyFixture(); const view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); await requestCopyIntent();
  if (boundary === 'pagehide') act(() => {window.dispatchEvent(new Event('pagehide'));}); else view.rerender(<TasksPanel api={boundary === 'API replacement' ? other.api : f.api} onUnauthorized={f.unauthorized} reviewCopyGeneration={1}/>);
  expectCopyCleared(copy); expect(f.calls.filter(c => c.path === '/tasks')).toHaveLength(0);
});
it('a confirmed independent task survives later source invalidation and uses explicit ordinary status edit', async () => {
  const copy = reviewCopy(), f = copyFixture(), view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); fireEvent.click(await requestCopyIntent()); await screen.findByText('Server confirmed task TODO, version 1.');
  view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} reviewCopyGeneration={1}/>); expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe(copy.fields.title);
  fireEvent.change(screen.getByLabelText('Task status'), {target: {value: 'DONE'}}); fireEvent.click(await requestCopyIntent()); await screen.findByText('Server confirmed task DONE, version 1.');
  expect(f.calls.filter(c => c.method === 'PATCH')).toHaveLength(1); expect(f.calls.find(c => c.method === 'PATCH')?.body).toMatchObject({status: 'DONE', expected_version: 1}); expect(f.calls.some(c => /study|feedback|inference|messages/.test(c.path))).toBe(false);
});
it('new personal task discards imported context and does not reuse its reviewed intent', async () => {
  const copy = reviewCopy(), f = copyFixture(); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); await requestCopyIntent(); fireEvent.click(screen.getByRole('button', {name: 'New personal task'})); expectCopyCleared(copy);
  fireEvent.change(screen.getByLabelText('Task title'), {target: {value: 'An ordinary task'}}); fireEvent.click(await requestCopyIntent()); await screen.findByText('Server confirmed task TODO, version 1.');
  expect(f.calls.filter(c => c.path === '/user-intents')).toHaveLength(2); expect(f.calls.find(c => c.path === '/tasks')?.body).toMatchObject({title: 'An ordinary task', project_id: null});
});
it('paused admission and UNKNOWN reject new copy offers without resetting pending identity', async () => {
  const copy = reviewCopy(), f = copyFixture({intent: () => ({})}), dismissed = vi.fn(); const view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy} writesPaused onDismissReviewCopy={dismissed}/>);
  await waitFor(() => expect(dismissed).toHaveBeenCalledExactlyOnceWith(copy.ui_id)); expect(screen.queryByRole('region', {name: 'Incoming study review copy'})).toBeNull(); expect(f.calls.filter(c => c.body)).toHaveLength(0);
  const another = {...copy, ui_id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'}; view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={another} onDismissReviewCopy={dismissed}/>); await importReview(); fireEvent.click(screen.getByRole('button', {name: 'Review task change'})); fireEvent.click(screen.getByRole('button', {name: 'Request task intent'})); await screen.findByRole('heading', {name: 'Task writes paused'});
  const request = f.calls.find(c => c.path === '/user-intents')!; view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={{...copy, ui_id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'}} onDismissReviewCopy={dismissed}/>);
  await waitFor(() => expect(dismissed).toHaveBeenLastCalledWith('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee')); expect(screen.getByText(`Request: ${request.key}`)).toBeTruthy(); expect(f.calls.filter(c => c.body)).toHaveLength(1);
});
it('no admitted imported write begins when the shared pending domain refuses acquisition', async () => {
  const f = copyFixture(); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={reviewCopy()} onAcquirePending={() => false}/>); await importReview(); fireEvent.click(screen.getByRole('button', {name: 'Review task change'})); fireEvent.click(screen.getByRole('button', {name: 'Request task intent'})); expect(f.calls.filter(c => c.body)).toHaveLength(0);
});
it('retired API issuance and its late 401 cannot install copied content or invalidate the new API', async () => {
  const held = deferred<unknown>(), f = copyFixture({intent: () => held.promise}), next = copyFixture(), copy = reviewCopy(); const view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); fireEvent.click(screen.getByRole('button', {name: 'Review task change'})); fireEvent.click(screen.getByRole('button', {name: 'Request task intent'}));
  view.rerender(<TasksPanel api={next.api} onUnauthorized={next.unauthorized}/>); await act(async () => {held.reject(new ApiError('UNAUTHENTICATED', false, 401)); await held.promise.catch(() => {});}); expectCopyCleared(copy); expect(f.unauthorized).not.toHaveBeenCalled(); expect(next.unauthorized).not.toHaveBeenCalled(); expect(f.calls.filter(c => c.path === '/tasks')).toHaveLength(0);
});
it('copied text and source IDs remain inert and never enter persistence, URL or logging sinks', async () => {
  const copy = reviewCopy(); copy.fields.description = '- <img src=x onerror=alert(1)> https://example.invalid/do-not-open\n- Synthetic copied checklist';
  const storage = vi.spyOn(Storage.prototype, 'setItem'), push = vi.spyOn(history, 'pushState'), replace = vi.spyOn(history, 'replaceState'), open = vi.fn(), cacheOpen = vi.fn(); vi.stubGlobal('indexedDB', {open}); vi.stubGlobal('caches', {open: cacheOpen});
  const log = vi.spyOn(console, 'log'), info = vi.spyOn(console, 'info'), warn = vi.spyOn(console, 'warn');
  const f = copyFixture(), view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); await requestCopyIntent(); expect(document.querySelector('img')).toBeNull(); expect(document.querySelector('a[href="https://example.invalid/do-not-open"]')).toBeNull();
  view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} reviewCopyGeneration={1}/>); expectCopyCleared(copy);
  for (const sink of [storage, push, replace, open, cacheOpen, log, info, warn]) expect(sink).not.toHaveBeenCalled();
  storage.mockRestore(); push.mockRestore(); replace.mockRestore(); log.mockRestore(); info.mockRestore(); warn.mockRestore();
});
it.each([400, 403, 409])('verified imported HTTP %s rejection retains the original project without UNKNOWN or substitution', async status => {
  const f = copyFixture({intent: () => {throw new ApiError('PROJECT_NOT_AVAILABLE', false, status);}}), copy = reviewCopy(); render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); fireEvent.click(screen.getByRole('button', {name: 'Review task change'})); fireEvent.click(screen.getByRole('button', {name: 'Request task intent'}));
  await screen.findByText('PROJECT_NOT_AVAILABLE'); expect(screen.queryByRole('heading', {name: 'Task writes paused'})).toBeNull(); expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe(copy.fields.title); expect(screen.getByText(`Project: ${copyProject}. To reopen, select TODO or IN_PROGRESS and review.`)).toBeTruthy(); expect(f.calls.filter(c => c.body)).toHaveLength(1);
});
it('a verified rejection after disposal remains content-free and does not fence a known noncommit', async () => {
  const held = deferred<unknown>(), f = copyFixture({intent: () => held.promise}), copy = reviewCopy(), view = render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy}/>); await importReview(); fireEvent.click(screen.getByRole('button', {name: 'Review task change'})); fireEvent.click(screen.getByRole('button', {name: 'Request task intent'})); view.rerender(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} reviewCopyGeneration={1}/>);
  await act(async () => {held.reject(new ApiError('PROJECT_NOT_AVAILABLE', false, 409)); await held.promise.catch(() => {});}); expectCopyCleared(copy); expect(screen.getByText('PROJECT_NOT_AVAILABLE')).toBeTruthy(); expect(screen.queryByRole('heading', {name: 'Task writes paused'})).toBeNull();
});
it('synchronous source-owner refusal prevents a captured incoming handler from installing any copied draft', async () => {
  const f = copyFixture(), copy = reviewCopy(); let sourceCurrent = true; const consume = vi.fn(() => sourceCurrent);
  render(<TasksPanel api={f.api} onUnauthorized={f.unauthorized} incomingReviewCopy={copy} onConsumeReviewCopy={consume}/>);
  const use = await screen.findByRole('button', {name: 'Use this review draft'}); sourceCurrent = false; fireEvent.click(use);
  expect(consume).toHaveBeenCalledExactlyOnceWith(copy.ui_id); expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe(''); expect((screen.getByLabelText('Task description') as HTMLTextAreaElement).value).toBe(''); expect(screen.queryByRole('button', {name: 'Discard review task draft'})).toBeNull(); expect(f.calls.filter(c => c.body)).toHaveLength(0);
  sourceCurrent = true; fireEvent.click(use); expect((screen.getByLabelText('Task title') as HTMLInputElement).value).toBe(copy.fields.title); expect(consume).toHaveBeenCalledTimes(2);
});
