-- LLM 调用框架 schema（P0）
-- 调用记录只存元数据与用量，不存 prompt/response 内容；内容审计走受控存储引用。

CREATE TABLE t_llm_call_record
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    request_id          VARCHAR(40)  NOT NULL,
    tenant_id           BIGINT       NOT NULL DEFAULT 0,
    app_id              BIGINT       NOT NULL DEFAULT 0,
    user_id             BIGINT       NOT NULL DEFAULT 0,
    operation           VARCHAR(20)  NOT NULL DEFAULT 'chat',
    provider            VARCHAR(32)  NULL,
    deployment          VARCHAR(64)  NULL,
    model_requested     VARCHAR(64)  NULL,
    model_served        VARCHAR(64)  NULL,
    stream              BOOLEAN      NOT NULL DEFAULT FALSE,
    attempt_count       INT          NOT NULL DEFAULT 0,
    status              VARCHAR(20)  NOT NULL COMMENT 'SUCCESS/FAILED/CANCELLED/INTERRUPTED',
    error_category      VARCHAR(40)  NULL,
    provider_code       VARCHAR(64)  NULL,
    retryable           BOOLEAN      NULL,
    billable            BOOLEAN      NULL,
    input_tokens        BIGINT       NOT NULL DEFAULT 0,
    output_tokens       BIGINT       NOT NULL DEFAULT 0,
    cached_read_tokens  BIGINT       NOT NULL DEFAULT 0,
    cached_write_tokens BIGINT       NOT NULL DEFAULT 0,
    reasoning_tokens    BIGINT       NOT NULL DEFAULT 0,
    usage_source        VARCHAR(16)  NULL COMMENT 'PROVIDER/ESTIMATED',
    latency_ms          BIGINT       NULL,
    ttft_ms             BIGINT       NULL,
    amount_micros       BIGINT       NULL,
    price_version       VARCHAR(32)  NULL,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_llm_request (request_id),
    KEY idx_llm_tenant_time (tenant_id, created_at),
    KEY idx_llm_provider_time (provider, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='LLM 调用记录（脱敏元数据）';

CREATE TABLE t_llm_outbox
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_id VARCHAR(40)  NOT NULL COMMENT 'request_id',
    event_type   VARCHAR(40)  NOT NULL,
    payload      JSON         NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SENT/FAILED/DEAD',
    attempts     INT          NOT NULL DEFAULT 0,
    next_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_error   VARCHAR(500) NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at      DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_llm_outbox_due (status, next_at),
    KEY idx_llm_outbox_aggregate (aggregate_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='LLM 调用完成事件发件箱';
