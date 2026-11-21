CREATE TABLE t_metering_invoice
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_no     VARCHAR(40)   NOT NULL,
    tenant_id      BIGINT        NOT NULL DEFAULT 0,
    app_id         BIGINT        NOT NULL DEFAULT 0,
    user_id        BIGINT        NOT NULL DEFAULT 0,
    currency       VARCHAR(8)    NOT NULL,
    period_start   DATETIME(3)   NOT NULL,
    period_end     DATETIME(3)   NOT NULL,
    total_amount   DECIMAL(20,6) NOT NULL,
    status         VARCHAR(16)   NOT NULL DEFAULT 'FINALIZED',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice_no (invoice_no),
    UNIQUE KEY uk_invoice_period (tenant_id, app_id, user_id, currency, period_start, period_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='账单';

CREATE TABLE t_metering_invoice_item
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_id  BIGINT        NOT NULL,
    charge_id   BIGINT        NOT NULL,
    event_id    VARCHAR(64)   NOT NULL,
    meter_key   VARCHAR(64)   NOT NULL,
    quantity    DECIMAL(20,6) NOT NULL,
    amount      DECIMAL(20,6) NOT NULL,
    currency    VARCHAR(8)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice_item_charge (charge_id),
    KEY idx_invoice_item_invoice (invoice_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='账单明细';

CREATE TABLE t_metering_recon_run
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    period_start   DATETIME(3)  NOT NULL,
    period_end     DATETIME(3)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    checked_events INT          NOT NULL,
    checked_charges INT         NOT NULL,
    diff_count     INT          NOT NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_recon_period (period_start, period_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对账任务';

CREATE TABLE t_metering_recon_diff
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    run_id     BIGINT       NOT NULL,
    diff_type  VARCHAR(32)  NOT NULL,
    ref        VARCHAR(128) NULL,
    expected   VARCHAR(64)  NULL,
    actual     VARCHAR(64)  NULL,
    detail     VARCHAR(500) NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_recon_diff_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对账差异';
