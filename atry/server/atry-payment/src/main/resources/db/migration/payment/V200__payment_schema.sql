-- 通用支付域 schema（与业务无关）
--
-- 支付单是独立聚合：业务侧用 app_id + biz_type + biz_no 关联，支付域不解析业务语义。
-- 历史 order_id 列保留为可空「业务引用」，新链路只认 payment_no + biz_no。

CREATE TABLE t_payment_order
(
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT        NOT NULL DEFAULT 0,
    payment_no        VARCHAR(40)   NOT NULL COMMENT '支付单号，对外唯一标识',
    app_id            VARCHAR(32)   NOT NULL COMMENT '接入方应用标识（多项目复用）',
    biz_type          VARCHAR(32)   NOT NULL COMMENT '业务类型，由业务侧定义，支付不解析',
    biz_no            VARCHAR(64)   NOT NULL COMMENT '业务单号，由业务侧生成',
    subject           VARCHAR(128)  NOT NULL COMMENT '商品标题快照',
    amount_cents      BIGINT        NOT NULL,
    currency          VARCHAR(3)    NOT NULL DEFAULT 'CNY',
    status            VARCHAR(20)   NOT NULL COMMENT 'CREATED/PAYING/PAID/FAILED/CLOSED/REFUNDING/REFUNDED/REFUND_FAILED',
    channel           VARCHAR(20)   NULL,
    provider_trade_no VARCHAR(64)   NULL,
    notify_url        VARCHAR(255)  NULL,
    metadata_json     VARCHAR(1024) NULL COMMENT '业务上下文透传，支付永不解析',
    source_updated_at DATETIME(3)   NOT NULL COMMENT '来源单据更新时间，用于防止影子写回退',
    expire_at         DATETIME(3)   NOT NULL,
    paid_at           DATETIME(3)   NULL,
    version           INT           NOT NULL DEFAULT 0,
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_no (payment_no),
    UNIQUE KEY uk_payment_biz (app_id, biz_type, biz_no),
    UNIQUE KEY uk_payment_provider_trade (channel, provider_trade_no),
    KEY idx_payment_status_expire (status, expire_at),
    KEY idx_payment_status_updated (status, updated_at),
    KEY idx_payment_channel_paid (channel, paid_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='支付单（独立聚合）';

CREATE TABLE t_payment_attempt
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id             BIGINT       NOT NULL DEFAULT 0,
    payment_no            VARCHAR(40)  NULL COMMENT '所属支付单号',
    order_id              BIGINT       NULL COMMENT '历史字段；反转后以 payment_no 关联支付单',
    attempt_no            INT          NOT NULL,
    channel               VARCHAR(20)  NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    provider_trade_no     VARCHAR(64)  NOT NULL,
    provider_status       VARCHAR(20)  NOT NULL,
    provider_amount_cents BIGINT       NOT NULL,
    request_digest        VARCHAR(64)  NULL,
    failure_code          VARCHAR(64)  NULL,
    failure_message       VARCHAR(300) NULL,
    mock_scenario         VARCHAR(40)  NULL,
    created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                              ON UPDATE CURRENT_TIMESTAMP(3),
    finished_at           DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_attempt_no (order_id, attempt_no),
    UNIQUE KEY uk_payment_provider_trade (channel, provider_trade_no),
    KEY idx_payment_attempt_order (order_id, created_at),
    KEY idx_attempt_payment_no (payment_no),
    KEY idx_attempt_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='支付尝试';

CREATE TABLE t_payment_event
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL DEFAULT 0,
    payment_no     VARCHAR(40)  NULL COMMENT '所属支付单号',
    order_id       BIGINT       NULL COMMENT '历史字段；反转后以 payment_no 关联支付单',
    provider       VARCHAR(20)  NOT NULL,
    event_key      VARCHAR(120) NOT NULL,
    order_no       VARCHAR(40)  NOT NULL COMMENT '业务单号快照',
    event_type     VARCHAR(30)  NOT NULL,
    amount_cents   BIGINT       NOT NULL,
    payload_digest VARCHAR(64)  NULL,
    process_status VARCHAR(20)  NOT NULL,
    error_message  VARCHAR(500) NULL,
    received_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    processed_at   DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_event_key (provider, event_key),
    KEY idx_payment_event_order (order_id, received_at),
    KEY idx_payment_event_status (process_status, received_at),
    KEY idx_event_payment_no (payment_no),
    KEY idx_event_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='支付事件';

CREATE TABLE t_refund
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT       NOT NULL DEFAULT 0,
    payment_no         VARCHAR(40)  NULL COMMENT '所属支付单号',
    order_id           BIGINT       NULL COMMENT '历史字段；反转后以 payment_no 关联支付单',
    refund_no          VARCHAR(40)  NOT NULL,
    amount_cents       BIGINT       NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    attempt_count      INT          NOT NULL DEFAULT 0,
    next_retry_at      DATETIME(3)  NULL,
    provider_refund_no VARCHAR(64)  NULL,
    reason             VARCHAR(200) NOT NULL,
    requested_by       BIGINT       NOT NULL,
    requested_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    succeeded_at       DATETIME(3)  NULL,
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                           ON UPDATE CURRENT_TIMESTAMP(3),
    error_message      VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_no (refund_no),
    UNIQUE KEY uk_refund_order (order_id),
    KEY idx_refund_status (status, updated_at),
    KEY idx_refund_retry (status, next_retry_at),
    KEY idx_refund_payment_no (payment_no),
    KEY idx_refund_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='退款记录';

CREATE TABLE t_payment_outbox
(
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT        NOT NULL DEFAULT 0,
    event_type   VARCHAR(40)   NOT NULL COMMENT 'PAYMENT_SUCCEEDED / PAYMENT_FAILED / REFUND_SUCCEEDED',
    aggregate_no VARCHAR(40)   NOT NULL COMMENT '支付单号',
    payload      VARCHAR(4000) NOT NULL,
    status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED/DEAD',
    attempts     INT           NOT NULL DEFAULT 0,
    next_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_error   VARCHAR(500)  NULL,
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at      DATETIME(3)   NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_due (status, next_at),
    KEY idx_outbox_aggregate (aggregate_no),
    KEY idx_outbox_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='支付事件发件箱';

CREATE TABLE t_recon_task
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    task_no     VARCHAR(40) NOT NULL,
    channel     VARCHAR(20) NOT NULL,
    bill_date   DATE        NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/FAILED',
    total_count INT         NOT NULL DEFAULT 0,
    match_count INT         NOT NULL DEFAULT 0,
    diff_count  INT         NOT NULL DEFAULT 0,
    started_at  DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_recon_task_no (task_no),
    UNIQUE KEY uk_recon_channel_date (channel, bill_date),
    KEY idx_recon_status (status, bill_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对账任务';

CREATE TABLE t_recon_diff
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    task_no         VARCHAR(40)  NOT NULL,
    payment_no      VARCHAR(40)  NULL,
    biz_no          VARCHAR(64)  NULL,
    diff_type       VARCHAR(32)  NOT NULL COMMENT 'LOCAL_ONLY/PROVIDER_ONLY/AMOUNT/STATUS',
    expected_amount BIGINT       NULL,
    actual_amount   BIGINT       NULL,
    detail          VARCHAR(500) NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/HANDLED/IGNORED',
    handled_by      BIGINT       NULL,
    handled_at      DATETIME(3)  NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_diff_task (task_no, diff_type),
    KEY idx_diff_status (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对账差异明细';

CREATE TABLE t_channel_bill
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    channel     VARCHAR(20)  NOT NULL,
    bill_date   DATE         NOT NULL,
    file_key    VARCHAR(255) NOT NULL,
    checksum    VARCHAR(64)  NULL,
    line_count  INT          NOT NULL DEFAULT 0,
    imported_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_bill_channel_date (channel, bill_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='渠道账单原始记录';
