package com.wherelee.base.modules.platform.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

/**
 * 租户隔离拦截器：所有 SQL 在进入数据库前强制注入租户条件。
 *
 * <p>覆盖 JdbcTemplate 的常用入口；MyBatis 语句不在其范围内，需要手写 tenant_id（见各自 XML）。
 * enforce=true 时，无法安全注入的语句直接抛错，而不是放行。</p>
 */
public class TenantAwareJdbcTemplate extends JdbcTemplate {

    private final boolean enforce;
    private final TenantSqlRewriter rewriter;

    public TenantAwareJdbcTemplate(DataSource dataSource, boolean enforce, TenantSqlRewriter rewriter) {
        super(dataSource);
        this.enforce = enforce;
        this.rewriter = rewriter;
    }

    /** 包级可见：单测直接验证注入决策，不必连真实库。 */
    TenantSqlRewriter.RewriteResult prepare(String sql) {
        // 系统作用域 hybrid：未回填租户 = 明确的跨租户后台入口（定时任务/公开回调/发件箱投递），
        // 放行自动注入；已回填租户 = 回到单租户语义，恢复自动注入（限定该租户）。
        // 非系统作用域行为不变：始终注入/校验。
        if (!enforce || (TenantContext.isSystemScope() && TenantContext.currentOrNull() == null)) {
            return new TenantSqlRewriter.RewriteResult(sql, -1);
        }
        return rewriter.rewrite(sql);
    }

    private Object[] argsWithTenant(TenantSqlRewriter.RewriteResult result, Object[] args) {
        if (!result.changed()) {
            return args;
        }
        Object[] answer = new Object[args.length + 1];
        int index = Math.min(Math.max(result.argIndex(), 0), args.length);
        System.arraycopy(args, 0, answer, 0, index);
        answer[index] = TenantContext.current();
        System.arraycopy(args, index, answer, index + 1, args.length - index);
        return answer;
    }

    @Override
    public int update(String sql) {
        TenantSqlRewriter.RewriteResult result = prepare(sql);
        return super.update(result.sql(), argsWithTenant(result, new Object[0]));
    }

    @Override
    public int update(String sql, Object... args) {
        TenantSqlRewriter.RewriteResult result = prepare(sql);
        return super.update(result.sql(), argsWithTenant(result, args));
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        TenantSqlRewriter.RewriteResult result = prepare(sql);
        return super.query(result.sql(), rowMapper, argsWithTenant(result, args));
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
        TenantSqlRewriter.RewriteResult result = prepare(sql);
        return super.query(result.sql(), rowMapper, argsWithTenant(result, new Object[0]));
    }

    @Override
    public void query(String sql, RowCallbackHandler rch, Object... args) {
        TenantSqlRewriter.RewriteResult result = prepare(sql);
        super.query(result.sql(), rch, argsWithTenant(result, args));
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        TenantSqlRewriter.RewriteResult result = prepare(sql);
        return super.queryForObject(result.sql(), requiredType, argsWithTenant(result, args));
    }

    @Override
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
        TenantSqlRewriter.RewriteResult result = prepare(sql);
        return super.queryForList(result.sql(), elementType, argsWithTenant(result, args));
    }

    @Override
    public <T> List<T> queryForList(String sql, Class<T> elementType) {
        TenantSqlRewriter.RewriteResult result = prepare(sql);
        return super.queryForList(result.sql(), elementType, argsWithTenant(result, new Object[0]));
    }
}
