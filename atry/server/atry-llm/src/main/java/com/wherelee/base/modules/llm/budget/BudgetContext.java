package com.wherelee.base.modules.llm.budget;

/**
 * 预算守卫的调用级上下文（线程内可见，调用结束即清理）。
 *
 * <p>当 {@code @BudgetGuard(onExceed = DEGRADE)} 触发时置位，业务方法内可通过
 * {@link #isDegraded()} 判断本次调用处于「预算降级」状态，从而选择更轻量的路径
 * （例如小模型、缓存回复、裁剪上下文）。</p>
 */
public final class BudgetContext {

    private static final ThreadLocal<String> DEGRADED_BUDGET_REF = new ThreadLocal<>();

    private BudgetContext() {
    }

    /** 当前调用是否因预算超限被降级。 */
    public static boolean isDegraded() {
        return DEGRADED_BUDGET_REF.get() != null;
    }

    /** 触发降级的预算标识；未降级时为 null。 */
    public static String degradedBudgetRef() {
        return DEGRADED_BUDGET_REF.get();
    }

    static void markDegraded(String budgetRef) {
        DEGRADED_BUDGET_REF.set(budgetRef);
    }

    static void clear() {
        DEGRADED_BUDGET_REF.remove();
    }
}
