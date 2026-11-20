import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../App.jsx';

export default function Login() {
  const { session, login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('demo');
  const [password, setPassword] = useState('demo123456');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  if (session) {
    return <Navigate to="/chat" replace />;
  }

  async function submit(event) {
    event.preventDefault();
    setError('');
    setBusy(true);
    try {
      await login(username.trim(), password);
      navigate('/chat', { replace: true });
    } catch (e) {
      setError(e.message || '登录失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <h1>
          chatry<span className="brand-dot">·</span>
        </h1>
        <p className="muted">对话 AI 演示：多轮对话、按用量扣额度、商城与秒杀。</p>
        <form onSubmit={submit}>
          <label>
            用户名
            <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
          </label>
          <label>
            密码
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          </label>
          {error && <div className="error">{error}</div>}
          <button type="submit" className="primary wide" disabled={busy}>
            {busy ? '登录中…' : '登录'}
          </button>
        </form>
        <p className="hint">
          演示账号 <code>demo / demo123456</code>
          <br />
          管理员 <code>admin / admin12345</code>
        </p>
      </div>
    </div>
  );
}
