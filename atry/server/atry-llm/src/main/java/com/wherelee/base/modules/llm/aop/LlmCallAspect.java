package com.wherelee.base.modules.llm.aop;

import com.wherelee.base.modules.llm.annotation.LlmCall;
import com.wherelee.base.modules.llm.observability.LlmEventLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** @LlmCall 切面：与限流/幂等等通用切面同层，只做观测，不改变调用语义。 */
@Aspect
@Component
@Order(0)
public class LlmCallAspect {

    private final MeterRegistry registry;
    private final LlmEventLogger events;

    public LlmCallAspect(MeterRegistry registry, LlmEventLogger events) {
        this.registry = registry;
        this.events = events;
    }

    @Around("@annotation(llmCall)")
    public Object around(ProceedingJoinPoint joinPoint, LlmCall llmCall) throws Throwable {
        String name = llmCall.value().isBlank() ? joinPoint.getSignature().toShortString() : llmCall.value();
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
                .tag("name", name).tag("status", status).tag("mode", "sync")
                .register(registry).increment();
    }
}
