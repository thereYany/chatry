-- 资金账本查看权限：授予内置 ADMIN
INSERT INTO t_permission (tenant_id, code, name, description)
VALUES (0, 'admin:finance:view', '查看资金账本', '额度负债、清算与对账差异');

INSERT INTO t_role_permission (tenant_id, role_id, permission_id)
SELECT 0, r.id, p.id
FROM t_role r
         JOIN t_permission p ON p.tenant_id = 0 AND p.code = 'admin:finance:view'
WHERE r.tenant_id = 0
  AND r.code = 'ADMIN';
