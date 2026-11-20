import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { useAuth } from '../App.jsx';

/** 管理端：审计统一检索（管理操作 + 认证事件）。 */
export default function AdminAudit() {
  const { token, user } = useAuth();
  const [tab, setTab] = useState('admin');
  const [rows, setRows] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [error, setError] = useState('');
  const [filters, setFilters] = useState({
    action: '', operatorId: '', username: '', eventType: '', success: '', from: '', to: '',
  });

  useEffect(() => {
    setPage(1);
  }, [tab]);

  useEffect(() => {
    load();
  }, [tab, page]);

  if (user?.role !== 'ADMIN') {
    return <div className="empty-page">仅管理员可访问。</div>;
  }

  async function load() {
    setError('');
    const query = new URLSearchParams({ page: String(page), size: '20' });
    const pairs = tab === 'admin'
      ? [['action', filters.action], ['operatorId', filters.operatorId]]
      : [['eventType', filters.eventType], ['username', filters.username]];
    for (const [key, value] of pairs) {
      if (value) query.set(key, value);
    }
    if (filters.success) query.set('success', filters.success);
    if (filters.from) query.set('from', filters.from);
    if (filters.to) query.set('to', filters.to);
    try {
      const data = await api(`/api/admin/${tab === 'admin' ? 'audit-logs' : 'auth-events'}?${query}`, { token });
      setRows(data.records || []);
      setTotal(data.total || 0);
    } catch (e) {
      setError(e.message);
    }
  }

  function submit(event) {
    event.preventDefault();
    setPage(1);
    load();
  }

  return (
    <div className="admin">
      <div className="page-head">
        <div>
          <h1>审计日志</h1>
          <p className="muted">管理操作与认证事件统一检索；按租户隔离，默认按时间倒序。</p>
        </div>
        <div className="admin-tabs">
          <Link className="ghost" to="/admin">商品管理</Link>
          <Link className="ghost" to="/admin/rbac">用户与权限</Link>
          <span className="ghost active">审计日志</span>
          <Link className="ghost" to="/admin/finance">资金账本</Link>
        </div>
      </div>

      {error && <div className="error bar">{error}</div>}

      <div className="tabs-line">
        <button type="button" className={tab === 'admin' ? 'active' : ''} onClick={() => setTab('admin')}>
          管理操作
        </button>
        <button type="button" className={tab === 'auth' ? 'active' : ''} onClick={() => setTab('auth')}>
          认证事件
        </button>
      </div>

      <form className="card audit-filters" onSubmit={submit}>
        {tab === 'admin' ? (
          <>
            <label>
              动作
              <input value={filters.action} placeholder="USER_CREATE"
                onChange={(e) => setFilters({ ...filters, action: e.target.value })} />
            </label>
            <label>
              操作人 ID
              <input value={filters.operatorId} inputMode="numeric"
                onChange={(e) => setFilters({ ...filters, operatorId: e.target.value })} />
            </label>
          </>
        ) : (
          <>
            <label>
              事件类型
              <select value={filters.eventType}
                onChange={(e) => setFilters({ ...filters, eventType: e.target.value })}>
                <option value="">全部</option>
                {['REGISTER', 'LOGIN_SUCCESS', 'LOGIN_FAILURE', 'LOGOUT', 'PASSWORD_CHANGE',
                  'REGISTER_THROTTLED'].map((type) => <option key={type}>{type}</option>)}
              </select>
            </label>
            <label>
              用户名
              <input value={filters.username} placeholder="模糊匹配"
                onChange={(e) => setFilters({ ...filters, username: e.target.value })} />
            </label>
          </>
        )}
        <label>
          结果
          <select value={filters.success}
            onChange={(e) => setFilters({ ...filters, success: e.target.value })}>
            <option value="">全部</option>
            <option value="true">成功</option>
            <option value="false">失败</option>
          </select>
        </label>
        <label>
          起
          <input type="date" value={filters.from}
            onChange={(e) => setFilters({ ...filters, from: e.target.value })} />
        </label>
        <label>
          止
          <input type="date" value={filters.to}
            onChange={(e) => setFilters({ ...filters, to: e.target.value })} />
        </label>
        <button type="submit" className="primary">查询</button>
      </form>

      <div className="card">
        <div className="muted small">共 {total} 条</div>
        {tab === 'admin' ? (
          <div className="audit-table">
            <div className="audit-row audit-head">
              <span>时间</span><span>操作人</span><span>动作</span><span>对象</span><span>结果</span><span>trace</span>
            </div>
            {rows.map((row) => (
              <div className="audit-row" key={row.id}>
                <span className="mono small">{formatTime(row.createdAt)}</span>
                <span>{row.operatorId}</span>
                <span className="mono">{row.action}</span>
                <span className="small">{row.targetType}{row.targetId ? `#${row.targetId}` : ''}</span>
                <span className={row.success ? 'status paid' : 'status closed'}>
                  {row.success ? `成功 ${row.durationMs}ms` : '失败'}
                </span>
                <span className="mono small" title={row.traceId || ''}>{row.traceId || '-'}</span>
              </div>
            ))}
          </div>
        ) : (
          <div className="audit-table auth">
            <div className="audit-row audit-head">
              <span>时间</span><span>用户</span><span>事件</span><span>结果</span><span>IP</span><span>详情</span>
            </div>
            {rows.map((row) => (
              <div className="audit-row" key={row.id}>
                <span className="mono small">{formatTime(row.createdAt)}</span>
                <span>{row.username || '-'}</span>
                <span className="mono small">{row.eventType}</span>
                <span className={row.success ? 'status paid' : 'status closed'}>
                  {row.success ? '成功' : '失败'}
                </span>
                <span className="mono small">{row.ip || '-'}</span>
                <span className="small" title={row.userAgent || ''}>{row.detail || '-'}</span>
              </div>
            ))}
          </div>
        )}
        {rows.length === 0 && <p className="muted small">没有匹配记录。</p>}
        <div className="pager">
          <button type="button" className="ghost" disabled={page <= 1} onClick={() => setPage(page - 1)}>
            上一页
          </button>
          <span className="muted small">第 {page} 页</span>
          <button type="button" className="ghost" disabled={page * 20 >= total} onClick={() => setPage(page + 1)}>
            下一页
          </button>
        </div>
      </div>
    </div>
  );
}

function formatTime(value) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}
