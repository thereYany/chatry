package com.wherelee.base.modules.platform.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 日志与可观测配置。
 *
 * @param accessLogEnabled     是否输出访问日志（默认开；压测/极低延迟场景可关）
 * @param slowSqlEnabled       是否给数据源套慢 SQL 探针（默认关，本地/预发打开）
 * @param slowSqlThresholdMs   慢 SQL 阈值（毫秒）
 * @param accessLogSlowMs      访问日志里标记「慢请求」的阈值（毫秒，仅用于告警字段）
 */
@ConfigurationProperties(prefix = "app.logging")
public record LoggingProperties(
        Boolean accessLogEnabled,
        Boolean slowSqlEnabled,
        Integer slowSqlThresholdMs,
        Integer accessLogSlowMs
) {

    public LoggingProperties {
        accessLogEnabled = accessLogEnabled == null || accessLogEnabled;
        slowSqlEnabled = slowSqlEnabled != null && slowSqlEnabled;
        slowSqlThresholdMs = slowSqlThresholdMs == null || slowSqlThresholdMs <= 0 ? 200 : slowSqlThresholdMs;
        accessLogSlowMs = accessLogSlowMs == null || accessLogSlowMs <= 0 ? 1_000 : accessLogSlowMs;
    }
}
