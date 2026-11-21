package com.wherelee.base.modules.payment.outbox;

import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.api.PaymentSettledListener;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付发件箱投递器测试。
 *
 * <p>业务不变式：at-least-once 投递，失败按退避重试、达到上限进死信（不静默吞）；
 * 投递时按发件箱行租户回填上下文（业务 listener 的 SQL 才能正确隔离）。</p>
 */
class PaymentOutboxDispatcherTest {

    private PaymentOutboxRepository repository;
    private SimpleMeterRegistry registry;

    private static final PaymentSettledEvent EVENT = new PaymentSettledEvent(
            "PAYMENT_SUCCEEDED", "P1", "RESOURCE_PACK", "ORDER-1", 9900, "CNY",
            "MOCK", "TRADE-1", LocalDateTime.now());

    @BeforeEach
    void setUp() {
        repository = mock(PaymentOutboxRepository.class);
        registry = new SimpleMeterRegistry();
    }

    private PaymentOutboxDispatcher dispatcher(List<PaymentSettledListener> listeners) {
        return new PaymentOutboxDispatcher(repository, listeners, registry, 10, 3, 5, 60);
    }

    private static PaymentOutboxRepository.OutboxRow row(long id, long tenantId, int attempts) {
        return new PaymentOutboxRepository.OutboxRow(id, "PAYMENT_SUCCEEDED", "P1", "{}",
                attempts, tenantId);
    }

    @Test
    void 投递成功标记SENT并按行租户回填上下文() {
        when(repository.claimDue(10)).thenReturn(List.of(row(1L, 7L, 0)));
        when(repository.payload(any())).thenReturn(EVENT);
        when(repository.countInFlight()).thenReturn(0);
        when(repository.countDead()).thenReturn(0);
        AtomicReference<Long> tenantSeen = new AtomicReference<>();
        PaymentSettledListener listener = event -> tenantSeen.set(TenantContext.currentOrNull());

        int delivered = dispatcher(List.of(listener)).dispatchOnce();

        assertThat(delivered).isEqualTo(1);
        assertThat(tenantSeen.get()).isEqualTo(7L);
        // 投递结束后恢复系统作用域（无租户）
        assertThat(TenantContext.currentOrNull()).isNull();
        verify(repository).markSent(1L);
        assertThat(registry.get("payment.outbox.sent").counter().count()).isEqualTo(1);
    }

    @Test
    void listener异常时按退避重试而不是标记已发送() {
        when(repository.claimDue(10)).thenReturn(List.of(row(1L, 0L, 0)));
        when(repository.payload(any())).thenReturn(EVENT);
        when(repository.countInFlight()).thenReturn(1);
        when(repository.countDead()).thenReturn(0);
        PaymentSettledListener failing = event -> {
            throw new IllegalStateException("履约失败");
        };

        int delivered = dispatcher(List.of(failing)).dispatchOnce();

        assertThat(delivered).isZero();
        verify(repository, never()).markSent(any());
        // attempts 0 -> 1，退避 5*1 秒
        verify(repository).reschedule(1L, 1, 5, "java.lang.IllegalStateException: 履约失败");
        assertThat(registry.get("payment.outbox.failed").counter().count()).isEqualTo(1);
    }

    @Test
    void 重试达到上限进入死信() {
        when(repository.claimDue(10)).thenReturn(List.of(row(1L, 0L, 2)));
        when(repository.payload(any())).thenReturn(EVENT);
        when(repository.countInFlight()).thenReturn(0);
        when(repository.countDead()).thenReturn(1);
        PaymentSettledListener failing = event -> {
            throw new IllegalStateException("持续失败");
        };

        assertThat(dispatcher(List.of(failing)).dispatchOnce()).isZero();

        verify(repository).markDead(eq(1L), contains("持续失败"));
        assertThat(registry.get("payment.outbox.dead_lettered").counter().count()).isEqualTo(1);
        // 死信指标 gaguge 反映积压
        assertThat(registry.get("payment.outbox.dead").gauge().value()).isEqualTo(1);
    }

    @Test
    void 多listener全部成功才算投递成功() {
        when(repository.claimDue(10)).thenReturn(List.of(row(1L, 0L, 0)));
        when(repository.payload(any())).thenReturn(EVENT);
        when(repository.countInFlight()).thenReturn(0);
        when(repository.countDead()).thenReturn(0);
        AtomicLong calls = new AtomicLong();
        PaymentSettledListener first = event -> calls.incrementAndGet();
        PaymentSettledListener second = event -> calls.incrementAndGet();

        assertThat(dispatcher(List.of(first, second)).dispatchOnce()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 没有listener时仍标记已发送并告警一次() {
        when(repository.claimDue(10)).thenReturn(List.of(row(1L, 0L, 0)));
        when(repository.payload(any())).thenReturn(EVENT);
        when(repository.countInFlight()).thenReturn(0);
        when(repository.countDead()).thenReturn(0);
        PaymentOutboxDispatcher dispatcher = dispatcher(List.of());

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        verify(repository).markSent(1L);
    }

    @Test
    void 每次投递前回收卡在SENDING的事件() {
        when(repository.reclaimStuck(60)).thenReturn(2);
        when(repository.claimDue(10)).thenReturn(List.of());
        when(repository.countInFlight()).thenReturn(0);
        when(repository.countDead()).thenReturn(0);

        assertThat(dispatcher(List.of(event -> {
        })).dispatchOnce()).isZero();
        verify(repository).reclaimStuck(60);
    }

    @Test
    void 单条事件异常不影响同批其他事件() {
        when(repository.claimDue(10)).thenReturn(List.of(row(1L, 0L, 0), row(2L, 0L, 0)));
        when(repository.payload(any()))
                .thenReturn(EVENT)
                .thenReturn(new PaymentSettledEvent("PAYMENT_SUCCEEDED", "P2", "RESOURCE_PACK",
                        "ORDER-2", 100, "CNY", "MOCK", "TRADE-2", LocalDateTime.now()));
        when(repository.countInFlight()).thenReturn(0);
        when(repository.countDead()).thenReturn(0);
        AtomicLong deliveredIds = new AtomicLong();
        PaymentSettledListener listener = event -> {
            if ("P1".equals(event.paymentNo())) {
                throw new IllegalStateException("第一笔失败");
            }
            deliveredIds.incrementAndGet();
        };

        int delivered = dispatcher(List.of(listener)).dispatchOnce();
        assertThat(delivered).isEqualTo(1);
        verify(repository).markSent(2L);
        verify(repository, never()).markSent(1L);
    }
}
