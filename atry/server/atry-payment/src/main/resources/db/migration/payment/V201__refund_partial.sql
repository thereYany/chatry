-- 退款支持部分退款/多次退款：去掉「一个订单只能退一次」的唯一键，改为按支付单索引
ALTER TABLE t_refund DROP INDEX uk_refund_order;

CREATE INDEX idx_refund_order ON t_refund (order_id);
CREATE INDEX idx_refund_payment_status ON t_refund (payment_no, status);
