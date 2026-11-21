package com.wherelee.base.modules.metering.aop;

import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import com.wherelee.base.modules.metering.annotation.UsageMetered;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** @UsageMetered 切面：只在方法成功后计量；计量失败不回滚业务（记录 error 日志，交给对账/重放）。 */
@Aspect
@Component
@Order(50)
public class UsageMeteredAspect {

    private static final Logger log = LoggerFactory.getLogger(UsageMeteredAspect.class);
    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private final MeteringOperations metering;

    public UsageMeteredAspect(MeteringOperations metering) {
        this.metering = metering;
    }

    @Around("@annotation(usageMetered)")
    public Object around(ProceedingJoinPoint joinPoint, UsageMetered usageMetered) throws Throwable {
        Object result = joinPoint.proceed();
        try {
            BigDecimal quantity = evaluate(joinPoint, usageMetered.quantity(), result);
            String method = ((MethodSignature) joinPoint.getSignature()).toShortString();
            metering.recordUsage(new UsageCommand(
                    UUID.randomUUID().toString(),
                    usageMetered.meterKey(),
                    new SubjectRef(0, 0, 0),
                    quantity,
                    usageMetered.unit(),
                    Map.of(),
                    Instant.now(),
                    method));
        } catch (RuntimeException e) {
            log.warn("@UsageMetered 计量失败 meterKey={} method={}：{}",
                    usageMetered.meterKey(), joinPoint.getSignature().toShortString(),
                    e.getClass().getSimpleName());
        }
        return result;
    }

    private BigDecimal evaluate(ProceedingJoinPoint joinPoint, String expression, Object result) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("result", result);
        Object[] args = joinPoint.getArgs();
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
        }
        String[] parameterNames = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
        if (parameterNames != null) {
            for (int i = 0; i < Math.min(parameterNames.length, args.length); i++) {
                if (parameterNames[i] != null) {
                    context.setVariable(parameterNames[i], args[i]);
                }
            }
        }
        Object value = PARSER.parseExpression(expression).getValue(context);
        if (value == null) {
            throw new IllegalStateException("计量表达式结果为空：" + expression);
        }
        return new BigDecimal(value.toString());
    }
}
