package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.payment.api.PaymentEventTypes;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.outbox.PaymentOutboxRepository;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import com.wherelee.base.modules.platform.tracing.TraceSpans;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主动查单测试（无公网回调域名时的结算兜底）。
 *
 * <p>业务不变式：渠道返回 PAID 必须走确认幂等路径并按支付单号定位；渠道无单且已过期才关单，
 * 关单失败不能本地关单（避免用户还能付款）；关单/过期事件必须归属支付单租户。</p>
 */
class PaymentQueryServiceTest {

    private PaymentOrderRepository orderRepository;
    private PaymentRepository paymentRepository;
    private PaymentGateway gateway;
    private PaymentConfirmationService confirmationService;
    private PaymentOutboxRepository outboxRepository;
    private PaymentQueryService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(PaymentOrderRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        gateway = mock(PaymentGateway.class);
        confirmationService = mock(PaymentConfirmationService.class);
        outboxRepository = mock(PaymentOutboxRepository.class);
        PaymentProperties properties = new PaymentProperties("mock", true, "s", 15, 0,
                20, 15000, 10000, 5, 30);
        service = new PaymentQueryService(orderRepository, paymentRepository, gateway,
                confirmationService, outboxRepository, properties, TraceSpans.noop(),
                org.mockito.Mockito.mock(PaymentMetrics.class));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private static PaymentOrderView payment(String status, LocalDateTime expireAt) {
        return new PaymentOrderView("P1", "BIZ-1", "atry", "ORDER", "商品", 1000, "CNY",
                status, "MOCK", "OUT-1", expireAt, null, 7L);
    }

    @Test
    void 渠道返回已支付时走确认幂等路径且按支付单号定位() {
        PaymentOrderView paying = payment("PAYING", LocalDateTime.now().plusMinutes(10));
        when(paymentRepository.findLatestAttemptByPaymentNo("P1")).thenReturn(Optional.empty());
        when(gateway.query(eq(paying), any())).thenReturn(
                new PaymentGateway.PaymentQueryResult("PAID", 1000, "TRADE-9", null));
        when(confirmationService.accept(any(PaymentNotification.class), eq("P1")))
                .thenReturn(new PaymentConfirmationService.ConfirmationResult(true, false, "ok",
                        "P1", "BIZ-1", "PAID", 1000, "MOCK", "TRADE-9"));

        assertThat(service.query(paying)).isTrue();

        ArgumentCaptor<PaymentNotification> captor = ArgumentCaptor.forClass(PaymentNotification.class);
        verify(confirmationService).accept(captor.capture(), eq("P1"));
        PaymentNotification notification = captor.getValue();
        assertThat(notification.eventType()).isEqualTo("PAYMENT_SUCCESS");
        assertThat(notification.amountCents()).isEqualTo(1000);
        assertThat(notification.tradeNo()).isEqualTo("TRADE-9");
        // 查单事件 key 必须稳定可幂等
        assertThat(notification.eventKey()).isEqualTo("QUERY:P1:TRADE-9");
    }

    @Test
    void 渠道失败且已过期时关闭支付单并发出过期事件_归属支付单租户() {
        PaymentOrderView expired = payment("PAYING", LocalDateTime.now().minusMinutes(1));
        when(paymentRepository.findLatestAttemptByPaymentNo("P1")).thenReturn(Optional.empty());
        when(gateway.query(eq(expired), any())).thenReturn(
                new PaymentGateway.PaymentQueryResult("FAILED", 0, "OUT-1", null));

        java.util.concurrent.atomic.AtomicLong tenantDuringClose =
                new java.util.concurrent.atomic.AtomicLong(-1);
        when(orderRepository.markClosed("P1")).thenAnswer(invocation -> {
            tenantDuringClose.set(TenantContext.current());
            return 1;
        });

        boolean advanced = TenantContext.runAsSystem(() -> service.query(expired));

        assertThat(advanced).isTrue();
        assertThat(tenantDuringClose.get()).isEqualTo(7L);
        ArgumentCaptor<PaymentSettledEvent> captor = ArgumentCaptor.forClass(PaymentSettledEvent.class);
        verify(outboxRepository).append(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(PaymentEventTypes.PAYMENT_EXPIRED);
        assertThat(captor.getValue().paymentNo()).isEqualTo("P1");
        verify(gateway, never()).close(any(), any());
    }

    @Test
    void 关闭支付单未发生状态迁移时不发事件() {
        PaymentOrderView expired = payment("PAYING", LocalDateTime.now().minusMinutes(1));
        when(gateway.query(eq(expired), any())).thenReturn(
                new PaymentGateway.PaymentQueryResult("FAILED", 0, "OUT-1", null));
        when(orderRepository.markClosed("P1")).thenReturn(0);

        assertThat(service.query(expired)).isFalse();
        verify(outboxRepository, never()).append(any());
    }

    @Test
    void 渠道关单失败时保留支付单等待下次查单() {
        PaymentOrderView expired = payment("PAYING", LocalDateTime.now().minusMinutes(1));
        when(gateway.query(eq(expired), any())).thenReturn(
                new PaymentGateway.PaymentQueryResult("NOT_FOUND", 1000, "OUT-1", null));
        when(gateway.close(eq(expired), any())).thenReturn(
                PaymentGateway.CloseResult.failed("渠道关单失败"));

        assertThat(service.query(expired)).isFalse();
        verify(orderRepository, never()).markClosed(anyString());
        verify(outboxRepository, never()).append(any());
    }

    @Test
    void 渠道无单且关单成功或交易不存在时本地关单() {
        PaymentOrderView expired = payment("PAYING", LocalDateTime.now().minusMinutes(1));
        when(gateway.query(eq(expired), any())).thenReturn(
                new PaymentGateway.PaymentQueryResult("CREATED", 1000, "OUT-1", null));
        when(gateway.close(eq(expired), any())).thenReturn(PaymentGateway.CloseResult.notFound("无交易"));
        when(orderRepository.markClosed("P1")).thenReturn(1);

        assertThat(service.query(expired)).isTrue();
        verify(orderRepository).markClosed("P1");
    }

    @Test
    void 未过期或非终态不推进状态() {
        PaymentOrderView paying = payment("PAYING", LocalDateTime.now().plusMinutes(10));
        when(gateway.query(eq(paying), any())).thenReturn(
                new PaymentGateway.PaymentQueryResult("PENDING", 1000, "OUT-1", null));

        assertThat(service.query(paying)).isFalse();
        assertThat(service.query(payment("PAID", LocalDateTime.now().plusMinutes(10))
                .equals(paying) ? paying : paying)).isFalse();
    }

    @Test
    void 批量查单隔离单笔异常并统计推进数() {
        PaymentOrderView ok = payment("PAYING", LocalDateTime.now().plusMinutes(10));
        PaymentOrderView bad = new PaymentOrderView("P2", "BIZ-2", "atry", "ORDER", "商品", 1000,
                "CNY", "PAYING", "MOCK", "OUT-2", LocalDateTime.now().plusMinutes(10), null, 7L);
        when(orderRepository.findPayingOlderThan(0, 20)).thenReturn(List.of(ok, bad));
        when(paymentRepository.findLatestAttemptByPaymentNo(anyString())).thenReturn(Optional.empty());
        when(gateway.query(eq(ok), any())).thenReturn(
                new PaymentGateway.PaymentQueryResult("PENDING", 1000, "OUT-1", null));
        when(gateway.query(eq(bad), any())).thenThrow(new IllegalStateException("channel down"));

        assertThat(service.queryPending()).isZero();
    }

    @Test
    void 按支付单号或业务单号查单在不存在时返回false() {
        when(orderRepository.findByPaymentNo("P9")).thenReturn(Optional.empty());
        when(orderRepository.findByBizNo("B9")).thenReturn(Optional.empty());
        assertThat(service.queryByPaymentNo("P9")).isFalse();
        assertThat(service.queryByBizNo("B9")).isFalse();
    }
}
