package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.payment.api.PaymentEventTypes;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.outbox.PaymentOutboxRepository;
import com.wherelee.base.modules.platform.alert.AlertPublisher;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import com.wherelee.base.modules.platform.tracing.TraceSpans;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付结果确认测试（支付域核心）。
 *
 * <p>业务不变式：金额不一致绝不置为已支付，只落事件+告警；同一渠道事件幂等；
 * 状态更新与发件箱必须归属支付单自己的租户（系统作用域回调不能写到租户 0）。</p>
 */
class PaymentConfirmationServiceTest {

    private PaymentOrderRepository orderRepository;
    private PaymentRepository paymentRepository;
    private PaymentOutboxRepository outboxRepository;
    private AlertPublisher alertPublisher;
    private PaymentConfirmationService service;

    private static final long TENANT = 7L;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        orderRepository = mock(PaymentOrderRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        outboxRepository = mock(PaymentOutboxRepository.class);
        alertPublisher = mock(AlertPublisher.class);
        service = new PaymentConfirmationService(orderRepository, paymentRepository, outboxRepository,
                alertPublisher, mock(PaymentMetrics.class), TraceSpans.noop());
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private static PaymentOrderView order(String status, long amountCents) {
        return new PaymentOrderView("P1", "BIZ-1", "atry", "ORDER", "商品", amountCents, "CNY",
                status, "MOCK", "TRADE-1", LocalDateTime.now().plusMinutes(15), null, TENANT);
    }

    private static PaymentNotification success(long amountCents) {
        return new PaymentNotification("MOCK", "EVT-1", "BIZ-1", "PAYMENT_SUCCESS",
                amountCents, "TRADE-1", null);
    }

    @Test
    void 未知支付单只告警不建单() {
        when(orderRepository.findByBizNo("BIZ-1")).thenReturn(Optional.empty());

        PaymentConfirmationService.ConfirmationResult result = service.accept(success(1000));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("UNKNOWN");
        verify(alertPublisher).publish(eq("PAYMENT_UNKNOWN_ORDER"), eq("ERROR"), contains("BIZ-1"),
                eq(false), anyString());
        verify(outboxRepository, never()).append(any());
    }

    @Test
    void 金额不一致时拒绝支付且只落失败事件与告警() {
        when(orderRepository.findByBizNo("BIZ-1")).thenReturn(Optional.of(order("PAYING", 1000)));
        when(paymentRepository.insertEvent("MOCK", "EVT-1", "P1", "BIZ-1",
                "PAYMENT_SUCCESS", 1, null)).thenReturn(true);
        when(paymentRepository.findEvent("MOCK", "EVT-1")).thenReturn(Optional.of(
                new PaymentEventRecord(11L, "MOCK", "EVT-1", null, "BIZ-1", "PAYMENT_SUCCESS",
                        1, "RECEIVED", null, LocalDateTime.now(), null)));

        PaymentConfirmationService.ConfirmationResult result = service.accept(success(1));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("金额不一致");
        verify(paymentRepository).markEventFailed(eq(11L), contains("金额不一致"));
        verify(alertPublisher).publish(eq("PAYMENT_AMOUNT_MISMATCH"), eq("ERROR"),
                contains("expected=1000"), eq(false), anyString());
        // 绝不把订单置为已支付
        verify(orderRepository, never()).markPaid(anyString(), anyString(), anyString());
        verify(outboxRepository, never()).append(any());
    }

    @Test
    void 重复事件幂等且不重复写发件箱() {
        when(orderRepository.findByBizNo("BIZ-1")).thenReturn(Optional.of(order("PAID", 1000)));
        when(paymentRepository.insertEvent(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), any())).thenReturn(false);
        when(paymentRepository.findEvent("MOCK", "EVT-1")).thenReturn(Optional.of(
                new PaymentEventRecord(11L, "MOCK", "EVT-1", null, "BIZ-1", "PAYMENT_SUCCESS",
                        1000, "PROCESSED", null, LocalDateTime.now(), LocalDateTime.now())));

        PaymentConfirmationService.ConfirmationResult result = service.accept(success(1000));

        assertThat(result.success()).isTrue();
        assertThat(result.duplicate()).isTrue();
        verify(outboxRepository, never()).append(any());
    }

    @Test
    void 支付成功在支付单租户上下文内更新状态并写发件箱() {
        when(orderRepository.findByBizNo("BIZ-1")).thenReturn(Optional.of(order("PAYING", 1000)));
        when(paymentRepository.insertEvent(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), any())).thenReturn(true);
        when(paymentRepository.findEvent("MOCK", "EVT-1")).thenReturn(Optional.of(
                new PaymentEventRecord(11L, "MOCK", "EVT-1", null, "BIZ-1", "PAYMENT_SUCCESS",
                        1000, "RECEIVED", null, LocalDateTime.now(), null)));
        when(paymentRepository.findLatestAttemptByPaymentNo("P1")).thenReturn(Optional.of(
                new PaymentAttemptRecord(5L, null, 1, "MOCK", "PROCESSING", "TRADE-1",
                        "CREATED", 1000, null, null, null, null,
                        LocalDateTime.now(), null)));

        // 模拟后台回调线程：系统作用域且未回填租户
        java.util.concurrent.atomic.AtomicLong tenantDuringUpdate = new java.util.concurrent.atomic.AtomicLong(-1);
        when(orderRepository.markPaid("P1", "MOCK", "TRADE-1")).thenAnswer(invocation -> {
            tenantDuringUpdate.set(TenantContext.current());
            return 1;
        });
        when(orderRepository.findByPaymentNo("P1")).thenReturn(Optional.of(order("PAID", 1000)));

        PaymentConfirmationService.ConfirmationResult result = TenantContext.runAsSystem(
                () -> service.accept(success(1000)));

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("PAID");
        assertThat(tenantDuringUpdate.get()).isEqualTo(TENANT);
        verify(paymentRepository).markAttemptSuccess(5L);
        verify(paymentRepository).markEventProcessed(11L);

        ArgumentCaptor<PaymentSettledEvent> captor = ArgumentCaptor.forClass(PaymentSettledEvent.class);
        verify(outboxRepository).append(captor.capture());
        PaymentSettledEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(PaymentEventTypes.PAYMENT_SUCCEEDED);
        assertThat(event.paymentNo()).isEqualTo("P1");
        assertThat(event.bizNo()).isEqualTo("BIZ-1");
        assertThat(event.amountCents()).isEqualTo(1000);
        assertThat(event.providerTradeNo()).isEqualTo("TRADE-1");

        // 确认结束后恢复进入前的作用域状态
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void 支付失败事件标记失败并发PAYMENT_FAILED事件() {
        when(orderRepository.findByBizNo("BIZ-1")).thenReturn(Optional.of(order("PAYING", 1000)));
        when(paymentRepository.insertEvent(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), any())).thenReturn(true);
        when(paymentRepository.findEvent("MOCK", "EVT-1")).thenReturn(Optional.of(
                new PaymentEventRecord(11L, "MOCK", "EVT-1", null, "BIZ-1", "PAYMENT_FAILED",
                        1000, "RECEIVED", null, LocalDateTime.now(), null)));
        when(paymentRepository.findLatestAttemptByPaymentNo("P1")).thenReturn(Optional.of(
                new PaymentAttemptRecord(5L, null, 1, "MOCK", "PROCESSING", "TRADE-1",
                        "CREATED", 1000, null, null, null, null,
                        LocalDateTime.now(), null)));
        when(orderRepository.findByPaymentNo("P1")).thenReturn(Optional.of(order("FAILED", 1000)));
        when(orderRepository.markFailed("P1")).thenReturn(1);

        PaymentConfirmationService.ConfirmationResult result = service.accept(
                new PaymentNotification("MOCK", "EVT-1", "BIZ-1", "PAYMENT_FAILED",
                        1000, "TRADE-1", null));

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("FAILED");
        verify(paymentRepository).markAttemptFailed(eq(5L), eq("PROVIDER_FAILED"), anyString());
        verify(orderRepository).markFailed("P1");
        ArgumentCaptor<PaymentSettledEvent> captor = ArgumentCaptor.forClass(PaymentSettledEvent.class);
        verify(outboxRepository).append(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(PaymentEventTypes.PAYMENT_FAILED);
        assertThat(TenantContext.currentOrNull()).isNull();
    }


    @Test
    void 关闭的支付单收到成功回调时拒绝且不写发件箱() {
        when(orderRepository.findByBizNo("BIZ-1")).thenReturn(Optional.of(order("PAYING", 1000)));
        when(paymentRepository.insertEvent(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), any())).thenReturn(true);
        when(paymentRepository.findEvent("MOCK", "EVT-1")).thenReturn(Optional.of(
                new PaymentEventRecord(11L, "MOCK", "EVT-1", null, "BIZ-1", "PAYMENT_SUCCESS",
                        1000, "RECEIVED", null, LocalDateTime.now(), null)));
        when(paymentRepository.findLatestAttemptByPaymentNo("P1")).thenReturn(Optional.of(
                new PaymentAttemptRecord(5L, null, 1, "MOCK", "PROCESSING", "TRADE-1",
                        "CREATED", 1000, null, null, null, null, LocalDateTime.now(), null)));
        when(orderRepository.markPaid("P1", "MOCK", "TRADE-1")).thenReturn(0);
        when(orderRepository.findByPaymentNo("P1")).thenReturn(Optional.of(order("CLOSED", 1000)));

        PaymentConfirmationService.ConfirmationResult result = service.accept(success(1000));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("CLOSED");
        verify(paymentRepository).markEventFailed(eq(11L), contains("CLOSED"));
        verify(alertPublisher).publish(eq("PAYMENT_STATE_CONFLICT"), eq("ERROR"),
                contains("status=CLOSED"), eq(false), anyString());
        verify(paymentRepository, never()).markAttemptSuccess(anyLong());
        verify(outboxRepository, never()).append(any());
    }

    @Test
    void 已支付支付单收到另一个成功事件按重复处理() {
        when(orderRepository.findByBizNo("BIZ-1")).thenReturn(Optional.of(order("PAID", 1000)));
        when(paymentRepository.insertEvent(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), any())).thenReturn(true);
        when(paymentRepository.findEvent("MOCK", "EVT-1")).thenReturn(Optional.of(
                new PaymentEventRecord(11L, "MOCK", "EVT-1", null, "BIZ-1", "PAYMENT_SUCCESS",
                        1000, "RECEIVED", null, LocalDateTime.now(), null)));
        when(paymentRepository.findLatestAttemptByPaymentNo("P1")).thenReturn(Optional.empty());
        when(orderRepository.markPaid("P1", "MOCK", "TRADE-1")).thenReturn(0);
        when(orderRepository.findByPaymentNo("P1")).thenReturn(Optional.of(order("PAID", 1000)));

        PaymentConfirmationService.ConfirmationResult result = service.accept(success(1000));

        assertThat(result.success()).isTrue();
        assertThat(result.duplicate()).isTrue();
        verify(paymentRepository).markEventProcessed(11L);
        verify(outboxRepository, never()).append(any());
    }

    @Test
    void 已支付支付单收到失败事件时拒绝() {
        when(orderRepository.findByBizNo("BIZ-1")).thenReturn(Optional.of(order("PAID", 1000)));
        when(paymentRepository.insertEvent(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), any())).thenReturn(true);
        when(paymentRepository.findEvent("MOCK", "EVT-1")).thenReturn(Optional.of(
                new PaymentEventRecord(11L, "MOCK", "EVT-1", null, "BIZ-1", "PAYMENT_FAILED",
                        1000, "RECEIVED", null, LocalDateTime.now(), null)));
        when(paymentRepository.findLatestAttemptByPaymentNo("P1")).thenReturn(Optional.empty());
        when(orderRepository.markFailed("P1")).thenReturn(0);
        when(orderRepository.findByPaymentNo("P1")).thenReturn(Optional.of(order("PAID", 1000)));

        PaymentConfirmationService.ConfirmationResult result = service.accept(
                new PaymentNotification("MOCK", "EVT-1", "BIZ-1", "PAYMENT_FAILED",
                        1000, "TRADE-1", null));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("PAID");
        verify(paymentRepository).markEventFailed(eq(11L), contains("PAID"));
        verify(outboxRepository, never()).append(any());
    }

    @Test
    void 指定支付单号时按支付单号定位而不是业务单号() {
        when(orderRepository.findByPaymentNo("P1")).thenReturn(Optional.of(order("PAID", 1000)));
        when(paymentRepository.insertEvent(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), any())).thenReturn(false);
        when(paymentRepository.findEvent("MOCK", "EVT-1")).thenReturn(Optional.empty());

        service.accept(success(1000), "P1");
        verify(orderRepository).findByPaymentNo("P1");
        verify(orderRepository, never()).findByBizNo(anyString());
    }

    @Test
    void 重新进入时保留外层租户上下文() {
        TenantContext.set(3L);
        when(orderRepository.findByBizNo("BIZ-1")).thenReturn(Optional.of(order("PAID", 1000)));
        when(paymentRepository.insertEvent(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), any())).thenReturn(false);
        when(paymentRepository.findEvent("MOCK", "EVT-1")).thenReturn(Optional.empty());

        service.accept(success(1000));
        assertThat(TenantContext.currentOrNull()).isEqualTo(3L);
    }
}
