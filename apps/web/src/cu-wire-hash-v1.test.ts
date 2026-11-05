import { readFileSync } from 'node:fs';
import { webcrypto } from 'node:crypto';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CU_LIMITS, CuWireError, admitCoordinate, binary64Rational, canonicalizeCuValue,
  canonicalizeCuWire, cuClientEncodingPreview, exactInteger, inspectNativeMapping, nativeQuantize,
  nativeRoundtrip, parseCuProposal, parseCuWire, pixelIndex, resolvePointer,
  sha256CuEncoding, sha256CuEncodingBytes, requireCuMapVersion, requireCuProfile, type Rational,
} from './cu-wire-hash-v1';

const utf8 = (s: string) => new TextEncoder().encode(s);
const text = (b: Uint8Array) => new TextDecoder().decode(b);
interface EncodingVector { id: string; input_json: string; expected_jcs_utf8: string; expected_sha256: string; expected_utf8_bytes: number }
interface CoordinateVector extends EncodingVector { width: number; height: number; input_tokens: Record<string, string>; expected_region_pixels: Record<string, number> }
interface RejectionVector { id: string; input_json?: string; input_utf8_hex?: string; number_token?: string; expected_error: string }
interface EnvelopeVector extends EncodingVector { expected_native_args?: { x: number; y: number } }
interface NativeVector extends EncodingVector { native_rationals?: { x: Rational; y: Rational }; full_capture_pixel?: { x: number; y: number }; scale?: Rational; expected_native_args: { x: number; y: number } }
interface Golden { encoding_vectors: EncodingVector[]; rejection_vectors: RejectionVector[]; coordinate_vectors: CoordinateVector[]; envelope_vectors: EnvelopeVector[]; native_mapping_vectors: NativeVector[] }
let golden: Golden | undefined;
// Selected ordinary synthetic contract fixture; no file read at module import.
function vectors(): Golden {
  return golden ??= JSON.parse(readFileSync(new URL('../../../packages/contracts/fixtures/cu-wire-hash-v1/golden-vectors.json', import.meta.url), 'utf8')) as Golden;
}
const rat = (numerator: string, denominator = '1'): Rational => ({ numerator, denominator });
const identity = () => [rat('1'), rat('0'), rat('0'), rat('0'), rat('1'), rat('0')];
function errorCode(work: () => unknown): string {
  try { work(); } catch (error) { if (error instanceof CuWireError) return error.code; throw error; }
  throw new Error('Expected fixed CU rejection');
}
function envelope(index = 0): Record<string, any> {
  // Fixture decoding is test construction, not the production wire admission API.
  return JSON.parse(vectors().envelope_vectors[index].expected_jcs_utf8) as Record<string, any>;
}
const session = () => ({ purpose: 'Synthetic test', app_refs: ['app_demo'], resource_refs: [], origin_refs: [],
  requested_actions: ['click'], duration_seconds: 60, max_actions: 1, max_frames: 0, data_mode: 'local_only' });
const action = () => ({ session_ref: 'session_demo', target_ref: 'target_demo', observation_ref: 'obs_demo', action: 'click',
  element_ref: null, text: null, keys: null, pointer: { region_ref: 'region_demo', x: 0.3, y: 0.5,
    end_x: null, end_y: null, delta_x: null, delta_y: null }, artifact_ref: null, destination_ref: null });
afterEach(() => vi.unstubAllGlobals());

describe('CU v1 selected golden encoding probes (not approvals)', () => {
  it.each(Array.from({ length: 21 }, (_, i) => i))('encoding vector %i has exact UTF-8 and checksum', async index => {
    vi.stubGlobal('crypto', webcrypto);
    const v = vectors().encoding_vectors[index], bytes = canonicalizeCuWire(utf8(v.input_json));
    expect(text(bytes), v.id).toBe(v.expected_jcs_utf8);
    expect(bytes.length).toBe(v.expected_utf8_bytes);
    expect(await sha256CuEncoding(utf8(v.input_json))).toBe(v.expected_sha256);
  });
  it.each(Array.from({ length: 24 }, (_, i) => i))('rejection vector %i yields its fixed classification', index => {
    const v = vectors().rejection_vectors[index];
    let run: () => unknown;
    if (v.input_json !== undefined) run = () => canonicalizeCuWire(utf8(v.input_json!));
    else if (v.input_utf8_hex !== undefined) {
      const bytes = Uint8Array.from(v.input_utf8_hex.match(/../g)!, h => Number.parseInt(h, 16));
      run = () => canonicalizeCuWire(bytes);
    } else if (v.id.startsWith('R17') || v.id.startsWith('R18')) run = () => admitCoordinate(v.number_token!);
    else if (v.id.startsWith('R19') || v.id.startsWith('R20')) run = () => exactInteger(v.number_token!);
    else if (v.id.startsWith('R21')) run = () => pixelIndex('0', 0);
    else if (v.id.startsWith('R22')) { const e = envelope(8); e.profile_id = 'cu-wire-hash-v2'; run = () => cuClientEncodingPreview(utf8(JSON.stringify(e))); }
    else if (v.id.startsWith('R23')) { const e = envelope(); e.binding.geometry.map_version = 'cu-pixel-map-v2'; run = () => cuClientEncodingPreview(utf8(JSON.stringify(e))); }
    else run = () => nativeRoundtrip(1, 0, [rat('1', '2'), rat('0'), rat('0'), rat('0'), rat('1'), rat('0')]);
    expect(errorCode(run), v.id).toBe(v.expected_error);
  });
  it.each(Array.from({ length: 13 }, (_, i) => i))('coordinate vector %i preserves original tokens and exact pixels', async index => {
    vi.stubGlobal('crypto', webcrypto);
    const v = vectors().coordinate_vectors[index];
    const wire = '{' + Object.entries(v.input_tokens).map(([k, token]) => '"' + k + '":' + token).join(',') + '}';
    const doc = parseCuWire(utf8(wire));
    for (const [axis, token] of Object.entries(v.input_tokens)) {
      expect(doc.numberToken('/' + axis)).toBe(token);
      expect(pixelIndex(token, axis.endsWith('x') ? v.width : v.height), v.id).toBe(v.expected_region_pixels[axis]);
    }
    expect(text(canonicalizeCuWire(utf8(wire)))).toBe(v.expected_jcs_utf8);
    expect(await sha256CuEncoding(utf8(wire))).toBe(v.expected_sha256);
  });
  it.each(Array.from({ length: 10 }, (_, i) => i))('envelope encoding probe %i never issues approval', async index => {
    vi.stubGlobal('crypto', webcrypto);
    const v = vectors().envelope_vectors[index], wire = utf8(v.expected_jcs_utf8);
    const preview = cuClientEncodingPreview(wire);
    expect(text(preview.encoding), v.id).toBe(v.expected_jcs_utf8);
    expect(await sha256CuEncodingBytes(preview.encoding)).toBe(v.expected_sha256);
    expect(preview.approvalPreimage).toBeNull(); expect(preview.approvalDigest).toBeNull();
    expect(preview.policyAdopted).toBe(false); expect(preview.executionAuthorized).toBe(false);
    if (v.id.startsWith('E03')) {
      expect(preview.mappingError).toBe('COORDINATE_MAPPING_UNSUPPORTED'); expect(preview.resolved).toBeNull();
    } else if (v.expected_native_args) {
      expect(preview.mappingError).toBeNull(); expect(preview.resolved?.start.native).toEqual(v.expected_native_args);
    }
  });
  it.each([0, 1])('native component probe %i quantizes without asserting full-profile support', async index => {
    vi.stubGlobal('crypto', webcrypto);
    const v = vectors().native_mapping_vectors[index];
    const actual = v.native_rationals
      ? { x: nativeQuantize(v.native_rationals.x), y: nativeQuantize(v.native_rationals.y) }
      : { x: nativeQuantize(rat((BigInt(v.full_capture_pixel!.x) * BigInt(v.scale!.numerator)).toString(), v.scale!.denominator)),
        y: nativeQuantize(rat((BigInt(v.full_capture_pixel!.y) * BigInt(v.scale!.numerator)).toString(), v.scale!.denominator)) };
    expect(actual, v.id).toEqual(v.expected_native_args);
    expect(text(canonicalizeCuValue(actual))).toBe(v.expected_jcs_utf8);
    expect(await sha256CuEncodingBytes(canonicalizeCuValue(actual))).toBe(v.expected_sha256);
  });
});

describe('CU wire boundaries beyond the selected vectors', () => {
  it('rejects a rounded fractional integer at the original proposal path', () => {
    const s = JSON.stringify(session()).replace('"max_actions":1', '"max_actions":1.0000000000000001');
    expect(errorCode(() => parseCuProposal('session', utf8(s)))).toBe('NOT_EXACT_INTEGER');
  });
  it('accepts mathematically integral decimal spelling without mutating bytes', () => {
    const b = utf8(JSON.stringify(session()).replace('"max_actions":1', '"max_actions":10e-1')), before = b.slice();
    expect((parseCuProposal('session', b).value as Record<string, unknown>).max_actions).toBe(1);
    expect(b).toEqual(before);
  });
  it('checks the coordinate before rounding and keeps negative underflow out', () => {
    const b = JSON.stringify(action());
    for (const token of ['1.0000000000000001', '-1e-10000']) {
      expect(errorCode(() => parseCuProposal('action', utf8(b.replace('"x":0.3', '"x":' + token))))).toBe('COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE');
    }
  });
  it('does not convert earlier numbers when a late end or delta fails original admission', () => {
    const nativeNumber = Number, calls: unknown[] = [];
    vi.stubGlobal('Number', new Proxy(nativeNumber, { apply(target, self, args) {
      calls.push(args[0]); return Reflect.apply(target, self, args);
    } }));
    for (const [field, token, expected] of [
      ['end_x', '1.0000000000000001', 'COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE'],
      ['end_y', '-1e-10000', 'COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE'],
      ['delta_y', '1.0000000000000001', 'NOT_EXACT_INTEGER'],
    ]) {
      const wire = '{"pointer":{"x":0.25,"y":0.75,"end_x":0.5,"end_y":0.25,"delta_x":0,"delta_y":0}}'
        .replace('"' + field + '":' + (field === 'end_x' ? '0.5' : field === 'end_y' ? '0.25' : '0'), '"' + field + '":' + token);
      calls.length = 0;
      const code = errorCode(() => parseCuWire(utf8(wire))), count = calls.length;
      expect(code).toBe(expected); expect(count).toBe(0);
    }
  });
  it('converts each admitted numeric token exactly once after the complete first pass', () => {
    const nativeNumber = Number, calls: unknown[] = [];
    vi.stubGlobal('Number', new Proxy(nativeNumber, { apply(target, self, args) {
      calls.push(args[0]); return Reflect.apply(target, self, args);
    } }));
    const doc = parseCuWire(utf8('{"pointer":{"x":0.25,"y":0.75,"end_x":0.5,"end_y":0.25,"delta_x":0,"delta_y":0}}'));
    const observed = calls.slice();
    expect(observed).toEqual(['0.25', '0.75', '0.5', '0.25', 0n, 0n]);
    expect(doc.numberToken('/pointer/end_y')).toBe('0.25');
    expect(doc.value).toEqual({ pointer: { x: 0.25, y: 0.75, end_x: 0.5, end_y: 0.25, delta_x: 0, delta_y: 0 } });
  });
  it('handles long signed exponents without constructing their decimal power', () => {
    expect(pixelIndex('1e-' + '9'.repeat(10000), 2)).toBe(0);
    expect(errorCode(() => admitCoordinate('-1e-' + '9'.repeat(10000)))).toBe('COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE');
    expect(errorCode(() => exactInteger('1e' + '9'.repeat(10000)))).toBe('UNSAFE_INTEGER');
  });
  it('retains __proto__ as an ordinary own property and detects its decoded duplicate', () => {
    const d = parseCuWire(utf8('{"__proto__":1}'));
    expect(Object.getPrototypeOf(d.value)).toBeNull();
    expect(text(canonicalizeCuWire(utf8('{"__proto__":1}')))).toBe('{"__proto__":1}');
    expect(errorCode(() => parseCuWire(utf8('{"__proto__":1,"\\u005f_proto__":2}')))).toBe('DUPLICATE_KEY');
  });
  it('escapes only JCS-required characters and uses lowercase control hex', () => {
    expect(text(canonicalizeCuWire(utf8('{"s":"\\u000f\\b\\t\\n\\f\\r\\\"\\\\/"}')))).toBe('{"s":"\\u000f\\b\\t\\n\\f\\r\\\"\\\\/"}');
  });
  it('rejects overlong, surrogate and out-of-range UTF-8 sequences', () => {
    for (const bytes of [[0xc0, 0xaf], [0xed, 0xa0, 0x80], [0xf4, 0x90, 0x80, 0x80]]) {
      expect(errorCode(() => parseCuWire(new Uint8Array(bytes)))).toBe('INVALID_UTF8');
    }
  });
  it('rejects trailing commas, comments, controls and incomplete escapes', () => {
    for (const s of ['[1,]', '{"a":1,}', '/*x*/{}', '"\u0001"', '"\\u123"', '"\\x20"']) expect(() => parseCuWire(utf8(s))).toThrow(CuWireError);
  });
  it('enforces the existing byte, depth and array ceilings', () => {
    expect(errorCode(() => parseCuWire(new Uint8Array(CU_LIMITS.bodyBytes + 1)))).toBe('BODY_LIMIT');
    expect(errorCode(() => parseCuWire(utf8('['.repeat(17) + '0' + ']'.repeat(17))))).toBe('TREE_LIMIT');
    expect(errorCode(() => parseCuWire(utf8('[' + Array(1001).fill('0').join(',') + ']')))).toBe('TREE_LIMIT');
    expect(parseCuWire(utf8('[' + Array(1000).fill('0').join(',') + ']')).value).toHaveLength(1000);
  });
  it('admits the exact body limit and rejects a longer UTF-8 representation', () => {
    expect(canonicalizeCuWire(utf8('"' + 'a'.repeat(CU_LIMITS.bodyBytes - 2) + '"')).length).toBe(CU_LIMITS.bodyBytes);
    expect(errorCode(() => parseCuWire(utf8('"' + 'é'.repeat(CU_LIMITS.bodyBytes / 2) + '"')))).toBe('BODY_LIMIT');
  });
  it('rejects finite host-tree impostors, sparse arrays, cycles and getters', () => {
    const cycle: Record<string, any> = {}; cycle.self = cycle;
    const getter = Object.defineProperty({}, 'x', { enumerable: true, get() { throw new Error('must not run'); } });
    for (const v of [Infinity, NaN, new Date(0), new Array(2), cycle, getter]) expect(() => canonicalizeCuValue(v as any)).toThrow(CuWireError);
  });
  it('does not expose raw context in admission errors', () => {
    const secret = 'synthetic-private-context';
    try { parseCuWire(utf8('{"' + secret + '":NaN}')); throw new Error('unreachable'); }
    catch (error) { expect(error).toBeInstanceOf(CuWireError); expect(String(error)).not.toContain(secret); }
  });
  it('hashes an immutable snapshot across the async boundary', async () => {
    const captured: number[][] = []; let complete!: (v: ArrayBuffer) => void;
    vi.stubGlobal('crypto', { subtle: { digest: (_algorithm: string, bytes: Uint8Array) => { captured.push([...bytes]); return new Promise<ArrayBuffer>(resolve => { complete = resolve; }); } } });
    const bytes = utf8('abc'), pending = sha256CuEncodingBytes(bytes); bytes.fill(0); complete(new Uint8Array(32).buffer);
    expect(await pending).toBe('00'.repeat(32)); expect(captured).toEqual([[97, 98, 99]]);
  });
  it('returns a fixed crypto failure rather than a provider error', async () => {
    vi.stubGlobal('crypto', { subtle: { digest: async () => { throw new Error('synthetic-private-context'); } } });
    await expect(sha256CuEncodingBytes(utf8('x'))).rejects.toMatchObject({ code: 'CRYPTO_UNAVAILABLE', message: 'CRYPTO_UNAVAILABLE' });
  });
});

describe('CU exact geometry and unchanged proposal shape', () => {
  it('derives the two binary64 counterexample rationals exactly', () => {
    expect(binary64Rational(0.49999999999999994)).toEqual(rat('9007199254740991', '18014398509481984'));
    expect(binary64Rational(0.3)).toEqual(rat('5404319552844595', '18014398509481984'));
    expect(pixelIndex('0.49999999999999994', 2)).toBe(0); expect(pixelIndex('0.3', 6)).toBe(1);
  });
  it('rounds signed values towards the higher integer at a half only', () => {
    expect(nativeQuantize(rat('-8', '5'))).toBe(-2); expect(nativeQuantize(rat('-3', '2'))).toBe(-1);
    expect(nativeQuantize(rat('-1', '2'))).toBe(0); expect(nativeQuantize(rat('3', '2'))).toBe(2);
  });
  it('rejects unsafe dimensions and unsafe native results', () => {
    for (const size of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) expect(errorCode(() => pixelIndex('0', size))).toBe('INVALID_GEOMETRY');
    expect(errorCode(() => nativeQuantize(rat('9007199254740992')))).toBe('UNSAFE_INTEGER');
  });
  it('requires canonical coprime rational strings and a nonzero determinant', () => {
    for (const r of [rat('-0'), rat('2', '2'), rat('0', '2'), rat('+1'), rat('01'), rat('1', '0')]) expect(errorCode(() => nativeQuantize(r))).toBe('INVALID_GEOMETRY');
    expect(errorCode(() => nativeRoundtrip(1, 1, [rat('0'), rat('0'), rat('0'), rat('0'), rat('0'), rat('0')]))).toBe('COORDINATE_MAPPING_UNSUPPORTED');
  });
  it('derives an affine inverse including shear and negative translation', () => {
    const r = nativeRoundtrip(11, 21, [rat('1'), rat('1'), rat('-100'), rat('0'), rat('1'), rat('-50')]);
    expect(r.native).toEqual({ x: -68, y: -29 }); expect(r.inverse).toEqual({ x: rat('11'), y: rat('21') });
    expect(r.effectiveCapturePixel).toEqual({ x: 11, y: 21 });
  });
  it('rejects the half-scale mapping despite a valid forward component result', () => {
    expect(nativeQuantize(rat('11', '2'))).toBe(6);
    const inspected = inspectNativeMapping(11, 21, [rat('1', '2'), rat('0'), rat('0'), rat('0'), rat('1', '2'), rat('0')]);
    expect(inspected).toEqual({ native_args: { x: 6, y: 11 }, inverse_capture_point: { x: rat('12'), y: rat('22') },
      effective_capture_pixel: { x: 12, y: 22 }, same_capture_pixel: false });
    expect(errorCode(() => nativeRoundtrip(11, 21, [rat('1', '2'), rat('0'), rat('0'), rat('0'), rat('1', '2'), rat('0')]))).toBe('COORDINATE_MAPPING_UNSUPPORTED');
  });
  it('maps start/end with the same crop and leaves inputs unchanged', () => {
    const tokens = Object.freeze({ x: '0.25', y: '0.75', end_x: '0.75', end_y: '0.25' });
    const g = { capture_size: { width: 100, height: 100 }, region_crop: { x: 10, y: 20, width: 5, height: 5 }, matrix: identity() };
    const before = JSON.stringify(g), r = resolvePointer(tokens, g);
    expect(r.start.capturePixel).toEqual({ x: 11, y: 23 }); expect(r.end?.capturePixel).toEqual({ x: 13, y: 21 });
    expect(JSON.stringify(g)).toBe(before);
  });
  it('admits every direct pointer token before any pixel or native conversion', () => {
    const nativeNumber = Number, calls: unknown[] = [];
    vi.stubGlobal('Number', new Proxy(nativeNumber, { apply(target, self, args) {
      calls.push(args[0]); return Reflect.apply(target, self, args);
    } }));
    const geometry = { capture_size: { width: 100, height: 100 }, region_crop: { x: 10, y: 20, width: 5, height: 5 }, matrix: identity() };
    for (const tokens of [
      { x: '0.25', y: '0.75', end_x: '1.0000000000000001', end_y: '0.5' },
      { x: '0.25', y: '0.75', end_x: '0.5', end_y: '-1e-10000' },
      { x: '0.25', y: '-1e-10000', end_x: null, end_y: null },
    ]) {
      calls.length = 0;
      const code = errorCode(() => resolvePointer(tokens, geometry)), count = calls.length;
      expect(code).toBe('COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE'); expect(count).toBe(0);
    }
  });
  it('rejects crops outside the same capture rather than clamping', () => {
    expect(errorCode(() => resolvePointer({ x: '1', y: '1', end_x: null, end_y: null }, {
      capture_size: { width: 10, height: 10 }, region_crop: { x: 9, y: 0, width: 2, height: 1 }, matrix: identity(),
    }))).toBe('INVALID_GEOMETRY');
  });
  it('admits unchanged session, observe and action operands', () => {
    expect(() => parseCuProposal('session', utf8(JSON.stringify(session())))).not.toThrow();
    expect(() => parseCuProposal('observe', utf8('{"session_ref":"s","target_ref":"t","mode":"DOM"}'))).not.toThrow();
    expect(() => parseCuProposal('action', utf8(JSON.stringify(action())))).not.toThrow();
  });
  it('rejects model-added fields and preserves action-specific null rules', () => {
    expect(errorCode(() => parseCuProposal('session', utf8(JSON.stringify({ ...session(), approval: true }))))).toBe('SCHEMA_VIOLATION');
    const a = action(); a.pointer.delta_x = 1 as any;
    expect(errorCode(() => parseCuProposal('action', utf8(JSON.stringify(a))))).toBe('SCHEMA_VIOLATION');
  });
  it('does not strengthen unrelated nullable select_text endpoints', () => {
    const a = action(); a.action = 'select_text'; a.pointer.end_x = 0.5 as any;
    expect(() => parseCuProposal('action', utf8(JSON.stringify(a)))).not.toThrow();
    expect(errorCode(() => resolvePointer({ x: '0', y: '0', end_x: '0.5', end_y: null }, {
      capture_size: { width: 5, height: 5 }, region_crop: { x: 0, y: 0, width: 5, height: 5 }, matrix: identity(),
    }))).toBe('COORDINATE_MAPPING_UNSUPPORTED');
  });
  it('never returns approval for an unknown semantic contract or synthetic driver', () => {
    const e = envelope(); e.binding.authority_context.contract_id = 'unregistered-contract';
    const p = cuClientEncodingPreview(utf8(JSON.stringify(e)));
    expect(p.approvalPreimage).toBeNull(); expect(p.approvalDigest).toBeNull(); expect(p.executionAuthorized).toBe(false);
  });
  it('does not fall back for an omitted profile or contract', () => {
    for (const value of [undefined, null, '', 'cu-wire-hash-v2']) expect(errorCode(() => requireCuProfile(value))).toBe('UNSUPPORTED_PROFILE');
    for (const value of [undefined, null, '', 'cu-pixel-map-v2']) expect(errorCode(() => requireCuMapVersion(value))).toBe('UNSUPPORTED_MAP_VERSION');
    const e = envelope(); delete e.profile_id;
    expect(() => cuClientEncodingPreview(utf8(JSON.stringify(e)))).toThrow(CuWireError);
    const e2 = envelope(); delete e2.binding.authority_context.contract_id;
    expect(() => cuClientEncodingPreview(utf8(JSON.stringify(e2)))).toThrow(CuWireError);
  });
});
