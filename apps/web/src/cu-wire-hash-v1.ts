/** CU-only RFC 8785 / I-JSON codec. No existing hash path selects this module.
 * Encoding/component results are not approvals, registered geometry or authority.
 */
export const CU_PROFILE = 'cu-wire-hash-v1';
export const CU_DOMAIN = 'JARVIS.CU.APPROVAL';
export const CU_MAP = 'cu-pixel-map-v1';
// Existing Intelligence contracts.py MAX_BODY_BYTES/MAX_DEPTH/_computer_shape.
export const CU_LIMITS = Object.freeze({ bodyBytes: 262144, depth: 16, arrayItems: 1000 });
export type CuJson = null | boolean | number | string | readonly CuJson[] | { readonly [key: string]: CuJson };
export type CuKind = 'session' | 'observe' | 'action';
export type CuErrorCode = 'BODY_LIMIT' | 'TREE_LIMIT' | 'INVALID_UTF8' | 'BOM' | 'INVALID_JSON'
  | 'INVALID_JSON_NUMBER' | 'TRAILING_VALUE' | 'DUPLICATE_KEY' | 'INVALID_UNICODE'
  | 'IJSON_NONCHARACTER' | 'NONFINITE_BINARY64' | 'COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE'
  | 'NOT_EXACT_INTEGER' | 'UNSAFE_INTEGER' | 'INVALID_GEOMETRY' | 'UNSUPPORTED_PROFILE'
  | 'UNSUPPORTED_MAP_VERSION' | 'COORDINATE_MAPPING_UNSUPPORTED' | 'SCHEMA_VIOLATION'
  | 'SEMANTIC_CONTRACT_UNSUPPORTED' | 'CRYPTO_UNAVAILABLE';
export class CuWireError extends Error {
  constructor(readonly code: CuErrorCode) { super(code); this.name = 'CuWireError'; }
}
function fail(code: CuErrorCode): never { throw new CuWireError(code); }
export function requireCuProfile(value: unknown): void { if (value !== CU_PROFILE) fail('UNSUPPORTED_PROFILE'); }
export function requireCuMapVersion(value: unknown): void { if (value !== CU_MAP) fail('UNSUPPORTED_MAP_VERSION'); }
class NumericToken { constructor(readonly token: string) {} }
type Raw = null | boolean | string | NumericToken | Raw[] | { [key: string]: Raw };
type Obj = { readonly [key: string]: CuJson };
function object(v: CuJson): Obj {
  if (v === null || typeof v !== 'object' || Array.isArray(v)) return fail('SCHEMA_VIOLATION');
  return v as Obj;
}
function closed(v: CuJson, keys: readonly string[]): Obj {
  const o = object(v), names = Object.keys(o);
  if (names.length !== keys.length || !keys.every(k => Object.hasOwn(o, k))) return fail('SCHEMA_VIOLATION');
  return o;
}
function scalarString(s: string): void {
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    let cp = c;
    if (c >= 0xd800 && c <= 0xdbff) {
      const lo = s.charCodeAt(++i);
      if (!(lo >= 0xdc00 && lo <= 0xdfff)) fail('INVALID_UNICODE');
      cp = 0x10000 + (c - 0xd800) * 1024 + lo - 0xdc00;
    } else if (c >= 0xdc00 && c <= 0xdfff) fail('INVALID_UNICODE');
    if ((cp >= 0xfdd0 && cp <= 0xfdef) || (cp & 0xffff) >= 0xfffe) fail('IJSON_NONCHARACTER');
  }
}

// No JSON.parse: decoded names are checked before insertion, and numbers stay tokens.
function readRaw(bytes: Uint8Array): Raw {
  if (!(bytes instanceof Uint8Array) || bytes.byteLength === 0 || bytes.byteLength > CU_LIMITS.bodyBytes) fail('BODY_LIMIT');
  if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) fail('BOM');
  let text: string;
  try { text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes); }
  catch { return fail('INVALID_UTF8'); }
  let i = 0;
  const ws = () => { while (i < text.length && /[\x20\x09\x0a\x0d]/.test(text[i])) i++; };
  function string(): string {
    if (text[i++] !== '"') return fail('INVALID_JSON');
    let out = '';
    while (i < text.length) {
      const c = text[i++];
      if (c === '"') { scalarString(out); return out; }
      if (c.charCodeAt(0) < 32) return fail('INVALID_JSON');
      if (c !== '\\') { out += c; continue; }
      const e = text[i++];
      const simple: Record<string, string> = { '"': '"', '\\': '\\', '/': '/', b: '\b', f: '\f', n: '\n', r: '\r', t: '\t' };
      if (Object.hasOwn(simple, e)) out += simple[e];
      else if (e === 'u') {
        const hex = text.slice(i, i + 4);
        if (!/^[0-9a-fA-F]{4}$/.test(hex)) return fail('INVALID_JSON');
        out += String.fromCharCode(Number.parseInt(hex, 16)); i += 4;
      } else return fail('INVALID_JSON');
    }
    return fail('INVALID_JSON');
  }
  function value(depth: number): Raw {
    if (depth > CU_LIMITS.depth) fail('TREE_LIMIT');
    ws(); const c = text[i];
    if (c === '"') return string();
    if (c === '{') {
      i++; ws(); const o: { [key: string]: Raw } = Object.create(null);
      if (text[i] === '}') { i++; return o; }
      for (;;) {
        ws(); if (depth + 1 > CU_LIMITS.depth) fail('TREE_LIMIT');
        const k = string(); if (Object.hasOwn(o, k)) fail('DUPLICATE_KEY');
        ws(); if (text[i++] !== ':') fail('INVALID_JSON');
        o[k] = value(depth + 1); ws(); const sep = text[i++];
        if (sep === '}') return o; if (sep !== ',') fail('INVALID_JSON');
      }
    }
    if (c === '[') {
      i++; ws(); const a: Raw[] = [];
      if (text[i] === ']') { i++; return a; }
      for (;;) {
        if (a.length >= CU_LIMITS.arrayItems) fail('TREE_LIMIT');
        a.push(value(depth + 1)); ws(); const sep = text[i++];
        if (sep === ']') return a; if (sep !== ',') fail('INVALID_JSON');
      }
    }
    for (const [word, result] of [['true', true], ['false', false], ['null', null]] as const) {
      if (text.startsWith(word, i)) { i += word.length; return result; }
    }
    const start = i;
    while (i < text.length && !/[\x20\x09\x0a\x0d,}\]]/.test(text[i])) i++;
    const token = text.slice(start, i);
    if (!/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/.test(token)) fail('INVALID_JSON_NUMBER');
    return new NumericToken(token);
  }
  const result = value(0); ws(); if (i !== text.length) fail('TRAILING_VALUE');
  return result;
}

// Decimal admission never constructs 10**an untrusted exponent. Saturation is
// only for exact order/integrality comparisons beyond any bounded coefficient.
function decimal(token: string): { negative: boolean; digits: string; exponent: number } {
  if (typeof token !== 'string' || token.length === 0 || token.length > CU_LIMITS.bodyBytes
      || !/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/.test(token)) fail('INVALID_JSON_NUMBER');
  const negative = token[0] === '-', unsigned = negative ? token.slice(1) : token;
  const parts = unsigned.split(/[eE]/), significand = parts[0], dot = significand.indexOf('.');
  const fraction = dot < 0 ? 0 : significand.length - dot - 1;
  let digits = significand.replace('.', '').replace(/^0+/, '');
  if (digits === '') return { negative, digits: '0', exponent: 0 };
  const expText = parts[1] ?? '0';
  const expNegative = expText[0] === '-', expDigits = expText.replace(/^[+-]/, '').replace(/^0+/, '') || '0';
  const cap = CU_LIMITS.bodyBytes * 2 + 32;
  let exp = 0;
  for (const digit of expDigits) exp = Math.min(cap, exp * 10 + digit.charCodeAt(0) - 48);
  if (expNegative) exp = -exp;
  const trimmed = digits.replace(/0+$/, ''), trim = digits.length - trimmed.length;
  digits = trimmed;
  return { negative, digits, exponent: exp - fraction + trim };
}
function checkCoordinate(token: string): void {
  const d = decimal(token);
  if (d.digits !== '0' && (d.negative || d.digits.length + d.exponent > 1
      || (d.digits.length + d.exponent === 1 && !(d.digits === '1' && d.exponent === 0)))) {
    fail('COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE');
  }
}
export function admitCoordinate(token: string): number {
  checkCoordinate(token);
  const n = Number(token); if (!Number.isFinite(n)) fail('NONFINITE_BINARY64'); return n;
}
function integerValue(token: string): bigint {
  const d = decimal(token);
  if (d.digits === '0') return 0n;
  if (d.exponent < 0) fail('NOT_EXACT_INTEGER');
  if (d.digits.length + d.exponent > 16) fail('UNSAFE_INTEGER');
  let n = BigInt(d.digits) * 10n ** BigInt(d.exponent);
  if (d.negative) n = -n;
  if (n < -9007199254740991n || n > 9007199254740991n) fail('UNSAFE_INTEGER');
  return n;
}
export function exactInteger(token: string): number { return Number(integerValue(token)); }
const integerPaths = new Set([
  '/duration_seconds', '/max_actions', '/max_frames', '/pointer/delta_x', '/pointer/delta_y',
  '/proposal/duration_seconds', '/proposal/max_actions', '/proposal/max_frames',
  '/proposal/pointer/delta_x', '/proposal/pointer/delta_y', '/binding/authority_context/revision',
  ...['capture_size/width', 'capture_size/height', 'region_crop/x', 'region_crop/y', 'region_crop/width', 'region_crop/height',
    'viewport/x', 'viewport/y', 'viewport/width', 'viewport/height', 'scroll_offset/x', 'scroll_offset/y'].map(p => '/binding/geometry/' + p),
  ...['x', 'y', 'end_x', 'end_y'].map(p => '/resolved_args/arguments/' + p),
]);
const coordinatePaths = new Set(['/pointer/x', '/pointer/y', '/pointer/end_x', '/pointer/end_y',
  '/proposal/pointer/x', '/proposal/pointer/y', '/proposal/pointer/end_x', '/proposal/pointer/end_y']);
const escapePath = (s: string) => s.replace(/~/g, '~0').replace(/\//g, '~1');
export interface CuDocument { readonly value: CuJson; numberToken(pointer: string): string | undefined }
export function parseCuWire(bytes: Uint8Array): CuDocument {
  const raw = readRaw(bytes), tokens = new Map<string, string>(), integers = new Map<NumericToken, bigint>();
  // Finish every original-decimal coordinate/integer check before ANY numeric
  // token becomes binary64. A late invalid end/delta cannot convert earlier x/y.
  function check(node: Raw, path: string): void {
    if (node instanceof NumericToken) {
      if (coordinatePaths.has(path)) checkCoordinate(node.token);
      if (integerPaths.has(path)) integers.set(node, integerValue(node.token));
    } else if (Array.isArray(node)) node.forEach((v, i) => check(v, path + '/' + i));
    else if (node !== null && typeof node === 'object') {
      for (const key of Object.keys(node)) check(node[key], path + '/' + escapePath(key));
    }
  }
  check(raw, '');
  function convert(node: Raw, path: string): CuJson {
    if (node instanceof NumericToken) {
      tokens.set(path, node.token);
      const n = integers.has(node) ? Number(integers.get(node)!) : Number(node.token);
      if (!Number.isFinite(n)) fail('NONFINITE_BINARY64'); return n;
    }
    if (Array.isArray(node)) return Object.freeze(node.map((v, i) => convert(v, path + '/' + i)));
    if (node !== null && typeof node === 'object') {
      const out: { [key: string]: CuJson } = Object.create(null);
      for (const key of Object.keys(node)) out[key] = convert(node[key], path + '/' + escapePath(key));
      return Object.freeze(out);
    }
    return node;
  }
  const value = convert(raw, '');
  return Object.freeze({ value, numberToken: (path: string) => tokens.get(path) });
}

function quote(s: string): string {
  if (s.length > CU_LIMITS.bodyBytes) fail('BODY_LIMIT');
  scalarString(s); let out = '"';
  const escapes: Record<string, string> = { '"': '\\"', '\\': '\\\\', '\b': '\\b', '\t': '\\t', '\n': '\\n', '\f': '\\f', '\r': '\\r' };
  for (let i = 0; i < s.length; i++) {
    const c = s[i], n = s.charCodeAt(i);
    out += Object.hasOwn(escapes, c) ? escapes[c] : n < 32 ? '\\u' + n.toString(16).padStart(4, '0') : c;
  }
  return out + '"';
}
/** Component encoder only: a host tree cannot establish original wire admission. */
export function canonicalizeCuValue(value: CuJson): Uint8Array {
  const active = new Set<object>(); let units = 0;
  const add = (s: string) => { units += s.length; if (units > CU_LIMITS.bodyBytes) fail('BODY_LIMIT'); return s; };
  function encode(v: CuJson, depth: number): string {
    if (depth > CU_LIMITS.depth) fail('TREE_LIMIT');
    if (v === null) return add('null');
    if (typeof v === 'boolean') return add(v ? 'true' : 'false');
    if (typeof v === 'number') {
      if (!Number.isFinite(v)) fail('NONFINITE_BINARY64');
      // RFC8785 3.2.2.3: ECMAScript Number::toString, not whole-object JSON.stringify.
      return add(v === 0 ? '0' : v.toString());
    }
    if (typeof v === 'string') return add(quote(v));
    if (typeof v !== 'object' || active.has(v)) fail('SCHEMA_VIOLATION');
    const proto = Object.getPrototypeOf(v);
    if (!Array.isArray(v) && proto !== null && proto !== Object.prototype) fail('SCHEMA_VIOLATION');
    active.add(v);
    let out: string;
    if (Array.isArray(v)) {
      if (v.length > CU_LIMITS.arrayItems || Object.keys(v).length !== v.length) fail('TREE_LIMIT');
      const parts: string[] = [];
      for (let i = 0; i < v.length; i++) {
        const d = Object.getOwnPropertyDescriptor(v, String(i));
        if (!d || !Object.hasOwn(d, 'value')) fail('SCHEMA_VIOLATION');
        parts.push(encode(d.value as CuJson, depth + 1));
      }
      out = add('[') + parts.map((part, i) => (i ? add(',') : '') + part).join('') + add(']');
    } else {
      if (Object.getOwnPropertySymbols(v).length) fail('SCHEMA_VIOLATION');
      const parts: string[] = [];
      // Default string sort is unsigned UTF-16 lexicographic order, including astral keys.
      for (const key of Object.keys(v).sort()) {
        if (depth + 1 > CU_LIMITS.depth) fail('TREE_LIMIT');
        const d = Object.getOwnPropertyDescriptor(v, key);
        if (!d || !Object.hasOwn(d, 'value')) fail('SCHEMA_VIOLATION');
        parts.push(add(quote(key)) + add(':') + encode(d.value as CuJson, depth + 1));
      }
      out = add('{') + parts.map((part, i) => (i ? add(',') : '') + part).join('') + add('}');
    }
    active.delete(v); return out;
  }
  const bytes = new TextEncoder().encode(encode(value, 0));
  if (bytes.byteLength > CU_LIMITS.bodyBytes) fail('BODY_LIMIT');
  return bytes;
}
export function canonicalizeCuWire(bytes: Uint8Array): Uint8Array { return canonicalizeCuValue(parseCuWire(bytes).value); }
export async function sha256CuEncodingBytes(bytes: Uint8Array): Promise<string> {
  if (!(bytes instanceof Uint8Array) || bytes.byteLength > CU_LIMITS.bodyBytes) fail('BODY_LIMIT');
  if (!globalThis.crypto?.subtle) fail('CRYPTO_UNAVAILABLE');
  // Snapshot before the asynchronous boundary; later caller mutation cannot change the digest.
  const copy = new Uint8Array(bytes);
  let result: ArrayBuffer;
  try { result = await globalThis.crypto.subtle.digest('SHA-256', copy); }
  catch { return fail('CRYPTO_UNAVAILABLE'); }
  return Array.from(new Uint8Array(result), b => b.toString(16).padStart(2, '0')).join('');
}
/** Encoding checksum only; never a server approval digest. */
export async function sha256CuEncoding(bytes: Uint8Array): Promise<string> { return sha256CuEncodingBytes(canonicalizeCuWire(bytes)); }

export interface Rational { readonly numerator: string; readonly denominator: string }
interface Q { n: bigint; d: bigint }
const abs = (n: bigint) => n < 0n ? -n : n;
function gcd(a: bigint, b: bigint): bigint { while (b !== 0n) { const c = a % b; a = b; b = c; } return abs(a); }
function q(n: bigint, d: bigint): Q {
  if (d === 0n) fail('COORDINATE_MAPPING_UNSUPPORTED');
  if (d < 0n) { n = -n; d = -d; } const g = gcd(n, d); return { n: n / g, d: d / g };
}
function readQ(value: Rational): Q {
  if (value === null || typeof value !== 'object' || Object.keys(value).length !== 2
      || !Object.hasOwn(value, 'numerator') || !Object.hasOwn(value, 'denominator')
      || typeof value.numerator !== 'string' || typeof value.denominator !== 'string'
      || value.numerator.length + value.denominator.length > CU_LIMITS.bodyBytes
      || !/^(?:0|-[1-9][0-9]*|[1-9][0-9]*)$/.test(value.numerator)
      || !/^[1-9][0-9]*$/.test(value.denominator)) fail('INVALID_GEOMETRY');
  const n = BigInt(value.numerator), d = BigInt(value.denominator);
  if (gcd(n, d) !== 1n) fail('INVALID_GEOMETRY'); return { n, d };
}
function displayQ(v: Q): Rational { return Object.freeze({ numerator: v.n.toString(), denominator: v.d.toString() }); }
const addQ = (a: Q, b: Q) => q(a.n * b.d + b.n * a.d, a.d * b.d);
const negQ = (a: Q) => ({ n: -a.n, d: a.d });
const mulQ = (a: Q, b: Q) => q(a.n * b.n, a.d * b.d);
const divQ = (a: Q, b: Q) => q(a.n * b.d, a.d * b.n);
function floorQ(n: bigint, d: bigint): bigint { const a = n / d; return n < 0n && n % d !== 0n ? a - 1n : a; }
function nearest(v: Q): bigint { return floorQ(2n * v.n + v.d, 2n * v.d); }
function safe(n: bigint): number {
  if (abs(n) > 9007199254740991n) fail('UNSAFE_INTEGER'); return Number(n);
}
function safeInt(n: number, positive = false): void {
  if (!Number.isSafeInteger(n) || (positive && n <= 0)) fail('INVALID_GEOMETRY');
}
export function binary64Rational(n: number): Rational {
  if (typeof n !== 'number' || !Number.isFinite(n)) fail('NONFINITE_BINARY64');
  const buffer = new ArrayBuffer(8), view = new DataView(buffer); view.setFloat64(0, n, false);
  const high = view.getUint32(0, false), low = view.getUint32(4, false);
  const exponent = (high >>> 20) & 0x7ff;
  let significand = (BigInt(high & 0xfffff) << 32n) | BigInt(low);
  if (exponent !== 0) significand |= 1n << 52n;
  if ((high >>> 31) !== 0) significand = -significand;
  const shift = exponent === 0 ? -1074 : exponent - 1023 - 52;
  return displayQ(shift >= 0 ? q(significand << BigInt(shift), 1n) : q(significand, 1n << BigInt(-shift)));
}
export function pixelIndex(token: string, size: number): number {
  safeInt(size, true);
  const n = readQ(binary64Rational(admitCoordinate(token)));
  return safe(nearest(mulQ(n, { n: BigInt(size - 1), d: 1n })));
}
export function nativeQuantize(value: Rational): number { return safe(nearest(readQ(value))); }
export interface NativePoint {
  readonly native: Readonly<{ x: number; y: number }>;
  readonly inverse: Readonly<{ x: Rational; y: Rational }>;
  readonly effectiveCapturePixel: Readonly<{ x: number; y: number }>;
}
/** Mathematical probe: preserves the mismatching point without claiming admission.
 * Derives the inverse from the same six coefficients; no supplied inverse.
 */
export function inspectNativeMapping(x: number, y: number, matrix: readonly Rational[]) {
  safeInt(x); safeInt(y);
  if (!Array.isArray(matrix) || matrix.length !== 6) fail('INVALID_GEOMETRY');
  const [a, b, c, d, e, f] = matrix.map(readQ);
  const det = addQ(mulQ(a, e), negQ(mulQ(b, d)));
  if (det.n === 0n) fail('COORDINATE_MAPPING_UNSUPPORTED');
  const pointX = { n: BigInt(x), d: 1n }, pointY = { n: BigInt(y), d: 1n };
  const nx = safe(nearest(addQ(addQ(mulQ(a, pointX), mulQ(b, pointY)), c)));
  const ny = safe(nearest(addQ(addQ(mulQ(d, pointX), mulQ(e, pointY)), f)));
  const dx = addQ({ n: BigInt(nx), d: 1n }, negQ(c)), dy = addQ({ n: BigInt(ny), d: 1n }, negQ(f));
  const ix = divQ(addQ(mulQ(e, dx), negQ(mulQ(b, dy))), det);
  const iy = divQ(addQ(mulQ(a, dy), negQ(mulQ(d, dx))), det);
  const px = safe(nearest(ix)), py = safe(nearest(iy));
  return Object.freeze({ native_args: Object.freeze({ x: nx, y: ny }),
    inverse_capture_point: Object.freeze({ x: displayQ(ix), y: displayQ(iy) }),
    effective_capture_pixel: Object.freeze({ x: px, y: py }), same_capture_pixel: px === x && py === y });
}
/** Enforcing component: no preimage/mapping success if final native units change the pixel. */
export function nativeRoundtrip(x: number, y: number, matrix: readonly Rational[]): NativePoint {
  const r = inspectNativeMapping(x, y, matrix);
  if (!r.same_capture_pixel) fail('COORDINATE_MAPPING_UNSUPPORTED');
  return Object.freeze({ native: r.native_args, inverse: r.inverse_capture_point, effectiveCapturePixel: r.effective_capture_pixel });
}
export interface CaptureGeometry {
  readonly capture_size: Readonly<{ width: number; height: number }>;
  readonly region_crop: Readonly<{ x: number; y: number; width: number; height: number }>;
  readonly matrix: readonly Rational[];
}
export function resolvePointer(tokens: Readonly<{ x: string; y: string; end_x: string | null; end_y: string | null }>, geometry: CaptureGeometry) {
  // Direct component callers have not necessarily passed through parseCuWire.
  // Admit the complete original coordinate set before converting even start.x.
  checkCoordinate(tokens.x); checkCoordinate(tokens.y);
  if (tokens.end_x !== null) checkCoordinate(tokens.end_x);
  if (tokens.end_y !== null) checkCoordinate(tokens.end_y);
  const { capture_size: size, region_crop: crop } = geometry;
  for (const n of [size.width, size.height, crop.width, crop.height]) safeInt(n, true);
  safeInt(crop.x); safeInt(crop.y);
  if (crop.x < 0 || crop.y < 0 || BigInt(crop.x) + BigInt(crop.width) > BigInt(size.width)
      || BigInt(crop.y) + BigInt(crop.height) > BigInt(size.height)) fail('INVALID_GEOMETRY');
  function point(x: string, y: string) {
    const rx = pixelIndex(x, crop.width), ry = pixelIndex(y, crop.height);
    const cx = safe(BigInt(crop.x) + BigInt(rx)), cy = safe(BigInt(crop.y) + BigInt(ry));
    return Object.freeze({ regionPixel: Object.freeze({ x: rx, y: ry }), capturePixel: Object.freeze({ x: cx, y: cy }),
      ...nativeRoundtrip(cx, cy, geometry.matrix) });
  }
  // The proposal schema, not this component, decides which nullable ends are applicable.
  // A partial end remains legal schema data where specified, but cannot supply a
  // 2-D native endpoint: never silently discard its non-null coordinate.
  if ((tokens.end_x === null) !== (tokens.end_y === null)) fail('COORDINATE_MAPPING_UNSUPPORTED');
  const start = point(tokens.x, tokens.y);
  const end = tokens.end_x === null || tokens.end_y === null ? null : point(tokens.end_x, tokens.end_y);
  return Object.freeze({ start, end });
}

const actions = ['activate_target', 'focus_element', 'click', 'double_click', 'scroll', 'drag', 'type_text', 'set_value',
  'press_keys', 'select_text', 'copy_selection', 'paste_artifact', 'open_resource', 'save_working_copy'] as const;
const refRE = /^[A-Za-z0-9_-]{1,100}$/;
function ref(v: CuJson): boolean { return typeof v === 'string' && refRE.test(v); }
function intRange(v: CuJson, lo: number, hi: number): boolean { return typeof v === 'number' && Number.isSafeInteger(v) && v >= lo && v <= hi; }
function strings(v: CuJson, lo: number, hi: number, allowed?: readonly string[]): boolean {
  return Array.isArray(v) && v.length >= lo && v.length <= hi && new Set(v).size === v.length
    && v.every(x => typeof x === 'string' && (allowed ? allowed.includes(x) : ref(x)));
}
function validateProposal(kind: CuKind, value: CuJson): void {
  if (kind === 'session') {
    const p = closed(value, ['purpose', 'app_refs', 'resource_refs', 'origin_refs', 'requested_actions', 'duration_seconds', 'max_actions', 'max_frames', 'data_mode']);
    if (!(typeof p.purpose === 'string' && Array.from(p.purpose).length >= 1 && Array.from(p.purpose).length <= 1000
      && strings(p.app_refs, 1, 12) && strings(p.resource_refs, 0, 40) && strings(p.origin_refs, 0, 10)
      && strings(p.requested_actions, 1, 20, [...actions, 'observe', 'wait_for_state'])
      && intRange(p.duration_seconds, 1, 600) && intRange(p.max_actions, 1, 40) && intRange(p.max_frames, 0, 60)
      && ['local_only', 'approved_non_AI_connections'].includes(p.data_mode as string))) fail('SCHEMA_VIOLATION');
    return;
  }
  if (kind === 'observe') {
    const p = closed(value, ['session_ref', 'target_ref', 'mode']);
    if (!ref(p.session_ref) || !ref(p.target_ref) || !['auto', 'native', 'DOM', 'AX', 'OCR_image'].includes(p.mode as string)) fail('SCHEMA_VIOLATION');
    return;
  }
  if (kind !== 'action') fail('SCHEMA_VIOLATION');
  const p = closed(value, ['session_ref', 'target_ref', 'observation_ref', 'action', 'element_ref', 'text', 'keys', 'pointer', 'artifact_ref', 'destination_ref']);
  if (![p.session_ref, p.target_ref, p.observation_ref].every(ref) || !actions.includes(p.action as typeof actions[number])
      || ![p.element_ref, p.artifact_ref, p.destination_ref].every(v => v === null || ref(v))
      || !(p.text === null || typeof p.text === 'string' && Array.from(p.text).length <= 4096)) fail('SCHEMA_VIOLATION');
  const keys = ['CMD', 'CTRL', 'ALT', 'SHIFT', 'ENTER', 'TAB', 'ESC', 'BACKSPACE', 'DELETE', 'LEFT', 'RIGHT', 'UP', 'DOWN', 'HOME', 'END', 'PAGEUP', 'PAGEDOWN', 'A', 'C', 'V', 'X', 'Z', 'S', 'F', 'L', 'W'];
  if (p.keys !== null && !(Array.isArray(p.keys) && p.keys.length >= 1 && p.keys.length <= 5 && p.keys.every(k => keys.includes(k as string)))) fail('SCHEMA_VIOLATION');
  let pointer: Obj | null = null;
  if (p.pointer !== null) {
    pointer = closed(p.pointer, ['region_ref', 'x', 'y', 'end_x', 'end_y', 'delta_x', 'delta_y']);
    if (!ref(pointer.region_ref) || !['x', 'y'].every(k => typeof pointer![k] === 'number' && (pointer![k] as number) >= 0 && (pointer![k] as number) <= 1)
      || !['end_x', 'end_y'].every(k => pointer![k] === null || typeof pointer![k] === 'number' && (pointer![k] as number) >= 0 && (pointer![k] as number) <= 1)
      || !['delta_x', 'delta_y'].every(k => pointer![k] === null || intRange(pointer![k], -1000, 1000))) fail('SCHEMA_VIOLATION');
  }
  const nulls = (...names: string[]) => names.every(k => p[k] === null);
  const elementOrPointer = () => (ref(p.element_ref) && pointer === null) || (p.element_ref === null && pointer !== null);
  const noEnds = () => pointer === null || ['end_x', 'end_y', 'delta_x', 'delta_y'].every(k => pointer![k] === null);
  let valid = false;
  switch (p.action) {
    case 'activate_target': valid = nulls('element_ref', 'text', 'keys', 'pointer', 'artifact_ref', 'destination_ref'); break;
    case 'focus_element': case 'click': case 'double_click':
      valid = nulls('text', 'keys', 'artifact_ref', 'destination_ref') && elementOrPointer() && noEnds(); break;
    case 'select_text': valid = nulls('text', 'keys', 'artifact_ref', 'destination_ref') && elementOrPointer(); break;
    case 'type_text': case 'set_value': valid = nulls('keys', 'artifact_ref', 'destination_ref') && typeof p.text === 'string' && p.text.length > 0 && elementOrPointer(); break;
    case 'press_keys': valid = nulls('text', 'pointer', 'artifact_ref', 'destination_ref') && ref(p.element_ref) && Array.isArray(p.keys); break;
    case 'copy_selection': valid = nulls('text', 'keys', 'pointer', 'artifact_ref', 'destination_ref') && ref(p.element_ref); break;
    case 'paste_artifact': valid = nulls('text', 'keys', 'destination_ref') && ref(p.artifact_ref) && elementOrPointer(); break;
    case 'open_resource': valid = nulls('element_ref', 'text', 'keys', 'pointer', 'destination_ref') && ref(p.artifact_ref); break;
    case 'save_working_copy': valid = nulls('element_ref', 'text', 'keys', 'pointer') && ref(p.artifact_ref) && ref(p.destination_ref); break;
    case 'scroll': valid = nulls('element_ref', 'text', 'keys', 'artifact_ref', 'destination_ref') && pointer !== null && pointer.end_x === null && pointer.end_y === null && intRange(pointer.delta_x, -1000, 1000) && intRange(pointer.delta_y, -1000, 1000) && (pointer.delta_x !== 0 || pointer.delta_y !== 0); break;
    case 'drag': valid = nulls('element_ref', 'text', 'keys', 'artifact_ref', 'destination_ref') && pointer !== null && typeof pointer.end_x === 'number' && typeof pointer.end_y === 'number' && pointer.delta_x === null && pointer.delta_y === null; break;
  }
  if (!valid) fail('SCHEMA_VIOLATION');
}
export function parseCuProposal(kind: CuKind, bytes: Uint8Array): CuDocument {
  const doc = parseCuWire(bytes); validateProposal(kind, doc.value); return doc;
}

/** Client encoding/mathematical preview ONLY. It neither selects nor calls a
 * semantic registry, authorizes a mask/driver, nor reconstructs server evidence.
 * Even a well-formed synthetic contract always has null approval fields here.
 */
export function cuClientEncodingPreview(bytes: Uint8Array) {
  const doc = parseCuWire(bytes), e = closed(doc.value, ['domain', 'profile_id', 'kind', 'proposal', 'binding', 'resolved_args']);
  requireCuProfile(e.profile_id);
  if (e.domain !== CU_DOMAIN || !['session', 'observe', 'action'].includes(e.kind as string)) fail('SCHEMA_VIOLATION');
  validateProposal(e.kind as CuKind, e.proposal);
  const b = closed(e.binding, ['authority_context', 'target', 'observation', 'geometry']);
  const authority = closed(b.authority_context, ['contract_id', 'revision', 'preimage']);
  if (typeof authority.contract_id !== 'string' || !authority.contract_id || !intRange(authority.revision, 0, Number.MAX_SAFE_INTEGER)) fail('SCHEMA_VIOLATION');
  object(authority.preimage);
  if (e.kind === 'session' && (b.target !== null || b.observation !== null || b.geometry !== null || e.resolved_args !== null)) fail('SCHEMA_VIOLATION');
  if (e.kind !== 'session') {
    const t = closed(b.target, ['target_ref', 'target_version', 'app_ref', 'app_version', 'window_ref', 'display_ref', 'tab_ref', 'origin_ref', 'frame_ref', 'document_version']);
    if (t.target_ref !== object(e.proposal).target_ref) fail('SCHEMA_VIOLATION');
  }
  if (e.kind === 'observe' && (b.observation !== null || b.geometry !== null || e.resolved_args !== null)) fail('SCHEMA_VIOLATION');
  let resolved: ReturnType<typeof resolvePointer> | null = null;
  let mappingError: CuErrorCode | null = null;
  if (e.kind === 'action') {
    const obs = closed(b.observation, ['observation_ref', 'observation_version', 'capture_ref', 'capture_version', 'observed_at', 'expires_at']);
    const p = object(e.proposal);
    if (obs.observation_ref !== p.observation_ref) fail('SCHEMA_VIOLATION');
    closed(e.resolved_args, ['driver_ref', 'driver_version', 'route', 'arguments']);
    if (p.pointer === null) { if (b.geometry !== null) fail('SCHEMA_VIOLATION'); }
    else {
      const g = closed(b.geometry, ['map_version', 'geometry_version', 'region_ref', 'region_version', 'capture_size', 'region_crop', 'coordinate_origin', 'axis_directions', 'display_ref', 'display_version', 'viewport', 'scroll_offset', 'focus_ref', 'focus_version', 'modal_version', 'dpi', 'native_mapping']);
      requireCuMapVersion(g.map_version);
      if (g.coordinate_origin !== 'TOP_LEFT_PIXEL_CENTER' || g.axis_directions !== 'X_RIGHT_Y_DOWN' || g.region_ref !== object(p.pointer).region_ref) fail('INVALID_GEOMETRY');
      const size = closed(g.capture_size, ['width', 'height']), crop = closed(g.region_crop, ['x', 'y', 'width', 'height']);
      closed(g.viewport, ['x', 'y', 'width', 'height']); closed(g.scroll_offset, ['x', 'y']);
      const dpi = closed(g.dpi, ['x', 'y']);
      for (const axis of ['x', 'y']) if (readQ(dpi[axis] as unknown as Rational).n <= 0n) fail('INVALID_GEOMETRY');
      const map = closed(g.native_mapping, ['contract_id', 'native_unit', 'matrix', 'quantization']);
      if (map.quantization !== 'NEAREST_TIES_HIGHER') fail('INVALID_GEOMETRY');
      const token = (name: string): string | null => {
        const v = object(p.pointer)[name]; if (v === null) return null;
        return doc.numberToken('/proposal/pointer/' + name) ?? fail('INVALID_GEOMETRY');
      };
      try {
        resolved = resolvePointer({ x: token('x')!, y: token('y')!, end_x: token('end_x'), end_y: token('end_y') }, {
          capture_size: size as unknown as CaptureGeometry['capture_size'], region_crop: crop as unknown as CaptureGeometry['region_crop'],
          matrix: map.matrix as unknown as readonly Rational[],
        });
      } catch (error) {
        if (!(error instanceof CuWireError) || error.code !== 'COORDINATE_MAPPING_UNSUPPORTED') throw error;
        mappingError = error.code;
      }
    }
  }
  return Object.freeze({ encoding: canonicalizeCuValue(doc.value), resolved, mappingError,
    approvalPreimage: null, approvalDigest: null, policyAdopted: false, executionAuthorized: false });
}
