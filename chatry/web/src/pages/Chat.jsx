import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, streamChat } from '../api.js';
import { useCredits } from '../App.jsx';
import Markdown from '../components/Markdown.jsx';

const WELCOME = {
  title: '开始新的对话',
  hint: '问点什么吧。回复按 token 实时扣额度，停止生成会释放未消费的预扣。',
};

export default function Chat() {
  const { conversationId } = useParams();
  const navigate = useNavigate();
  const { refresh } = useCredits();

  const [conversations, setConversations] = useState([]);
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const [activeId, setActiveId] = useState(conversationId ? Number(conversationId) : null);

  const abortRef = useRef(null);
  const bottomRef = useRef(null);
  const inputRef = useRef(null);

  async function loadConversations() {
    try {
      setConversations(await api('/api/chat/conversations'));
    } catch (e) {
      setError(e.message);
    }
  }

  async function loadMessages(id) {
    try {
      setMessages(await api(`/api/chat/conversations/${id}/messages`));
    } catch (e) {
      setError(e.message);
    }
  }

  useEffect(() => {
    loadConversations();
  }, []);

  useEffect(() => {
    if (conversationId) {
      setActiveId(Number(conversationId));
      loadMessages(conversationId);
    } else {
      setActiveId(null);
      setMessages([]);
    }
  }, [conversationId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages]);

  async function ensureConversation() {
    if (activeId) {
      return activeId;
    }
    const conversation = await api('/api/chat/conversations', { method: 'POST', body: {} });
    setActiveId(conversation.id);
    await loadConversations();
    return conversation.id;
  }

  async function send(event) {
    event?.preventDefault();
    const content = input.trim();
    if (!content || pending) return;
    setError('');
    setInput('');
    setPending(true);

    const userMessage = { id: `tmp-u-${Date.now()}`, role: 'user', content, status: 'OK' };
    const assistantId = `tmp-a-${Date.now()}`;
    const assistantMessage = { id: assistantId, role: 'assistant', content: '', streaming: true };
    setMessages((prev) => [...prev, userMessage, assistantMessage]);

    let convId = null;
    try {
      convId = await ensureConversation();
      const controller = new AbortController();
      abortRef.current = controller;
      const done = await streamChat({
        conversationId: convId,
        content,
        signal: controller.signal,
        onDelta: (delta) =>
          setMessages((prev) =>
            prev.map((message) =>
              message.id === assistantId ? { ...message, content: message.content + delta } : message,
            ),
          ),
      });
      setMessages((prev) =>
        prev.map((message) =>
          message.id === assistantId
            ? { ...message, streaming: false, status: 'OK', costMicros: done?.cost ? Math.round(done.cost * 1e6) : 0 }
            : message,
        ),
      );
      refresh();
      loadConversations();
      navigate(`/chat/${convId}`, { replace: true });
    } catch (e) {
      if (e.name === 'AbortError') {
        setMessages((prev) =>
          prev.map((message) =>
            message.streaming
              ? { ...message, streaming: false, status: 'CANCELLED', content: `${message.content}（已停止）` }
              : message,
          ),
        );
      } else {
        setError(e.message || '发送失败');
        setMessages((prev) => prev.filter((message) => message.id !== assistantId));
      }
    } finally {
      setPending(false);
      abortRef.current = null;
      inputRef.current?.focus();
    }
  }

  function stop() {
    abortRef.current?.abort();
  }

  function newChat() {
    setActiveId(null);
    setMessages([]);
    navigate('/chat');
    setSidebarOpen(false);
    inputRef.current?.focus();
  }

  function onKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      send(event);
    }
  }

  return (
    <div className="chat-shell">
      <aside className={`chat-sidebar ${sidebarOpen ? 'open' : ''}`}>
        <button type="button" className="primary wide" onClick={newChat}>
          ＋ 新对话
        </button>
        <div className="conversation-list">
          {conversations.map((conversation) => (
            <button
              type="button"
              key={conversation.id}
              className={`conversation-item ${Number(conversation.id) === activeId ? 'active' : ''}`}
              onClick={() => {
                navigate(`/chat/${conversation.id}`);
                setSidebarOpen(false);
              }}
            >
              <span className="conversation-title">{conversation.title}</span>
              <span className="conversation-meta">{conversation.messageCount} 条消息</span>
            </button>
          ))}
          {conversations.length === 0 && <p className="muted small">还没有会话，直接发消息即可创建。</p>}
        </div>
      </aside>

      <section className="chat-main">
        <div className="chat-toolbar">
          <button type="button" className="ghost mobile-only" onClick={() => setSidebarOpen((open) => !open)}>
            ☰
          </button>
          <span className="chat-title">{activeId ? currentTitle(conversations, activeId) : '新对话'}</span>
          {pending && (
            <button type="button" className="ghost" onClick={stop}>
              停止生成
            </button>
          )}
        </div>

        <div className="messages">
          {messages.length === 0 && (
            <div className="welcome">
              <h2>{WELCOME.title}</h2>
              <p className="muted">{WELCOME.hint}</p>
            </div>
          )}
          {messages.map((message) => (
            <article key={message.id} className={`message ${message.role}`}>
              <div className="avatar">{message.role === 'user' ? '我' : 'AI'}</div>
              <div className={`bubble ${message.streaming ? 'streaming' : ''}`}>
                {message.role === 'assistant' ? (
                  message.content ? (
                    <Markdown content={message.content} />
                  ) : (
                    <span className="typing">正在思考…</span>
                  )
                ) : (
                  <p className="plain">{message.content}</p>
                )}
                {message.status === 'CANCELLED' && <div className="message-foot">已停止，未计费</div>}
                {message.costMicros > 0 && (
                  <div className="message-foot">本次消耗 ¥{(message.costMicros / 1e6).toFixed(6)}</div>
                )}
              </div>
            </article>
          ))}
          <div ref={bottomRef} />
        </div>

        {error && <div className="error bar">{error}</div>}

        <form className="composer" onSubmit={send}>
          <textarea
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder="给 chatry 发消息（Enter 发送，Shift+Enter 换行）"
            rows={2}
            disabled={pending}
          />
          {pending ? (
            <button type="button" className="ghost" onClick={stop}>
              停止
            </button>
          ) : (
            <button type="submit" className="primary" disabled={!input.trim()}>
              发送
            </button>
          )}
        </form>
      </section>
    </div>
  );
}

function currentTitle(conversations, id) {
  return conversations.find((conversation) => String(conversation.id) === String(id))?.title || '对话';
}
