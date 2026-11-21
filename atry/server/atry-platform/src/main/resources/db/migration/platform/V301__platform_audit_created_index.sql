-- 管理审计按时间清理：补 created_at 索引
CREATE INDEX idx_admin_audit_created ON t_admin_audit_log (created_at);
