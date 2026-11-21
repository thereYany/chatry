package com.wherelee.base.modules.platform.aop;

/**
 * 切面优先级：数值越小越先执行（前置），后置反过来。
 *
 * <p>约定：度量最外层（统计整条链路）→ 权限 → 限流 → 幂等 → 分布式锁 → 业务；
 * 审计、计量在业务内层（能看到业务结果）。不要在业务代码里依赖未声明的默认顺序。</p>
 */
public final class AspectOrders {

    /** 度量最外层：包含权限/限流/锁/业务全部耗时。 */
    public static final int MEASURED = -1_100;

    /** 权限必须最先拒绝，避免无效请求占用限流/锁资源。 */
    public static final int PERMISSION = -1_000;

    public static final int RATE_LIMIT = -900;

    public static final int IDEMPOTENT = -800;

    public static final int DISTRIBUTED_LOCK = -700;

    /** 审计在业务内层，记录成功/失败与耗时。 */
    public static final int AUDIT = 100;

    public static final int METERING = 500;

    private AspectOrders() {
    }
}
