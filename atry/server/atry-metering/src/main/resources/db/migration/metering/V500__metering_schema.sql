-- 通用计量计费 schema（P0）
-- 金额统一 DECIMAL(20,6)，币种显式；token 只是 metric 的一种单位。

CREATE TABLE t_metering_meter
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    meter_key        VARCHAR(64)  NOT NULL,
    event_name       VARCHAR(64)  NOT NULL,
    aggregation      VARCHAR(16)  NOT NULL DEFAULT 'SUM' COMMENT 'SUM/COUNT/UNIQUE/LATEST/MAX',
    unit             VARCHAR(16)  NOT NULL,
    dimensions_json  JSON         NULL,
    version          INT          NOT NULL DEFAULT 1,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_meter_key_version (meter_key, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='计量定义';

CREATE TABLE t_metering_usage_event
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    event_id        VARCHAR(64)   NOT NULL COMMENT '业务确定性幂等键',
    meter_key       VARCHAR(64)   NOT NULL,
    tenant_id       BIGINT        NOT NULL DEFAULT 0,
    app_id          BIGINT        NOT NULL DEFAULT 0,
    user_id         BIGINT        NOT NULL DEFAULT 0,
    quantity        DECIMAL(20,6) NOT NULL,
    unit            VARCHAR(16)   NOT NULL,
    dimensions_json JSON          NULL,
    occurred_at     DATETIME(3)   NOT NULL,
    received_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    source_ref      VARCHAR(128)  NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'RECEIVED',
    charge_id       BIGINT        NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_usage_event_id (event_id),
    KEY idx_usage_meter_time (meter_key, occurred_at),
    KEY idx_usage_tenant_time (tenant_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用量事件（append-only）';

CREATE TABLE t_metering_price_book
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    book_key       VARCHAR(64)  NOT NULL,
    currency       VARCHAR(8)   NOT NULL,
    version        INT          NOT NULL,
    effective_from DATETIME(3)  NOT NULL,
    effective_to   DATETIME(3)  NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_price_book_version (book_key, version),
    KEY idx_price_book_effective (book_key, effective_from, effective_to)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='价格书（版本化）';

CREATE TABLE t_metering_rate_card
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    book_id          BIGINT        NOT NULL,
    meter_key        VARCHAR(64)   NOT NULL,
    subject_selector VARCHAR(64)   NULL,
    unit_price       DECIMAL(24,12) NOT NULL,
    currency         VARCHAR(8)    NOT NULL,
    tiers_json       JSON          NULL,
    rounding         VARCHAR(16)   NOT NULL DEFAULT 'CEIL',
    created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_rate_card_book_meter (book_id, meter_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='费率卡';

CREATE TABLE t_metering_account
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT        NOT NULL DEFAULT 0,
    app_id        BIGINT        NOT NULL DEFAULT 0,
    user_id       BIGINT        NOT NULL DEFAULT 0,
    currency      VARCHAR(8)    NOT NULL,
    balance       DECIMAL(20,6) NOT NULL DEFAULT 0,
    frozen        DECIMAL(20,6) NOT NULL DEFAULT 0,
    credit_limit  DECIMAL(20,6) NOT NULL DEFAULT 0,
    status        VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    version       INT           NOT NULL DEFAULT 0,
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_subject (tenant_id, app_id, user_id, currency),
    CONSTRAINT ck_account_non_negative CHECK (balance >= 0 AND frozen >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='余额账户';

CREATE TABLE t_metering_ledger
(
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    account_id         BIGINT        NOT NULL,
    direction          VARCHAR(8)    NOT NULL COMMENT 'DEBIT/CREDIT',
    amount             DECIMAL(20,6) NOT NULL,
    currency           VARCHAR(8)    NOT NULL,
    biz_type           VARCHAR(20)   NOT NULL COMMENT 'RECHARGE/SETTLE/REFUND/ADJUST',
    biz_no             VARCHAR(64)   NOT NULL,
    balance_after      DECIMAL(20,6) NOT NULL,
    created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_biz_no (biz_no),
    KEY idx_ledger_account_time (account_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='资金流水（不可变）';

CREATE TABLE t_metering_reservation
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    reservation_id  VARCHAR(40)   NOT NULL,
    account_id      BIGINT        NOT NULL,
    amount          DECIMAL(20,6) NOT NULL,
    currency        VARCHAR(8)    NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SETTLED/RELEASED/EXPIRED',
    biz_ref         VARCHAR(64)   NULL,
    expires_at      DATETIME(3)   NOT NULL,
    settled_at      DATETIME(3)   NULL,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_id (reservation_id),
    KEY idx_reservation_status_expire (status, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='预扣记录';

CREATE TABLE t_metering_charge
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    event_id            VARCHAR(64)   NOT NULL,
    meter_key           VARCHAR(64)   NOT NULL,
    quantity            DECIMAL(20,6) NOT NULL,
    amount              DECIMAL(20,6) NOT NULL,
    currency            VARCHAR(8)    NOT NULL,
    price_version       VARCHAR(32)   NOT NULL,
    rate_card_id        BIGINT        NULL,
    price_snapshot_json JSON          NOT NULL,
    rule_version        VARCHAR(32)   NULL,
    rated_at            DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_charge_event (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='计价结果';
