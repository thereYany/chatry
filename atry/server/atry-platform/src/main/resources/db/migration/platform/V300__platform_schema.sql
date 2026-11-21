-- 平台内核 schema：告警记录与管理员操作审计。
-- 内核不存业务数据，只存跨业务共用的运维事实。

CREATE TABLE t_alert_log
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT        NOT NULL DEFAULT 0,
    alert_type     VARCHAR(50)   NOT NULL,
    severity       VARCHAR(20)   NOT NULL,
    message        VARCHAR(1000) NOT NULL,
    delivered      TINYINT(1)    NOT NULL DEFAULT 0,
    delivery_error VARCHAR(500)  NULL,
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_alert_type_time (alert_type, created_at),
    KEY idx_alert_created (created_at),
    KEY idx_alert_tenant_created (tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='主动告警记录';

CREATE TABLE t_admin_audit_log
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL DEFAULT 0,
    operator_id   BIGINT       NOT NULL,
    action        VARCHAR(50)  NOT NULL,
    target_type   VARCHAR(30)  NOT NULL,
    target_id     BIGINT       NULL,
    success       TINYINT(1)   NOT NULL,
    duration_ms   BIGINT       NOT NULL DEFAULT 0,
    error_message VARCHAR(500) NULL,
    trace_id      VARCHAR(64)  NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_admin_audit_operator_time (operator_id, created_at),
    KEY idx_admin_audit_action_time (action, created_at),
    KEY idx_admin_audit_target (target_type, target_id, created_at),
    KEY idx_admin_audit_trace (trace_id),
    KEY idx_audit_tenant_created (tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='管理员操作审计';
