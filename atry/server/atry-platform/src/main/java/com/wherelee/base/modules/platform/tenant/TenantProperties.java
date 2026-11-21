package com.wherelee.base.modules.platform.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 租户隔离配置。
 *
 * @param enforce       true = SQL 未带租户条件时直接拒绝（生产必须为 true）
 * @param defaultTenantId 未显式指定租户时的默认租户 id
 * @param extraTables   垂直应用自己的租户表；内核表已内置，无需重复声明
 */
@ConfigurationProperties(prefix = "app.tenant")
public record TenantProperties(boolean enforce, long defaultTenantId, List<String> extraTables) {
}
