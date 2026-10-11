import { Api, ApiError } from './api';
import { read_memory, read_memory_key_state, valid_memory_identity, type Memory, type MemoryIdentity, type MemoryKeyState, type MemoryKind, type MemoryScope } from './memories';

/** Product keys only. Values are opaque owner-entered data, never prompt templates. */
export const learning_fields = [
  { key: 'learning.level', kind: 'PROFILE', label: 'Learning level' },
  { key: 'learning.explanation_style', kind: 'PREFERENCE', label: 'Explanation style' },
  { key: 'learning.code_language', kind: 'PREFERENCE', label: 'Code example language' },
] as const satisfies readonly { key: string; kind: MemoryKind; label: string }[];
export type LearningField = typeof learning_fields[number];
export interface LearningSlot { identity: MemoryIdentity; state: MemoryKeyState; memory: Memory | null; conflict: boolean }
export interface LearningEntry { field: LearningField; global: LearningSlot; project: LearningSlot | null }
export type LearningEffective = { status: 'ACTIVE'; memory: Memory } | { status: 'NOT_SET' | 'MASKED' | 'CONFLICT'; memory: null };

export function learning_identity(field: LearningField, scope: MemoryScope, project: string | null): MemoryIdentity {
  return { scope, project_id: scope === 'PROJECT' ? project : null, key: field.key };
}
export function learning_record_matches(memory: Memory, identity: MemoryIdentity, state: MemoryKeyState): boolean {
  return state.status === 'ACTIVE' && memory.id === state.memory_id && memory.version === state.version && memory.generation === state.generation && memory.key === identity.key && memory.scope === identity.scope && memory.project_id === identity.project_id;
}
export async function read_learning_slot(api: Api, field: LearningField, scope: MemoryScope, project: string | null, current: () => boolean): Promise<LearningSlot> {
  const identity = learning_identity(field, scope, project);
  if (!valid_memory_identity(identity)) throw new ApiError('INVALID_LEARNING_IDENTITY');
  if (!current()) throw new ApiError('STALE_LEARNING_READ');
  const state = read_memory_key_state(await api.request('/memories/key-state', identity));
  if (!current()) throw new ApiError('STALE_LEARNING_READ');
  if (state.status !== 'ACTIVE') return { identity, state, memory: null, conflict: false };
  const memory = read_memory(await api.request(`/memories/${state.memory_id}`));
  if (!current()) throw new ApiError('STALE_LEARNING_READ');
  if (!learning_record_matches(memory, identity, state)) throw new ApiError('INVALID_LEARNING_SLOT');
  return { identity, state, memory: memory.kind === field.kind ? memory : null, conflict: memory.kind !== field.kind };
}
export function effective_learning(entry: LearningEntry): LearningEffective {
  const slot = entry.project?.state.status !== 'ABSENT' && entry.project ? entry.project : entry.global;
  if (slot.conflict) return { status: 'CONFLICT', memory: null };
  if (slot.state.status === 'DELETED') return { status: 'MASKED', memory: null };
  if (slot.memory) return { status: 'ACTIVE', memory: slot.memory };
  return { status: 'NOT_SET', memory: null };
}
export function same_learning_slot(a: LearningSlot, b: LearningSlot): boolean {
  return a.identity.scope === b.identity.scope && a.identity.project_id === b.identity.project_id && a.identity.key === b.identity.key && a.state.status === b.state.status && a.state.memory_id === b.state.memory_id && a.state.version === b.state.version && a.state.generation === b.state.generation && a.conflict === b.conflict;
}
