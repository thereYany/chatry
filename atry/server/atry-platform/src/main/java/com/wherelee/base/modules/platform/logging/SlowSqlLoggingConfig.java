package com.wherelee.base.modules.platform.logging;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * 慢 SQL 探针（默认关闭，本地/预发通过 {@code app.logging.slow-sql-enabled=true} 打开）。
 *
 * <p>用 datasource-proxy 包一层 DataSource，只记录超过阈值的语句，避免全量 SQL 日志把磁盘打满。
 * 打开后 SQL 文本会做脱敏；参数值默认不落盘（参数里最常出现的是手机号/证件号）。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.logging", name = "slow-sql-enabled", havingValue = "true")
public class SlowSqlLoggingConfig {

    @Bean
    static BeanPostProcessor slowSqlDataSourceWrapper(ObjectProvider<LoggingProperties> propertiesProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!"dataSource".equals(beanName) || !(bean instanceof DataSource dataSource)
                        || bean instanceof net.ttddyy.dsproxy.support.ProxyDataSource) {
                    return bean;
                }
                LoggingProperties properties = propertiesProvider.getObject();
                return ProxyDataSourceBuilder.create(dataSource)
                        .name("atry-slow-sql")
                        .listener(new SlowQueryListener(properties.slowSqlThresholdMs()))
                        .build();
            }
        };
    }

    /** 只记录慢查询：SQL 脱敏、不带参数值。 */
    static final class SlowQueryListener implements QueryExecutionListener {

        private static final Logger log = LoggerFactory.getLogger("SLOW_SQL");

        private final long thresholdMs;

        SlowQueryListener(long thresholdMs) {
            this.thresholdMs = thresholdMs;
        }

        @Override
        public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
            // 只在结束后判定耗时
        }

        @Override
        public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
            long elapsed = execInfo.getElapsedTime();
            if (elapsed < thresholdMs || queryInfoList == null || queryInfoList.isEmpty()) {
                return;
            }
            String sql = queryInfoList.get(0).getQuery();
            log.warn("slow-sql elapsedMs={} datasource={} success={} sql=\"{}\"",
                    elapsed, execInfo.getDataSourceName(), execInfo.isSuccess(),
                    SensitiveDataMasker.mask(sql));
        }
    }
}
