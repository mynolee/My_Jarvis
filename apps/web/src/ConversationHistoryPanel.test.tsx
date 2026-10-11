import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, ApiError } from './api';
import { ConversationHistoryPanel } from './ConversationHistoryPanel';
import { watch_history_events } from './conversation-history-events';
vi.mock('./conversation-history-events',()=>({watch_history_events:vi.fn(()=>vi.fn())}));
const id='11111111-1111-4111-8111-111111111111', other='22222222-2222-4222-8222-222222222222';
const conversation=(key=id)=>({id:key,title:key===id?'First saved chat':'Second saved chat',project_id:null,created_at:'2026-10-10T00:00:00Z'});
const message=(key=id)=>({id:key,run_id:id,role:'ASSISTANT',text:'<img src="https://example.invalid/private">',created_at:'2026-10-10T00:00:00Z',execution_mode:'MOCK'});
const page={items:[message()],next_cursor:null,current_run:null,stream_cursor:null};
afterEach(()=>{cleanup();vi.clearAllMocks();});
describe('saved conversation interface',()=>{
  it('shows keyboard-operable choices, escaped transcript and execution mode',async()=>{const api=new Api();vi.spyOn(api,'request').mockImplementation(async path=>path.startsWith('/conversations?')?{items:[conversation()],next_cursor:null}:page);const onSnapshot=vi.fn(),onSelect=vi.fn();const {container}=render(<ConversationHistoryPanel api={api} conversationId={id} onSelect={onSelect} onSnapshot={onSnapshot} onUnauthorized={vi.fn()}/>);await screen.findByText(message().text);expect(container.querySelector('img,script,a,iframe')).toBeNull();expect(screen.getByText('· Mock response')).toBeTruthy();expect(screen.getByRole('button',{name:'First saved chat'}).getAttribute('aria-current')).toBe('true');expect(onSnapshot).toHaveBeenCalledWith(id,page);});
  it('selects the newest saved conversation once on initial load only',async()=>{const api=new Api();vi.spyOn(api,'request').mockResolvedValue({items:[conversation()],next_cursor:null});const onSelect=vi.fn();render(<ConversationHistoryPanel api={api} conversationId={null} onSelect={onSelect} onSnapshot={vi.fn()} onUnauthorized={vi.fn()}/>);await waitFor(()=>expect(onSelect).toHaveBeenCalledTimes(1));fireEvent.click(screen.getByRole('button',{name:'Refresh conversations'}));await waitFor(()=>expect(api.request).toHaveBeenCalledTimes(2));expect(onSelect).toHaveBeenCalledTimes(1);});
  it('loads older messages chronologically and deduplicates overlapping IDs',async()=>{const api=new Api();vi.spyOn(api,'request').mockImplementation(async path=>path.startsWith('/conversations?')?{items:[conversation()],next_cursor:null}:path.includes('cursor=older')?{...page,items:[{...message(other),text:'Older saved answer'},message()]}:{...page,next_cursor:'older'});render(<ConversationHistoryPanel api={api} conversationId={id} onSelect={vi.fn()} onSnapshot={vi.fn()} onUnauthorized={vi.fn()}/>);fireEvent.click(await screen.findByRole('button',{name:'Load older messages'}));await screen.findByText('Older saved answer');expect(screen.getAllByText(message().text)).toHaveLength(1);expect(screen.queryByRole('button',{name:'Load older messages'})).toBeNull();});
  it('fences deferred history reads when the selected conversation changes',async()=>{const api=new Api();let resolveOld!:(value:unknown)=>void;vi.spyOn(api,'request').mockImplementation(async path=>path.startsWith('/conversations?')?{items:[conversation(),conversation(other)],next_cursor:null}:path.includes(id)?new Promise(resolve=>{resolveOld=resolve;}):{...page,items:[{...message(other),text:'Current selection'}]});const onSnapshot=vi.fn();const props={api,onSelect:vi.fn(),onSnapshot,onUnauthorized:vi.fn()};const {rerender}=render(<ConversationHistoryPanel {...props} conversationId={id}/>);await waitFor(()=>expect(resolveOld).toBeTruthy());rerender(<ConversationHistoryPanel {...props} conversationId={other}/>);await screen.findByText('Current selection');resolveOld(page);await waitFor(()=>expect(onSnapshot).toHaveBeenCalledTimes(1));expect(onSnapshot.mock.calls[0][0]).toBe(other);expect(screen.queryByText(message().text)).toBeNull();});
  it('does not navigate or interrupt the stream while a write locks selection',async()=>{const api=new Api();vi.spyOn(api,'request').mockImplementation(async path=>path.startsWith('/conversations?')?{items:[conversation(),conversation(other)],next_cursor:null}:page);const onSelect=vi.fn();render(<ConversationHistoryPanel api={api} conversationId={id} selectionDisabled onSelect={onSelect} onSnapshot={vi.fn()} onUnauthorized={vi.fn()}/>);await screen.findByText(message().text);const choice=screen.getByRole('button',{name:'Second saved chat'}) as HTMLButtonElement;expect(choice.disabled).toBe(true);fireEvent.click(choice);expect(onSelect).not.toHaveBeenCalled();const stop=vi.mocked(watch_history_events).mock.results[0].value;expect(stop).not.toHaveBeenCalled();expect((screen.getByRole('button',{name:'Refresh conversations'}) as HTMLButtonElement).disabled).toBe(false);});
  it('clears the current session on a delayed history 401 after switching conversations',async()=>{const api=new Api();let rejectOld!:(error:unknown)=>void;vi.spyOn(api,'request').mockImplementation(async path=>path.startsWith('/conversations?')?{items:[conversation(),conversation(other)],next_cursor:null}:path.includes(id)?new Promise((_,reject)=>{rejectOld=reject;}):{...page,items:[{...message(other),text:'Private second conversation'}]});const onUnauthorized=vi.fn();const props={api,onSelect:vi.fn(),onSnapshot:vi.fn(),onUnauthorized};const {rerender}=render(<ConversationHistoryPanel {...props} conversationId={id}/>);await waitFor(()=>expect(rejectOld).toBeTruthy());rerender(<ConversationHistoryPanel {...props} conversationId={other}/>);await screen.findByText('Private second conversation');await act(async()=>{rejectOld(new ApiError('UNAUTHENTICATED',false,401));});expect(onUnauthorized).toHaveBeenCalledTimes(1);expect(screen.queryByText('Private second conversation')).toBeNull();expect(screen.queryByRole('button',{name:'Second saved chat'})).toBeNull();});
  it('does not let an unmounted prior session 401 clear a new session',async()=>{const oldApi=new Api();let rejectOld!:(error:unknown)=>void;vi.spyOn(oldApi,'request').mockImplementation(async path=>path.startsWith('/conversations?')?{items:[conversation()],next_cursor:null}:new Promise((_,reject)=>{rejectOld=reject;}));const oldUnauthorized=vi.fn();const old=render(<ConversationHistoryPanel api={oldApi} conversationId={id} onSelect={vi.fn()} onSnapshot={vi.fn()} onUnauthorized={oldUnauthorized}/>);await waitFor(()=>expect(rejectOld).toBeTruthy());old.unmount();const api=new Api();vi.spyOn(api,'request').mockImplementation(async path=>path.startsWith('/conversations?')?{items:[conversation(other)],next_cursor:null}:{...page,items:[{...message(other),text:'New session transcript'}]});const onUnauthorized=vi.fn();render(<ConversationHistoryPanel api={api} conversationId={other} onSelect={vi.fn()} onSnapshot={vi.fn()} onUnauthorized={onUnauthorized}/>);await screen.findByText('New session transcript');await act(async()=>{rejectOld(new ApiError('UNAUTHENTICATED',false,401));});expect(oldUnauthorized).not.toHaveBeenCalled();expect(onUnauthorized).not.toHaveBeenCalled();expect(screen.getByText('New session transcript')).toBeTruthy();});
  it('clears history and notifies the parent exactly once on unauthorized reads',async()=>{const api=new Api();vi.spyOn(api,'request').mockRejectedValue(new ApiError('UNAUTHENTICATED',false,401));const onUnauthorized=vi.fn();render(<ConversationHistoryPanel api={api} conversationId={id} onSelect={vi.fn()} onSnapshot={vi.fn()} onUnauthorized={onUnauthorized}/>);await waitFor(()=>expect(onUnauthorized).toHaveBeenCalledTimes(1));expect(screen.queryByText(message().text)).toBeNull();expect(watch_history_events).not.toHaveBeenCalled();});
});

describe('remote conversation deletion barriers',()=>{
  it('clears visible private content and fences deferred pages, list reads and stream callbacks',async()=>{
    const api=new Api();let latest=0,lists=0;let resolveOlder!:(value:unknown)=>void,resolveList!:(value:unknown)=>void;
    vi.spyOn(api,'request').mockImplementation(async path=>{
      if(path.startsWith('/conversations?')){if(++lists===2)return new Promise(resolve=>{resolveList=resolve;});return {items:[conversation(),conversation(other)],next_cursor:null};}
      if(path.includes('cursor=older'))return new Promise(resolve=>{resolveOlder=resolve;});
      if(++latest===1)return {...page,next_cursor:'older'};
      throw new ApiError('CONVERSATION_DELETED',false,410);
    });
    const onDeleted=vi.fn(),onSnapshot=vi.fn(),onUnauthorized=vi.fn();
    render(<ConversationHistoryPanel api={api} conversationId={id} onSelect={vi.fn()} onSnapshot={onSnapshot} onDeleted={onDeleted} onUnauthorized={onUnauthorized}/>);
    await screen.findByText(message().text);await waitFor(()=>expect(watch_history_events).toHaveBeenCalledTimes(1));
    const options=vi.mocked(watch_history_events).mock.calls[0][0],stop=vi.mocked(watch_history_events).mock.results[0].value;
    fireEvent.click(screen.getByRole('button',{name:'Load older messages'}));fireEvent.click(screen.getByRole('button',{name:'Refresh conversations'}));
    await waitFor(()=>{expect(resolveOlder).toBeTruthy();expect(resolveList).toBeTruthy();});
    await act(async()=>{options.onUpdate();});await waitFor(()=>expect(onDeleted).toHaveBeenCalledExactlyOnceWith(id));
    expect(screen.queryByText(message().text)).toBeNull();expect(screen.queryByRole('button',{name:'First saved chat'})).toBeNull();
    expect(screen.queryByRole('button',{name:/Load older messages|Loading older messages/})).toBeNull();expect(stop).toHaveBeenCalledTimes(1);expect(onUnauthorized).not.toHaveBeenCalled();
    expect(screen.getByRole('button',{name:'Second saved chat'})).toBeTruthy();expect(screen.getByText('This conversation was deleted.')).toBeTruthy();
    await act(async()=>{resolveOlder({...page,items:[{...message(other),text:'Deferred private older sentinel'}]});resolveList({items:[conversation()],next_cursor:null});});
    expect(screen.queryByText('Deferred private older sentinel')).toBeNull();expect(screen.queryByRole('button',{name:'First saved chat'})).toBeNull();expect(onSnapshot).toHaveBeenCalledTimes(1);
    const calls=vi.mocked(api.request).mock.calls.length;await act(async()=>{options.onUpdate();options.onState('CONNECTED');});expect(api.request).toHaveBeenCalledTimes(calls);
    fireEvent.click(screen.getByRole('button',{name:'Refresh conversations'}));await waitFor(()=>expect(api.request).toHaveBeenCalledTimes(calls+1));
    expect(screen.queryByRole('button',{name:'First saved chat'})).toBeNull();expect(onDeleted).toHaveBeenCalledTimes(1);
  });
  it('handles exact deletion from an older-page read without retaining its visible transcript',async()=>{
    const api=new Api();vi.spyOn(api,'request').mockImplementation(async path=>{if(path.startsWith('/conversations?'))return {items:[conversation()],next_cursor:null};if(path.includes('cursor=older'))throw new ApiError('CONVERSATION_DELETED',false,410);return {...page,next_cursor:'older'};});
    const onDeleted=vi.fn();render(<ConversationHistoryPanel api={api} conversationId={id} onSelect={vi.fn()} onSnapshot={vi.fn()} onDeleted={onDeleted} onUnauthorized={vi.fn()}/>);
    await screen.findByText(message().text);fireEvent.click(screen.getByRole('button',{name:'Load older messages'}));await waitFor(()=>expect(onDeleted).toHaveBeenCalledExactlyOnceWith(id));
    expect(screen.queryByText(message().text)).toBeNull();expect(screen.queryByRole('button',{name:'First saved chat'})).toBeNull();
  });
  it('handles a refresh deletion even without a parent callback and cannot reload a known deleted target',async()=>{
    const api=new Api();let reads=0;vi.spyOn(api,'request').mockImplementation(async path=>{if(path.startsWith('/conversations?'))return {items:[conversation()],next_cursor:null};if(++reads===1)return page;throw new ApiError('CONVERSATION_DELETED',false,410);});
    const props={api,conversationId:id,onSelect:vi.fn(),onSnapshot:vi.fn(),onUnauthorized:vi.fn()};const view=render(<ConversationHistoryPanel {...props}/>);
    await screen.findByText(message().text);view.rerender(<ConversationHistoryPanel {...props} refreshKey={1}/>);await screen.findByText('This conversation was deleted.');
    expect(screen.queryByText(message().text)).toBeNull();expect(screen.queryByRole('button',{name:'First saved chat'})).toBeNull();
    view.rerender(<ConversationHistoryPanel {...props} conversationId={null} refreshKey={1}/>);view.rerender(<ConversationHistoryPanel {...props} refreshKey={1}/>);
    await screen.findByText('This conversation was deleted.');expect(reads).toBe(2);expect(screen.queryByText(message().text)).toBeNull();
  });
  it('verifies a blocked stream with one canonical read before recognizing deletion',async()=>{
    const api=new Api();let reads=0;vi.spyOn(api,'request').mockImplementation(async path=>{if(path.startsWith('/conversations?'))return {items:[conversation()],next_cursor:null};if(++reads===1)return page;throw new ApiError('CONVERSATION_DELETED',false,410);});
    const onDeleted=vi.fn();render(<ConversationHistoryPanel api={api} conversationId={id} onSelect={vi.fn()} onSnapshot={vi.fn()} onDeleted={onDeleted} onUnauthorized={vi.fn()}/>);
    await screen.findByText(message().text);await waitFor(()=>expect(watch_history_events).toHaveBeenCalledTimes(1));const options=vi.mocked(watch_history_events).mock.calls[0][0];
    await act(async()=>{options.onState('BLOCKED');});await waitFor(()=>expect(onDeleted).toHaveBeenCalledExactlyOnceWith(id));expect(reads).toBe(2);expect(screen.queryByText(message().text)).toBeNull();
  });
  it('routes a gap recovery deletion through the same private-state fence',async()=>{
    const api=new Api();let reads=0;vi.spyOn(api,'request').mockImplementation(async path=>{if(path.startsWith('/conversations?'))return {items:[conversation()],next_cursor:null};if(++reads===1)return page;throw new ApiError('CONVERSATION_DELETED',false,410);});
    const onDeleted=vi.fn();render(<ConversationHistoryPanel api={api} conversationId={id} onSelect={vi.fn()} onSnapshot={vi.fn()} onDeleted={onDeleted} onUnauthorized={vi.fn()}/>);
    await screen.findByText(message().text);await waitFor(()=>expect(watch_history_events).toHaveBeenCalledTimes(1));const options=vi.mocked(watch_history_events).mock.calls[0][0];
    await act(async()=>{await expect(options.onGap()).rejects.toMatchObject({code:'CONVERSATION_DELETED',status:410});});
    expect(onDeleted).toHaveBeenCalledExactlyOnceWith(id);expect(screen.queryByText(message().text)).toBeNull();
  });
  it.each([[404,'NOT_FOUND'],[410,'EVENT_CURSOR_UNAVAILABLE'],[404,'CONVERSATION_DELETED']] as const)('never treats %s/%s as proof of target deletion',async(status,code)=>{
    const api=new Api();let reads=0;vi.spyOn(api,'request').mockImplementation(async path=>{if(path.startsWith('/conversations?'))return {items:[conversation()],next_cursor:null};if(++reads===1)return page;throw new ApiError(code,false,status);});
    const onDeleted=vi.fn();render(<ConversationHistoryPanel api={api} conversationId={id} onSelect={vi.fn()} onSnapshot={vi.fn()} onDeleted={onDeleted} onUnauthorized={vi.fn()}/>);
    await screen.findByText(message().text);await waitFor(()=>expect(watch_history_events).toHaveBeenCalledTimes(1));await act(async()=>{vi.mocked(watch_history_events).mock.calls[0][0].onUpdate();});
    await screen.findByText(code);expect(onDeleted).not.toHaveBeenCalled();expect(screen.getByText(message().text)).toBeTruthy();
  });
  it('does not clear a new selection when a delayed old-target read reports deletion',async()=>{
    const api=new Api();let oldReads=0;let rejectOld!:(error:unknown)=>void;
    vi.spyOn(api,'request').mockImplementation(async path=>{if(path.startsWith('/conversations?'))return {items:[conversation(),conversation(other)],next_cursor:null};if(path.includes(other))return {...page,items:[{...message(other),text:'Current unrelated private sentinel'}]};if(++oldReads===1)return page;return new Promise((_,reject)=>{rejectOld=reject;});});
    const onDeleted=vi.fn(),onSnapshot=vi.fn();const props={api,onSelect:vi.fn(),onSnapshot,onDeleted,onUnauthorized:vi.fn()};const view=render(<ConversationHistoryPanel {...props} conversationId={id}/>);
    await screen.findByText(message().text);await waitFor(()=>expect(watch_history_events).toHaveBeenCalledTimes(1));await act(async()=>{vi.mocked(watch_history_events).mock.calls[0][0].onUpdate();});await waitFor(()=>expect(rejectOld).toBeTruthy());
    view.rerender(<ConversationHistoryPanel {...props} conversationId={other}/>);await screen.findByText('Current unrelated private sentinel');
    await act(async()=>{rejectOld(new ApiError('CONVERSATION_DELETED',false,410));});
    expect(onDeleted).not.toHaveBeenCalled();expect(screen.getByText('Current unrelated private sentinel')).toBeTruthy();expect(screen.queryByText('This conversation was deleted.')).toBeNull();
  });
  it('does not carry old-session deletion observations across an API/session change',async()=>{
    const oldApi=new Api();let reads=0;let rejectOld!:(error:unknown)=>void;vi.spyOn(oldApi,'request').mockImplementation(async path=>{if(path.startsWith('/conversations?'))return {items:[conversation()],next_cursor:null};if(++reads===1)return page;return new Promise((_,reject)=>{rejectOld=reject;});});
    const onDeleted=vi.fn();const props={conversationId:id,onSelect:vi.fn(),onSnapshot:vi.fn(),onDeleted,onUnauthorized:vi.fn()};const view=render(<ConversationHistoryPanel {...props} api={oldApi}/>);
    await screen.findByText(message().text);await waitFor(()=>expect(watch_history_events).toHaveBeenCalledTimes(1));await act(async()=>{vi.mocked(watch_history_events).mock.calls[0][0].onUpdate();});await waitFor(()=>expect(rejectOld).toBeTruthy());
    const api=new Api();vi.spyOn(api,'request').mockImplementation(async path=>path.startsWith('/conversations?')?{items:[conversation()],next_cursor:null}:{...page,items:[{...message(),text:'New authenticated session sentinel'}]});view.rerender(<ConversationHistoryPanel {...props} api={api}/>);
    await screen.findByText('New authenticated session sentinel');await act(async()=>{rejectOld(new ApiError('CONVERSATION_DELETED',false,410));});
    expect(onDeleted).not.toHaveBeenCalled();expect(screen.getByText('New authenticated session sentinel')).toBeTruthy();
  });
  it('does not infer a selected target from a list-only 410 response',async()=>{
    const api=new Api();let lists=0;vi.spyOn(api,'request').mockImplementation(async path=>{if(!path.startsWith('/conversations?'))return page;if(++lists===1)return {items:[conversation()],next_cursor:null};throw new ApiError('CONVERSATION_DELETED',false,410);});
    const onDeleted=vi.fn();render(<ConversationHistoryPanel api={api} conversationId={id} onSelect={vi.fn()} onSnapshot={vi.fn()} onDeleted={onDeleted} onUnauthorized={vi.fn()}/>);
    await screen.findByText(message().text);fireEvent.click(screen.getByRole('button',{name:'Refresh conversations'}));await screen.findByText('CONVERSATION_DELETED');
    expect(onDeleted).not.toHaveBeenCalled();expect(screen.getByText(message().text)).toBeTruthy();
  });
});
