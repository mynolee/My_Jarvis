export interface Conversation { id: string; title: string; project_id: string | null; created_at: string }
export interface Message { id: string; text: string; role: string; run_id: string | null; created_at: string }
export interface Run { execution_mode?: 'LOCAL_INFERENCE' | 'MOCK'; id: string; status: string; version: number; result: string | null; approval_id: string | null; job_id: string | null }
export interface Approval { id: string; action_id: string; action_version: number; payload_hash: string; status: string; expires_at: string; payload: Record<string, unknown> }
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function valid_snapshot(value: Approval): boolean { return UUID.test(value.id) && UUID.test(value.action_id) && Number.isSafeInteger(value.action_version) && value.action_version > 0 && /^[0-9a-f]{64}$/.test(value.payload_hash) && Number.isFinite(Date.parse(value.expires_at)) && value.payload !== null && typeof value.payload === 'object' && !Array.isArray(value.payload); }
export function can_decide(value: Approval, now = Date.now()): boolean { return valid_snapshot(value) && value.status === 'PENDING' && Date.parse(value.expires_at) > now; }
export function decision_body(value: Approval, decision: 'APPROVE' | 'REJECT') { if (!can_decide(value)) throw new Error('STALE_APPROVAL'); return { decision, expected_action_version: value.action_version, payload_hash: value.payload_hash }; }
export const terminal = new Set(['SUCCEEDED', 'VERIFIED', 'COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED', 'BLOCKED', 'UNKNOWN', 'MODEL_UNAVAILABLE', 'EXPIRED', 'PARTIAL_SUCCESS']);
