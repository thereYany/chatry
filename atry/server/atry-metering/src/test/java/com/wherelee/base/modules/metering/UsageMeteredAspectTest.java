package com.wherelee.base.modules.metering;

import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import com.wherelee.base.modules.metering.annotation.UsageMetered;
import com.wherelee.base.modules.metering.aop.UsageMeteredAspect;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 声明式计量切面与过期预扣 job 测试。
 *
 * <p>业务不变式：只在方法成功后计量；SpEL 支持 #result/参数名；计量失败不影响业务返回；
 * 过期回收 job 在系统作用域执行（跨租户平台任务）。</p>
 */
class UsageMeteredAspectTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void 方法成功后按result表达式上报用量() {
        MeteringOperations metering = mock(MeteringOperations.class);
        AspectJProxyFactory factory = new AspectJProxyFactory(new MeteredTarget());
        factory.addAspect(new UsageMeteredAspect(metering));
        MeteredTarget proxy = factory.getProxy();

        assertThat(proxy.generate("user-1")).isEqualTo(new BigDecimal("12.5"));

        ArgumentCaptor<UsageCommand> captor = ArgumentCaptor.forClass(UsageCommand.class);
        verify(metering).recordUsage(captor.capture());
        UsageCommand command = captor.getValue();
        assertThat(command.meterKey()).isEqualTo("unit.quantity");
        assertThat(command.quantity()).isEqualByComparingTo("12.5");
        assertThat(command.unit()).isEqualTo("count");
        assertThat(command.subject()).isEqualTo(new SubjectRef(0, 0, 0));
        assertThat(command.eventId()).isNotBlank();
    }

    @Test
    void 方法抛异常时不计量() {
        MeteringOperations metering = mock(MeteringOperations.class);
        AspectJProxyFactory factory = new AspectJProxyFactory(new MeteredTarget());
        factory.addAspect(new UsageMeteredAspect(metering));
        MeteredTarget proxy = factory.getProxy();

        assertThatThrownBy(proxy::fail).isInstanceOf(IllegalStateException.class);
        verify(metering, never()).recordUsage(any());
    }

    @Test
    void 计量失败不影响业务返回() {
        MeteringOperations metering = mock(MeteringOperations.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("metering down"))
                .when(metering).recordUsage(any());
        AspectJProxyFactory factory = new AspectJProxyFactory(new MeteredTarget());
        factory.addAspect(new UsageMeteredAspect(metering));
        MeteredTarget proxy = factory.getProxy();

        assertThat(proxy.generate("u")).isEqualByComparingTo("12.5");
    }

    @Test
    void 支持按参数名取数量表达式() {
        MeteringOperations metering = mock(MeteringOperations.class);
        AspectJProxyFactory factory = new AspectJProxyFactory(new MeteredTarget());
        factory.addAspect(new UsageMeteredAspect(metering));
        MeteredTarget proxy = factory.getProxy();

        proxy.consume("topic", 3);
        ArgumentCaptor<UsageCommand> captor = ArgumentCaptor.forClass(UsageCommand.class);
        verify(metering).recordUsage(captor.capture());
        assertThat(captor.getValue().quantity()).isEqualByComparingTo("3");
        assertThat(captor.getValue().meterKey()).isEqualTo("unit.consumed");
    }

    @Test
    void 表达式为空结果时计量失败但不影响业务() {
        MeteringOperations metering = mock(MeteringOperations.class);
        AspectJProxyFactory factory = new AspectJProxyFactory(new MeteredTarget());
        factory.addAspect(new UsageMeteredAspect(metering));
        MeteredTarget proxy = factory.getProxy();

        // result 为 null -> 计算表达式抛错 -> 被切面吞掉，业务返回 null
        assertThat(proxy.nullResult()).isNull();
        verify(metering, never()).recordUsage(any());
    }

    @Test
    void 过期回收job在系统作用域执行() {
        MeteringAdminOperations admin = mock(MeteringAdminOperations.class);
        when(admin.expireReservations(any())).thenReturn(2);
        AtomicReference<Boolean> systemScope = new AtomicReference<>();
        when(admin.expireReservations(any())).thenAnswer(invocation -> {
            systemScope.set(TenantContext.isSystemScope());
            return 3;
        });
        MeteringReservationExpiryJob job = new MeteringReservationExpiryJob(admin);
        TenantContext.set(5L);

        job.expire();

        assertThat(systemScope.get()).isTrue();
        // 作业结束后调用线程租户上下文不被污染
        assertThat(TenantContext.currentOrNull()).isEqualTo(5L);
        verify(admin).expireReservations(any());
    }

    public static class MeteredTarget {
        @UsageMetered(meterKey = "unit.quantity", quantity = "#result")
        public BigDecimal generate(String userId) {
            return new BigDecimal("12.5");
        }

        @UsageMetered(meterKey = "unit.consumed", quantity = "#count")
        public void consume(String topic, int count) {
        }

        @UsageMetered(meterKey = "unit.nullable", quantity = "#result")
        public String nullResult() {
            return null;
        }

        public void fail() {
            throw new IllegalStateException("业务失败");
        }
    }
}
