import { describe, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { history_path, merge_history, read_conversation_page, read_history, read_history_page } from './conversation-history';
const id = '11111111-1111-4111-8111-111111111111';
const message = { id, role: 'USER', run_id: id, text: '<img src=x>', created_at: '2026-10-10T00:00:00Z', execution_mode: 'MOCK' as const };
const page = { items: [message], next_cursor: 'opaque/+=', current_run: null, stream_cursor: { event_id: id, sequence: 2 } };
describe('canonical conversation history', () => {
  it('retains server chronological ordering and independent canonical run', () => { expect(read_history_page(page)).toEqual(page); expect(read_history_page({ ...page, current_run: { id, status: 'RUNNING', version: 1, result: null, approval_id: null, job_id: null, execution_mode: 'MOCK' } }).current_run?.status).toBe('RUNNING'); });
  it('encodes opaque cursors and rejects route injection', () => { expect(history_path(id, 'opaque/+=')).toContain('cursor=opaque%2F%2B%3D'); expect(() => history_path('../auth')).toThrow(); });
  it('rejects malformed pages and duplicate IDs', () => { for (const bad of [{...page,items:[message,message]}, {...page,next_cursor:''}, {...page,current_run:{}}, {...page,items:[{...message,execution_mode:'remote'}]}, {...page,stream_cursor:{event_id:id,sequence:-1}}]) expect(() => read_history_page(bad)).toThrow(); expect(() => read_conversation_page({items:[{}],next_cursor:null})).toThrow(); });
  it('deduplicates overlapping historical pages', () => { expect(merge_history([message], [message])).toEqual([message]); });
  it('bounds transient read retries and never retries authorization failures', async () => { const api = new Api(); const request = vi.spyOn(api,'request').mockRejectedValue(new ApiError('CONNECTION_UNAVAILABLE')); await expect(read_history(api,'/conversations',read_conversation_page)).rejects.toThrow(); expect(request).toHaveBeenCalledTimes(2); request.mockClear().mockRejectedValue(new ApiError('UNAUTHENTICATED',false,401)); await expect(read_history(api,'/conversations',read_conversation_page)).rejects.toThrow(); expect(request).toHaveBeenCalledTimes(1); });
  it('does not retry or return an obsolete generation', async () => { const api = new Api(); vi.spyOn(api,'request').mockResolvedValue(page); await expect(read_history(api,history_path(id),read_history_page,()=>false)).rejects.toThrow('CANCELLED'); expect(api.request).not.toHaveBeenCalled(); });
});
