-- 充值语义修正：额度不是人民币余额，商品描述明确「支付金额 / 到账额度 / 赠送」
UPDATE t_mall_sku
SET description = '支付 ¥9.9，到账 12.00 额度（含赠送 ¥2.01）'
WHERE tenant_id = 0 AND code = 'CREDIT_12';

UPDATE t_mall_sku
SET description = '支付 ¥49，到账 65.00 额度（含赠送 ¥16.00）'
WHERE tenant_id = 0 AND code = 'CREDIT_65';

UPDATE t_mall_sku
SET description = '支付 ¥99，到账 140.00 额度（含赠送 ¥41.00）'
WHERE tenant_id = 0 AND code = 'CREDIT_140';

UPDATE t_mall_sku
SET title = '限时秒杀 · 抢 30 额度',
    description = '支付 ¥1.9，到账 30.00 额度'
WHERE tenant_id = 0 AND code = 'FLASH_30';
