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
