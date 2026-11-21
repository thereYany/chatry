package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.contracts.payment.RefundOperations;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.outbox.PaymentOutboxRepository;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 退款编排：超退拒绝、重复幂等、成功发事件、失败落重试。 */
class RefundServiceTest {

    private PaymentOrderRepository orders;
    private PaymentRepository payments;
    private PaymentGateway gateway;
    private PaymentOutboxRepository outbox;
    private RefundService service;

    private static final long TENANT = 7L;

    @BeforeEach
    void setUp() {
        orders = mock(PaymentOrderRepository.class);
        payments = mock(PaymentRepository.class);
        gateway = mock(PaymentGateway.class);
        outbox = mock(PaymentOutboxRepository.class);
        PaymentProperties properties = new PaymentProperties("mock", true, "secret", 15, 15, 20,
                30_000L, 10_000L, 3, 10);
        service = new RefundService(orders, payments, gateway, outbox, properties,
                mock(PaymentMetrics.class));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private static PaymentOrderView paid(long amount) {
        return new PaymentOrderView("P1", "BIZ-1", "atry", "MALL", "额度包", amount, "CNY",
                "PAID", "MOCK", "TRADE-1", LocalDateTime.now().plusMinutes(15), null, TENANT);
    }

    private static RefundRecord refund(String no, long amount, String status, int attempts) {
        return refund(9L, no, amount, status, attempts);
    }

    private static RefundRecord refund(long id, String no, long amount, String status, int attempts) {
        return new RefundRecord(id, no, null, "P1", amount, status, attempts, null, null,
                "测试", 1L, LocalDateTime.now(), null, null);
    }

    @Test
    void rejectsRefundWhenAlreadyRefunded() {
        when(orders.findByPaymentNo("P1")).thenReturn(Optional.of(paid(1000)));
        when(payments.sumRefundedCents("P1")).thenReturn(800L);

        assertThatThrownBy(() -> service.request(new RefundOperations.RefundCommand(
                "RF1", "P1", 300, "测试", 1L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过可退金额");
        verify(payments, never()).createRefund(anyString(), any(), anyString(), anyLong(),
                anyString(), anyLong());
    }

    @Test
    void rejectsRefundForUnpaidPayment() {
        when(orders.findByPaymentNo("P1")).thenReturn(Optional.of(new PaymentOrderView(
                "P1", "BIZ-1", "atry", "MALL", "额度包", 1000, "CNY", "PAYING", "MOCK",
                null, LocalDateTime.now().plusMinutes(15), null, TENANT)));

        assertThatThrownBy(() -> service.request(new RefundOperations.RefundCommand(
                "RF1", "P1", 300, "测试", 1L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许退款");
    }

    @Test
    void duplicateRefundNoReturnsExisting() {
        when(payments.findRefundByNo("RF1")).thenReturn(Optional.of(refund("RF1", 300, "SUCCEEDED", 1)));
        when(orders.findByPaymentNo("P1")).thenReturn(Optional.of(paid(1000)));

        RefundOperations.RefundView view = service.request(new RefundOperations.RefundCommand(
                "RF1", "P1", 300, "测试", 1L));

        assertThat(view.status()).isEqualTo("SUCCEEDED");
        verify(payments, never()).createRefund(anyString(), any(), anyString(), anyLong(),
                anyString(), anyLong());
    }

    @Test
    void successPublishesRefundSucceededEventAndMarksPaymentRefunded() {
        when(orders.findByPaymentNo("P1")).thenReturn(Optional.of(paid(1000)));
        when(payments.sumRefundedCents("P1")).thenReturn(0L, 1000L);
        when(payments.findRefundByNo("RF1")).thenReturn(Optional.empty());
        when(payments.createRefund(eq("RF1"), eq(null), eq("P1"), eq(1000L), eq("测试"), eq(1L)))
                .thenReturn(9L);
        when(payments.findRefundById(9L)).thenReturn(Optional.of(
                refund(9L, "RF1", 1000, "PROCESSING", 0)));
        when(payments.findLatestAttemptByPaymentNo("P1")).thenReturn(Optional.empty());
        when(gateway.refund(any(), any(), eq(false)))
                .thenReturn(new PaymentGateway.RefundResult(true, "MOCKRF-1", null));

        service.request(new RefundOperations.RefundCommand("RF1", "P1", 1000, "测试", 1L));

        verify(payments).markRefundSucceeded(9L, "MOCKRF-1");
        verify(orders).markRefunded("P1");
        ArgumentCaptor<PaymentSettledEvent> captor = ArgumentCaptor.forClass(PaymentSettledEvent.class);
        verify(outbox).append(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("REFUND_SUCCEEDED");
        assertThat(captor.getValue().amountCents()).isEqualTo(1000L);
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void gatewayFailureSchedulesRetry() {
        when(orders.findByPaymentNo("P1")).thenReturn(Optional.of(paid(1000)));
        when(payments.sumRefundedCents("P1")).thenReturn(0L);
        when(payments.createRefund(eq("RF1"), eq(null), eq("P1"), eq(500L), eq("测试"), eq(1L)))
                .thenReturn(9L);
        when(payments.findRefundById(9L)).thenReturn(Optional.of(
                refund(9L, "RF1", 500, "PROCESSING", 0)));
        when(payments.findLatestAttemptByPaymentNo("P1")).thenReturn(Optional.empty());
        when(gateway.refund(any(), any(), eq(false)))
                .thenReturn(new PaymentGateway.RefundResult(false, null, "渠道异常"));

        service.request(new RefundOperations.RefundCommand("RF1", "P1", 500, "测试", 1L));

        verify(payments).markRefundFailed(eq(9L), eq("渠道异常"), any(LocalDateTime.class));
        verify(outbox, never()).append(any());
    }
}
