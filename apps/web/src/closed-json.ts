const fail = (): never => { throw new Error('INVALID_CLOSED_JSON'); };
const integer = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const unicode = (value: unknown): value is string => typeof value === 'string' && !/[\u0000\uD800-\uDFFF]/u.test(value);

/** Parse bounded JSON before JSON.parse can discard duplicate keys or numeric syntax. */
export function parse_closed_json(text: string, limit: number): unknown {
  if (typeof text !== 'string' || text.length === 0 || text.length > limit || new TextEncoder().encode(text).length > limit) return fail();
  let i = 0;
  const whitespace = () => { while (i < text.length && /[\t\n\r ]/.test(text[i])) i++; };
  function string(): string {
    const start = i++;
    while (i < text.length) {
      const character = text[i++];
      if (character === '\\') i++;
      else if (character === '"') { const result: unknown = JSON.parse(text.slice(start, i)); if (!unicode(result)) return fail(); return result; }
    }
    return fail();
  }
  function value(depth: number): void {
    if (depth > 12) return fail(); whitespace();
    if (text[i] === '"') { string(); return; }
    if (text[i] === '{') {
      i++; whitespace(); const keys = new Set<string>(); if (text[i] === '}') { i++; return; }
      for (;;) { whitespace(); if (text[i] !== '"') return fail(); const key = string(); if (keys.has(key)) return fail(); keys.add(key); whitespace(); if (text[i++] !== ':') return fail(); value(depth + 1); whitespace(); const separator = text[i++]; if (separator === '}') return; if (separator !== ',') return fail(); }
    }
    if (text[i] === '[') {
      i++; whitespace(); if (text[i] === ']') { i++; return; }
      for (;;) { value(depth + 1); whitespace(); const separator = text[i++]; if (separator === ']') return; if (separator !== ',') return fail(); }
    }
    const start = i; while (i < text.length && !/[\t\n\r ,}\]]/.test(text[i])) i++; if (i === start) return fail();
    const token = text.slice(start, i);
    if (token !== 'null' && token !== 'true' && token !== 'false' && (!/^(?:0|[1-9][0-9]*)$(?![\s\S])/.test(token) || !integer(Number(token)))) return fail();
  }
  try { value(0); whitespace(); if (i !== text.length) return fail(); return JSON.parse(text) as unknown; }
  catch { return fail(); }
}
