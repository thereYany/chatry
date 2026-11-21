package com.wherelee.base.modules.llm.aop;

import com.wherelee.base.modules.llm.annotation.LlmStream;
import com.wherelee.base.modules.llm.observability.LlmEventLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** @LlmStream 切面：流式方法生命周期观测；不拦截/缓存 chunk。 */
@Aspect
@Component
@Order(0)
public class LlmStreamAspect {

    private final MeterRegistry registry;
    private final LlmEventLogger events;

    public LlmStreamAspect(MeterRegistry registry, LlmEventLogger events) {
        this.registry = registry;
        this.events = events;
    }

    @Around("@annotation(llmStream)")
    public Object around(ProceedingJoinPoint joinPoint, LlmStream llmStream) throws Throwable {
        String name = llmStream.value().isBlank() ? joinPoint.getSignature().toShortString() : llmStream.value();
        long started = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            record(name, "SUCCESS");
            events.annotation(name, "SUCCESS", System.currentTimeMillis() - started);
            return result;
        } catch (Throwable t) {
            record(name, "FAILED");
            events.annotation(name, "FAILED", System.currentTimeMillis() - started);
            throw t;
        }
    }

    private void record(String name, String status) {
        Counter.builder("atry.llm.annotated.calls")
                .tag("name", name).tag("status", status).tag("mode", "stream")
                .register(registry).increment();
    }
}
