import { describe, expect, it, vi } from 'vitest';
import { Api } from './api';
import { effective_learning, learning_fields, learning_identity, learning_record_matches, read_learning_slot, same_learning_slot, type LearningEntry, type LearningSlot } from './learning-profile';
import type { Memory } from './memories';
const id = '11111111-1111-4111-8111-111111111111', project = '22222222-2222-4222-8222-222222222222', time = '2026-10-10T00:00:00Z';
const memory: Memory = { id, scope: 'GLOBAL', project_id: null, key: 'learning.level', kind: 'PROFILE', value: 'Synthetic owner description', change_reason: 'Synthetic choice', source: { type: 'EXPLICIT_OWNER_FORM', submission_id: id, submitted_at: time, source_hash: 'a'.repeat(64) }, version: 2, generation: 1, confirmed_at: time, created_at: time, updated_at: time };
function slot(status: 'ACTIVE' | 'ABSENT' | 'DELETED', scope: 'GLOBAL' | 'PROJECT' = 'GLOBAL'): LearningSlot { return { identity: learning_identity(learning_fields[0], scope, project), state: status === 'ACTIVE' ? { status, generation: 1, memory_id: id, version: 2 } : { status, generation: status === 'ABSENT' ? 0 : 2, memory_id: null, version: null }, memory: status === 'ACTIVE' ? { ...memory, scope, project_id: scope === 'PROJECT' ? project : null } : null, conflict: false }; }
const entry = (global: LearningSlot, p: LearningSlot | null): LearningEntry => ({ field: learning_fields[0], global, project: p });
describe('learning product catalog and existing-memory identity', () => {
  it('contains three exact keys, no default values or new wire fields', () => {
    expect(learning_fields.map(({ key, kind }) => ({ key, kind }))).toEqual([{ key: 'learning.level', kind: 'PROFILE' }, { key: 'learning.explanation_style', kind: 'PREFERENCE' }, { key: 'learning.code_language', kind: 'PREFERENCE' }]);
    expect(learning_fields.every(field => Object.keys(field).sort().join(',') === 'key,kind,label')).toBe(true);
  });
  it('uses a global identity without accidentally binding a selected project', () => { expect(learning_identity(learning_fields[0], 'GLOBAL', project)).toEqual({ scope: 'GLOBAL', project_id: null, key: 'learning.level' }); });
  it('inherits global only when the project slot is absent', () => { expect(effective_learning(entry(slot('ACTIVE'), slot('ABSENT', 'PROJECT')))).toEqual({ status: 'ACTIVE', memory }); });
  it('selects the project active record rather than the global record', () => { const p = slot('ACTIVE', 'PROJECT'); expect(effective_learning(entry(slot('ACTIVE'), p)).memory).toEqual(p.memory); });
  it('keeps a project tombstone mask even if no project record remains', () => { expect(effective_learning(entry(slot('ACTIVE'), slot('DELETED', 'PROJECT')))).toEqual({ status: 'MASKED', memory: null }); });
  it('never falls back through a conflicting project kind', () => { expect(effective_learning(entry(slot('ACTIVE'), { ...slot('ACTIVE', 'PROJECT'), conflict: true, memory: null }))).toEqual({ status: 'CONFLICT', memory: null }); });
  it('represents absent and deleted global slots without invented values', () => { expect(effective_learning(entry(slot('ABSENT'), null)).status).toBe('NOT_SET'); expect(effective_learning(entry(slot('DELETED'), null)).status).toBe('MASKED'); });
  it.each(['id', 'scope', 'project_id', 'key', 'version', 'generation'] as const)('binds ACTIVE record %s to the requested slot and observed state', key => {
    const s = slot('ACTIVE'); const changes = { id: project, scope: 'PROJECT', project_id: project, key: 'learning.explanation_style', version: 3, generation: 2 };
    expect(learning_record_matches({ ...memory, [key]: changes[key] }, s.identity, s.state)).toBe(false);
  });
  it('compares all generation, version, identity and conflict fences', () => { const s = slot('ACTIVE'); expect(same_learning_slot(s, s)).toBe(true); expect(same_learning_slot(s, { ...s, conflict: true })).toBe(false); expect(same_learning_slot(s, slot('DELETED'))).toBe(false); });
  it('reads canonical key-state then canonical memory with no mutation key', async () => {
    const api = new Api(), s = slot('ACTIVE'), request = vi.spyOn(api, 'request').mockResolvedValueOnce(s.state).mockResolvedValueOnce(memory);
    expect(await read_learning_slot(api, learning_fields[0], 'GLOBAL', project, () => true)).toEqual(s);
    expect(request.mock.calls).toEqual([['/memories/key-state', s.identity], [`/memories/${id}`]]);
  });
  it('identifies a wrong-kind record without coercing its kind or exposing a fallback', async () => {
    const api = new Api(); vi.spyOn(api, 'request').mockResolvedValueOnce(slot('ACTIVE').state).mockResolvedValueOnce({ ...memory, kind: 'PREFERENCE' });
    const result = await read_learning_slot(api, learning_fields[0], 'GLOBAL', null, () => true); expect(result.conflict).toBe(true); expect(result.memory).toBeNull();
  });
  it('does not issue a follow-on GET after the read authority is lost', async () => {
    const api = new Api(); let current = true; const request = vi.spyOn(api, 'request').mockImplementation(async () => { current = false; return slot('ACTIVE').state; });
    await expect(read_learning_slot(api, learning_fields[0], 'GLOBAL', null, () => current)).rejects.toThrow(); expect(request).toHaveBeenCalledTimes(1);
  });
});
