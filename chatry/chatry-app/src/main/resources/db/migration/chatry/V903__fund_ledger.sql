-- 资金侧双分录账本：现金/清算、用户额度负债、收入、赠送成本、期初权益
CREATE TABLE t_fund_ledger_account
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL DEFAULT 0,
    code         VARCHAR(40)  NOT NULL,
    owner_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '0=平台账户；非 0=用户账户',
    currency     VARCHAR(8)   NOT NULL,
    account_type VARCHAR(16)  NOT NULL COMMENT 'ASSET/LIABILITY/REVENUE/EXPENSE/EQUITY',
    normality    VARCHAR(8)   NOT NULL COMMENT 'DEBIT/CREDIT',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fund_account (tenant_id, code, owner_id, currency)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='资金账本账户';

CREATE TABLE t_fund_ledger_transaction
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL DEFAULT 0,
    event_key   VARCHAR(120) NOT NULL COMMENT '幂等键，如 topup:orderNo / usage:settle:...',
    biz_type    VARCHAR(40)  NOT NULL,
    biz_no      VARCHAR(80)  NOT NULL,
    description VARCHAR(255) NULL,
    occurred_at DATETIME(3)  NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fund_tx_event (tenant_id, event_key),
    KEY idx_fund_tx_biz (tenant_id, biz_type, biz_no),
    KEY idx_fund_tx_time (tenant_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='资金账本交易';

CREATE TABLE t_fund_ledger_entry
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    transaction_id BIGINT        NOT NULL,
    account_id     BIGINT        NOT NULL,
    direction      VARCHAR(8)    NOT NULL COMMENT 'DEBIT/CREDIT',
    amount         DECIMAL(20,6) NOT NULL,
    currency       VARCHAR(8)    NOT NULL,
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_fund_entry_tx (transaction_id),
    KEY idx_fund_entry_account (account_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='资金账本分录';

CREATE TABLE t_fund_ledger_meta
(
    meta_key   VARCHAR(80) NOT NULL,
    meta_value VARCHAR(255) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (meta_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='资金账本元数据（期初标记等）';

CREATE TABLE t_fund_ledger_check
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT        NOT NULL DEFAULT 0,
    check_date     DATE          NOT NULL,
    liability      DECIMAL(20,6) NOT NULL,
    metering       DECIMAL(20,6) NOT NULL,
    clearing       DECIMAL(20,6) NOT NULL,
    paid_orders    DECIMAL(20,6) NOT NULL,
    liability_diff DECIMAL(20,6) NOT NULL,
    cash_diff      DECIMAL(20,6) NOT NULL,
    unbalanced     INT           NOT NULL,
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fund_check (tenant_id, check_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='资金账本日终对账快照';
