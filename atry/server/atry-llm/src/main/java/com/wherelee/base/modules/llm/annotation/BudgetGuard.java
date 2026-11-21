package com.wherelee.base.modules.llm.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 调用前预算守卫（设计 03 §1.2，切面顺序 -50：在限流/幂等之后、业务方法之前）。
 *
 * <p>语义：按登录主体（tenant/user）读取计量账户可用额（balance - frozen），
 * 低于 {@link #minAvailable()} 时按 {@link #onExceed()} 拒绝或降级。
 * 它只是**快速失败**的守卫：真正的准入权威仍是 {@code MeteringOperations.reserve}（预扣），
 * 两者失败语义一致（402），守卫的价值是更早失败、不进入昂贵调用。</p>
 *
 * <p>未登录上下文（无请求属性）时 fail-open：不阻断，交给下游预扣/鉴权兜底。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BudgetGuard {

    /** 预算用途标识，只用于日志/指标标签，不参与计价。 */
    String budgetRef() default "default";

    /** 可用额下限（金额字符串，如 "0.000001"）；可用额低于该值时触发 {@link #onExceed()}。 */
    String minAvailable() default "0";

    /** 超限动作：REJECT 抛 402；DEGRADE 放行并标记降级上下文（业务可据此走轻量模型/缓存）。 */
    Action onExceed() default Action.REJECT;

    /** 守卫检查的主体维度。 */
    SubjectScope scope() default SubjectScope.TENANT;

    enum Action { REJECT, DEGRADE }

    enum SubjectScope { TENANT, USER }
}
