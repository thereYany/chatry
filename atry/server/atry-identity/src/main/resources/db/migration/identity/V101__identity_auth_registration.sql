-- 身份内核 0.2.x：邮箱字段 + 认证事件审计（注册/登录成功/登录失败/登出）
--
-- 邮箱暂不做验证（无邮件服务商），只做格式校验 + 租户内唯一；
-- 以后接邮件/短信时只加发送与验证状态，不改表结构。

ALTER TABLE t_user
    ADD COLUMN email VARCHAR(120) NULL COMMENT '邮箱（可选，租户内唯一）' AFTER nickname;

ALTER TABLE t_user
    ADD UNIQUE KEY uk_user_tenant_email (tenant_id, email);

CREATE TABLE t_auth_event
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT       NOT NULL DEFAULT 0,
    user_id    BIGINT       NULL,
    username   VARCHAR(50)  NULL,
    event_type VARCHAR(30)  NOT NULL COMMENT 'REGISTER/LOGIN_SUCCESS/LOGIN_FAILURE/LOGOUT/PASSWORD_CHANGE',
    success    TINYINT(1)   NOT NULL DEFAULT 1,
    ip         VARCHAR(64)  NULL,
    user_agent VARCHAR(255) NULL,
    detail     VARCHAR(255) NULL COMMENT '失败原因等非敏感说明，禁止写密码/令牌',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_auth_event_user (tenant_id, user_id, id),
    KEY idx_auth_event_type (tenant_id, event_type, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='认证事件审计（注册/登录/登出）';
