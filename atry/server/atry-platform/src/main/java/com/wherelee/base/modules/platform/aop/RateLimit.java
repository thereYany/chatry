package com.wherelee.base.modules.platform.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流维度：登录用户 / 客户端 IP / 全局匿名。 */
    enum Dimension { USER, IP, ANONYMOUS }

    String key() default "";

    int limit() default 60;

    int windowSeconds() default 60;

    /** 默认按用户限流；注册等公开接口用 IP，避免所有匿名用户共用一个桶。 */
    Dimension dimension() default Dimension.USER;
}