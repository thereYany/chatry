-- 审计日志查看权限：授予内置 ADMIN，后续租户可按需授权
INSERT INTO t_permission (tenant_id, code, name, description)
VALUES (0, 'admin:audit:view', '查看审计日志', '管理操作审计与认证事件检索');

INSERT INTO t_role_permission (tenant_id, role_id, permission_id)
SELECT 0, r.id, p.id
FROM t_role r
         JOIN t_permission p ON p.tenant_id = 0 AND p.code = 'admin:audit:view'
WHERE r.tenant_id = 0
  AND r.code = 'ADMIN';
