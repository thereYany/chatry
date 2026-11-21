-- 身份内核 schema（通用基座）
--
-- 只包含与业务无关的身份数据：账号、邀请、角色、权限、用户-角色。
-- 业务角色码是数据，由垂直应用在自己的迁移里插入，内核不定义。

CREATE TABLE t_user
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT       NOT NULL DEFAULT 0,
    username             VARCHAR(50)  NOT NULL,
    password_hash        VARCHAR(100) NOT NULL,
    nickname             VARCHAR(50)  NOT NULL,
    role                 VARCHAR(40)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    must_change_password TINYINT(1)   NOT NULL DEFAULT 0,
    token_version        INT          NOT NULL DEFAULT 0,
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                              ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_tenant_username (tenant_id, username),
    KEY idx_user_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='账号';

CREATE TABLE t_user_invitation
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL DEFAULT 0,
    token_hash  CHAR(64)     NOT NULL,
    username    VARCHAR(50)  NOT NULL,
    nickname    VARCHAR(50)  NOT NULL,
    role_code   VARCHAR(40)  NOT NULL COMMENT '受邀角色码，由垂直应用定义',
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    expires_at  DATETIME(3)  NOT NULL,
    created_by  BIGINT       NOT NULL,
    used_by     BIGINT       NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    used_at     DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_invitation_token (token_hash),
    KEY idx_invitation_status (status, expires_at),
    KEY idx_invitation_creator (created_by, created_at),
    KEY idx_invitation_username (username, status),
    KEY idx_invitation_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户邀请（一次性链接注册）';

CREATE TABLE t_role
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL DEFAULT 0,
    code        VARCHAR(40)  NOT NULL COMMENT '角色码，由垂直应用定义',
    name        VARCHAR(60)  NOT NULL,
    description VARCHAR(200) NULL,
    built_in    TINYINT      NOT NULL DEFAULT 0 COMMENT '内置角色不允许删除',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_tenant_code (tenant_id, code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '角色';

CREATE TABLE t_permission
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL DEFAULT 0,
    code        VARCHAR(80)  NOT NULL COMMENT '权限码，形如 resource:action',
    name        VARCHAR(80)  NOT NULL,
    description VARCHAR(200) NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_perm_tenant_code (tenant_id, code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '权限点';

CREATE TABLE t_role_permission
(
    id            BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT NOT NULL DEFAULT 0,
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (tenant_id, role_id, permission_id),
    KEY idx_rp_role (tenant_id, role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '角色-权限';

CREATE TABLE t_user_role
(
    id        BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    user_id   BIGINT NOT NULL,
    role_id   BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (tenant_id, user_id, role_id),
    KEY idx_ur_user (tenant_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户-角色（支持多角色）';

-- 内置管理员角色：拥有全部内核权限。垂直业务角色与权限由垂直应用迁移追加。
INSERT INTO t_role (tenant_id, code, name, description, built_in)
VALUES (0, 'ADMIN', '管理员', '平台管理员，拥有全部权限', 1);

INSERT INTO t_permission (tenant_id, code, name)
VALUES (0, 'admin:user:manage', '管理账号'),
       (0, 'admin:role:manage', '管理角色与权限'),
       (0, 'admin:invitation:manage', '管理用户邀请'),
       (0, 'admin:recon:manage', '资金对账管理'),
       (0, 'payment:mock', '模拟支付（测试用）');

INSERT INTO t_role_permission (tenant_id, role_id, permission_id)
SELECT 0, r.id, p.id
FROM t_role r
         JOIN t_permission p ON p.tenant_id = 0
WHERE r.tenant_id = 0
  AND r.code = 'ADMIN';
