import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { useAuth, useCredits } from '../App.jsx';

function yuan(cents) {
  return `¥${(Number(cents) / 100).toFixed(2)}`;
}

function credit(cents) {
  return (Number(cents) / 100).toFixed(2);
}

function bonusOf(sku) {
  const paid = sku.effectivePriceCents ?? sku.priceCents;
  return Math.max(0, Number(sku.creditCents) - Number(paid));
}

function useCountdown(target) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  return useMemo(() => {
    if (!target) return '';
    const diff = new Date(target).getTime() - now;
    if (diff <= 0) return '';
    const days = Math.floor(diff / 86_400_000);
    const hours = Math.floor((diff % 86_400_000) / 3_600_000);
    const minutes = Math.floor((diff % 3_600_000) / 60_000);
    const seconds = Math.floor((diff % 60_000) / 1000);
    const clock = [hours, minutes, seconds].map((value) => String(value).padStart(2, '0')).join(':');
    return days > 0 ? `${days} 天 ${clock}` : clock;
  }, [target, now]);
}

export default function Mall() {
  const { token } = useAuth();
  const { credits, refresh } = useCredits();
  const [skus, setSkus] = useState([]);
  const [orders, setOrders] = useState([]);
  const [pending, setPending] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  async function load() {
    try {
      const [skuList, orderList] = await Promise.all([
        api('/api/mall/skus', { token }),
        api('/api/mall/orders', { token }),
      ]);
      setSkus(skuList);
      setOrders(orderList);
    } catch (e) {
      setError(e.message);
    }
  }

  useEffect(() => {
    load();
  }, [token]);

  const flashSkus = skus.filter((sku) => sku.flashSale && sku.flashActive);
  const normalSkus = skus.filter((sku) => !(sku.flashSale && sku.flashActive));

  async function buy(sku) {
    setError('');
    setNotice('');
    setBusy(true);
    try {
      const order = await api('/api/mall/orders', { method: 'POST', body: { skuId: sku.id }, token });
      setPending(order);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function pay() {
    if (!pending) return;
    setBusy(true);
    try {
      const result = await api(`/api/mall/orders/${pending.orderNo}/mock-pay`, { method: 'POST', token });
      setPending({ ...pending, status: result.status, balance: result.balance });
      if (result.status === 'PAID') {
        const bonus = Math.max(0, Number(pending.creditCents) - Number(pending.amountCents));
        setNotice(`充值成功：支付 ${yuan(pending.amountCents)}，到账 ${credit(pending.creditCents)} 额度`
          + (bonus > 0 ? `（含赠送 ${yuan(bonus)}）` : ''));
      } else {
        setNotice('支付已完成，但订单已关闭，额度未入账，请联系管理员处理');
      }
      refresh();
      load();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mall">
      <div className="page-head">
        <div>
          <h1>额度商城</h1>
          <p className="muted">充值额度用于对话，按 token 实际成本扣减；额度不是现金余额。演示环境使用模拟支付。</p>
        </div>
        <div className="balance-card">
          <span className="muted">当前可用额度</span>
          <strong>{credits ? `${Number(credits.balance).toFixed(2)} 额度` : '—'}</strong>
        </div>
      </div>

      {error && <div className="error bar">{error}</div>}
      {notice && <div className="notice bar">{notice}</div>}

      {flashSkus.length > 0 && (
        <section className="flash-section">
          <div className="section-title">
            <span className="flash-tag">限时秒杀</span>
            <span className="muted">极低价格，限量供应，每人限购</span>
          </div>
          <div className="flash-grid">
            {flashSkus.map((sku) => (
              <FlashCard key={sku.id} sku={sku} busy={busy} onBuy={() => buy(sku)} />
            ))}
          </div>
        </section>
      )}

      <section>
        <div className="section-title">
          <h2>额度包</h2>
          <span className="muted">按需充值，即充即用</span>
        </div>
        <div className="sku-grid">
          {normalSkus.map((sku) => (
            <article key={sku.id} className="sku-card">
              <header>
                <h3>{sku.title}</h3>
                {sku.description && <p className="muted small">{sku.description}</p>}
              </header>
              <div className="sku-credit">
                <small>到账额度</small>
                <strong>{credit(sku.creditCents)}</strong>
              </div>
              <div className="sku-price">
                <span>支付 {yuan(sku.priceCents)}</span>
                {sku.flashPriceCents != null && <s>{yuan(sku.priceCents)}</s>}
              </div>
              {bonusOf(sku) > 0 && (
                <p className="muted small">含赠送 {yuan(bonusOf(sku))}</p>
              )}
              <button type="button" className="primary" disabled={busy} onClick={() => buy(sku)}>
                立即充值
              </button>
            </article>
          ))}
          {normalSkus.length === 0 && <p className="muted">暂无在售额度包。</p>}
        </div>
      </section>

      <section>
        <div className="section-title">
          <h2>我的订单</h2>
        </div>
        <div className="order-table">
          <div className="order-row order-head">
            <span>订单号</span>
            <span>商品</span>
            <span>支付金额</span>
            <span>到账额度</span>
            <span>状态</span>
          </div>
          {orders.map((order) => (
            <div className="order-row" key={order.orderNo}>
              <span className="mono">{order.orderNo}</span>
              <span>{order.skuTitle}</span>
              <span>{yuan(order.amountCents)}</span>
              <span>{credit(order.creditCents)} 额度</span>
              <span className={`status ${order.status.toLowerCase()}`}>{statusText(order.status)}</span>
            </div>
          ))}
          {orders.length === 0 && <p className="muted small">还没有订单。</p>}
        </div>
      </section>

      {pending && (
        <div className="modal-backdrop" onClick={() => !busy && setPending(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h2>{pending.status === 'PAID' ? '充值成功' : '确认充值'}</h2>
            <dl className="order-detail">
              <div>
                <dt>订单号</dt>
                <dd className="mono">{pending.orderNo}</dd>
              </div>
              <div>
                <dt>支付金额</dt>
                <dd>{yuan(pending.amountCents)}</dd>
              </div>
              <div>
                <dt>到账额度</dt>
                <dd>{credit(pending.creditCents)} 额度
                  {Math.max(0, Number(pending.creditCents) - Number(pending.amountCents)) > 0
                    && `（含赠送 ${yuan(Math.max(0, pending.creditCents - pending.amountCents))}）`}
                </dd>
              </div>
              <div>
                <dt>状态</dt>
                <dd>{statusText(pending.status)}</dd>
              </div>
            </dl>
            {pending.status === 'PAID' ? (
              <div className="modal-actions">
                <button type="button" className="primary" onClick={() => setPending(null)}>
                  完成
                </button>
              </div>
            ) : (
              <div className="modal-actions">
                <button type="button" className="ghost" onClick={() => setPending(null)} disabled={busy}>
                  取消
                </button>
                <button type="button" className="primary" onClick={pay} disabled={busy}>
                  {busy ? '支付中…' : '确认模拟支付（演示）'}
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function FlashCard({ sku, busy, onBuy }) {
  const countdown = useCountdown(sku.flashEndAt);
  return (
    <article className="flash-card">
      <div className="flash-card-top">
        <span className="flash-tag">秒杀进行中</span>
        <span className="countdown">{countdown || '已结束'}</span>
      </div>
      <h3>{sku.title}</h3>
      <div className="flash-price">
        <strong>{yuan(sku.effectivePriceCents)}</strong>
        <s>{yuan(sku.priceCents)}</s>
      </div>
      <p className="muted">到账额度 {credit(sku.creditCents)}</p>
      <div className="flash-meta">
        <span>剩余 {sku.stock < 0 ? '不限量' : `${sku.stock} 份`}</span>
        <span>每人限 {sku.perUserLimit > 0 ? `${sku.perUserLimit} 份` : '不限'}</span>
      </div>
      <button type="button" className="flash-buy" disabled={busy || (sku.stock !== -1 && sku.stock <= 0)} onClick={onBuy}>
        {sku.stock !== -1 && sku.stock <= 0 ? '已售罄' : '立即抢购'}
      </button>
    </article>
  );
}

function statusText(status) {
  return (
    {
      CREATED: '待支付',
      PAYING: '待支付',
      PAID: '已支付',
      PAID_AFTER_CLOSE: '已支付·待处理',
      CLOSED: '已关闭',
    }[status] || status
  );
}
