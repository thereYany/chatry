import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { BrowserRouter, Link, Navigate, NavLink, Outlet, Route, Routes, useNavigate } from 'react-router-dom';
import { api, clearSession, getSession, setSession } from './api.js';
import { BASE_PATH } from './config.js';
import Login from './pages/Login.jsx';
import Chat from './pages/Chat.jsx';
import Mall from './pages/Mall.jsx';
import Admin from './pages/Admin.jsx';
import Account from './pages/Account.jsx';

const AuthContext = createContext(null);
const CreditsContext = createContext(null);

export const useAuth = () => useContext(AuthContext);
export const useCredits = () => useContext(CreditsContext);

function AuthProvider({ children }) {
  const [session, setSessionState] = useState(() => getSession());

  const login = useCallback(async (username, password) => {
    const result = await api('/api/auth/login', { method: 'POST', body: { username, password } });
    setSession(result);
    setSessionState(result);
    return result;
  }, []);

  const register = useCallback(async ({ username, password, nickname, email }) => {
    const result = await api('/api/auth/register', {
      method: 'POST',
      body: { username, password, nickname, email: email || null },
    });
    setSession(result);
    setSessionState(result);
    return result;
  }, []);

  const logout = useCallback(async () => {
    // 先尽力通知服务端吊销当前会话，失败也不阻塞本地登出
    try {
      await api('/api/auth/logout', { method: 'POST' });
    } catch {
      // 令牌已过期等情况忽略
    }
    clearSession();
    setSessionState(null);
  }, []);

  return (
    <AuthContext.Provider value={{ session, token: session?.token, user: session?.user, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

function CreditsProvider({ children }) {
  const { token } = useAuth();
  const [credits, setCredits] = useState(null);

  const refresh = useCallback(async () => {
    if (!token) return;
    try {
      setCredits(await api('/api/chat/credits'));
    } catch {
      // 未登录或临时失败时保持旧值
    }
  }, [token]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return <CreditsContext.Provider value={{ credits, refresh }}>{children}</CreditsContext.Provider>;
}

function Layout() {
  const { session, user, logout } = useAuth();
  const { credits } = useCredits();
  const navigate = useNavigate();

  if (!session) {
    return <Navigate to="/login" replace />;
  }
  const balance = credits ? `¥${Number(credits.balance).toFixed(2)}` : '—';

  return (
    <div className="app">
      <header className="topbar">
        <Link className="brand" to="/chat">
          chatry<span className="brand-dot">·</span><span className="brand-sub">对话 AI</span>
        </Link>
        <nav className="nav">
          <NavLink to="/chat">对话</NavLink>
          <NavLink to="/mall">额度商城</NavLink>
          <NavLink to="/account">账号</NavLink>
          {user?.role === 'ADMIN' && <NavLink to="/admin">管理</NavLink>}
        </nav>
        <div className="topbar-right">
          <Link className="credits-badge" to="/mall" title="当前额度">
            {balance}
          </Link>
          <span className="username">{user?.nickname || user?.username}</span>
          <button
            type="button"
            className="ghost"
            onClick={() => {
              logout();
              navigate('/login');
            }}
          >
            退出
          </button>
        </div>
      </header>
      <main className="page">
        <Outlet />
      </main>
    </div>
  );
}

export default function App() {
  return (
    <BrowserRouter basename={BASE_PATH || undefined}>
      <AuthProvider>
        <CreditsProvider>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route element={<Layout />}>
              <Route index element={<Navigate to="/chat" replace />} />
              <Route path="/chat" element={<Chat />} />
              <Route path="/chat/:conversationId" element={<Chat />} />
              <Route path="/mall" element={<Mall />} />
              <Route path="/admin" element={<Admin />} />
              <Route path="/account" element={<Account />} />
            </Route>
            <Route path="*" element={<Navigate to="/chat" replace />} />
          </Routes>
        </CreditsProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}
