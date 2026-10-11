import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { ApiError } from './api';
import { acceptedFeedback, appFixture, deferred, projection, queued, saved } from '../tests/study-feedback-fixtures';
import { at, cid, lesson, run, sid } from '../tests/study-fixtures';
vi.mock('./TodayPanel', () => ({ TodayPanel: () => <section aria-label="Synthetic Today"/> }));
vi.mock('./conversation-history-events', () => ({ watch_history_events: () => () => {} }));
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name }), click = (name: string) => fireEvent.click(button(name));
async function open(f = appFixture()) { const view = render(<App api={f.api}/>); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); await screen.findByText('No saved messages.'); await waitFor(() => expect(button('Find saved study lessons').disabled).toBe(false)); click('Find saved study lessons'); await screen.findByRole('button', { name: `Resume lesson ${run}` }); click(`Resume lesson ${run}`); return { ...f, ...view }; }
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it.each(['navigation', 'new conversation', 'logout', 'execution boundary'])('actual App cannot paint held feedback after%s', async boundary => { const f = appFixture(), hold = deferred<unknown>(); f.state.read = hold.promise; await open(f); await screen.findByRole('region', { name: 'Saved answer feedback' }); if (boundary === 'navigation') click('Other conversation'); else if (boundary === 'new conversation') click('Start new conversation'); else if (boundary === 'logout') click('Sign out'); else { fireEvent.click(screen.getByRole('checkbox', { name: 'I reviewed the account-wide impact for this checked version' })); click('Pause account execution'); } await act(async () => hold.resolve(projection(acceptedFeedback()))); expect(screen.queryByText('Synthetic boundary feedback')).toBeNull(); expect(f.request.mock.calls.filter(([path, body]) => body !== undefined && path.includes('feedback'))).toHaveLength(0); });
it('logout clears unknown recovery and a new login cannot repaint its old feedback', async () => { const f = await open(); await waitFor(() => expect(button('Request feedback for question 1').disabled).toBe(false)); f.state.mutation = Promise.reject(new ApiError('UNKNOWN', true)); f.state.mutation.catch(() => {}); click('Request feedback for question 1'); await screen.findByText(/OUTCOME_UNKNOWN:/); click('Sign out'); await screen.findByRole('heading', { name: 'Owner sign in' }); await waitFor(() => expect(button('Sign in').disabled).toBe(false)); fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'synthetic-owner' } }); fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } }); click('Sign in'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); expect(screen.queryByRole('button', { name: 'Check original feedback outcome' })).toBeNull(); expect(screen.queryByText('Saved synthetic first answer')).toBeNull(); expect(f.request.mock.calls.filter(([path, body]) => body !== undefined && path.endsWith('/feedback-requests'))).toHaveLength(1); });
it('feedback source410 clears the selected conversation and all source prose', async () => { const f = appFixture(); f.state.read = Promise.reject(new ApiError('CONVERSATION_DELETED', false, 410)); f.state.read.catch(() => {}); await open(f); await screen.findByText(/대화 원문 삭제가 확인되었습니다/); expect(screen.queryByText('Saved synthetic first answer')).toBeNull(); expect(screen.queryByRole('region', { name: 'Saved answer feedback' })).toBeNull(); });

it.each([false, true])('selection fences held study A feedback while selecting saved B=%s', async savedB => {
  const f = appFixture(), heldA = deferred<unknown>(), heldB = deferred<unknown>();
  const runB = 'b1111111-1111-4111-8111-111111111111', studyB = 'b2222222-2222-4222-8222-222222222222';
  f.state.read = heldA.promise;
  f.state.extra[`/conversations/${cid}/study-index`] = { conversation_id: cid, conversation_content_version: 13, checked_at: at, next_cursor: null, items: [
    { lesson_run_id: run, lesson_run_version: 4, created_at: at, study_session_id: sid, study_version: 3, lifecycle: 'OPEN' },
    { lesson_run_id: runB, lesson_run_version: 4, created_at: at, study_session_id: savedB ? studyB : null, study_version: savedB ? 3 : null, lifecycle: savedB ? 'OPEN' : null }
  ] };
  f.state.extra[savedB ? `/study-sessions/${studyB}` : `/runs/${runB}/study-lesson`] = heldB.promise;
  f.state.extra[`/study-sessions/${studyB}/feedback`] = { ...projection(), study_session_id: studyB };
  await open(f); await screen.findByRole('region', { name: 'Saved answer feedback' });
  click(`${savedB ? 'Resume' : 'View'} lesson ${runB}`);
  expect(screen.queryByRole('region', { name: 'Saved answer feedback' })).toBeNull();
  await act(async () => heldA.resolve(projection(acceptedFeedback())));
  expect(screen.queryByText('Synthetic boundary feedback')).toBeNull();
  await act(async () => heldB.resolve(savedB ? { ...saved(), id: studyB, lesson_run_id: runB } : { run_id: runB, status: 'SUCCEEDED', lesson }));
  if (savedB) await screen.findByRole('button', { name: 'Request feedback for question 1' });
  else { await screen.findByRole('button', { name: 'Start study session' }); expect(screen.queryByRole('region', { name: 'Saved answer feedback' })).toBeNull(); }
  expect(screen.queryByText('Synthetic boundary feedback')).toBeNull();
  expect(f.request.mock.calls.filter(([path, body]) => body !== undefined && path.includes('feedback'))).toHaveLength(0);
});

it.each([false, true])('same-tick selection blocks a retained outgoing feedback control, revoke=%s', async revoking => {
  const f = appFixture(), hold = deferred<unknown>(), runB = 'c1111111-1111-4111-8111-111111111111';
  if (revoking) f.state.feedback = projection(queued());
  f.state.extra[`/conversations/${cid}/study-index`] = { conversation_id: cid, conversation_content_version: 13, checked_at: at, next_cursor: null, items: [
    { lesson_run_id: run, lesson_run_version: 4, created_at: at, study_session_id: sid, study_version: 3, lifecycle: 'OPEN' },
    { lesson_run_id: runB, lesson_run_version: 4, created_at: at, study_session_id: null, study_version: null, lifecycle: null }
  ] }; f.state.extra[`/runs/${runB}/study-lesson`] = hold.promise;
  await open(f); const label = revoking ? 'Do not keep question 1 result' : 'Request feedback for question 1'; await waitFor(() => expect(button(label).disabled).toBe(false)); const old = button(label);
  act(() => { button(`View lesson ${runB}`).click(); old.click(); });
  expect(f.request.mock.calls.filter(([path, body]) => body !== undefined && path.includes('feedback'))).toHaveLength(0);
  await act(async () => hold.resolve({ run_id: runB, status: 'SUCCEEDED', lesson }));
  expect(screen.queryByRole('region', { name: 'Saved answer feedback' })).toBeNull();
});

it.each([false, true])('same-tick pending feedback keeps its original recovery when discover is clicked, revoke=%s', async revoking => { const f = appFixture(), hold = deferred<unknown>(); if (revoking) f.state.feedback = projection(queued()); f.state.mutation = hold.promise; await open(f); const label = revoking ? 'Do not keep question 1 result' : 'Request feedback for question 1'; await waitFor(() => expect(button(label).disabled).toBe(false)); const discover = button('Find saved study lessons'); const before = f.request.mock.calls.filter(([path]) => path.includes('/study-index')).length; act(() => { button(label).click(); discover.click(); }); expect(f.request.mock.calls.filter(([path]) => path.includes('/study-index'))).toHaveLength(before); await act(async () => hold.reject(new ApiError('UNKNOWN', true))); await screen.findByRole('button', { name: 'Check original feedback outcome' }); expect(f.request.mock.calls.filter(([path, body]) => body !== undefined && path.includes('feedback'))).toHaveLength(1); });

it('accepted feedback can be read after a fresh login and explicit selection without any generation POST', async () => { const f = appFixture(); f.state.feedback = projection(acceptedFeedback()); await open(f); await screen.findByText('Synthetic boundary feedback'); click('Sign out'); await screen.findByRole('heading', { name: 'Owner sign in' }); expect(screen.queryByText('Synthetic boundary feedback')).toBeNull(); fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'synthetic-owner' } }); fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } }); await waitFor(() => expect(button('Sign in').disabled).toBe(false)); click('Sign in'); await screen.findByText('Account state: AVAILABLE FOR NEW WORK'); expect(screen.queryByText('Synthetic boundary feedback')).toBeNull(); await waitFor(() => expect(button('Find saved study lessons').disabled).toBe(false)); click('Find saved study lessons'); await screen.findByRole('button', { name: `Resume lesson ${run}` }); click(`Resume lesson ${run}`); await screen.findByText('Synthetic boundary feedback'); expect(f.request.mock.calls.filter(([path, body]) => body !== undefined && /feedback|messages|local-inference|study-lesson/.test(path))).toHaveLength(0); });
