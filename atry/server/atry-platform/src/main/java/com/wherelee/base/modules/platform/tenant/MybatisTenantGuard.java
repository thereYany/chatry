package com.wherelee.base.modules.platform.tenant;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/**
 * MyBatis 租户守卫：MyBatis 不走 {@link TenantAwareJdbcTemplate} 的自动注入，
 * 因此要求 Mapper SQL **显式**带 tenant_id；缺失时直接拒绝执行（fail-fast），
 * 而不是静默注入参数（prepare 阶段无法安全改写参数列表）。
 *
 * <p>系统作用域（定时任务/回调）跳过检查，与 JdbcTemplate 侧语义一致。</p>
 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
        args = {Connection.class, Integer.class}))
public class MybatisTenantGuard implements Interceptor {

    private final TenantSqlRewriter rewriter;

    public MybatisTenantGuard(TenantSqlRewriter rewriter) {
        this.rewriter = rewriter;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (invocation.getTarget() instanceof StatementHandler handler && !TenantContext.isSystemScope()) {
            validate(handler.getBoundSql().getSql());
        }
        return invocation.proceed();
    }

    /** 包级可见：单测直接验证判定，不必启动 MyBatis。 */
    void validate(String sql) {
        if (sql == null || sql.isBlank() || TenantContext.isSystemScope()) {
            return;
        }
        try {
            TenantSqlRewriter.RewriteResult result = rewriter.rewrite(sql);
            if (result.changed()) {
                throw new IllegalStateException("MyBatis SQL 必须显式带 tenant_id：" + compact(sql));
            }
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("MyBatis SQL 必须显式带 tenant_id")) {
                throw e;
            }
            throw new IllegalStateException("MyBatis SQL 必须显式带 tenant_id（跨表/子查询无法自动校验）："
                    + compact(sql), e);
        }
    }

    private String compact(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "...";
    }
}
