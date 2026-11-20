-- chatry 商城与秒杀（V9xx 垂直段）
--
-- 额度包 = 充值商品：下单 → 支付 → 支付成功事件 → 幂等充值到计量账户。
-- 秒杀 = SKU 上的限时特价 + 库存：v1 用条件更新扣库存 + 限购唯一键，压测后再考虑 Redis 预扣。

CREATE TABLE t_mall_sku
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT       NOT NULL DEFAULT 0,
    code              VARCHAR(40)  NOT NULL COMMENT '商品编码（租户内唯一）',
    title             VARCHAR(120) NOT NULL,
    description       VARCHAR(255) NULL,
    price_cents       BIGINT       NOT NULL COMMENT '原价（分）',
    credit_cents      BIGINT       NOT NULL COMMENT '到账额度（分，即 CNY*100）',
    stock             INT          NOT NULL DEFAULT -1 COMMENT '-1=不限量',
    per_user_limit    INT          NOT NULL DEFAULT 0 COMMENT '每人限购，0=不限',
    flash_sale        TINYINT      NOT NULL DEFAULT 0 COMMENT '是否秒杀品',
    flash_price_cents BIGINT       NULL COMMENT '秒杀价（分）',
    flash_start_at    DATETIME(3)  NULL,
    flash_end_at      DATETIME(3)  NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ON_SALE' COMMENT 'ON_SALE/OFF_SHELF',
    sort              INT          NOT NULL DEFAULT 0,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_tenant_code (tenant_id, code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='商城 SKU（额度包/秒杀品）';

CREATE TABLE t_mall_order
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL DEFAULT 0,
    order_no     VARCHAR(40)  NOT NULL,
    user_id      BIGINT       NOT NULL,
    sku_id       BIGINT       NOT NULL,
    sku_title    VARCHAR(120) NOT NULL,
    amount_cents BIGINT       NOT NULL COMMENT '实付（分）',
    credit_cents BIGINT       NOT NULL COMMENT '到账额度（分）',
    status       VARCHAR(20)  NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PAYING/PAID/CLOSED',
    payment_no   VARCHAR(40)  NULL,
    flash_sale   TINYINT      NOT NULL DEFAULT 0,
    limit_key    VARCHAR(120) NULL COMMENT '限购唯一键（限购 1 时使用，null 不参与唯一约束）',
    expire_at    DATETIME(3)  NOT NULL COMMENT '未支付关单时间',
    paid_at      DATETIME(3)  NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (tenant_id, order_no),
    UNIQUE KEY uk_order_limit (tenant_id, limit_key),
    KEY idx_order_user (tenant_id, user_id, created_at),
    KEY idx_order_expire (status, expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='商城订单（额度包购买）';

-- 演示商品：三档常规额度包 + 一档秒杀（窗口放宽到 2027，管理员可随时改）
INSERT INTO t_mall_sku (tenant_id, code, title, description, price_cents, credit_cents,
                        stock, per_user_limit, flash_sale, flash_price_cents,
                        flash_start_at, flash_end_at, status, sort)
VALUES (0, 'CREDIT_12', '入门额度包', '¥9.9 得 ¥12 额度',
        999, 1200, -1, 0, 0, NULL, NULL, NULL, 'ON_SALE', 10),
       (0, 'CREDIT_65', '进阶额度包', '¥49 得 ¥65 额度',
        4900, 6500, -1, 0, 0, NULL, NULL, NULL, 'ON_SALE', 20),
       (0, 'CREDIT_140', '重度额度包', '¥99 得 ¥140 额度',
        9900, 14000, -1, 0, 0, NULL, NULL, NULL, 'ON_SALE', 30),
       (0, 'FLASH_30', '限时秒杀 · 抢 ¥30 额度', '秒杀价 ¥1.9，每人限 1 份',
        3000, 3000, 100, 1, 1, 190,
        '2026-11-01 00:00:00.000', '2027-12-31 23:59:59.000', 'ON_SALE', 5);
