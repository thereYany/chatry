package com.wherelee.base.modules.llm.budget;

import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.llm.annotation.BudgetGuard;
import com.wherelee.base.modules.llm.observability.LlmMetrics;
import com.wherelee.base.modules.platform.common.BizException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;

/**
 * {@link BudgetGuard} 切面：调用前读取计量账户可用额，低于下限时拒绝或降级。
 *
 * <p>顺序 -50（设计 03 §2）：鉴权/限流/幂等之后、业务方法之前。
 * 失败语义与预扣一致（402），但只是快速失败；权威准入仍是 metering.reserve。</p>
 */
@Aspect
@Component
@Order(-50)
public class BudgetGuardAspect {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuardAspect.class);

    private final ObjectProvider<MeteringOperations> meteringProvider;
    private final LlmMetrics metrics;

    public BudgetGuardAspect(ObjectProvider<MeteringOperations> meteringProvider, LlmMetrics metrics) {
        this.meteringProvider = meteringProvider;
        this.metrics = metrics;
    }

    @Around("@annotation(budgetGuard)")
    public Object around(ProceedingJoinPoint joinPoint, BudgetGuard budgetGuard) throws Throwable {
        MeteringOperations metering = meteringProvider.getIfAvailable();
        if (metering == null) {
            // 未装配计量模块（只使用 atry-llm 的部署）：守卫不可用，fail-open
            return joinPoint.proceed();
        }
        SubjectRef subject = resolveSubject(budgetGuard.scope());
        if (subject == null) {
            // 无登录上下文：fail-open，由下方预扣/鉴权兜底
            return joinPoint.proceed();
        }
        BigDecimal minimum = parseMinimum(budgetGuard);
        BigDecimal available = availableOf(metering, subject);
        if (available == null || available.compareTo(minimum) >= 0) {
            return joinPoint.proceed();
        }

        String budgetRef = budgetGuard.budgetRef();
        if (budgetGuard.onExceed() == BudgetGuard.Action.REJECT) {
            metrics.budgetExceeded(budgetRef, "REJECT");
            log.warn("llm.budget.rejected budgetRef={} subject={}/{}/{} available={} min={}",
                    budgetRef, subject.tenantId(), subject.appId(), subject.userId(),
                    available.toPlainString(), minimum.toPlainString());
            throw new BizException(HttpStatus.PAYMENT_REQUIRED,
                    "预算不足：budgetRef=" + budgetRef + "，可用=" + available.toPlainString());
        }

        metrics.budgetExceeded(budgetRef, "DEGRADE");
        log.warn("llm.budget.degraded budgetRef={} subject={}/{}/{} available={} min={}",
                budgetRef, subject.tenantId(), subject.appId(), subject.userId(),
                available.toPlainString(), minimum.toPlainString());
        boolean marked = !BudgetContext.isDegraded();
        if (marked) {
            BudgetContext.markDegraded(budgetRef);
        }
        try {
            return joinPoint.proceed();
        } finally {
            if (marked) {
                BudgetContext.clear();
            }
        }
    }

    /** 可用额 = balance - frozen；计量查询异常时返回 null（fail-open，不因守卫本身打挂业务）。 */
    private BigDecimal availableOf(MeteringOperations metering, SubjectRef subject) {
        try {
            BalanceView view = metering.queryBalance(subject);
            if (view == null || view.balance() == null) {
                return null;
            }
            BigDecimal frozen = view.frozen() == null ? BigDecimal.ZERO : view.frozen();
            return view.balance().subtract(frozen);
        } catch (RuntimeException e) {
            log.warn("llm.budget.check_failed subject={}/{}/{} error={}",
                    subject.tenantId(), subject.appId(), subject.userId(), e.toString());
            return null;
        }
    }

    private BigDecimal parseMinimum(BudgetGuard budgetGuard) {
        try {
            return new BigDecimal(budgetGuard.minAvailable());
        } catch (NumberFormatException e) {
            log.warn("llm.budget.invalid_minimum value={}，按 0 处理", budgetGuard.minAvailable());
            return BigDecimal.ZERO;
        }
    }

    private SubjectRef resolveSubject(BudgetGuard.SubjectScope scope) {
        try {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
                return null;
            }
            Object tenantId = attributes.getRequest().getAttribute("tenantId");
            Object userId = attributes.getRequest().getAttribute("userId");
            if (tenantId == null && userId == null) {
                return null;
            }
            long tenant = tenantId instanceof Number number ? number.longValue() : 0L;
            long user = userId instanceof Number number ? number.longValue() : 0L;
            return switch (scope) {
                case USER -> new SubjectRef(tenant, 0, user);
                case TENANT -> new SubjectRef(tenant, 0, 0);
            };
        } catch (RuntimeException e) {
            return null;
        }
    }
}
