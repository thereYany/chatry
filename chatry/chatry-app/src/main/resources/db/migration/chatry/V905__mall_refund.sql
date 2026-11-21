-- 退款：订单增加已退金额与退款单号；状态扩展 REFUNDING / REFUNDED / PARTIALLY_REFUNDED
ALTER TABLE t_mall_order
    ADD COLUMN refunded_cents BIGINT NOT NULL DEFAULT 0 COMMENT '已退款金额（分）',
    ADD COLUMN refund_no VARCHAR(40) NULL COMMENT '最近一次退款单号';

CREATE INDEX idx_mall_order_refund ON t_mall_order (tenant_id, status, refunded_cents);

-- 退款申请权限：授予内置 ADMIN
INSERT INTO t_permission (tenant_id, code, name, description)
VALUES (0, 'admin:refund:manage', '管理退款', '发起退款与查看退款记录');

INSERT INTO t_role_permission (tenant_id, role_id, permission_id)
SELECT 0, r.id, p.id
FROM t_role r
         JOIN t_permission p ON p.tenant_id = 0 AND p.code = 'admin:refund:manage'
WHERE r.tenant_id = 0
  AND r.code = 'ADMIN';
