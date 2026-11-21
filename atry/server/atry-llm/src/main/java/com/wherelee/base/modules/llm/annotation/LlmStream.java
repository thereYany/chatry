package com.wherelee.base.modules.llm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明式 LLM 流式入口观测：只记录元数据/耗时/结果，不包裹内容输出。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LlmStream {
    String value() default "";
}
