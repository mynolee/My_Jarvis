import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { delete_confirm_body, delete_confirm_path, delete_preparation_path, delete_prepare_body, deletion_receipt_path, deletion_state_path, deletion_time, parse_deletion_json, read_delete_preparation, read_deletion_outcome, read_deletion_state, type EligibleDeletionState, type PendingConversationDeletion } from './conversation-deletion';

const fixture = (name: string) => JSON.parse(readFileSync(`../../packages/contracts/fixtures/conversation-deletion/${name}.json`, 'utf8'));
const target = fixture('state-eligible').conversation_id as string, other = '81000000-0000-4000-8000-000000000002';
const state = () => read_deletion_state(fixture('state-eligible'), target) as EligibleDeletionState;
const pending = (): PendingConversationDeletion => ({ stage: 'CONFIRM', key: '84000000-0000-4000-8000-000000000001', conversation_id: target, preparation_id: fixture('preparation').id, expected_version: 7, prepared_at: fixture('preparation').prepared_at, expires_at: fixture('preparation').expires_at });

describe('single-conversation deletion strict synthetic wire', () => {
  it('accepts the shared closed state/preparation/outcome fixtures and exact request bodies', () => {
    expect(read_deletion_state(fixture('state-blocked'), target)).toEqual(fixture('state-blocked'));
    expect(read_deletion_state(fixture('state-deleted'), target)).toEqual(fixture('state-deleted'));
    const prepared = read_delete_preparation(fixture('preparation'), state());
    expect(prepared).toEqual(fixture('preparation'));
    expect(delete_prepare_body(state())).toEqual(fixture('prepare-request'));
    expect(delete_confirm_body(prepared)).toEqual(fixture('confirm-request'));
    expect(read_deletion_outcome(fixture('outcome-committed'), pending())).toEqual(fixture('outcome-committed'));
    expect(read_deletion_outcome(fixture('outcome-not-observed'), pending())).toEqual(fixture('outcome-not-observed'));
  });
  it.each([undefined, null, [], {}, { ...fixture('state-eligible'), extra: true }, { ...fixture('state-eligible'), status: 'UNKNOWN' }, { ...fixture('state-eligible'), conversation_id: other }, { ...fixture('state-eligible'), content_version: Number.MAX_SAFE_INTEGER + 1 }, { ...fixture('state-eligible'), content_digest: 'A'.repeat(64) }, { ...fixture('state-eligible'), eligibility: { status: 'ELIGIBLE', blockers: [], authority: true } }])('rejects malformed/foreign active state %#', value => expect(() => read_deletion_state(value, target)).toThrow());
  it.each([
    { status: 'BLOCKED', blockers: [] }, { status: 'BLOCKED', blockers: ['UNKNOWN_VALUE'] },
    { status: 'BLOCKED', blockers: ['UNKNOWN_WORK', 'ACTIVE_WORK'] }, { status: 'BLOCKED', blockers: ['ACTIVE_WORK', 'ACTIVE_WORK'] },
    { status: 'ELIGIBLE', blockers: ['ACTIVE_WORK'] },
  ])('rejects unknown, empty, duplicate and out-of-order blocker unions %#', eligibility => expect(() => read_deletion_state({ ...fixture('state-blocked'), eligibility }, target)).toThrow());
  it('refuses authority digest in a blocked state and any source material in tombstone state', () => {
    expect(() => read_deletion_state({ ...fixture('state-blocked'), content_digest: 'a'.repeat(64) }, target)).toThrow();
    for (const extra of [{ title: 'synthetic' }, { content_digest: 'a'.repeat(64) }, { source_counts: {} }, { conversation: { title: 'synthetic' } }]) expect(() => read_deletion_state({ ...fixture('state-deleted'), ...extra }, target)).toThrow();
  });
  it.each(['0000-01-01T00:00:00Z', '2025-02-29T00:00:00Z', '2026-02-30T00:00:00Z', '2026-10-10T24:00:00Z', '2026-10-10T17:40:60Z', '2026-10-10T17:40:00+00:00', '2026-10-10t17:40:00z', '2026-10-10T17:40:00.1234567Z', '2026-10-10T17:40:00Z\n'])('rejects invalid or noncanonical UTC %s', checked_at => expect(() => read_deletion_state({ ...fixture('state-eligible'), checked_at }, target)).toThrow());
  it('validates true leap dates and microsecond UTC relationships', () => {
    expect(read_deletion_state({ ...fixture('state-eligible'), checked_at: '2024-02-29T00:00:00.123456Z' }, target).checked_at).toBe('2024-02-29T00:00:00.123456Z');
    const p = fixture('preparation'); p.prepared_at = '2026-10-10T17:40:00.000001Z'; p.expires_at = '2026-10-10T17:45:00.000001Z'; expect(read_delete_preparation(p, state()).prepared_at).toBe(p.prepared_at);
    p.expires_at = '2026-10-10T17:45:00.000002Z'; expect(() => read_delete_preparation(p, state())).toThrow();
  });
  it.each([
    { scope_policy: 'ERASE_EVERYTHING' }, { expected_version: 8 }, { expected_digest: 'b'.repeat(64) }, { expected_version: Number.MAX_SAFE_INTEGER },
    { payload_hash: 'B'.repeat(64) }, { expires_at: '2026-10-10T17:45:01Z' }, { prepared_at: '2026-10-10T17:39:59Z' }, { secret: 'synthetic' },
  ])('rejects stale authority and invalid preparation relationships %#', patch => expect(() => read_delete_preparation({ ...fixture('preparation'), ...patch }, state())).toThrow());
  it.each([
    { id: other }, { title: '' }, { title: 'x'.repeat(201) }, { title: 'x\u0000' }, { title: '\uD800' }, { project_id: 'wrong' }, { created_at: '2026-10-10T17:40:01Z' }, { extra: true },
  ])('validates exact target identity and safe Unicode display metadata %#', patch => {
    const p = fixture('preparation'); p.conversation = { ...p.conversation, ...patch }; expect(() => read_delete_preparation(p, state())).toThrow();
  });
  it('accepts 200 supplementary Unicode scalar values without truncating the title', () => {
    const p = fixture('preparation'); p.conversation.title = '🐈'.repeat(200); expect(read_delete_preparation(p, state()).conversation.title).toBe(p.conversation.title);
  });
  it.each([{ messages: -1 }, { messages: 2001 }, { runs: 1001 }, { run_results: 2 }, { inference_contexts: 2 }, { action_payloads: 1001 }, { messages: 1.1 }, { messages: '2' }, { hidden: 2 }])('rejects invalid/cap-exceeding source counts %#', patch => {
    const p = fixture('preparation'); p.source_counts = { ...p.source_counts, ...patch }; expect(() => read_delete_preparation(p, state())).toThrow();
  });
  it.each([{ conversation_id: other }, { preparation_id: other }, { deleted_version: 8 }, { tombstone_version: 9 }, { scope_policy: 'UNKNOWN' }, { title: 'synthetic' }, { deleted_at: '2026-10-10T17:42:00.000001Z' }])('rejects mismatched or content-bearing receipts %#', patch => {
    const o = fixture('outcome-committed'); o.receipt = { ...o.receipt, ...patch }; expect(() => read_deletion_outcome(o, pending())).toThrow();
  });
  it('rejects ambiguous outcome and overflow instead of crediting a commit', () => {
    expect(() => read_deletion_outcome({ ...fixture('outcome-committed'), status: 'NOT_OBSERVED' }, pending())).toThrow();
    expect(() => read_deletion_outcome({ ...fixture('outcome-not-observed'), status: 'COMMITTED' }, pending())).toThrow();
    expect(() => read_deletion_outcome(fixture('outcome-committed'), { ...pending(), expected_version: Number.MAX_SAFE_INTEGER })).toThrow();
  });
  it.each(['0001-01-01T00:00:00Z', '2026-10-10T17:39:59.999999Z', '2026-10-10T17:45:00Z', '2026-10-10T17:45:00.000001Z'])('rejects receipt outside the exact reviewed lifetime: %s', deleted_at => {
    const o = fixture('outcome-committed'); o.receipt.deleted_at = deleted_at; o.checked_at = '2026-10-11T00:00:00Z';
    expect(() => read_deletion_outcome(o, pending())).toThrow();
  });
  it.each(['2026-10-10T17:40:00Z', '2026-10-10T17:44:59.999999Z'])('allows an in-window commit at %s to be reconciled long after authority expiry', deleted_at => {
    const o = fixture('outcome-committed'); o.receipt.deleted_at = deleted_at; o.checked_at = '2027-01-01T00:00:00Z';
    expect(read_deletion_outcome(o, pending())).toEqual(o);
  });
  it.each([
    { prepared_at: '2026-10-10T17:40:00+00:00' }, { prepared_at: '2026-02-30T17:40:00Z' },
    { expires_at: '2026-10-10T17:45:00.000001Z' }, { expires_at: '2026-10-10T17:40:00Z' },
    { prepared_at: undefined }, { expires_at: undefined },
  ])('requires canonical reviewed preparation metadata and an exact five-minute window %#', patch => {
    expect(() => read_deletion_outcome(fixture('outcome-committed'), { ...pending(), ...patch } as PendingConversationDeletion)).toThrow();
    expect(() => read_deletion_outcome(fixture('outcome-not-observed'), { ...pending(), ...patch } as PendingConversationDeletion)).toThrow();
  });
  it('builds only canonical UUID routes without query or authority material', () => {
    expect(deletion_state_path(target)).toBe(`/conversations/${target}/deletion-state`);
    expect(delete_preparation_path(target)).toBe(`/conversations/${target}/delete-preparations`);
    expect(delete_confirm_path(pending().preparation_id)).toBe(`/conversation-delete-preparations/${pending().preparation_id}/confirm`);
    expect(deletion_receipt_path(pending().key)).toBe(`/conversation-deletions/requests/${pending().key}`);
    for (const fn of [deletion_state_path, delete_preparation_path, delete_confirm_path, deletion_receipt_path]) for (const bad of ['../../', target + '\n', target + '?owner=x', 'AAAAAAAA-0000-4000-8000-000000000001']) expect(() => fn(bad)).toThrow();
  });
  it('renders explicit KST date rollover independent of the UTC wire string', () => {
    expect(deletion_time('2026-10-10T17:40:00Z')).toBe('2026-10-11 02:40:00 KST');
  });
  it.each(['{"a":1,"a":2}', '{"a":1,"\\u0061":2}', '{"a":{"b":1,"b":2}}', '{"n":1e1}', '{"n":1.0}', '{"n":-0}', '{"n":-1}', '{"n":9007199254740992}', '{"x":"\\ud800"}', '{"x":"\\u0000"}', '{"x":true,}', '\uFEFF{"x":1}', '{"x":1}junk'])('rejects ambiguous raw JSON %s', text => expect(() => parse_deletion_json(text)).toThrow());
  it('allows valid nested wire JSON without coercion', () => expect(parse_deletion_json(JSON.stringify(fixture('preparation')))).toEqual(fixture('preparation')));
});
