package com.wherelee.base.modules.platform.tenant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.util.List;

/**
 * 用手持租户拦截的 JdbcTemplate 替换默认实现。
 */
@Configuration
public class TenantJdbcConfig {

    @Bean
    public TenantSqlRewriter tenantSqlRewriter(TenantProperties properties) {
        List<String> extraTables = properties.extraTables() == null ? List.of() : properties.extraTables();
        return new TenantSqlRewriter(extraTables);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource, TenantProperties properties,
                                     TenantSqlRewriter tenantSqlRewriter) {
        return new TenantAwareJdbcTemplate(dataSource, properties.enforce(), tenantSqlRewriter);
    }
}
