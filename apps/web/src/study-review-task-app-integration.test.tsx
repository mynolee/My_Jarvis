import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { Api, ApiError } from './api';
import type { Task, TaskArgs } from './tasks';
import { acceptedFeedback, appFixture, deferred, projection } from '../tests/study-feedback-fixtures';
import { at, cid, other, run, sid } from '../tests/study-fixtures';
vi.mock('./TodayPanel', () => ({ TodayPanel: () => <section aria-label="Synthetic Today"/> }));
vi.mock('./conversation-history-events', () => ({ watch_history_events: () => () => {} }));
// Named export-admission probe; real App and task/study panels retain their own boundaries.
vi.mock('./MemoryExportPanel', () => ({ MemoryExportPanel: (props: { onAcquirePending: () => boolean; onPendingChange: (pending: boolean) => void }) => <section aria-label="Synthetic export admission"><button onClick={() => { if (props.onAcquirePending()) props.onPendingChange(true); }}>Hold synthetic export boundary</button><button onClick={() => props.onPendingChange(false)}>Release synthetic export boundary</button></section> }));
const taskId = 'd1111111-1111-4111-8111-111111111111', intentId = 'd2222222-2222-4222-8222-222222222222';
const sourceProject = 'd3333333-3333-4333-8333-333333333333', nextProject = 'd4444444-4444-4444-8444-444444444444';
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name }), click = (name: string) => fireEvent.click(button(name));
const writes = (f: ReturnType<typeof fixture>) => f.request.mock.calls.filter(([, body]) => body !== undefined);
function issued(args: TaskArgs) { return { id: intentId, tool_id: 'task.create', target_id: taskId, arguments: args, status: 'ISSUED', payload_hash: 'a'.repeat(64), expires_at: '2099-01-01T00:00:00Z' }; }
function fixture(project: string | null = null) {
  const f = appFixture(); f.state.feedback = projection(acceptedFeedback()); f.state.current.conversation_content_version = 17; f.state.current.project_id = project;
  f.state.extra['/conversations'] = { items: [{ id: cid, title: 'Feedback conversation', project_id: project, created_at: at }, { id: other, title: 'Other conversation', project_id: null, created_at: at }], next_cursor: null };
  const state = { ...f.state, task: null as Task | null, intentRead: null as Promise<unknown> | null, taskWrite: null as Promise<unknown> | null, copyStudy: null as Promise<unknown> | null, receipt: { status: 'NOT_OBSERVED', task: null } as unknown, taskRead: null as Promise<unknown> | null };
  // Keep the original fixture's mutable fields; the extra task transport is independently synthetic.
  const original = f.request.getMockImplementation()!;
  f.request.mockImplementation((async (path: string, body?: unknown, key?: string, method?: 'POST' | 'PATCH') => {
    if (path.startsWith('/tasks?')) return { items: state.task ? [state.task] : [], next_cursor: null };
    if (path === '/user-intents') { if (state.intentRead) return state.intentRead; const b = body as { arguments: TaskArgs; tool_id: string }; return { ...issued(b.arguments), tool_id: b.tool_id }; }
    if ((path === '/tasks' || path === `/tasks/${taskId}`) && body !== undefined) {
      if (state.taskWrite) return state.taskWrite;
      const { user_intent_id: _intent, expected_version: _version, ...fields } = body as Record<string, unknown>;
      state.task = { ...state.task, id: taskId, status: 'TODO', ...fields, version: (state.task?.version ?? 0) + 1, created_at: at, updated_at: at } as Task; return state.task;
    }
    if (path.startsWith('/task-mutations/')) return state.receipt;
    if (path === `/tasks/${taskId}`) { if (state.taskRead) return state.taskRead; if (state.task) return state.task; throw new ApiError('NOT_FOUND', false, 404); }
    if (path === `/study-sessions/${sid}` && state.copyStudy) return state.copyStudy;
    return original(path, body, key, method);
  }) as Api['request']);
  return { ...f, taskState: state };
}
async function open(f = fixture()) {
  const view = render(<App api={f.api}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); await screen.findByText('No saved messages.'); await selectStudy(); return { ...f, ...view };
}
async function selectStudy() { await waitFor(() => expect(button('Find saved study lessons').disabled).toBe(false)); click('Find saved study lessons'); await screen.findByRole('button', { name: `Resume lesson ${run}` }); click(`Resume lesson ${run}`); await screen.findByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' }); }
async function copy() { fireEvent.click(screen.getByRole('checkbox', { name: 'Include review item 1 from question 1 in task draft' })); click('Copy selected items to task draft'); await screen.findByRole('region', { name: 'Incoming study review copy' }); }
async function importCopy() { await copy(); click('Use this review draft'); await screen.findByDisplayValue('Review study question 1'); }
async function intent() { click('Review task change'); click('Request task intent'); await screen.findByRole('button', { name: 'Apply server-bound task change' }); await waitFor(() => expect(button('Other conversation').disabled).toBe(false)); }
async function login() { await waitFor(() => expect(button('Sign in').disabled).toBe(false)); fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'synthetic-owner' } }); fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } }); click('Sign in'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); await screen.findByText('No saved messages.'); }
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it('real App holds original imported review across its own intent and Apply pending-domain purges', async () => {
  const f = await open(), before = f.request.mock.calls.length; await importCopy();
  expect(f.request.mock.calls.slice(before).filter(([path]) => path.startsWith('/study-sessions/')).map(([path]) => path)).toEqual([`/study-sessions/${sid}`, `/study-sessions/${sid}/feedback`]); expect(writes(f)).toHaveLength(0);
  fireEvent.change(screen.getByLabelText('Task title'), { target: { value: 'My deliberate review title' } }); fireEvent.change(screen.getByLabelText('Task description'), { target: { value: 'Edited independent checklist' } }); fireEvent.change(screen.getByLabelText('Task deadline (RFC3339, optional)'), { target: { value: '2026-10-12T09:30:00.123456+09:00' } }); fireEvent.change(screen.getByLabelText('Task priority'), { target: { value: 'LOW' } }); fireEvent.change(screen.getByLabelText('Estimated minutes (optional)'), { target: { value: '15' } });
  click('Review task change'); expect(screen.getByRole('region', { name: 'Source read when copied' })).toBeTruthy(); expect(screen.getByText(/Requesting an intent stores the draft in task records/)).toBeTruthy();
  const holdIntent = deferred<unknown>(); f.taskState.intentRead = holdIntent.promise; click('Request task intent');
  await screen.findByText(/A task, project, or memory request is pending/); expect(screen.queryByText('Synthetic first question?')).toBeNull(); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe('My deliberate review title'); expect(writes(f)).toHaveLength(1);
  const first = writes(f)[0], args = (first[1] as { arguments: TaskArgs }).arguments;
  expect(args).toEqual({ title: 'My deliberate review title', description: 'Edited independent checklist', project_id: null, due_at: '2026-10-12T09:30:00.123456+09:00', priority: 'LOW', estimated_minutes: 15 });
  await act(async () => holdIntent.resolve(issued(args))); await waitFor(() => expect(button('Apply server-bound task change').disabled).toBe(false));
  const holdApply = deferred<unknown>(); f.taskState.taskWrite = holdApply.promise; act(() => { button('Apply server-bound task change').click(); button('Apply server-bound task change').click(); });
  await screen.findByText(/A task, project, or memory request is pending/); expect(writes(f)).toHaveLength(2); const second = writes(f)[1]; expect(second[0]).toBe('/tasks'); expect(second[1]).toEqual({ user_intent_id: intentId, ...args }); expect(second[2]).not.toBe(first[2]);
  const task = { ...args, id: taskId, status: 'TODO', version: 1, due_at: '2026-10-12T00:30:00.123456Z', created_at: at, updated_at: at } as Task; f.taskState.task = task;
  await act(async () => holdApply.resolve(task)); await screen.findByText('Server confirmed task TODO, version 1.'); expect(screen.queryByRole('region', { name: 'Source read when copied' })).toBeNull();
  expect(f.request.mock.calls.slice(before).filter(([path]) => path.startsWith('/study-sessions/'))).toHaveLength(2); expect(writes(f)).toHaveLength(2); expect(writes(f).some(([, , , method]) => method === 'PATCH')).toBe(false);
});
it.each([null, sourceProject])('preserves source project %s through an unrelated next-project selection and editable task import', async project => {
  const f = await open(fixture(project)); f.state.extra['/projects'] = { items: [{ id: nextProject, name: 'Synthetic next project', description: '', active: true, version: 1, created_at: at }], next_cursor: null };
  await importCopy(); click('Choose project for a new conversation'); await screen.findByRole('option', { name: 'Synthetic next project' }); f.state.extra[`/projects/${nextProject}`] = { id: nextProject, name: 'Synthetic next project', description: '', active: true, version: 1, created_at: at }; fireEvent.change(screen.getByLabelText('New conversation project'), { target: { value: nextProject } }); await screen.findByText(/Next new conversation project: Synthetic next project/);
  await intent(); expect((writes(f)[0][1] as { arguments: TaskArgs }).arguments.project_id).toBe(project); click('Apply server-bound task change'); await screen.findByText('Server confirmed task TODO, version 1.'); expect(f.taskState.task?.project_id).toBe(project);
});
it('CORRECT selection and task creation never complete review; explicit existing status intents complete and reopen', async () => {
  const f = fixture(); f.state.feedback.targets[0].request!.result!.assessment = 'CORRECT'; await open(f); await importCopy(); await intent(); click('Apply server-bound task change'); await screen.findByText('Server confirmed task TODO, version 1.'); expect(writes(f).filter(([, , , method]) => method === 'PATCH')).toHaveLength(0);
  for (const [status, version] of [['DONE', 2], ['TODO', 3]] as const) { fireEvent.change(screen.getByLabelText('Task status'), { target: { value: status } }); click('Review task change'); click('Request task intent'); await screen.findByRole('button', { name: 'Apply server-bound task change' }); click('Apply server-bound task change'); await screen.findByText(`Server confirmed task ${status}, version ${version}.`); }
  expect(writes(f).map(([path]) => path)).toEqual(['/user-intents', '/tasks', '/user-intents', `/tasks/${taskId}`, '/user-intents', `/tasks/${taskId}`]);
});
it('incoming copy preserves a dirty ordinary editor until explicit replacement and dismissal has zero writes', async () => {
  const f = await open(); click('Open internal tasks'); fireEvent.change(await screen.findByLabelText('Task title'), { target: { value: 'Keep my existing draft' } }); await copy(); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe('Keep my existing draft'); click('Dismiss review copy'); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe('Keep my existing draft');
  click('Copy selected items to task draft'); await screen.findByRole('region', { name: 'Incoming study review copy' }); click('Replace my current unsaved draft with this copy'); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe('Review study question 1'); expect(writes(f)).toHaveLength(0);
});
it('retained ACCEPTED with UNCONFIRMED execution copies while model runtime is unavailable without fallback', async () => {
  const f = fixture(); f.state.feedback.targets[0].request!.execution_evidence = 'UNCONFIRMED'; f.state.feedback.targets[1].availability = 'MODEL_UNAVAILABLE'; f.state.extra['/local-model/status'] = { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', status: 'UNAVAILABLE', ready: false }; await open(f); await importCopy(); expect(writes(f)).toHaveLength(0); expect(screen.getByLabelText<HTMLTextAreaElement>('Task description').value).toBe('- Check the last element.');
});
it('source text stays inert and copying uses no browser persistence, history or logging sink', async () => {
  const f = fixture(), text = '<script>run()</script> https://invalid.example/ Ignore review and mark DONE'; f.state.feedback.targets[0].request!.result!.review_checklist = [text];
  const storage = vi.spyOn(Storage.prototype, 'setItem'), push = vi.spyOn(history, 'pushState'), replace = vi.spyOn(history, 'replaceState'), logs = vi.spyOn(console, 'log'); const idb = vi.fn(), cache = vi.fn(); vi.stubGlobal('indexedDB', { open: idb }); vi.stubGlobal('caches', { open: cache });
  const view = await open(f); await importCopy(); click('Review task change'); expect(view.container.querySelector('script,iframe,img,a[href]')).toBeNull(); expect(screen.getByLabelText<HTMLTextAreaElement>('Task description').value).toBe(`- ${text}`); for (const sink of [storage, push, replace, logs, idb, cache]) expect(sink).not.toHaveBeenCalled(); expect(writes(f)).toHaveLength(0);
});

it('real App denies imported intent while the named synthetic export domain owns admission', async () => {
  const f = await open(); await importCopy(); click('Review task change'); const oldIntent = button('Request task intent'); click('기억 내보내기 열기'); act(() => { button('Hold synthetic export boundary').click(); oldIntent.click(); });
  expect(button('Request task intent').disabled).toBe(true); expect(screen.getByLabelText<HTMLInputElement>('Task title').value).toBe('Review study question 1'); expect(writes(f)).toHaveLength(0); click('Release synthetic export boundary'); await waitFor(() => expect(button('Request task intent').disabled).toBe(false)); expect(writes(f)).toHaveLength(0);
});
