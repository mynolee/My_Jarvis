import { parse_closed_json } from './closed-json';

export function parse_deletion_json(text: string): unknown {
  try { return parse_closed_json(text, 65536); }
  catch { throw new Error('INVALID_DELETION_RESPONSE'); }
}
