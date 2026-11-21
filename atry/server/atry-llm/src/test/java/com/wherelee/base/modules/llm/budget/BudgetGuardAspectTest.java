package com.wherelee.base.modules.llm.budget;

import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.llm.LlmTestSupport;
import com.wherelee.base.modules.llm.annotation.BudgetGuard;
import com.wherelee.base.modules.llm.observability.LlmMetrics;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预算守卫切面测试。
 *
 * <p>业务不变式：可用额 = balance - frozen；低于下限时 REJECT 返回 402 / DEGRADE 放行并标记
 * 降级上下文；未登录、无计量模块、计量查询异常都 fail-open（守卫不得打挂业务）。</p>
 */
class BudgetGuardAspectTest {

    private MeteringOperations metering;
    private LlmMetrics metrics;

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    @SuppressWarnings("unchecked")
    private Guarded proxy(Boolean meteringPresent) {
        metering = mock(MeteringOperations.class);
        metrics = mock(LlmMetrics.class);
        ObjectProvider<MeteringOperations> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meteringPresent ? metering : null);
        AspectJProxyFactory factory = new AspectJProxyFactory(new Guarded());
        factory.addAspect(new BudgetGuardAspect(provider, metrics));
        return factory.getProxy();
    }

    private static void requestContext(Long tenantId, Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/demo");
        if (tenantId != null) {
            request.setAttribute("tenantId", tenantId);
        }
        if (userId != null) {
            request.setAttribute("userId", userId);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void balance(String balance, String frozen) {
        when(metering.queryBalance(any())).thenReturn(new BalanceView(
                new SubjectRef(1L, 0L, 2L), new BigDecimal(balance), new BigDecimal(frozen), "CNY"));
    }

    @Test
    void 可用额充足时放行() {
        Guarded proxy = proxy(true);
        requestContext(1L, 2L);
        balance("10.00", "2.00");
        assertThat(proxy.reject()).isEqualTo("ok");
        verify(metrics, never()).budgetExceeded(any(), any());
    }

    @Test
    void 可用额低于下限时REJECT返回402() {
        Guarded proxy = proxy(true);
        requestContext(1L, 2L);
        balance("10.00", "9.50"); // 可用 0.5 < 下限 1

        assertThatThrownBy(proxy::reject)
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).status()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));
        verify(metrics).budgetExceeded(eq("unit-budget"), eq("REJECT"));
    }

    @Test
    void 可用额恰好等于下限时放行_边界为一分钱() {
        Guarded proxy = proxy(true);
        requestContext(1L, 2L);
        balance("10.000000", "9.000000"); // 可用 1.000000，下限 1
        assertThat(proxy.reject()).isEqualTo("ok");

        balance("9.999999", "9.000000"); // 可用 0.999999，差 0.000001
        assertThatThrownBy(proxy::reject).isInstanceOf(BizException.class);
    }

    @Test
    void DEGRADE放行并标记降级上下文_结束后清理() {
        Guarded proxy = proxy(true);
        requestContext(1L, 2L);
        balance("0", "0");

        assertThat(proxy.degrade()).isEqualTo("true|unit-budget");
        // 调用结束后线程内降级标记必须清理
        assertThat(BudgetContext.isDegraded()).isFalse();
        verify(metrics).budgetExceeded(eq("unit-budget"), eq("DEGRADE"));
    }

    @Test
    void DEGRADE目标抛异常时降级上下文同样清理() {
        Guarded proxy = proxy(true);
        requestContext(1L, 2L);
        balance("0", "0");

        assertThatThrownBy(proxy::degradeAndFail).isInstanceOf(IllegalStateException.class);
        assertThat(BudgetContext.isDegraded()).isFalse();
    }

    @Test
    void USER维度按用户主体检查_TENANT维度按租户主体检查() {
        Guarded proxy = proxy(true);
        requestContext(9L, 5L);
        when(metering.queryBalance(any())).thenReturn(
                new BalanceView(new SubjectRef(9L, 0L, 5L), new BigDecimal("100"), BigDecimal.ZERO, "CNY"));

        proxy.userScoped();
        proxy.tenantScoped();

        ArgumentCaptor<SubjectRef> subjects = ArgumentCaptor.forClass(SubjectRef.class);
        verify(metering, org.mockito.Mockito.times(2)).queryBalance(subjects.capture());
        // USER 维度带用户 id；TENANT 维度用户 id 固定 0
        assertThat(subjects.getAllValues()).containsExactly(
                new SubjectRef(9L, 0L, 5L), new SubjectRef(9L, 0L, 0L));
    }

    @Test
    void 未装配计量模块时failOpen() {
        Guarded proxy = proxy(false);
        requestContext(1L, 2L);
        assertThat(proxy.reject()).isEqualTo("ok");
    }

    @Test
    void 无登录上下文时failOpen() {
        Guarded proxy = proxy(true);
        RequestContextHolder.resetRequestAttributes();
        assertThat(proxy.reject()).isEqualTo("ok");
    }

    @Test
    void 计量查询异常时failOpen不阻断业务() {
        Guarded proxy = proxy(true);
        requestContext(1L, 2L);
        when(metering.queryBalance(any())).thenThrow(new IllegalStateException("db down"));
        assertThat(proxy.reject()).isEqualTo("ok");
    }

    @Test
    void 余额为空或下限配置非法时按安全默认处理() {
        Guarded proxy = proxy(true);
        requestContext(1L, 2L);
        when(metering.queryBalance(any())).thenReturn(new BalanceView(
                new SubjectRef(1L, 0L, 2L), null, null, "CNY"));
        assertThat(proxy.reject()).isEqualTo("ok");

        when(metering.queryBalance(any())).thenReturn(new BalanceView(
                new SubjectRef(1L, 0L, 2L), new BigDecimal("5"), BigDecimal.ZERO, "CNY"));
        // 非法下限字符串按 0 处理 -> 放行
        assertThat(proxy.invalidMinimum()).isEqualTo("ok");
    }

    public static class Guarded {
        @BudgetGuard(budgetRef = "unit-budget", minAvailable = "1")
        public String reject() {
            return "ok";
        }

        @BudgetGuard(budgetRef = "unit-budget", minAvailable = "1", onExceed = BudgetGuard.Action.DEGRADE)
        public String degrade() {
            return BudgetContext.isDegraded() + "|" + BudgetContext.degradedBudgetRef();
        }

        @BudgetGuard(budgetRef = "unit-budget", minAvailable = "1", onExceed = BudgetGuard.Action.DEGRADE)
        public String degradeAndFail() {
            throw new IllegalStateException("业务失败");
        }

        @BudgetGuard(budgetRef = "user-budget", minAvailable = "1", scope = BudgetGuard.SubjectScope.USER)
        public String userScoped() {
            return "ok";
        }

        @BudgetGuard(budgetRef = "tenant-budget", minAvailable = "1", scope = BudgetGuard.SubjectScope.TENANT)
        public String tenantScoped() {
            return "ok";
        }

        @BudgetGuard(budgetRef = "bad", minAvailable = "not-a-number")
        public String invalidMinimum() {
            return "ok";
        }
    }
}
