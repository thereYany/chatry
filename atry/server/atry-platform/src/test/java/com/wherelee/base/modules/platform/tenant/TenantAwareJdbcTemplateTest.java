package com.wherelee.base.modules.platform.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 租户 JdbcTemplate 注入决策测试（不连库，直接验证 prepare 决策）。
 *
 * <p>业务不变式：非系统作用域内始终注入/校验；系统作用域未回填租户 = 跨租户后台入口，放行；
 * 系统作用域已回填租户 = 回到单租户语义，恢复注入（hybrid）。</p>
 */
class TenantAwareJdbcTemplateTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final TenantSqlRewriter rewriter = new TenantSqlRewriter(List.of());

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void enforce关闭时不做任何注入() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, false, rewriter);
        TenantContext.set(5L);
        assertThat(template.prepare("SELECT * FROM t_user").changed()).isFalse();
    }

    @Test
    void enforce开启且普通请求线程时强制注入() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);
        TenantContext.set(5L);
        TenantSqlRewriter.RewriteResult result = template.prepare("SELECT * FROM t_user WHERE id = ?");
        assertThat(result.changed()).isTrue();
        assertThat(result.sql()).contains("tenant_id");
    }

    @Test
    void 系统作用域未回填租户时放行跨租户SQL() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);
        TenantContext.runAsSystem(() -> {
            assertThat(template.prepare("SELECT * FROM t_user").changed()).isFalse();
            return null;
        });
    }

    @Test
    void 系统作用域回填租户后恢复单租户注入() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);
        TenantContext.runAsSystem(() -> {
            TenantContext.set(77L);
            TenantSqlRewriter.RewriteResult result = template.prepare("SELECT * FROM t_user");
            assertThat(result.changed()).isTrue();
            assertThat(result.argIndex()).isZero();
            return null;
        });
    }

    @Test
    void 系统作用域结束后普通请求线程恢复强制注入() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);
        TenantContext.runAsSystem(() -> {
            assertThat(template.prepare("SELECT * FROM t_user").changed()).isFalse();
            return null;
        });
        TenantContext.set(5L);
        assertThat(template.prepare("SELECT * FROM t_user").changed()).isTrue();
        assertThat(TenantContext.isSystemScope()).isFalse();
    }
}
