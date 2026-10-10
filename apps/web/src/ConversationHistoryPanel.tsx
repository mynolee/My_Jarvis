import { useEffect, useRef, useState } from 'react';
import { Api, ApiError } from './api';
import type { Conversation } from './domain';
import { history_path, merge_history, read_conversation_page, read_history, read_history_page, type HistoryMessage, type HistoryPage } from './conversation-history';
import { watch_history_events } from './conversation-history-events';
export interface ConversationHistoryPanelProps { api: Api; conversationId: string | null; onSelect: (conversation: Conversation) => void; onSnapshot: (conversationId: string, page: HistoryPage) => void; onUnauthorized: () => void; refreshKey?: number; selectionDisabled?: boolean }
export function ConversationHistoryPanel({ api, conversationId, onSelect, onSnapshot, onUnauthorized, refreshKey = 0, selectionDisabled = false }: ConversationHistoryPanelProps) {
  const [conversations, setConversations] = useState<Conversation[]>([]), [listCursor, setListCursor] = useState<string | null>(null), [listBusy, setListBusy] = useState(false);
  const [messages, setMessages] = useState<HistoryMessage[]>([]), [olderCursor, setOlderCursor] = useState<string | null>(null), [messageBusy, setMessageBusy] = useState(false), [olderBusy, setOlderBusy] = useState(false);
  const [notice, setNotice] = useState(''), [streamState, setStreamState] = useState(''), [loadedFor, setLoadedFor] = useState<string | null>(null);
  const mounted = useRef(false), sessionGeneration = useRef(0), authenticated = useRef(true), generation = useRef(0), listGeneration = useRef(0), selected = useRef(conversationId), stop = useRef<(() => void) | null>(null), reload = useRef<(() => Promise<HistoryPage | null>) | null>(null), olderLock = useRef(false), listLock = useRef(false), snapshotRevision = useRef(0), initialSelection = useRef(true), transcript = useRef<{ items: HistoryMessage[]; cursor: string | null; hasOlder: boolean }>({ items: [], cursor: null, hasOlder: false });
  const callbacks = useRef({ onSelect, onSnapshot, onUnauthorized, selectionDisabled }); callbacks.current = { onSelect, onSnapshot, onUnauthorized, selectionDisabled }; selected.current = conversationId;
  function fail(error: unknown) {
    if (!mounted.current || !authenticated.current) return;
    if (error instanceof ApiError && error.status === 401) { authenticated.current = false; generation.current++; listGeneration.current++; stop.current?.(); transcript.current = { items: [], cursor: null, hasOlder: false }; reload.current = null; api.clear(); setConversations([]); setMessages([]); setOlderCursor(null); setListCursor(null); setLoadedFor(null); setNotice(''); callbacks.current.onUnauthorized(); return; }
    setNotice(error instanceof ApiError && /^[A-Z][A-Z0-9_]{0,63}$/.test(error.code) ? error.code : 'History could not be verified.');
  }
  function reportReadFailure(error: unknown, session: number, active: () => boolean) {
    if (!mounted.current || !authenticated.current || session !== sessionGeneration.current) return;
    if ((error instanceof ApiError && error.status === 401) || active()) fail(error);
  }
  async function loadConversations(cursor: string | null = null) {
    if (listLock.current || !authenticated.current) return; listLock.current = true; const epoch = ++listGeneration.current, session = sessionGeneration.current; setListBusy(true);
    const active = () => mounted.current && authenticated.current && epoch === listGeneration.current;
    try { const page = await read_history(api, history_path(null, cursor), read_conversation_page, active); if (!active()) return; setConversations(old => cursor ? [...old, ...page.items.filter(item => !old.some(existing => existing.id === item.id))] : page.items); setListCursor(page.next_cursor); if (initialSelection.current) { initialSelection.current = false; if (!callbacks.current.selectionDisabled && selected.current === null && page.items.length) callbacks.current.onSelect(page.items[0]); } } catch (error) { reportReadFailure(error, session, active); } finally { if (epoch === listGeneration.current) { listLock.current = false; if (active()) setListBusy(false); } }
  }
  useEffect(() => { mounted.current = true; sessionGeneration.current++; authenticated.current = true; listLock.current = false; void loadConversations(); return () => { mounted.current = false; sessionGeneration.current++; generation.current++; listGeneration.current++; stop.current?.(); }; }, [api]);
  useEffect(() => {
    const epoch = ++generation.current, session = sessionGeneration.current; stop.current?.(); stop.current = null; reload.current = null; olderLock.current = false; transcript.current = { items: [], cursor: null, hasOlder: false }; setMessages([]); setLoadedFor(null); setOlderCursor(null); setNotice(''); setStreamState(''); setOlderBusy(false);
    if (!conversationId) { setMessageBusy(false); return; }
    const target = conversationId; const active = () => mounted.current && authenticated.current && generation.current === epoch && selected.current === target;
    let pending: Promise<HistoryPage | null> | null = null; let dirty = false;
    const fetchLatest = (reset = false): Promise<HistoryPage | null> => {
      if (reset) transcript.current.hasOlder = false;
      if (pending) { dirty = true; return pending; }
      pending = (async () => { let result: HistoryPage | null = null; do { dirty = false; const page = await read_history(api, history_path(target), read_history_page, active); if (!active()) return null; snapshotRevision.current++; const previous = transcript.current; const preserve = previous.hasOlder && page.items.some(item => previous.items.some(old => old.id === item.id)); transcript.current = { items: preserve ? merge_history(previous.items, page.items) : page.items, cursor: preserve ? previous.cursor : page.next_cursor, hasOlder: preserve }; setMessages(transcript.current.items); setOlderCursor(transcript.current.cursor); setLoadedFor(target); setNotice(''); callbacks.current.onSnapshot(target, page); result = page; } while (dirty && active()); return result; })().finally(() => { pending = null; }); return pending;
    };
    reload.current = fetchLatest; setMessageBusy(true);
    void fetchLatest().then(page => { if (!page || !active()) return; stop.current = watch_history_events({ conversationId: target, cursor: page.stream_cursor, onUpdate: () => { if (active()) void fetchLatest().catch(error => { reportReadFailure(error, session, active); }); }, onGap: async () => { const latest = await fetchLatest(true); return latest?.stream_cursor ?? null; }, onState: state => { if (active()) setStreamState(state); }, onUnauthorized: () => { reportReadFailure(new ApiError('UNAUTHENTICATED', false, 401), session, active); } }); }).catch(error => { reportReadFailure(error, session, active); }).finally(() => { if (active()) setMessageBusy(false); });
    return () => { generation.current++; stop.current?.(); stop.current = null; reload.current = null; };
  }, [api, conversationId]);
  useEffect(() => { if (refreshKey > 0) { void loadConversations(); const target = conversationId; const epoch = generation.current, session = sessionGeneration.current; void reload.current?.().catch(error => { reportReadFailure(error, session, () => selected.current === target && generation.current === epoch); }); } }, [refreshKey]);
  async function loadOlder() {
    if (!conversationId || !olderCursor || olderLock.current || messageBusy) return;
    const target = conversationId, epoch = generation.current, cursor = olderCursor, revision = snapshotRevision.current, session = sessionGeneration.current; const active = () => mounted.current && authenticated.current && generation.current === epoch && selected.current === target;
    olderLock.current = true; setOlderBusy(true);
    try { const page = await read_history(api, history_path(target, cursor), read_history_page, active); if (!active() || revision !== snapshotRevision.current) return; transcript.current = { items: merge_history(page.items, transcript.current.items), cursor: page.next_cursor, hasOlder: true }; setMessages(transcript.current.items); setOlderCursor(page.next_cursor); } catch (error) { reportReadFailure(error, session, active); } finally { if (active()) { olderLock.current = false; setOlderBusy(false); } }
  }
  return <section className="panel conversation-history" aria-label="Conversation history"><h2>Conversation history</h2>
    <nav aria-label="Saved conversations"><button className="secondary" disabled={listBusy} onClick={() => void loadConversations()}>Refresh conversations</button><ul>{conversations.map(item => <li key={item.id}><button className="secondary" disabled={selectionDisabled} aria-current={item.id === conversationId ? 'true' : undefined} onClick={() => { if (!callbacks.current.selectionDisabled && item.id !== selected.current) { generation.current++; stop.current?.(); callbacks.current.onSelect(item); } }}>{item.title || 'Untitled conversation'}</button></li>)}</ul>{listBusy && <p role="status">Loading conversations…</p>}{!listBusy && !conversations.length && <p>No saved conversations.</p>}{listCursor && <button className="secondary" disabled={listBusy} onClick={() => void loadConversations(listCursor)}>Load more conversations</button>}</nav>
    {notice && <p role="alert">{notice}</p>}
    {conversationId ? <section aria-label="Saved transcript" aria-busy={messageBusy || olderBusy}>{messageBusy && <p role="status">Loading conversation…</p>}{streamState && <p role="status">History connection: {streamState}</p>}{streamState === 'BLOCKED' && <p>Live updates paused. Refresh the page to reconnect.</p>}{loadedFor === conversationId && <>{olderCursor && <button className="secondary" disabled={olderBusy} onClick={() => void loadOlder()}>{olderBusy ? 'Loading older messages…' : 'Load older messages'}</button>}<ol>{messages.map(message => <li key={message.id}><strong>{message.role}</strong>{message.execution_mode && <span> · {message.execution_mode === 'MOCK' ? 'Mock response' : 'Local model'}</span>}<p>{message.text}</p><time dateTime={message.created_at}>{message.created_at}</time></li>)}</ol>{!messages.length && <p>No saved messages.</p>}</>}</section> : <p>Select a conversation to view its history.</p>}
  </section>;
}
