package com.wherelee.base.modules.platform.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计/认证事件保留配置。
 *
 * @param enabled      是否启用清理任务
 * @param retentionDays 保留天数
 * @param batchSize    每批删除行数（限制单次锁表时间）
 * @param maxBatches   单轮最多删除批次（避免一次清理拖太久）
 * @param cron         清理任务 cron（默认每天 03:20）
 */
@ConfigurationProperties(prefix = "app.audit")
public record AuditRetentionProperties(
        Boolean enabled,
        Integer retentionDays,
        Integer batchSize,
        Integer maxBatches,
        String cron
) {

    public AuditRetentionProperties {
        enabled = enabled == null || enabled;
        retentionDays = retentionDays == null || retentionDays <= 0 ? 180 : retentionDays;
        batchSize = batchSize == null || batchSize <= 0 ? 1_000 : batchSize;
        maxBatches = maxBatches == null || maxBatches <= 0 ? 10 : maxBatches;
        cron = cron == null || cron.isBlank() ? "0 20 3 * * *" : cron.trim();
    }
}
