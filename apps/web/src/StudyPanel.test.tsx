import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { StudyPanel, type StudyPanelProps } from './StudyPanel';
import type { DurableStudy, PendingStudy } from './study';
import { accepted, at, attempt, cid, index, input, key, other, outcome, q1, q2, run, sid, study } from '../tests/study-fixtures';
const deferred = <T,>() => { let resolve!: (v: T) => void, reject!: (e: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name });
const click = (name: string) => fireEvent.click(button(name));
function fixture(saved = false) {
  const api = new Api(), state = { saved, current: study(), mutation: null as Promise<unknown> | null, read: null as Promise<unknown> | null, receipt: null as unknown, next: null as string | null };
  const request = vi.spyOn(api, 'request').mockImplementation(async (path, raw, k) => {
    if (path.includes('/study-index')) return { ...index(state.saved), next_cursor: state.next };
    if (path === `/runs/${run}/study-lesson`) return accepted;
    if (path === `/study-sessions/${sid}` && raw === undefined) return state.read ?? state.current;
    if (path.startsWith('/study-mutations/')) return state.receipt;
    if (raw !== undefined) {
      if (state.mutation) return state.mutation;
      const body = raw as any, operation = path.endsWith('/answers') ? 'ANSWER' : path.endsWith('/close') ? 'CLOSE' : 'START';
      const p = { operation, key: k!, conversation_id: cid, lesson_run_id: run, study_session_id: operation === 'START' ? null : sid, expected_version: operation === 'START' ? null : body.expected_version, expected_conversation_version: body.expected_conversation_version, ...(operation === 'ANSWER' ? { question_id: body.question_id } : {}) } as PendingStudy;
      const result = outcome(p); state.saved = true;
      if (operation === 'ANSWER') state.current = { ...state.current, version: state.current.version + 1, conversation_content_version: state.current.conversation_content_version + 1, stage: state.current.attempts.length === 1 ? 'FEEDBACK_PENDING' : 'WAITING_ANSWERS', attempts: [...state.current.attempts, { input_id: state.current.attempts.length ? other : input, attempt_id: state.current.attempts.length ? key : attempt, question_id: body.question_id, text: body.text, feedback: null }] };
      if (operation === 'CLOSE') state.current = { ...state.current, version: state.current.version + 1, conversation_content_version: state.current.conversation_content_version + 1, lifecycle: 'CLOSED_BY_OWNER', stage: 'CLOSED_BY_OWNER' };
      if (operation === 'ANSWER' && result.status === 'COMMITTED' && result.receipt.operation === 'ANSWER') { const a = state.current.attempts.at(-1)!; result.receipt.input_id = a.input_id; result.receipt.attempt_id = a.attempt_id; }
      return result;
    }
    throw new Error('Unexpected route');
  });
  const props: StudyPanelProps = { api, sessionKey: 1, conversationId: cid, projectId: null, snapshotKey: 1, onUnauthorized: vi.fn(), onDeleted: vi.fn(), onAcquirePending: vi.fn(() => true), onPendingChange: vi.fn(), onOutcomeUnknown: vi.fn() };
  return { api, state, request, props };
}
async function discover(saved = false) { await waitFor(() => expect(button('Find saved study lessons').disabled).toBe(false)); click('Find saved study lessons'); await screen.findByRole('button', { name: `${saved ? 'Resume' : 'View'} lesson ${run}` }); click(`${saved ? 'Resume' : 'View'} lesson ${run}`); await screen.findByText('Synthetic first question?'); }
async function openSaved(f = fixture(true)) { const rendered = render(<StudyPanel {...f.props}/>); await discover(true); await waitFor(() => expect(screen.getByLabelText<HTMLTextAreaElement>('Answer 1').disabled).toBe(false)); return { ...f, ...rendered }; }
const writes = (request: ReturnType<typeof fixture>['request']) => request.mock.calls.filter(([, body]) => body !== undefined);
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
it('discovers actual accepted typed lessons, starts with the current index version, records two new answers, reloads and explicitly closes', async () => {
  const f = fixture(); const rendered = render(<StudyPanel {...f.props}/>); expect(f.request).not.toHaveBeenCalled(); await discover(); click('Start study session');
  await screen.findByLabelText('Answer 1'); expect(writes(f.request)[0]).toEqual([`/conversations/${cid}/study-sessions`, { lesson_run_id: run, expected_conversation_version: 10 }, expect.any(String)]);
  fireEvent.change(screen.getByLabelText('Answer 1'), { target: { value: '  First exact answer\n😀  ' } }); click('Record answer 1'); await screen.findByText('Study version: 2');
  expect(f.state.current.attempts[0]).toMatchObject({ question_id: q1, text: '  First exact answer\n😀  ' });
  expect(writes(f.request)[1][1]).toEqual({ expected_version: 1, expected_conversation_version: 11, question_id: q1, text: '  First exact answer\n😀  ' });
  fireEvent.change(screen.getByLabelText('Answer 2'), { target: { value: 'Second exact answer' } }); click('Record answer 2'); await screen.findByText(/FEEDBACK_PENDING describes two recorded answers/);
  expect(screen.queryByRole('progressbar')).toBeNull(); expect(screen.getAllByText('Answer recorded. Feedback has not been generated.').length).toBeGreaterThan(0);
  rendered.unmount(); render(<StudyPanel {...f.props} sessionKey={2}/>); await discover(true); expect(screen.getByText('Second exact answer')).toBeTruthy();
  click('Close study session'); await screen.findByText('Lifecycle: CLOSED_BY_OWNER'); expect(screen.queryByRole('button', { name: 'Close study session' })).toBeNull();
  expect(writes(f.request)).toHaveLength(4); expect(writes(f.request).every(([path]) => /study-sessions/.test(path))).toBe(true);
  expect(f.request.mock.calls.some(([path]) => /\/(messages|local-inference|study-lessons)$/.test(path))).toBe(false);
});
it('allows reading while execution is paused, without granting write authority or needing model readiness', async () => {
  const f = fixture(true); render(<StudyPanel {...f.props} writesPaused/>); await discover(true); expect(screen.getByLabelText<HTMLTextAreaElement>('Answer 1').disabled).toBe(true); expect(writes(f.request)).toHaveLength(0);
});
it('shows honest absence rather than interpreting arbitrary prose as a lesson', async () => {
  const f = fixture(); f.request.mockResolvedValue({ ...index(), items: [] }); render(<StudyPanel {...f.props}/>); click('Find saved study lessons'); await screen.findByText('No accepted typed study lessons are available in this conversation.'); expect(screen.queryByRole('button', { name: 'Start study session' })).toBeNull();
});
it('renders hostile lesson and answer strings as literal text without active content or automatic links', async () => {
  const f = fixture(true); const attack = '<img src="https://example.invalid/leak" onerror="alert(1)">'; f.state.current = { ...study(), version: 2, conversation_content_version: 12, attempts: [{ input_id: input, attempt_id: attempt, question_id: q1, text: attack, feedback: null }] };
  const view = render(<StudyPanel {...f.props}/>); await discover(true); expect(screen.getByText(attack)).toBeTruthy(); expect(view.container.querySelector('img,a,iframe,script')).toBeNull();
});
it('acquires synchronously, sends once on double click, and clears all raw draft text immediately', async () => {
  const f = await openSaved(); const hold = deferred<unknown>(); f.state.mutation = hold.promise;
  fireEvent.change(screen.getByLabelText('Answer 1'), { target: { value: 'Private one' } }); fireEvent.change(screen.getByLabelText('Answer 2'), { target: { value: 'Private two' } });
  act(() => { button('Record answer 1').click(); button('Record answer 1').click(); button('Close study session').click(); }); expect(writes(f.request)).toHaveLength(1); expect(f.props.onAcquirePending).toHaveBeenCalledTimes(1);
  expect(screen.getByLabelText<HTMLTextAreaElement>('Answer 1').value).toBe(''); expect(screen.getByLabelText<HTMLTextAreaElement>('Answer 2').value).toBe('');
  await act(async () => hold.reject(new ApiError('UNKNOWN', true))); await screen.findByRole('region', { name: 'Study outcome unknown' }); expect(screen.queryByText('Synthetic first question?')).toBeNull();
});
it.each([new ApiError('UNKNOWN', true), new ApiError('STUDY_TIME_LIMIT', true, 503), new Error('private body must not be reflected'), { unexpected: true }])('keeps ambiguous answer outcome fenced and NOT_OBSERVED never resends', async failure => {
  const f = await openSaved(); f.state.mutation = failure instanceof Error ? Promise.reject(failure) : Promise.resolve(failure); f.state.mutation.catch(() => {});
  fireEvent.change(screen.getByLabelText('Answer 1'), { target: { value: 'Private answer' } }); click('Record answer 1'); await screen.findByRole('region', { name: 'Study outcome unknown' });
  expect(screen.queryByText('Private answer')).toBeNull(); expect(screen.queryByText(/private body must/)).toBeNull(); expect(button('Find saved study lessons').disabled).toBe(true);
  f.state.receipt = { status: 'NOT_OBSERVED', receipt: null, checked_at: at }; click('Check original study outcome'); await screen.findByText(/NOT_OBSERVED:/); expect(writes(f.request)).toHaveLength(1); expect(f.props.onPendingChange).toHaveBeenLastCalledWith(true);
  click('Check original study outcome'); await waitFor(() => expect(f.request.mock.calls.filter(([p]) => p.startsWith('/study-mutations/'))).toHaveLength(2)); expect(writes(f.request)).toHaveLength(1);
});
it('reconciles a matching committed receipt using only GET and reads fresh canonical state', async () => {
  const f = await openSaved(); f.state.mutation = Promise.reject(new ApiError('UNKNOWN', true)); f.state.mutation.catch(() => {}); fireEvent.change(screen.getByLabelText('Answer 1'), { target: { value: 'Recorded remotely' } }); click('Record answer 1'); await screen.findByRole('region', { name: 'Study outcome unknown' });
  const [, body, k] = writes(f.request)[0]; const pending: PendingStudy = { operation: 'ANSWER', key: k!, conversation_id: cid, lesson_run_id: run, study_session_id: sid, expected_version: 1, expected_conversation_version: 11, question_id: q1 };
  f.state.receipt = outcome(pending); f.state.current = { ...study(), version: 2, conversation_content_version: 12, attempts: [{ input_id: input, attempt_id: attempt, question_id: q1, text: (body as any).text, feedback: null }] };
  click('Check original study outcome'); await screen.findByText('Recorded remotely'); expect(writes(f.request)).toHaveLength(1); expect(f.props.onPendingChange).toHaveBeenLastCalledWith(false);
});
it.each(['conversation', 'snapshot', 'hidden', 'session', 'paused'])('fences held detail responses across the %s boundary', async boundary => {
  const f = fixture(true), hold = deferred<unknown>(); f.state.read = hold.promise; const view = render(<StudyPanel {...f.props}/>); click('Find saved study lessons'); await screen.findByRole('button', { name: `Resume lesson ${run}` }); click(`Resume lesson ${run}`);
  const changed = boundary === 'conversation' ? { conversationId: other } : boundary === 'snapshot' ? { snapshotKey: 2 } : boundary === 'hidden' ? { contentHidden: true } : boundary === 'paused' ? { writesPaused: true } : { sessionKey: 2 };
  view.rerender(<StudyPanel {...f.props} {...changed}/>); await act(async () => hold.resolve(study())); expect(screen.queryByText('Synthetic first question?')).toBeNull(); expect(screen.queryByLabelText('Answer 1')).toBeNull();
});
it('drops old session unknown authority on reauthentication and ignores its late mutation result', async () => {
  const f = await openSaved(), hold = deferred<unknown>(); f.state.mutation = hold.promise; fireEvent.change(screen.getByLabelText('Answer 1'), { target: { value: 'Old private answer' } }); click('Record answer 1');
  const k = writes(f.request)[0][2]!; f.rerender(<StudyPanel {...f.props} sessionKey={2}/>); await act(async () => hold.resolve(outcome({ operation: 'ANSWER', key: k, conversation_id: cid, lesson_run_id: run, study_session_id: sid, expected_version: 1, expected_conversation_version: 11, question_id: q1 })));
  expect(screen.queryByRole('button', { name: 'Check original study outcome' })).toBeNull(); expect(screen.queryByText('Old private answer')).toBeNull(); expect(f.props.onOutcomeUnknown).toHaveBeenCalled(); expect(writes(f.request)).toHaveLength(1);
});
it('requires fresh discovery after a known precondition conflict without silently rebasing the answer', async () => {
  const f = await openSaved(); f.state.mutation = Promise.reject(new ApiError('PRECONDITION_CHANGED', false, 409)); f.state.mutation.catch(() => {}); fireEvent.change(screen.getByLabelText('Answer 1'), { target: { value: 'Stale answer' } }); click('Record answer 1'); await screen.findByText(/PRECONDITION_CHANGED:/); expect(screen.queryByLabelText('Answer 1')).toBeNull(); expect(writes(f.request)).toHaveLength(1); expect(f.props.onPendingChange).toHaveBeenLastCalledWith(false);
});
it('401 clears the current owner boundary even after a newer selection invalidated the read', async () => {
  const f = fixture(true), hold = deferred<unknown>(); f.state.read = hold.promise; const view = render(<StudyPanel {...f.props}/>); click('Find saved study lessons'); await screen.findByRole('button', { name: `Resume lesson ${run}` }); click(`Resume lesson ${run}`); view.rerender(<StudyPanel {...f.props} conversationId={other}/>); await act(async () => hold.reject(new ApiError('SESSION_REVOKED', false, 401))); expect(f.props.onUnauthorized).toHaveBeenCalledTimes(1);
});
it('retains only the original content-free descriptor after snapshot change during POST and ignores its late 200', async () => {
  const f = await openSaved(), hold = deferred<unknown>(); f.state.mutation = hold.promise; fireEvent.change(screen.getByLabelText('Answer 1'), { target: { value: 'Late private answer' } }); click('Record answer 1');
  const k = writes(f.request)[0][2]!; f.rerender(<StudyPanel {...f.props} snapshotKey={2}/>); await screen.findByRole('region', { name: 'Study outcome unknown' });
  await act(async () => hold.resolve(outcome({ operation: 'ANSWER', key: k, conversation_id: cid, lesson_run_id: run, study_session_id: sid, expected_version: 1, expected_conversation_version: 11, question_id: q1 })));
  expect(screen.queryByText('Synthetic first question?')).toBeNull(); expect(screen.queryByDisplayValue('Late private answer')).toBeNull(); expect(f.props.onPendingChange).toHaveBeenLastCalledWith(true);
  f.state.receipt = { status: 'NOT_OBSERVED', receipt: null, checked_at: at }; click('Check original study outcome'); await screen.findByText(/NOT_OBSERVED:/); expect(f.request.mock.calls.at(-1)).toEqual([`/study-mutations/${k}`]); expect(writes(f.request)).toHaveLength(1);
});
it('does not conflate a verified receipt with an unavailable subsequent canonical read or repeat the mutation', async () => {
  const f = await openSaved(); f.state.read = Promise.reject(new ApiError('CONNECTION_UNAVAILABLE')); f.state.read.catch(() => {}); fireEvent.change(screen.getByLabelText('Answer 1'), { target: { value: 'Committed answer' } }); click('Record answer 1'); await screen.findByText(/mutation receipt was verified, but the current study could not be read/); expect(writes(f.request)).toHaveLength(1); expect(screen.queryByLabelText('Answer 1')).toBeNull(); expect(f.props.onPendingChange).toHaveBeenLastCalledWith(false);
});
it('clears deleted source when a committed reconciliation is followed by canonical 410, keeping metadata outcome separate', async () => {
  const f = await openSaved(); f.state.mutation = Promise.reject(new ApiError('UNKNOWN', true)); f.state.mutation.catch(() => {}); fireEvent.change(screen.getByLabelText('Answer 1'), { target: { value: 'Deleted answer' } }); click('Record answer 1'); await screen.findByRole('region', { name: 'Study outcome unknown' });
  const k = writes(f.request)[0][2]!; f.state.receipt = outcome({ operation: 'ANSWER', key: k, conversation_id: cid, lesson_run_id: run, study_session_id: sid, expected_version: 1, expected_conversation_version: 11, question_id: q1 }); f.state.read = Promise.reject(new ApiError('CONVERSATION_DELETED', false, 410)); f.state.read.catch(() => {}); click('Check original study outcome'); await waitFor(() => expect(f.props.onDeleted).toHaveBeenCalledWith(cid)); expect(screen.queryByLabelText('Answer 1')).toBeNull(); expect(writes(f.request)).toHaveLength(1);
});

it('keeps exhausted conversation versions readable but makes answer and close actions visibly unavailable', async () => {
  const f = fixture(true); f.state.current = { ...study(), conversation_content_version: Number.MAX_SAFE_INTEGER - 1 }; const rendered = render(<StudyPanel {...f.props}/>); await discover(true); expect(button('Close study session').disabled).toBe(true); expect(screen.getByLabelText<HTMLTextAreaElement>('Answer 1').disabled).toBe(true); expect(screen.getByText(/no safe headroom/)).toBeTruthy(); expect(writes(f.request)).toHaveLength(0); rendered.unmount();
});
