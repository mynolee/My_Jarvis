import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { empty_mail_draft, read_mail_draft, read_mail_metadata, read_mail_page, read_mail_receipt, read_mail_result, valid_mail_create, valid_mail_patch } from './mail-drafts';
const cases = JSON.parse(readFileSync('../../packages/contracts/fixtures/mail-drafts/cases.json', 'utf8')) as { name: string; schema: string; instance: unknown; valid: boolean }[];
const readers: Record<string, (v: unknown) => unknown> = { 'create-request': valid_mail_create, 'patch-request': valid_mail_patch, record: read_mail_draft, metadata: read_mail_metadata, list: read_mail_page, 'mutation-receipt': read_mail_receipt, 'mutation-result': read_mail_result };
describe('strict mail draft wire parser', () => {
  for (const c of cases) { const name = c.schema.replace('mail-draft-', ''); if (!readers[name]) continue; it(c.name, () => { let valid = false; try { const result = readers[name](c.instance); valid = typeof result === 'boolean' ? result : true; } catch {} expect(valid).toBe(c.valid); }); }
  it('rejects combined recipients and header injection while allowing multiline plaintext', () => { expect(valid_mail_create({ ...empty_mail_draft(), to: Array(20).fill('candidate'), cc: Array(11).fill('candidate') })).toBe(false); expect(valid_mail_create({ ...empty_mail_draft(), subject: 'a\r\nBcc: victim' })).toBe(false); expect(valid_mail_create({ ...empty_mail_draft(), body: '<script>inert</script>\r\n\t' })).toBe(true); });
  it('counts astral scalars and rejects lone surrogates', () => { expect(valid_mail_create({ ...empty_mail_draft(), subject: '😀'.repeat(200) })).toBe(true); expect(valid_mail_create({ ...empty_mail_draft(), subject: '😀'.repeat(201) })).toBe(false); expect(valid_mail_create({ ...empty_mail_draft(), body: '\ud800' })).toBe(false); });
  it('rejects oversized aggregate content in a returned record', () => { const v = { ...empty_mail_draft(), body: '😀'.repeat(16000), to: Array(30).fill('x'.repeat(320)), id: '11111111-1111-4111-8111-111111111111', version: 1, created_at: '2030-01-01T00:00:00Z', updated_at: '2030-01-01T00:00:00Z', delivery_status: 'NOT_SENT' }; expect(() => read_mail_draft(v)).toThrow(); });

});
