-- chatry 垂直业务 schema（V9xx 段，避开 atry 内核的 V1xx/V2xx/V3xx/V4xx/V5xx）
--
-- 设计要点：
-- 1) 每张表都带 tenant_id：租户拦截器看到 tenant_id 就不再改写，查询条件可审计；
-- 2) 消息表是「已发生事实」：内容、用量、成本、状态都落库，前端回放与对账共用；
-- 3) 额度不建独立账本，直接复用 t_metering_*（充值=额度入账，对话=按 token 扣减）。

CREATE TABLE t_chat_conversation
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    user_id         BIGINT       NOT NULL,
    title           VARCHAR(120) NOT NULL DEFAULT '新对话',
    model           VARCHAR(64)  NOT NULL DEFAULT 'mock-small',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DELETED',
    message_count   INT          NOT NULL DEFAULT 0,
    last_message_at DATETIME(3)  NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_conv_user (tenant_id, user_id, status, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对话会话';

CREATE TABLE t_chat_message
(
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT      NOT NULL DEFAULT 0,
    conversation_id    BIGINT      NOT NULL,
    user_id            BIGINT      NOT NULL,
    role               VARCHAR(20) NOT NULL COMMENT 'user/assistant',
    content            MEDIUMTEXT  NOT NULL,
    input_tokens       INT         NOT NULL DEFAULT 0,
    output_tokens      INT         NOT NULL DEFAULT 0,
    cached_read_tokens INT         NOT NULL DEFAULT 0,
    cost_micros        BIGINT      NOT NULL DEFAULT 0 COMMENT '本次成本，微元（1e-6 CNY）',
    llm_request_id     VARCHAR(64) NULL,
    provider           VARCHAR(40) NULL,
    model_served       VARCHAR(64) NULL,
    finish_reason      VARCHAR(40) NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'OK' COMMENT 'OK/CANCELLED/FAILED',
    created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_msg_conversation (tenant_id, conversation_id, id),
    KEY idx_msg_request (tenant_id, llm_request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对话消息（含用量与成本）';

-- 业务角色：内核只内置 ADMIN，普通对话用户角色由垂直应用定义
INSERT INTO t_role (tenant_id, code, name, description, built_in)
VALUES (0, 'USER', '普通用户', '对话用户', 1);
