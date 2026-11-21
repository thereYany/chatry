import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api.js';
import { useAuth } from '../App.jsx';

/** 账号安全：登录会话列表 + 单会话踢下线。 */
export default function Account() {
  const { token, user, logout } = useAuth();
  const navigate = useNavigate();
  const [sessions, setSessions] = useState([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  async function load() {
    try {
      setSessions(await api('/api/auth/sessions', { token }));
    } catch (e) {
      setError(e.message);
    }
  }

  useEffect(() => {
    load();
  }, [token]);

  async function revoke(session) {
    setError('');
    setNotice('');
    setBusy(true);
    try {
      await api(`/api/auth/sessions/${session.sessionId}`, { method: 'DELETE', token });
      if (session.current) {
        logout();
        navigate('/login', { replace: true });
        return;
      }
      setNotice('已踢下线该设备');
      load();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="account">
      <div className="page-head">
        <div>
          <h1>账号安全</h1>
          <p className="muted">
            当前账号 {user?.username}。这里列出所有有效登录会话，可单独踢下线（多设备即时生效）。
          </p>
        </div>
      </div>

      {error && <div className="error bar">{error}</div>}
      {notice && <div className="notice bar">{notice}</div>}

      <div className="session-list">
        {sessions.map((session) => (
          <div className="session-card" key={session.sessionId}>
            <div className="session-main">
              <div className="session-title">
                {session.current ? <span className="status paid">当前设备</span> : <span className="status">其他设备</span>}
                <span className="mono">{session.sessionId.slice(0, 8)}…</span>
              </div>
              <div className="muted small">
                IP {session.ip || '-'} · {session.userAgent || '未知客户端'}
              </div>
              <div className="muted small">
                创建于 {formatTime(session.createdAt)} · 过期于 {formatTime(session.expiresAt)}
              </div>
            </div>
            <button type="button" className="ghost" disabled={busy} onClick={() => revoke(session)}>
              {session.current ? '退出当前会话' : '踢下线'}
            </button>
          </div>
        ))}
        {sessions.length === 0 && <p className="muted">没有有效会话。</p>}
      </div>
    </div>
  );
}

function formatTime(value) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}
