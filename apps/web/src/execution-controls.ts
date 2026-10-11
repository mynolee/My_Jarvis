import { ApiError } from './api';
import { valid_date, task_time_micros } from './tasks';

export interface ExecutionState { execution_paused: boolean; version: number; checked_at: string }
export type ExecutionAction = 'pause' | 'resume';
const fail = (): never => { throw new ApiError('INVALID_EXECUTION_RESPONSE'); };
const version = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) > 0;
const instant = (value: unknown): value is string => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$(?![\s\S])/.test(value) && valid_date(value);
/** The dedicated account projection contains no session, credential, or authority material. */
export function read_execution_state(value: unknown, previous?: ExecutionState | null): ExecutionState {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return fail();
  const state = value as Record<string, unknown>;
  if (Object.keys(state).length !== 3 || !['execution_paused', 'version', 'checked_at'].every(key => Object.hasOwn(state, key)) || typeof state.execution_paused !== 'boolean' || !version(state.version) || !instant(state.checked_at)) return fail();
  if (previous && (state.version < previous.version || state.version === previous.version && state.execution_paused !== previous.execution_paused || task_time_micros(state.checked_at) < task_time_micros(previous.checked_at))) return fail();
  return { execution_paused: state.execution_paused, version: state.version, checked_at: state.checked_at };
}
export function execution_body(expected_version: number): { expected_version: number } {
  if (!version(expected_version)) return fail();
  return { expected_version };
}
export function execution_path(action: ExecutionAction): string {
  if (action !== 'pause' && action !== 'resume') return fail();
  return `/execution/${action}`;
}
export function read_execution_result(value: unknown, action: ExecutionAction, previous: ExecutionState): ExecutionState {
  execution_path(action);
  const state = read_execution_state(value, previous), paused = action === 'pause';
  const expected = previous.version + (previous.execution_paused === paused ? 0 : 1);
  if (!Number.isSafeInteger(expected) || state.execution_paused !== paused || state.version !== expected) return fail();
  return state;
}
export function execution_time(value: string): string {
  if (!instant(value)) return fail();
  return `${new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'medium', hour12: false }).format(new Date(value))} KST`;
}
