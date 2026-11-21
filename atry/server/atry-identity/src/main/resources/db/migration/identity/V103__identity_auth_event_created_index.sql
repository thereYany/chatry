-- 认证事件按时间清理：补 created_at 索引，避免归档任务全表扫描
CREATE INDEX idx_auth_event_created ON t_auth_event (created_at);
