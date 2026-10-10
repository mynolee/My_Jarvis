import {afterEach,it,expect,vi} from 'vitest';
import {Api} from './api';
afterEach(()=>vi.unstubAllGlobals());
it('does not erase new session csrf when an earlier-session GET returns unauthorized',async()=>{
 let resolveOld!:(value:Response)=>void;
 const fetcher=vi.fn().mockImplementationOnce(()=>new Promise(resolve=>{resolveOld=resolve})).mockResolvedValue(new Response(JSON.stringify({data:{}}),{headers:{'content-type':'application/json'}}));
 vi.stubGlobal('fetch',fetcher);
 const api=new Api();api.set_csrf('old');
 const old=api.request('/me').catch(error=>error);
 api.clear();api.set_csrf('new');
 resolveOld(new Response(null,{status:401}));await old;
 await expect(api.request('/conversations',{})).resolves.toEqual({});
 expect(fetcher.mock.calls[1][1].headers['X-CSRF-Token']).toBe('new');
});
it('fences pre-emergency late 401 without replacing the initiating csrf', async () => {
 let rejectOld!: (value: Response) => void;
 const fetcher = vi.fn().mockImplementationOnce(() => new Promise(resolve => { rejectOld = resolve; })).mockResolvedValue(new Response(JSON.stringify({ data: {} }), { headers: { 'content-type': 'application/json' } }));
 vi.stubGlobal('fetch', fetcher); const api = new Api(); api.set_csrf('retained-token');
 const old = api.request('/me').catch(error => error); api.fence_requests();
 rejectOld(new Response(null, { status: 401 })); await old;
 await expect(api.request('/execution/pause', { expected_version: 1 })).resolves.toEqual({});
 expect(fetcher.mock.calls[1][1].headers['X-CSRF-Token']).toBe('retained-token');
});
it('still clears csrf when a current-generation 401 occurs after an emergency fence', async () => {
 const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 401 })); vi.stubGlobal('fetch', fetcher);
 const api = new Api(); api.set_csrf('current-token'); api.fence_requests();
 await expect(api.request('/execution')).rejects.toMatchObject({ status: 401 });
 await expect(api.request('/execution/pause', { expected_version: 1 })).rejects.toMatchObject({ code: 'CSRF_REQUIRED' });
 expect(fetcher).toHaveBeenCalledTimes(1);
});
it('does not let an old mutation 401 erase confirmed rotated credentials', async () => {
 let finishOld!: (value: Response) => void;
 const fetcher = vi.fn().mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve; })).mockResolvedValue(new Response(JSON.stringify({ data: {} }), { headers: { 'content-type': 'application/json' } })); vi.stubGlobal('fetch', fetcher);
 const api = new Api(); api.set_csrf('before'); const old = api.request('/projects', { name: 'synthetic' }).catch(error => error);
 api.fence_requests(); api.set_csrf('after-reauth'); finishOld(new Response(null, { status: 401 })); await old;
 await api.request('/execution/pause', { expected_version: 1 }); expect(fetcher.mock.calls[1][1].headers['X-CSRF-Token']).toBe('after-reauth');
});
