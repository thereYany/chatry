package com.wherelee.chatry.mall;

import com.wherelee.base.modules.contracts.metering.AdjustCommand;
import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.payment.RefundOperations;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.chatry.credits.ChatBalanceView;
import com.wherelee.chatry.credits.CreditService;
import com.wherelee.chatry.ledger.FundLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MallRefundServiceTest {

    private static final long TENANT = 0L;
    private static final long USER = 9L;

    @Mock
    private MallOrderRepository orders;
    @Mock
    private RefundOperations refunds;
    @Mock
    private MeteringAdminOperations meteringAdmin;
    @Mock
    private CreditService credits;
    @Mock
    private FundLedgerService fundLedger;

    private MallRefundService service;

    @BeforeEach
    void setUp() {
        service = new MallRefundService(orders, refunds, meteringAdmin, credits, fundLedger);
    }

    private static MallOrder order(String status, long amountCents, long creditCents,
                                   long refundedCents) {
        return new MallOrder(1, TENANT, "M1", USER, 4, "入门额度包", amountCents, creditCents,
                status, "P1", false, Instant.now().plusSeconds(600), Instant.now(), Instant.now(),
                refundedCents, null);
    }

    private void balance(String value) {
        when(credits.balance(TENANT, USER)).thenReturn(new ChatBalanceView(
                new BigDecimal(value), BigDecimal.ZERO, "CNY"));
    }

    @Test
    void paidOrderRefundsOnlyUnconsumedPortion() {
        balance("6.00");
        assertThat(service.refundableCents(TENANT, order("PAID", 999, 1200, 0)))
                .isEqualTo(499);   // 6.00 / 12.00 × 9.99 = 4.995 → 499 分
    }

    @Test
    void fullyConsumedOrderHasNothingRefundable() {
        balance("0.00");
        assertThat(service.refundableCents(TENANT, order("PAID", 999, 1200, 0))).isZero();
    }

    @Test
    void paidAfterCloseOrderRefundsFullAmount() {
        assertThat(service.refundableCents(TENANT, order("PAID_AFTER_CLOSE", 999, 1200, 0)))
                .isEqualTo(999);
    }

    @Test
    void refundableNeverExceedsRemaining() {
        balance("12.00");
        assertThat(service.refundableCents(TENANT, order("PAID", 999, 1200, 800))).isEqualTo(199);
    }

    @Test
    void requestRefundRejectsWhenNothingRefundable() {
        when(orders.findByNo(TENANT, "M1")).thenReturn(Optional.of(order("PAID", 999, 1200, 0)));
        balance("0.00");

        assertThatThrownBy(() -> service.requestRefund(TENANT, "M1", null, "测试", 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有可退金额");
        verify(refunds, never()).request(any());
    }

    @Test
    void requestRefundMarksOrderRefundingThenCallsPayment() {
        when(orders.findByNo(TENANT, "M1")).thenReturn(Optional.of(
                order("PAID_AFTER_CLOSE", 999, 1200, 0)));
        when(orders.attachRefundNo(eq(TENANT), eq("M1"), any())).thenReturn(1);
        when(refunds.request(any())).thenReturn(new RefundOperations.RefundView(
                "MR1", "P1", "M1", 999, "PROCESSING", "测试", null, null,
                java.time.LocalDateTime.now(), null));

        RefundOperations.RefundView view = service.requestRefund(TENANT, "M1", null, "关单后退款", 1L);

        assertThat(view.refundNo()).isEqualTo("MR1");
        verify(orders).attachRefundNo(eq(TENANT), eq("M1"), any());
        verify(orders, never()).markRefunding(eq(TENANT), eq("M1"), any());
        ArgumentCaptor<RefundOperations.RefundCommand> captor =
                ArgumentCaptor.forClass(RefundOperations.RefundCommand.class);
        verify(refunds).request(captor.capture());
        assertThat(captor.getValue().paymentNo()).isEqualTo("P1");
        assertThat(captor.getValue().amountCents()).isEqualTo(999);
    }

    @Test
    void refundSucceededDeductsCreditAndPostsReversal() {
        when(orders.findByNo(TENANT, "M1")).thenReturn(Optional.of(order("REFUNDING", 999, 1200, 0)));
        when(orders.addRefunded(eq(TENANT), eq("M1"), eq(999L), any())).thenReturn(1);

        service.onRefundSucceeded(TENANT, "M1", 999, "MR1");

        ArgumentCaptor<AdjustCommand> adjust = ArgumentCaptor.forClass(AdjustCommand.class);
        verify(meteringAdmin).adjust(adjust.capture());
        assertThat(adjust.getValue().amount()).isEqualByComparingTo("-12.00");
        assertThat(adjust.getValue().bizNo()).isEqualTo("mall-refund:MR1");
        assertThat(adjust.getValue().allowNegative()).isTrue();
        verify(fundLedger).postRefund(eq(TENANT), eq(USER), eq(999L), eq(1200L), eq(1200L), eq("MR1"));
    }

    @Test
    void refundSucceededForAfterCloseDoesNotDeductCredit() {
        when(orders.findByNo(TENANT, "M1")).thenReturn(Optional.of(
                order("PAID_AFTER_CLOSE", 999, 1200, 0)));
        when(orders.addRefunded(eq(TENANT), eq("M1"), eq(999L), any())).thenReturn(1);

        service.onRefundSucceeded(TENANT, "M1", 999, "MR2");

        verify(meteringAdmin, never()).adjust(any());
        verify(fundLedger).postRefundNoCredit(TENANT, 999L, "MR2");
    }

    @Test
    void refundFailureRestoresOrderToPaid() {
        service.onRefundFailed(TENANT, "M1", "MR3", "渠道异常");
        verify(orders).markRefundFailed(TENANT, "M1", "MR3");
    }
}
