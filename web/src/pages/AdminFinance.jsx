import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { useAuth } from '../App.jsx';

/** 管理端：资金账本（额度负债 / 清算 / 收入 / 赠送上本）与借贷平衡。 */
export default function AdminFinance() {
  const { token, user } = useAuth();
  const [summary, setSummary] = useState(null);
  const [error, setError] = useState('');

  async function load() {
    try {
      setSummary(await api('/api/admin/fund-ledger/summary', { token }));
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

  return (
    <div className="admin">
      <div className="page-head">
        <div>
          <h1>资金账本</h1>
          <p className="muted">
            额度是平台负债：充值记「借清算/借营销费用，贷额度负债」，消费记「借额度负债，贷收入」。
            每笔交易借贷必须相等，只允许冲正，不改余额。
          </p>
        </div>
        <div className="admin-tabs">
          <Link className="ghost" to="/admin">商品管理</Link>
          <Link className="ghost" to="/admin/rbac">用户与权限</Link>
          <Link className="ghost" to="/admin/audit">审计日志</Link>
          <span className="ghost active">资金账本</span>
          <Link className="ghost" to="/admin/refund">退款</Link>
        </div>
      </div>

      {error && <div className="error bar">{error}</div>}

      {summary && (
        <>
          <div className="fund-cards">
            <FundCard label="用户额度负债" value={summary.liability} hint="CREDIT_LIABILITY 余额" />
            <FundCard label="计量账户余额" value={summary.metering} hint="t_metering_account 合计" />
            <FundCard label="清算账户" value={summary.clearing} hint="PSP_CLEARING 余额" />
            <FundCard label="已支付订单" value={summary.paidOrders} hint="商城已支付金额" />
          </div>

          <div className="fund-checks">
            <CheckItem
              label="额度账实差异（负债 − 计量余额）"
              value={summary.liabilityDiff}
              ok={Number(summary.liabilityDiff) === 0}
            />
            <CheckItem
              label="资金账实差异（清算 − 已支付）"
              value={summary.cashDiff}
              ok={Number(summary.cashDiff) === 0}
            />
            <CheckItem
              label="借贷不平交易数"
              value={summary.unbalanced}
              ok={Number(summary.unbalanced) === 0}
            />
          </div>

          <div className="card">
            <h2>最近交易（借贷分录）</h2>
            <div className="audit-table">
              <div className="audit-row audit-head">
                <span>时间</span><span>事件键</span><span>类型</span><span>业务单</span><span>借方</span><span>贷方</span>
              </div>
              {summary.recent.map((row) => (
                <div className="audit-row" key={row.id}>
                  <span className="mono small">{formatTime(row.occurredAt)}</span>
                  <span className="mono small" title={row.eventKey}>{short(row.eventKey)}</span>
                  <span className="mono small">{row.bizType}</span>
                  <span className="mono small" title={row.bizNo}>{short(row.bizNo)}</span>
                  <span className="mono">{money(row.debit)}</span>
                  <span className="mono">{money(row.credit)}</span>
                </div>
              ))}
              {summary.recent.length === 0 && <p className="muted small">还没有资金交易。</p>}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function FundCard({ label, value, hint }) {
  return (
    <div className="fund-card">
      <span className="muted small">{label}</span>
      <strong>{money(value)}</strong>
      <span className="muted small">{hint}</span>
    </div>
  );
}

function CheckItem({ label, value, ok }) {
  return (
    <div className={`fund-check ${ok ? 'ok' : 'bad'}`}>
      <span>{label}</span>
      <strong>{ok ? '0.00（平）' : money(value)}</strong>
    </div>
  );
}

function money(value) {
  const number = Number(value ?? 0);
  return `¥${number.toFixed(6)}`;
}

function short(value) {
  if (!value) return '-';
  return value.length <= 28 ? value : `${value.slice(0, 28)}…`;
}

function formatTime(value) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}
