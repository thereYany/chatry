CREATE TABLE t_resource_pack (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(500) NULL,
    price_cents BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ON_SALE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_resource_pack_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    order_no VARCHAR(40) NOT NULL,
    pack_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    payment_no VARCHAR(40) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    paid_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rp_order_tenant_no (tenant_id, order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_resource_pack_grant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    user_id BIGINT NOT NULL,
    pack_id BIGINT NOT NULL,
    order_no VARCHAR(40) NOT NULL,
    granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rp_ent (tenant_id, user_id, pack_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO t_resource_pack (tenant_id, title, description, price_cents, status)
VALUES (0, '数据结构与算法资料包', '讲解 + 配套代码合集', 9900, 'ON_SALE'),
       (0, '面试八股文资料包', 'Java 后端高频题整理', 4900, 'ON_SALE');
