import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useEffect, useRef } from 'react';
import { App } from './App';
import { Api, ApiError } from './api';
import type { Conversation } from './domain';
// Coordinator-only synthetic tests. No real deletion, SQL or browser execution.
type Props = { writesPaused: boolean; selectionDisabled?: boolean; sessionKey?: number; snapshotKey?: number; selectedConversationId?: string | null; onAcquirePending: () => boolean; onPendingChange: (pending: boolean) => void; onContentHiddenChange?: (hidden: boolean) => void; onOutcomeUnknown?: () => void; onDeleted?: (id: string) => void; onUnauthorized: () => void; onReauthenticate?: () => void };
const observed = vi.hoisted(() => ({ acquired: vi.fn(), last: null as Props | null }));
const target = '11111111-1111-4111-8111-111111111111', other = '22222222-2222-4222-8222-222222222222', stamp = '2026-10-10T00:00:00Z';
function acquire(p: Props, domain: string) { if (!p.onAcquirePending()) return false; observed.acquired(domain); p.onPendingChange(true); return true; }
function Peer(p: Props & { domain: string }) { return <><button disabled={p.writesPaused} onClick={() => acquire(p,p.domain)}>Begin {p.domain}</button><button onClick={() => p.onPendingChange(false)}>Resolve {p.domain}</button></>; }
function DeletionProbe(p: Props) {
  observed.last = p; const pending = useRef(false), latest = useRef(p); latest.current = p;
  useEffect(() => () => { if (pending.current) latest.current.onOutcomeUnknown?.(); latest.current.onPendingChange(false); }, []);
  return <section aria-label="Deletion probe"><span>Selected {p.selectedConversationId ?? 'none'}</span><input aria-label="Private deletion marker"/>
    <button disabled={p.writesPaused} onClick={() => { if(acquire(p,'deletion')) { pending.current=true; p.onContentHiddenChange?.(true); } }}>Begin deletion</button>
    <button disabled={p.writesPaused} onClick={() => acquire(p,'preparation')}>Begin preparation</button>
    <button onClick={() => { pending.current=false; p.onContentHiddenChange?.(false); p.onPendingChange(false); }}>Resolve deletion</button>
    <button onClick={() => { p.onDeleted?.(target); pending.current=false; p.onContentHiddenChange?.(false); p.onPendingChange(false); }}>Confirm target deleted</button>
    <button onClick={() => p.onDeleted?.(other)}>Report unrelated deletion</button>
    <button onClick={p.onUnauthorized}>Expire deletion session</button>
    <button onClick={p.onReauthenticate}>Reauthenticate deletion</button>
    <button>Read deletion receipt</button></section>;
}
vi.mock('./ConversationDeletionPanel', () => ({ ConversationDeletionPanel: (p: Props) => <DeletionProbe {...p}/> }));
vi.mock('./TasksPanel', () => ({ TasksPanel: (p: Props) => <><Peer {...p} domain="task"/><input aria-label="Task session marker"/></> }));
vi.mock('./MemoryExportPanel', () => ({ MemoryExportPanel: (p: Props) => <Peer {...p} domain="export"/> }));
vi.mock('./TodayPanel', () => ({ TodayPanel: (p: { sessionKey: number }) => <input aria-label="Today session marker" data-session-key={p.sessionKey}/> }));
vi.mock('./ConversationHistoryPanel', () => ({ ConversationHistoryPanel: (p: { selectionDisabled: boolean; onSelect: (c: Conversation) => void; conversationId: string | null }) => <section aria-label="Source history"><button disabled={p.selectionDisabled} onClick={() => p.onSelect({ id:target, title:'Synthetic source title', project_id:null, created_at:stamp })}>Select target</button>{p.conversationId && <span>Synthetic source title</span>}</section> }));
function fixture(authenticated=true, paused=false) { const api=new Api(); vi.spyOn(api,'bootstrap_csrf').mockResolvedValue(); const request=vi.spyOn(api,'request').mockImplementation(async path => {
  if(path==='/me') { if(!authenticated) throw new ApiError('UNAUTHENTICATED',false,401); return {}; }
  if(path==='/execution') return { execution_paused:paused, version:1, checked_at:stamp };
  if(path==='/local-model/status') return { mode:'LOCAL_MLX',model_id:'local-mlx-v1',ready:false,status:'DISABLED' };
  if(path==='/auth/logout') return {logged_out:true};
  if(path==='/auth/login') { authenticated=true; return {csrf_token:'synthetic-test-token'}; }
  if(path==='/auth/reauthenticate') return {csrf_token:'synthetic-rotated-test-token',authenticated:true};
  throw Error('Unexpected synthetic route');
}); return {api,request}; }
const button=(name:string)=>screen.getByRole<HTMLButtonElement>('button',{name});
const click=(name:string)=>fireEvent.click(button(name));
async function open(paused=false) { await screen.findByText(`Account state: ${paused?'PAUSED':'AVAILABLE FOR NEW WORK'}`); click('Select target'); click('대화 원문 삭제 열기'); }
afterEach(()=>{cleanup();vi.restoreAllMocks();observed.acquired.mockClear();observed.last=null;});
it('requires authentication and explicit opening without issuing deletion writes',async()=>{const {api,request}=fixture(false);render(<App api={api}/>);await screen.findByRole('heading',{name:'Owner sign in'});expect(screen.queryByText('대화 원문 삭제 열기')).toBeNull();expect(request.mock.calls.some(([,body])=>body!==undefined)).toBe(false);});
it('selects the exact conversation and clears private staged input on close',async()=>{const {api}=fixture();render(<App api={api}/>);await open();expect(screen.getByText(`Selected ${target}`)).toBeTruthy();fireEvent.change(screen.getByLabelText('Private deletion marker'),{target:{value:'private synthetic review'}});click('대화 원문 삭제 닫기');click('대화 원문 삭제 열기');expect((screen.getByLabelText('Private deletion marker') as HTMLInputElement).value).toBe('');});
it('fences peers, close and navigation while hiding source during unresolved deletion',async()=>{const {api,request}=fixture();render(<App api={api}/>);await open();click('Open internal tasks');click('기억 내보내기 열기');click('Begin deletion');for(const name of ['Begin task','Begin export','Start new conversation','대화 원문 삭제 닫기'])expect(button(name).disabled).toBe(true);expect(screen.queryByRole('region',{name:'Source history'})).toBeNull();expect(screen.queryByText('Synthetic source title')).toBeNull();expect(button('Read deletion receipt').disabled).toBe(false);click('Refresh server state');await waitFor(()=>expect(button('Start new conversation').disabled).toBe(true));click('Resolve deletion');expect(screen.getByRole('region',{name:'Source history'})).toBeTruthy();expect(button('Begin task').disabled).toBe(false);expect(request.mock.calls.some(([,body])=>body!==undefined)).toBe(false);});
it.each(['task','export'])('refuses deletion while %s is pending',async domain=>{const {api}=fixture();render(<App api={api}/>);await open();click(domain==='task'?'Open internal tasks':'기억 내보내기 열기');click(`Begin ${domain}`);expect(button('Begin deletion').disabled).toBe(true);click('Begin deletion');expect(observed.acquired).toHaveBeenCalledExactlyOnceWith(domain);click(`Resolve ${domain}`);expect(button('Begin deletion').disabled).toBe(false);});
it.each(['deletion','task'])('atomically blocks a same-turn competing acquisition after %s',async first=>{const {api}=fixture();render(<App api={api}/>);await open();click('Open internal tasks');const second=first==='deletion'?'task':'deletion';act(()=>{button(`Begin ${first}`).click();button(`Begin ${second}`).click();});expect(observed.acquired).toHaveBeenCalledExactlyOnceWith(first);});
it('blocks same-turn close before a pending render',async()=>{const {api}=fixture();render(<App api={api}/>);await open();act(()=>{button('Begin deletion').click();button('대화 원문 삭제 닫기').click();});expect(screen.getByRole('region',{name:'Deletion probe'})).toBeTruthy();expect(button('대화 원문 삭제 닫기').disabled).toBe(true);});
it('clears exactly the confirmed selected target and refreshes without replay',async()=>{const {api,request}=fixture();render(<App api={api}/>);await open();click('Report unrelated deletion');expect(screen.getByText(`Selected ${target}`)).toBeTruthy();click('Begin deletion');click('Confirm target deleted');expect(screen.getByText('Selected none')).toBeTruthy();expect(screen.queryByText('Synthetic source title')).toBeNull();expect(button('Start new conversation').disabled).toBe(false);expect(request.mock.calls.some(([,body])=>body!==undefined)).toBe(false);});
it('allows read recovery while paused but blocks new deletion',async()=>{const {api}=fixture(true,true);render(<App api={api}/>);await open(true);expect(button('Begin deletion').disabled).toBe(true);expect(button('Read deletion receipt').disabled).toBe(false);});
it('clears source on session expiry and rejects stale completion callbacks',async()=>{const {api}=fixture();render(<App api={api}/>);await open();const old=observed.last!;click('Begin deletion');click('Expire deletion session');await screen.findByRole('heading',{name:'Owner sign in'});act(()=>{old.onDeleted?.(target);old.onPendingChange(true);});expect(screen.queryByText(/대화 원문 삭제가 확인/)).toBeNull();expect(screen.queryByText('Synthetic source title')).toBeNull();expect(old.onAcquirePending()).toBe(false);});

it('keeps history mounted during preparation so release does not itself invalidate review',async()=>{const {api}=fixture();render(<App api={api}/>);await open();click('Begin preparation');expect(screen.getByRole('region',{name:'Source history'})).toBeTruthy();expect(button('Start new conversation').disabled).toBe(true);click('Resolve deletion');expect(screen.getByText(`Selected ${target}`)).toBeTruthy();});

it.each(['reauthentication', 'sign-out and sign-in'])('keeps sibling keys distinct and isolates panel state across %s', async boundary => {
  // This spy calls through: unexpected React diagnostics remain visible in stderr.
  const consoleError = vi.spyOn(console, 'error');
  const { api } = fixture(); const view = render(<App api={api}/>); await open();
  const oldToday = screen.getByLabelText<HTMLInputElement>('Today session marker');
  const oldSession = oldToday.dataset.sessionKey;
  fireEvent.change(oldToday, { target: { value: 'Private previous Today view' } });
  click('Open internal tasks');
  const oldTask = screen.getByLabelText<HTMLInputElement>('Task session marker');
  fireEvent.change(oldTask, { target: { value: 'Private previous task view' } });
  click('Refresh server state');
  await waitFor(() => expect(button('Refresh server state').disabled).toBe(false));
  expect(screen.getByLabelText('Today session marker')).toBe(oldToday);
  expect(screen.getByLabelText('Task session marker')).toBe(oldTask);
  expect(oldToday.value).toBe('Private previous Today view');
  expect(oldTask.value).toBe('Private previous task view');

  if (boundary === 'reauthentication') {
    click('Reauthenticate deletion');
    fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'synthetic-password' } });
    click('Confirm identity');
    await screen.findByText('Identity confirmed. Review the current snapshot before deciding.');
  } else {
    click('Sign out');
    await screen.findByRole('heading', { name: 'Owner sign in' });
    expect(oldToday.isConnected).toBe(false); expect(oldTask.isConnected).toBe(false);
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'synthetic-owner' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } });
    await waitFor(() => expect(button('Sign in').disabled).toBe(false));
    click('Sign in');
    await screen.findByText('Account state: AVAILABLE FOR NEW WORK');
    expect(screen.queryByLabelText('Task session marker')).toBeNull();
    click('Open internal tasks');
  }

  const newToday = await screen.findByLabelText<HTMLInputElement>('Today session marker');
  const newTask = await screen.findByLabelText<HTMLInputElement>('Task session marker');
  expect(newToday).not.toBe(oldToday); expect(newTask).not.toBe(oldTask);
  expect(oldToday.isConnected).toBe(false); expect(oldTask.isConnected).toBe(false);
  expect(newToday.dataset.sessionKey).not.toBe(oldSession);
  expect(newToday.value).toBe(''); expect(newTask.value).toBe('');
  fireEvent.change(oldToday, { target: { value: 'Late detached Today edit' } });
  fireEvent.change(oldTask, { target: { value: 'Late detached task edit' } });
  expect(newToday.value).toBe(''); expect(newTask.value).toBe('');
  view.unmount();
  const duplicateKeyWarnings = consoleError.mock.calls.filter(args => args.some(value => typeof value === 'string' && value.includes('Encountered two children with the same key')));
  expect(duplicateKeyWarnings).toEqual([]);
});
