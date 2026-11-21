package com.wherelee.base.modules.platform.audit;

import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/** 管理审计清理：按保留期分批删除，系统作用域跨租户执行。 */
@Component
@ConditionalOnProperty(name = "app.audit.enabled", havingValue = "true", matchIfMissing = true)
public class AdminAuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final AuditRetentionProperties properties;

    public AdminAuditRetentionJob(JdbcTemplate jdbc, AuditRetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.audit.cron:0 20 3 * * *}")
    public void run() {
        int deleted = TenantContext.runAsSystem(() -> deleteExpired(properties));
        if (deleted > 0) {
            log.info("admin.audit.cleanup deleted={} retentionDays={}", deleted, properties.retentionDays());
        }
    }

    /** 包级可见：单测直接验证分批删除逻辑。 */
    int deleteExpired(AuditRetentionProperties props) {
        Timestamp cutoff = Timestamp.valueOf(LocalDateTime.now().minusDays(props.retentionDays()));
        int total = 0;
        for (int batch = 0; batch < props.maxBatches(); batch++) {
            int deleted = jdbc.update("""
                    DELETE FROM t_admin_audit_log WHERE created_at < ? LIMIT ?
                    """, cutoff, props.batchSize());
            total += deleted;
            if (deleted < props.batchSize()) {
                break;
            }
        }
        return total;
    }
}
