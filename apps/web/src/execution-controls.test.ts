import { describe, expect, it } from 'vitest';
import { execution_body, execution_path, execution_time, read_execution_result, read_execution_state } from './execution-controls';
const state = { execution_paused: false, version: 7, checked_at: '2026-10-10T15:00:00.123456Z' };
describe('strict account execution contract', () => {
  it('accepts only the closed canonical projection', () => expect(read_execution_state(state)).toEqual(state));
  it.each([null, [], {}, { ...state, csrf_token: 'withheld' }, { ...state, execution_paused: 'false' }, { ...state, version: 0 }, { ...state, version: 1.2 }, { ...state, version: Number.MAX_SAFE_INTEGER + 1 }, { ...state, checked_at: '2026-02-30T00:00:00Z' }, { ...state, checked_at: '2026-10-11T00:00:00+09:00' }, { ...state, checked_at: '2026-10-10T15:00:00Z\n' }, { ...state, checked_at: '2026-10-10T15:00:00.1234567Z' }])('rejects malformed or extra data %#', value => expect(() => read_execution_state(value)).toThrow());
  it('rejects state/version/time rollback and equal-version state disagreement', () => {
    for (const value of [{ ...state, version: 6 }, { ...state, execution_paused: true }, { ...state, checked_at: '2026-10-10T15:00:00.123455Z' }]) expect(() => read_execution_state(value, state)).toThrow();
  });
  it('emits only the reviewed CAS version and allowlisted path', () => { expect(execution_body(7)).toEqual({ expected_version: 7 }); expect(execution_path('pause')).toBe('/execution/pause'); expect(execution_path('resume')).toBe('/execution/resume'); expect(() => execution_path('pause?owner=other' as 'pause')).toThrow(); expect(() => execution_body(0)).toThrow(); });
  it('requires exact target state and exactly one version increment for a real transition', () => {
    const changed = { ...state, version: 8, execution_paused: true };
    expect(read_execution_result(changed, 'pause', state)).toEqual(changed);
    for (const value of [{ ...changed, version: 7 }, { ...changed, version: 9 }, { ...changed, execution_paused: false }]) expect(() => read_execution_result(value, 'pause', state)).toThrow();
  });
  it('allows an unchanged version only for an already-matching request', () => { expect(read_execution_result(state, 'resume', state)).toEqual(state); expect(() => read_execution_result({ ...state, version: 8 }, 'resume', state)).toThrow(); });
  it('uses KST across the date boundary', () => expect(execution_time(state.checked_at)).toBe('11 Oct 2026, 00:00:00 KST'));
});
