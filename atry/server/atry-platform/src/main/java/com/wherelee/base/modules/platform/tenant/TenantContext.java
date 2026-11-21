package com.wherelee.base.modules.platform.tenant;

import java.util.function.Supplier;

/**
 * 租户上下文。
 *
 * <p>由鉴权过滤器从 JWT claim 写入，SQL 拦截器从这里取租户值注入查询条件。
 * 业务代码**不得**从请求参数里取租户——那等于把越权入口交给客户端。</p>
 *
 * <p>系统作用域（{@link #runAsSystem}）：只给明确在做跨租户工作的入口用——定时任务、
 * 公开回调、发件箱投递等没有登录租户的后台线程。作用域内 {@link TenantAwareJdbcTemplate}
 * 跳过自动注入与「必须手写 tenant_id」校验；离开作用域自动恢复，支持嵌套与异常回滚。</p>
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    /**
     * 系统作用域标记与租户值分开存放：回调线程可以先跨租户定位支付单，
     * 再 {@link #set(Long)} 回填支付单自己的租户，两者互不覆盖。
     */
    private static final ThreadLocal<Boolean> SYSTEM_SCOPE = new ThreadLocal<>();
    private static final long DEFAULT_TENANT = 0L;

    private TenantContext() {
    }

    public static Long currentOrNull() {
        return CURRENT.get();
    }

    public static long current() {
        Long value = CURRENT.get();
        return value == null ? DEFAULT_TENANT : value;
    }

    public static void set(Long tenantId) {
        if (tenantId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 当前线程是否处于系统作用域（跨租户后台入口）。 */
    public static boolean isSystemScope() {
        return Boolean.TRUE.equals(SYSTEM_SCOPE.get());
    }

    /**
     * 在系统作用域内执行：跳过 SQL 自动租户注入，允许跨租户查询。
     *
     * <p>异常安全、支持嵌套：结束后恢复到进入前的状态（不把外层作用域清掉）。</p>
     */
    public static <T> T runAsSystem(Supplier<T> action) {
        Boolean previous = SYSTEM_SCOPE.get();
        SYSTEM_SCOPE.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                SYSTEM_SCOPE.remove();
            } else {
                SYSTEM_SCOPE.set(previous);
            }
        }
    }

    /** 无返回值的系统作用域版本。 */
    public static void runAsSystem(Runnable action) {
        runAsSystem(() -> {
            action.run();
            return null;
        });
    }
}
