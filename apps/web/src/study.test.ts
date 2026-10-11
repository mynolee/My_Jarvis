import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { parse_closed_json } from './closed-json';
import { read_accepted_lesson, read_durable_study, read_study_index, read_study_outcome, study_index_path, study_path, study_receipt_path, valid_study_text } from './study';
import { accepted, answer, at, cid, index, input, key, lesson, other, outcome, q1, q2, run, sid, start, study } from '../tests/study-fixtures';
const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v));
const expected = { id: sid, conversation_id: cid, lesson_run_id: run, project_id: null };
describe('closed durable study wire', () => {
  it('shares the repository-relative public lifecycle fixture with Core and contract checks', () => {
    const fixture = JSON.parse(readFileSync('../../packages/contracts/fixtures/durable-study/synthetic-lifecycle.json', 'utf8'));
    for (const operation of ['start', 'answer', 'close'] as const) {
      const r = fixture[operation].receipt;
      const pending: any = { operation: r.operation, key: r.mutation_id, conversation_id: r.conversation_id, lesson_run_id: r.lesson_run_id, study_session_id: operation === 'start' ? null : r.study_session_id, expected_version: r.prior_version, expected_conversation_version: r.prior_conversation_version, ...(operation === 'answer' ? { question_id: r.question_id } : {}) };
      expect(read_study_outcome(fixture[operation], pending, true)).toEqual(fixture[operation]);
    }
    const v = fixture.view; expect(read_durable_study(v, { id: v.id, conversation_id: v.conversation_id, lesson_run_id: v.lesson_run_id }, fixture.answer.receipt)).toEqual(v);
    expect(read_study_index(fixture.index, v.conversation_id)).toEqual(fixture.index);
  });
  it('accepts only actual typed success, exact original questions, index and session structures', () => {
    expect(read_accepted_lesson(accepted, run)).toEqual(lesson); expect(read_study_index(index(), cid)).toEqual(index()); expect(read_durable_study(study(), expected)).toEqual(study());
    const o = outcome(start()); expect(read_study_outcome(o, start(), true)).toEqual(o);
  });
  it.each(['WAITING_INPUT', 'UNKNOWN', 'FAILED', 'CANCELLED', 'QUEUED'])('never treats %s as an accepted lesson', status => expect(() => read_accepted_lesson({ ...accepted, status }, run)).toThrow());
  it.each([JSON.stringify(lesson), 'A lesson-looking answer', { ...accepted, purpose: 'STUDY_LESSON' }, { ...accepted, run_id: other }, { ...accepted, lesson: { ...lesson, questions: [lesson.questions[0], lesson.questions[0]] } }])('refuses prose and forged or malformed lesson authority %j', v => expect(() => read_accepted_lesson(v, run)).toThrow());
  it.each(['operation', 'mutation_id', 'conversation_id', 'study_session_id', 'lesson_run_id', 'prior_version', 'resulting_version', 'prior_conversation_version', 'resulting_conversation_version', 'question_id', 'attempt_id', 'input_id'])('strictly binds ANSWER receipt %s', field => {
    const o: any = clone(outcome(answer())); o.receipt[field] = ['attempt_id', 'input_id'].includes(field) ? 'invalid' : typeof o.receipt[field] === 'number' ? o.receipt[field] + 1 : other;
    expect(() => read_study_outcome(o, answer(), true)).toThrow();
  });
  it.each(['text', 'lesson', 'feedback', 'owner_id', 'request_digest'])('rejects receipt content or authority field %s', field => { const o: any = clone(outcome(answer())); o.receipt[field] = 'forbidden'; expect(() => read_study_outcome(o, answer())).toThrow(); });
  it('closes operation-specific fields and requires checked time after commitment', () => {
    const o: any = clone(outcome(start())); o.receipt.question_id = q1; expect(() => read_study_outcome(o, start())).toThrow();
    const future: any = clone(outcome(answer())); future.receipt.committed_at = '2026-10-12T00:00:00Z'; expect(() => read_study_outcome(future, answer())).toThrow();
    expect(() => read_study_outcome({ status: 'NOT_OBSERVED', receipt: null, checked_at: at }, start(), true)).toThrow();
    expect(read_study_outcome({ status: 'NOT_OBSERVED', receipt: null, checked_at: at }, start()).status).toBe('NOT_OBSERVED');
  });
  it.each([0, 5, 1.5, Number.MAX_SAFE_INTEGER + 1])('refuses invalid session version %s', version => expect(() => read_durable_study({ ...study(), version }, expected)).toThrow());
  it.each([{ owner_id: other }, { feedback_availability: 'AVAILABLE' }, { feedback_availability: 'QUEUED' }, { stage: 'FEEDBACK_PENDING' }, { lifecycle: 'CLOSED_BY_OWNER' }, { project_id: other }, { lesson_run_id: other }, { conversation_id: other }, { created_at: '2026-10-12T00:00:00Z' }])('refuses malformed/foreign session %j', patch => expect(() => read_durable_study({ ...study(), ...patch }, expected)).toThrow());
  it('rejects detail snapshots older than the selected index version or checked observation', () => {
    expect(() => read_durable_study(study(), { ...expected, minimum_version: 2 })).toThrow();
    expect(() => read_durable_study(study(), { ...expected, minimum_conversation_version: 12 })).toThrow();
    expect(() => read_durable_study(study(), { ...expected, checked_after: '2026-10-12T00:00:00Z' })).toThrow();
  });
  it('requires exact recorded attempt IDs, question membership and no feedback', () => {
    const p = answer(), o = outcome(p); if (o.status !== 'COMMITTED' || o.receipt.operation !== 'ANSWER') throw new Error();
    const data = { ...study(), version: 2, conversation_content_version: 12, attempts: [{ input_id: input, attempt_id: o.receipt.attempt_id, question_id: q1, text: '  Exact answer\n😀  ', feedback: null }] };
    expect(read_durable_study(data, expected, o.receipt).attempts[0].text).toBe('  Exact answer\n😀  ');
    for (const patch of [{ input_id: other }, { question_id: q2 }, { feedback: {} }]) expect(() => read_durable_study({ ...data, attempts: [{ ...data.attempts[0], ...patch }] }, expected, o.receipt)).toThrow();
    expect(() => read_durable_study({ ...data, version: 3, attempts: [data.attempts[0], data.attempts[0]] }, expected)).toThrow();
  });
  it.each([{ study_session_id: sid }, { study_version: 1 }, { lifecycle: 'OPEN' }, { study_session_id: sid, study_version: 4, lifecycle: 'OPEN' }, { study_session_id: sid, study_version: 1, lifecycle: 'CLOSED_BY_OWNER' }])('requires index nullable triplet/version consistency %j', patch => expect(() => read_study_index({ ...index(), items: [{ ...index().items[0], ...patch }] }, cid)).toThrow());
  it('bounds, scopes and closes discovery pages', () => {
    expect(() => read_study_index(index(), other)).toThrow(); expect(() => read_study_index({ ...index(), items: Array(51).fill(index().items[0]) }, cid)).toThrow();
    expect(() => read_study_index({ ...index(), items: [index().items[0], index().items[0]] }, cid)).toThrow();
    expect(() => read_study_index({ ...index(), next_cursor: 'https://example.invalid' }, cid)).toThrow();
    const cursor = `YWJj.${'a'.repeat(64)}`; expect(read_study_index({ ...index(), next_cursor: cursor }, cid).next_cursor).toBe(cursor); expect(study_index_path(cid, cursor)).toContain(cursor);
  });
  it.each(['', ' \t\n\u0085\u00a0', 'x\0', '\ud800', '😀'.repeat(8001)])('rejects invalid text without trimming/coercion', text => expect(valid_study_text(text)).toBe(false));
  it('accepts exactly 8000 astral scalars/32000 bytes, preserved whitespace and escaped display text', () => { expect(valid_study_text('😀'.repeat(8000))).toBe(true); expect(valid_study_text('  <script>literal()</script>\n')).toBe(true); });
  it.each(['{"version":1,"version":2}', '{"version":1e0}', '{"version":1.0}', '{"version":-0}', '{"value":"\\ud800"}', '{"value":"\\u0000"}'])('rejects ambiguous raw wire %s', text => expect(() => parse_closed_json(text, 262144)).toThrow());
  it('bounds UTF-8 raw bytes, including non-ASCII text', () => { expect(() => parse_closed_json(JSON.stringify({ value: '😀'.repeat(30) }), 100)).toThrow(); });
  it('builds only fixed canonical routes and content-free descriptor fields', () => { expect(study_path(sid)).toBe(`/study-sessions/${sid}`); expect(study_receipt_path(key)).toBe(`/study-mutations/${key}`); expect(() => study_index_path(`${cid}\n`)).toThrow(); expect(Object.keys(answer()).sort()).toEqual(['conversation_id', 'expected_conversation_version', 'expected_version', 'key', 'lesson_run_id', 'operation', 'question_id', 'study_session_id']); });
});
