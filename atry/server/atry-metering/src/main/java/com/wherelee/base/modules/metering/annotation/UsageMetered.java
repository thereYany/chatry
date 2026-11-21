package com.wherelee.base.modules.metering.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式通用计量：方法成功返回后按表达式提取数量并上报 MeteringOperations.recordUsage。
 *
 * <p>表达式可用 {@code #result}、参数名或 {@code #p0/#a0}；异常不影响业务返回。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UsageMetered {
    String meterKey();

    String quantity() default "#result";

    String unit() default "count";
}
