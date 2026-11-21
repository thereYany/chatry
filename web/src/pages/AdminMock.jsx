import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { useAuth } from '../App.jsx';

/** 开发台：Mock 渠道故障剧本（仅非生产环境可用）。 */
export default function AdminMock() {
  const { token, user } = useAuth();
  const [paymentNo, setPaymentNo] = useState('');
  const [scenario, setScenario] = useState('SUCCESS');
  const [delayMs, setDelayMs] = useState('1500');
  const [scenarios, setScenarios] = useState([]);
  const [log, setLog] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    api('/api/mock/payments/scenarios', { token })
      .then(setScenarios)
      .catch((e) => setError(e.message));
  }, [token]);

  if (user?.role !== 'ADMIN') {
    return <div className="empty-page">仅管理员可访问。</div>;
  }

  function append(text) {
    setLog((prev) => [`${new Date().toLocaleTimeString('zh-CN', { hour12: false })} ${text}`, ...prev].slice(0, 30));
  }

  async function applyScenario() {
    if (!paymentNo) return;
    setError('');
    try {
      await api(`/api/mock/payments/${paymentNo}/scenario`, {
        method: 'POST',
        body: { scenario, delayMs: Number(delayMs) || 0 },
        token,
      });
      append(`已设置剧本 ${scenario}${scenario === 'DELAYED' ? `（延迟 ${delayMs}ms）` : ''} → ${paymentNo}`);
    } catch (e) {
      setError(e.message);
    }
  }

  async function simulate() {
    if (!paymentNo) return;
    setError('');
    try {
      const result = await api(`/api/mock/payments/${paymentNo}/simulate`, { method: 'POST', token });
      append(`模拟 ${paymentNo} → status=${result.status}${result.message ? ` message=${result.message}` : ''}`);
    } catch (e) {
      append(`模拟 ${paymentNo} → 拒绝：${e.message}`);
    }
  }

  return (
    <div className="admin">
      <div className="page-head">
        <div>
          <h1>Mock 渠道开发台</h1>
          <p className="muted">
            按剧本触发渠道行为：所有回调都走真实签名与确认链路，不是跳过支付。仅非生产环境注册。
          </p>
        </div>
        <div className="admin-tabs">
          <Link className="ghost" to="/admin">商品管理</Link>
          <Link className="ghost" to="/admin/refund">退款</Link>
          <Link className="ghost" to="/admin/finance">资金账本</Link>
          <span className="ghost active">Mock 渠道</span>
        </div>
      </div>

      {error && <div className="error bar">{error}</div>}

      <div className="card">
        <h2>触发剧本</h2>
        <div className="audit-filters">
          <label>
            支付单号
            <input value={paymentNo} onChange={(e) => setPaymentNo(e.target.value.trim())}
              placeholder="P1795..." style={{ width: 260 }} />
          </label>
          <label>
            场景
            <select value={scenario} onChange={(e) => setScenario(e.target.value)}>
              {scenarios.map((item) => <option key={item}>{item}</option>)}
            </select>
          </label>
          <label>
            延迟（ms）
            <input value={delayMs} onChange={(e) => setDelayMs(e.target.value)} inputMode="numeric"
              style={{ width: 100 }} />
          </label>
          <button type="button" className="ghost" onClick={applyScenario}>设置剧本</button>
          <button type="button" className="primary" onClick={simulate}>触发回调</button>
        </div>
        <p className="muted small">
          提示：QUERY_ONLY 只写渠道事实不回调，用于验证主动查单；AFTER_CLOSE 需要先把支付单关闭。
        </p>
      </div>

      <div className="card">
        <h2>执行记录</h2>
        {log.length === 0 && <p className="muted small">还没有操作。</p>}
        {log.map((line, index) => (
          <p key={index} className="mono small">{line}</p>
        ))}
      </div>
    </div>
  );
}
