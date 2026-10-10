import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,it,expect,vi} from 'vitest';
import {App} from './App';
import {Api,ApiError} from './api';
vi.mock('./conversation-history-events',()=>({watch_history_events:vi.fn(()=>vi.fn())}));
afterEach(cleanup);
const a='11111111-1111-4111-8111-111111111111',b='22222222-2222-4222-8222-222222222222';
it('retains mutation uncertainty if the user switches conversations while sending',async()=>{
 const api=new Api();let rejectSend!:(e:unknown)=>void;
 vi.spyOn(api,'bootstrap_csrf').mockResolvedValue();
 vi.spyOn(api,'request').mockImplementation(async (path,body)=>{
  if(path==='/me')return {};
  if(path==='/local-model/status')return {mode:'LOCAL_MLX',model_id:'local-mlx-v1',ready:false,status:'DISABLED'};
  if(path.startsWith('/conversations?'))return {items:[{id:a,title:'A',project_id:null,created_at:'2026-10-10T00:00:00Z'},{id:b,title:'B',project_id:null,created_at:'2026-10-10T00:00:00Z'}],next_cursor:null};
  if(path.endsWith('/messages')&&body)return new Promise((_,reject)=>{rejectSend=reject});
  if(path.includes('/messages?'))return {items:[],next_cursor:null,stream_cursor:null,current_run:null};
  throw Error(path);
 });
 render(<App api={api}/>);
 await screen.findByRole('button',{name:'B'});
 await waitFor(()=>expect(screen.getByRole('button',{name:'A'}).getAttribute('aria-current')).toBe('true'));
 fireEvent.change(screen.getByLabelText('Message'),{target:{value:'test'}});
 fireEvent.click(screen.getByRole('button',{name:'Send mock message'}));
 await waitFor(()=>expect(rejectSend).toBeTruthy());
 fireEvent.click(screen.getByRole('button',{name:'B'}));
 rejectSend(new ApiError('UNKNOWN',true));
 await screen.findByText('Writes paused');
});
it('clears the same session when an earlier selection approval read returns 401',async()=>{
 const api=new Api();let rejectApproval!:(e:unknown)=>void;
 vi.spyOn(api,'bootstrap_csrf').mockResolvedValue();
 vi.spyOn(api,'request').mockImplementation(async (path)=>{
  if(path==='/me')return {};
  if(path==='/local-model/status')return {mode:'LOCAL_MLX',model_id:'local-mlx-v1',ready:false,status:'DISABLED'};
  if(path.startsWith('/conversations?'))return {items:[{id:a,title:'A',project_id:null,created_at:'2026-10-10T00:00:00Z'},{id:b,title:'B',project_id:null,created_at:'2026-10-10T00:00:00Z'}],next_cursor:null};
  if(path.startsWith('/approvals/'))return new Promise((_,reject)=>{rejectApproval=reject});
  if(path.includes('/messages?'))return {items:[],next_cursor:null,stream_cursor:null,current_run:path.includes(a)?{id:a,status:'WAITING_APPROVAL',version:1,result:null,approval_id:a,job_id:null,execution_mode:'MOCK'}:null};
  throw Error(path);
 });
 render(<App api={api}/>);
 await waitFor(()=>expect(rejectApproval).toBeTruthy());
 fireEvent.click(screen.getByRole('button',{name:'B'}));
 await waitFor(()=>expect(screen.getByRole('button',{name:'B'}).getAttribute('aria-current')).toBe('true'));
 rejectApproval(new ApiError('UNAUTHENTICATED',false,401));
 await screen.findByRole('heading',{name:'Owner sign in'});
});
