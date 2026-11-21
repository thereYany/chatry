package com.wherelee.base.modules.llm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明式 LLM 同步调用观测：记录耗时/结果/异常，并输出结构化事件。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LlmCall {
    String value() default "";
}
