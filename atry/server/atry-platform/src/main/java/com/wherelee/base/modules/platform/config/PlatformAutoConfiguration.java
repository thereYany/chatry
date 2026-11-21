package com.wherelee.base.modules.platform.config;

import com.wherelee.base.modules.platform.config.SecurityProperties;
import com.wherelee.base.modules.platform.audit.AuditRetentionProperties;
import com.wherelee.base.modules.platform.logging.LoggingProperties;
import com.wherelee.base.modules.platform.storage.StorageProperties;
import com.wherelee.base.modules.platform.tenant.TenantProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 平台内核自动配置（starter 入口）。
 *
 * <p>扫描并注册 platform 包内的全部内核组件（租户、追踪、告警、对象存储、AOP、审计等），
 * 业务应用只需依赖 atry-platform 并在自己的包下放业务组件，不必再扫描 platform 包。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties({SecurityProperties.class, TenantProperties.class, StorageProperties.class,
        LoggingProperties.class, AuditRetentionProperties.class})
@ComponentScan(
        basePackages = "com.wherelee.base.modules.platform",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = PlatformAutoConfiguration.class))
public class PlatformAutoConfiguration {
}
