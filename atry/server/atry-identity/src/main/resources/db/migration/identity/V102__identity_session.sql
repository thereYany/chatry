-- 会话表：JWT 里带 session_id（jti），服务端可单独吊销某个会话。
--
-- 状态双写：DB 是事实来源，Redis 只做「活跃会话」快速判断（多实例共享、踢下线即时生效）。
-- 兼容：没有 sid 的旧 token 不做会话校验（只校验用户状态与 token_version）。

CREATE TABLE t_user_session
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL DEFAULT 0,
    session_id    CHAR(32)     NOT NULL COMMENT 'JWT sid（32 位 hex）',
    user_id       BIGINT       NOT NULL,
    ip            VARCHAR(64)  NULL,
    user_agent    VARCHAR(255) NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_seen_at  DATETIME(3)  NULL,
    expires_at    DATETIME(3)  NOT NULL,
    revoked       TINYINT(1)   NOT NULL DEFAULT 0,
    revoked_at    DATETIME(3)  NULL,
    revoked_reason VARCHAR(40) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_id (tenant_id, session_id),
    KEY idx_session_user (tenant_id, user_id, revoked, expires_at),
    KEY idx_session_expire (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='登录会话（可单独吊销）';
