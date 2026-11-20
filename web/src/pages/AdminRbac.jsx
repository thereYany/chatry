import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { useAuth } from '../App.jsx';

/** 管理端：用户管理 + 角色/权限管理（接口来自身份内核 /api/admin/*）。 */
export default function AdminRbac() {
  const { token, user } = useAuth();
  const [users, setUsers] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [roles, setRoles] = useState([]);
  const [permissions, setPermissions] = useState([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [resetTarget, setResetTarget] = useState(null);
  const [newPassword, setNewPassword] = useState('');
  const [roleForm, setRoleForm] = useState({ code: '', name: '', description: '', permissions: [] });

  async function loadAll() {
    try {
      const [pageResult, roleList, permissionList] = await Promise.all([
        api(`/api/admin/users?page=${page}&size=10`, { token }),
        api('/api/admin/roles', { token }),
        api('/api/admin/permissions', { token }),
      ]);
      setUsers(pageResult.records || []);
      setTotal(pageResult.total || 0);
      setRoles(roleList || []);
      setPermissions(permissionList || []);
    } catch (e) {
      setError(e.message);
    }
  }

  useEffect(() => {
    loadAll();
  }, [token, page]);

  if (user?.role !== 'ADMIN') {
    return <div className="empty-page">仅管理员可访问。</div>;
  }

  function flash(message) {
    setNotice(message);
    setError('');
  }

  async function changeStatus(target) {
    setBusy(true);
    try {
      const status = target.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
      await api(`/api/admin/users/${target.id}/status`, { method: 'PUT', body: { status }, token });
      flash(`已将 ${target.username} 设为 ${status}`);
      loadAll();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function resetPassword() {
    if (!resetTarget || !newPassword) return;
    setBusy(true);
    try {
      await api(`/api/admin/users/${resetTarget.id}/password`, {
        method: 'PUT',
        body: { newPassword },
        token,
      });
      flash(`已重置 ${resetTarget.username} 的密码（下次登录需改密）`);
      setResetTarget(null);
      setNewPassword('');
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function assignRole(target, roleCode) {
    if (!roleCode) return;
    setBusy(true);
    try {
      await api(`/api/admin/users/${target.id}/roles`, {
        method: 'PUT',
        body: { roles: [roleCode] },
        token,
      });
      flash(`已将 ${target.username} 的角色设为 ${roleCode}`);
      loadAll();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  function togglePermission(code) {
    setRoleForm((prev) => ({
      ...prev,
      permissions: prev.permissions.includes(code)
        ? prev.permissions.filter((item) => item !== code)
        : [...prev.permissions, code],
    }));
  }

  async function createRole(event) {
    event.preventDefault();
    setBusy(true);
    try {
      await api('/api/admin/roles', { method: 'POST', body: roleForm, token });
      flash(`角色 ${roleForm.code} 已创建`);
      setRoleForm({ code: '', name: '', description: '', permissions: [] });
      loadAll();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function saveRolePermissions(role, nextPermissions) {
    setBusy(true);
    try {
      await api(`/api/admin/roles/${role.code}/permissions`, {
        method: 'PUT',
        body: { permissions: nextPermissions },
        token,
      });
      flash(`角色 ${role.code} 的权限已更新（多实例缓存广播生效）`);
      loadAll();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="admin">
      <div className="page-head">
        <div>
          <h1>用户与权限</h1>
          <p className="muted">
            账号、角色、权限点均来自身份内核；改权限后通过 Redis 失效广播在全部实例即时生效。
          </p>
        </div>
        <div className="admin-tabs">
          <Link className="ghost" to="/admin">商品管理</Link>
          <span className="ghost active">用户与权限</span>
          <Link className="ghost" to="/admin/audit">审计日志</Link>
        </div>
      </div>

      {error && <div className="error bar">{error}</div>}
      {notice && <div className="notice bar">{notice}</div>}

      <div className="card">
        <h2>用户（共 {total} 个）</h2>
        <div className="rbac-table">
          <div className="rbac-row rbac-head">
            <span>用户名</span>
            <span>昵称</span>
            <span>角色</span>
            <span>状态</span>
            <span>操作</span>
          </div>
          {users.map((item) => (
            <div className="rbac-row" key={item.id}>
              <span className="mono">{item.username}</span>
              <span>{item.nickname}</span>
              <span>{item.role}</span>
              <span className={`status ${item.status === 'ACTIVE' ? 'paid' : 'closed'}`}>
                {item.status === 'ACTIVE' ? '正常' : '禁用'}
              </span>
              <span className="row-actions">
                <button type="button" className="ghost" disabled={busy} onClick={() => changeStatus(item)}>
                  {item.status === 'ACTIVE' ? '禁用' : '启用'}
                </button>
                <button type="button" className="ghost" disabled={busy} onClick={() => setResetTarget(item)}>
                  重置密码
                </button>
                <select
                  defaultValue=""
                  disabled={busy || item.username === 'admin'}
                  onChange={(event) => assignRole(item, event.target.value)}
                >
                  <option value="" disabled>分配角色…</option>
                  {roles.map((role) => (
                    <option key={role.code} value={role.code}>{role.code}</option>
                  ))}
                </select>
              </span>
            </div>
          ))}
          {users.length === 0 && <p className="muted small">暂无用户。</p>}
        </div>
        <div className="pager">
          <button type="button" className="ghost" disabled={page <= 1} onClick={() => setPage(page - 1)}>
            上一页
          </button>
          <span className="muted small">第 {page} 页</span>
          <button type="button" className="ghost" disabled={page * 10 >= total} onClick={() => setPage(page + 1)}>
            下一页
          </button>
        </div>
      </div>

      <div className="admin-grid">
        <form className="card" onSubmit={createRole}>
          <h2>新建角色</h2>
          <div className="field-row">
            <label>
              角色码
              <input
                value={roleForm.code}
                onChange={(event) => setRoleForm({ ...roleForm, code: event.target.value.toUpperCase() })}
                placeholder="REVIEWER"
                required
              />
            </label>
            <label>
              名称
              <input
                value={roleForm.name}
                onChange={(event) => setRoleForm({ ...roleForm, name: event.target.value })}
                placeholder="审核员"
                required
              />
            </label>
          </div>
          <label>
            描述
            <input
              value={roleForm.description}
              onChange={(event) => setRoleForm({ ...roleForm, description: event.target.value })}
            />
          </label>
          <div className="permission-picker">
            {permissions.map((permission) => (
              <label key={permission.code} className="checkbox small">
                <input
                  type="checkbox"
                  checked={roleForm.permissions.includes(permission.code)}
                  onChange={() => togglePermission(permission.code)}
                />
                {permission.code}
              </label>
            ))}
          </div>
          <button type="submit" className="primary wide" disabled={busy}>创建角色</button>
        </form>

        <div className="card">
          <h2>角色与权限</h2>
          <div className="role-list">
            {roles.map((role) => (
              <RoleCard key={role.code} role={role} permissions={permissions} busy={busy}
                onSave={(next) => saveRolePermissions(role, next)} />
            ))}
          </div>
        </div>
      </div>

      {resetTarget && (
        <div className="modal-backdrop" onClick={() => !busy && setResetTarget(null)}>
          <div className="modal" onClick={(event) => event.stopPropagation()}>
            <h2>重置密码：{resetTarget.username}</h2>
            <p className="muted small">重置后该用户所有会话立即失效，下次登录需修改密码。</p>
            <input
              type="password"
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
              placeholder="8-32 位，字母+数字"
              autoFocus
            />
            <div className="modal-actions">
              <button type="button" className="ghost" onClick={() => setResetTarget(null)} disabled={busy}>取消</button>
              <button type="button" className="primary" onClick={resetPassword} disabled={busy || !newPassword}>
                确认重置
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function RoleCard({ role, permissions, busy, onSave }) {
  const [selected, setSelected] = useState(role.permissions || []);
  const dirty = JSON.stringify([...selected].sort()) !== JSON.stringify([...(role.permissions || [])].sort());
  return (
    <div className="role-card">
      <div className="role-head">
        <strong>{role.code}</strong>
        <span className="muted small">{role.name}{role.builtIn ? ' · 内置' : ''}</span>
      </div>
      <div className="permission-picker">
        {permissions.map((permission) => (
          <label key={permission.code} className="checkbox small">
            <input
              type="checkbox"
              checked={selected.includes(permission.code)}
              disabled={busy}
              onChange={() => setSelected((prev) => prev.includes(permission.code)
                ? prev.filter((item) => item !== permission.code)
                : [...prev, permission.code])}
            />
            {permission.code}
          </label>
        ))}
      </div>
      <button type="button" className="ghost" disabled={busy || !dirty} onClick={() => onSave(selected)}>
        保存权限
      </button>
    </div>
  );
}
