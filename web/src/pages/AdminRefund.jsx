import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { useAuth } from '../App.jsx';

/** 管理端：退款（可退订单、发起退款、结果反馈）。 */
export default function AdminRefund() {
  const { token, user } = useAuth();
  const [candidates, setCandidates] = useState([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [target, setTarget] = useState(null);
  const [amountYuan, setAmountYuan] = useState('');
  const [reason, setReason] = useState('');

  async function load() {
    try {
      setCandidates(await api('/api/admin/mall/refunds/candidates', { token }));
    } catch (e) {
      setError(e.message);
    }
  }

  useEffect(() => {
    load();
  }, [token]);

  if (user?.role !== 'ADMIN') {
    return <div className="empty-page">仅管理员可访问。</div>;
  }

  function open(item) {
    setTarget(item);
    setAmountYuan((item.refundableCents / 100).toFixed(2));
    setReason('');
    setError('');
    setNotice('');
  }

  async function submit() {
    if (!target) return;
    const cents = Math.round(Number(amountYuan) * 100);
    if (!cents || cents <= 0) {
      setError('退款金额需大于 0');
      return;
    }
    setBusy(true);
    try {
      const result = await api(`/api/admin/mall/orders/${target.orderNo}/refund`, {
        method: 'POST',
        body: { amountCents: cents, reason: reason || '管理员退款' },
        token,
      });
      setNotice(`退款 ${result.refundNo} 已提交：${result.status}`);
      setTarget(null);
      load();
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
          <h1>退款与售后</h1>
          <p className="muted">
            已支付订单只退未消费额度折算的金额；关单后支付（钱到账、额度未发）可全额退。
            退款成功后自动扣回额度并写资金账本反向分录。
          </p>
        </div>
        <div className="admin-tabs">
          <Link className="ghost" to="/admin">商品管理</Link>
          <Link className="ghost" to="/admin/rbac">用户与权限</Link>
          <Link className="ghost" to="/admin/audit">审计日志</Link>
          <Link className="ghost" to="/admin/finance">资金账本</Link>
          <span className="ghost active">退款</span>
          <Link className="ghost" to="/admin/mock">Mock 渠道</Link>
        </div>
      </div>

      {error && <div className="error bar">{error}</div>}
      {notice && <div className="notice bar">{notice}</div>}

      <div className="card">
        <h2>可退订单</h2>
        <div className="audit-table">
          <div className="audit-row audit-head">
            <span>订单号</span><span>商品</span><span>支付</span><span>到账额度</span><span>已退</span><span>可退</span><span>操作</span>
          </div>
          {candidates.map((item) => (
            <div className="audit-row" key={item.orderNo}>
              <span className="mono small">{item.orderNo}</span>
              <span className="small">{item.skuTitle}</span>
              <span className="mono small">{yuan(item.amountCents)}</span>
              <span className="mono small">{yuan(item.creditCents)}</span>
              <span className="mono small">{yuan(item.refundedCents)}</span>
              <span className="mono small">{yuan(item.refundableCents)}</span>
              <span>
                <button type="button" className="ghost" disabled={busy || item.refundableCents <= 0}
                  onClick={() => open(item)}>
                  退款
                </button>
              </span>
            </div>
          ))}
          {candidates.length === 0 && <p className="muted small">没有可退订单。</p>}
        </div>
        <p className="muted small">状态：{candidates.map((c) => c.status).filter(Boolean).join(' / ') || '-'}</p>
      </div>

      {target && (
        <div className="modal-backdrop" onClick={() => !busy && setTarget(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h2>退款：{target.orderNo}</h2>
            <p className="muted small">
              可退上限 {yuan(target.refundableCents)}；退款成功后额度自动扣回。
            </p>
            <label className="field">
              退款金额（元）
              <input value={amountYuan} onChange={(e) => setAmountYuan(e.target.value)} inputMode="decimal" />
            </label>
            <label className="field">
              原因
              <input value={reason} onChange={(e) => setReason(e.target.value)}
                placeholder="如：用户申请退款" />
            </label>
            <div className="modal-actions">
              <button type="button" className="ghost" disabled={busy} onClick={() => setTarget(null)}>取消</button>
              <button type="button" className="primary" disabled={busy} onClick={submit}>确认退款</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function yuan(cents) {
  return `¥${(Number(cents || 0) / 100).toFixed(2)}`;
}
