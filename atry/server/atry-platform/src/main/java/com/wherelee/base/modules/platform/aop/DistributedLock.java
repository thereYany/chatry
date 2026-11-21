package com.wherelee.base.modules.platform.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分布式锁：同一 key 在多实例间串行执行。
 *
 * <p>用于支付回调、下单等「重复执行会产生副作用」的入口；锁只做互斥，业务仍要保证幂等。
 * key 可以用静态前缀 + SpEL（如 {@code #orderNo}），支持 waitMs 等锁、leaseMs 自动过期。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /** 静态 key 前缀（为空时用方法签名）。 */
    String key() default "";

    /** SpEL 表达式，例如 {@code #orderNo} 或 {@code #command.bizNo()}。 */
    String keyExpression() default "";

    /** 最多等待多久拿锁；0 表示立即失败。 */
    long waitMs() default 0;

    /** 锁租约，防止持有者崩溃后死锁。 */
    long leaseMs() default 30_000;

    String failMessage() default "操作处理中，请稍后重试";
}
