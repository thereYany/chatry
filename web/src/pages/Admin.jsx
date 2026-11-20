import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { useAuth } from '../App.jsx';

const EMPTY_FORM = {
  code: '',
  title: '',
  description: '',
  priceYuan: '9.9',
  creditYuan: '12',
  stock: '-1',
  perUserLimit: '0',
  flashSale: false,
  flashPriceYuan: '1.9',
  flashStart: '',
  flashEnd: '',
};

export default function Admin() {
  const { token, user } = useAuth();
  const [skus, setSkus] = useState([]);
  const [form, setForm] = useState(EMPTY_FORM);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  async function load() {
    try {
      setSkus(await api('/api/admin/mall/skus', { token }));
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

  function update(field, value) {
    setForm((prev) => ({ ...prev, [field]: value }));
  }

  async function create(event) {
    event.preventDefault();
    setError('');
    setNotice('');
    setBusy(true);
    try {
      const body = {
        code: form.code.trim(),
        title: form.title.trim(),
        description: form.description.trim() || null,
        priceCents: Math.round(Number(form.priceYuan) * 100),
        creditCents: Math.round(Number(form.creditYuan) * 100),
        stock: Number(form.stock),
        perUserLimit: Number(form.perUserLimit),
        flashSale: form.flashSale,
        flashPriceCents: form.flashSale ? Math.round(Number(form.flashPriceYuan) * 100) : null,
        flashStartAt: form.flashSale && form.flashStart ? new Date(form.flashStart).toISOString() : null,
        flashEndAt: form.flashSale && form.flashEnd ? new Date(form.flashEnd).toISOString() : null,
      };
      const created = await api('/api/admin/mall/skus', { method: 'POST', body, token });
      setNotice(`已创建商品「${created.title}」（id=${created.id}）`);
      setForm(EMPTY_FORM);
      load();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function toggle(sku) {
    setError('');
    setBusy(true);
    try {
      const status = sku.status === 'ON_SALE' ? 'OFF_SHELF' : 'ON_SALE';
      await api(`/api/admin/mall/skus/${sku.id}/status`, { method: 'PUT', body: { status }, token });
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
          <h1>商城管理</h1>
          <p className="muted">创建额度包与秒杀品、上下架。秒杀库存按条件更新扣减，售罄即止。</p>
        </div>
        <div className="admin-tabs">
          <span className="ghost active">商品管理</span>
          <Link className="ghost" to="/admin/rbac">用户与权限</Link>
        </div>
      </div>

      {error && <div className="error bar">{error}</div>}
      {notice && <div className="notice bar">{notice}</div>}

      <div className="admin-grid">
        <form className="card" onSubmit={create}>
          <h2>新建商品</h2>
          <div className="field-row">
            <label>
              商品编码
              <input value={form.code} onChange={(e) => update('code', e.target.value)} placeholder="FLASH_30" required />
            </label>
            <label>
              标题
              <input value={form.title} onChange={(e) => update('title', e.target.value)} placeholder="限时秒杀 · 抢 ¥30 额度" required />
            </label>
          </div>
          <label>
            描述
            <input value={form.description} onChange={(e) => update('description', e.target.value)} placeholder="秒杀价 ¥1.9，每人限 1 份" />
          </label>
          <div className="field-row">
            <label>
              原价（元）
              <input type="number" step="0.01" min="0" value={form.priceYuan} onChange={(e) => update('priceYuan', e.target.value)} />
            </label>
            <label>
              到账额度（元）
              <input type="number" step="0.01" min="0.01" value={form.creditYuan} onChange={(e) => update('creditYuan', e.target.value)} />
            </label>
          </div>
          <div className="field-row">
            <label>
              库存（-1 不限）
              <input type="number" step="1" min="-1" value={form.stock} onChange={(e) => update('stock', e.target.value)} />
            </label>
            <label>
              每人限购（0 不限）
              <input type="number" step="1" min="0" value={form.perUserLimit} onChange={(e) => update('perUserLimit', e.target.value)} />
            </label>
          </div>
          <label className="checkbox">
            <input type="checkbox" checked={form.flashSale} onChange={(e) => update('flashSale', e.target.checked)} />
            设为秒杀品（限时特价）
          </label>
          {form.flashSale && (
            <>
              <div className="field-row">
                <label>
                  秒杀价（元）
                  <input type="number" step="0.01" min="0.01" value={form.flashPriceYuan} onChange={(e) => update('flashPriceYuan', e.target.value)} />
                </label>
              </div>
              <div className="field-row">
                <label>
                  开始时间
                  <input type="datetime-local" value={form.flashStart} onChange={(e) => update('flashStart', e.target.value)} required />
                </label>
                <label>
                  结束时间
                  <input type="datetime-local" value={form.flashEnd} onChange={(e) => update('flashEnd', e.target.value)} required />
                </label>
              </div>
            </>
          )}
          <button type="submit" className="primary wide" disabled={busy}>
            {busy ? '提交中…' : '创建商品'}
          </button>
        </form>

        <div className="card">
          <h2>全部商品</h2>
          <div className="admin-sku-list">
            {skus.map((sku) => (
              <div className="admin-sku" key={sku.id}>
                <div>
                  <strong>{sku.title}</strong>
                  <div className="muted small">
                    {sku.code} · {yuan(sku.priceCents)} → {yuan(sku.creditCents)} 额度
                    {sku.flashSale && ` · 秒杀价 ${yuan(sku.flashPriceCents)} · 库存 ${sku.stock}`}
                  </div>
                </div>
                <div className="admin-sku-actions">
                  <span className={`status ${sku.status.toLowerCase()}`}>{sku.status === 'ON_SALE' ? '在售' : '下架'}</span>
                  <button type="button" className="ghost" onClick={() => toggle(sku)} disabled={busy}>
                    {sku.status === 'ON_SALE' ? '下架' : '上架'}
                  </button>
                </div>
              </div>
            ))}
            {skus.length === 0 && <p className="muted">暂无商品。</p>}
          </div>
        </div>
      </div>
    </div>
  );
}

function yuan(cents) {
  return `¥${(Number(cents || 0) / 100).toFixed(2)}`;
}
