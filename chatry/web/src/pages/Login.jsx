import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../App.jsx';

export default function Login() {
  const { session, login, register } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState('login');
  const [username, setUsername] = useState('demo');
  const [password, setPassword] = useState('demo123456');
  const [nickname, setNickname] = useState('');
  const [email, setEmail] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  if (session) {
    return <Navigate to="/chat" replace />;
  }

  const isRegister = mode === 'register';

  function switchMode(next) {
    setMode(next);
    setError('');
    if (next === 'register') {
      setUsername('');
      setPassword('');
    } else {
      setUsername('demo');
      setPassword('demo123456');
    }
  }

  async function submit(event) {
    event.preventDefault();
    setError('');
    setBusy(true);
    try {
      if (isRegister) {
        await register({ username: username.trim(), password, nickname: nickname.trim(), email: email.trim() });
      } else {
        await login(username.trim(), password);
      }
      navigate('/chat', { replace: true });
    } catch (e) {
      setError(e.message || (isRegister ? '注册失败' : '登录失败'));
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
        <p className="muted">
          {isRegister ? '注册后自动登录，新用户首轮对话自动赠送体验额度。' : '对话 AI 演示：多轮对话、按用量扣额度、商城与秒杀。'}
        </p>

        <div className="auth-tabs">
          <button type="button" className={!isRegister ? 'active' : ''} onClick={() => switchMode('login')}>
            登录
          </button>
          <button type="button" className={isRegister ? 'active' : ''} onClick={() => switchMode('register')}>
            注册
          </button>
        </div>

        <form onSubmit={submit}>
          <label>
            用户名
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              placeholder="3-32 位字母、数字或下划线"
              required
            />
          </label>
          <label>
            密码
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={isRegister ? 'new-password' : 'current-password'}
              placeholder={isRegister ? '8-32 位，需同时包含字母和数字' : ''}
              required
            />
          </label>
          {isRegister && (
            <>
              <label>
                昵称
                <input
                  value={nickname}
                  onChange={(e) => setNickname(e.target.value)}
                  placeholder="2-32 位，展示用"
                  required
                />
              </label>
              <label>
                邮箱（可选）
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="不发送验证邮件，仅作为账号标识"
                />
              </label>
            </>
          )}
          {error && <div className="error">{error}</div>}
          <button type="submit" className="primary wide" disabled={busy}>
            {busy ? (isRegister ? '注册中…' : '登录中…') : isRegister ? '注册并登录' : '登录'}
          </button>
        </form>

        {!isRegister && (
          <p className="hint">
            演示账号 <code>demo / demo123456</code>
            <br />
            管理员 <code>admin / admin12345</code>
          </p>
        )}
        {isRegister && (
          <p className="hint">
            注册即表示你同意演示用途；不接短信/邮件服务商，邮箱仅做唯一性校验与后续找回密码的预留字段。
          </p>
        )}
      </div>
    </div>
  );
}
