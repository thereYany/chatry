package com.wherelee.chatry.mall;

import com.wherelee.base.modules.contracts.payment.PaymentOperations;
import com.wherelee.base.modules.payment.PaymentConfirmationService;
import com.wherelee.base.modules.payment.outbox.PaymentOutboxDispatcher;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.chatry.credits.ChatBalanceView;
import com.wherelee.chatry.credits.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MallServiceTest {

    private static final long TENANT = 0L;
    private static final long USER = 7L;
    private static final long SKU_ID = 4L;

    @Mock
    private MallSkuRepository skus;
    @Mock
    private MallOrderRepository orders;
    @Mock
    private PaymentOperations payment;
    @Mock
    private PaymentConfirmationService confirmation;
    @Mock
    private PaymentOutboxDispatcher outbox;
    @Mock
    private CreditService credits;
    @Mock
    private MockPaymentGuard mockPaymentGuard;

    private MallService service;

    @BeforeEach
    void setUp() {
        service = new MallService(skus, orders, payment, confirmation, outbox, credits,
                mockPaymentGuard, new MallProperties(15, 30_000L));
    }

    @Test
    void flashOrderUsesFlashPriceAndDecrementsStock() {
        when(skus.findById(TENANT, SKU_ID)).thenReturn(Optional.of(flashSku(5, 1)));
        when(skus.decrementStock(TENANT, SKU_ID)).thenReturn(1);
        when(orders.insert(eq(TENANT), anyString(), eq(USER), eq(SKU_ID), anyString(),
                eq(190L), eq(3000L), eq(true), anyString(), any(Instant.class))).thenReturn(11L);
        when(payment.initiate(any())).thenReturn(
                new PaymentOperations.InitiateResult("P-1", "PAYING", "MOCK", "/mock-pay/x"));
        when(orders.markPaying(eq(TENANT), anyString(), eq("P-1"))).thenReturn(1);

        CreateOrderResponse response = service.createOrder(TENANT, USER, SKU_ID);

        assertThat(response.amountCents()).isEqualTo(190L);
        assertThat(response.creditCents()).isEqualTo(3000L);
        assertThat(response.flashSale()).isTrue();
        assertThat(response.status()).isEqualTo("PAYING");
        verify(skus).decrementStock(TENANT, SKU_ID);
    }

    @Test
    void soldOutFlashIsRejectedWithoutOrder() {
        when(skus.findById(TENANT, SKU_ID)).thenReturn(Optional.of(flashSku(0, 1)));

        assertThatThrownBy(() -> service.createOrder(TENANT, USER, SKU_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已售罄");
        verify(orders, never()).insert(anyLong(), anyString(), anyLong(), anyLong(), anyString(),
                anyLong(), anyLong(), anyBoolean(), any(), any(Instant.class));
    }

    @Test
    void duplicateLimitKeyRestoresStockAndRejects() {
        when(skus.findById(TENANT, SKU_ID)).thenReturn(Optional.of(flashSku(3, 1)));
        when(skus.decrementStock(TENANT, SKU_ID)).thenReturn(1);
        when(orders.insert(eq(TENANT), anyString(), eq(USER), eq(SKU_ID), anyString(),
                eq(190L), eq(3000L), eq(true), anyString(), any(Instant.class)))
                .thenThrow(new DuplicateKeyException("uk_order_limit"));

        assertThatThrownBy(() -> service.createOrder(TENANT, USER, SKU_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("限购");
        verify(skus).restoreStock(TENANT, SKU_ID);
        verify(payment, never()).initiate(any());
    }

    @Test
    void mockPayRejectsOtherUsersOrder() {
        MallOrder order = new MallOrder(1, TENANT, "M1", USER + 1, SKU_ID, "秒杀", 190, 3000,
                MallOrder.STATUS_PAYING, "P-1", true, Instant.now().plus(10, ChronoUnit.MINUTES), null,
                Instant.now(), 0L, null);
        when(orders.findByNo(TENANT, "M1")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.mockPay(TENANT, USER, "M1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单不存在");
        verify(confirmation, never()).accept(any());
    }


    @Test
    void mockPayRejectedWhenGuardDisabled() {
        doThrow(BizException.notFound("模拟支付未启用")).when(mockPaymentGuard).requireEnabled();

        assertThatThrownBy(() -> service.mockPay(TENANT, USER, "M1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模拟支付未启用");
        verify(orders, never()).findByNo(anyLong(), anyString());
    }

    @Test
    void mockPayClosedOrderIsRejected() {
        MallOrder order = new MallOrder(1, TENANT, "M1", USER, SKU_ID, "额度包", 999, 1200,
                MallOrder.STATUS_CLOSED, "P-1", false, Instant.now().plusSeconds(600), null, Instant.now(),
                0L, null);
        when(orders.findByNo(TENANT, "M1")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.mockPay(TENANT, USER, "M1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单已关闭");
        verify(confirmation, never()).accept(any());
    }

    @Test
    void mockPayDoesNotReportSuccessWhenConfirmationRejected() {
        MallOrder order = new MallOrder(1, TENANT, "M1", USER, SKU_ID, "额度包", 999, 1200,
                MallOrder.STATUS_PAYING, "P-1", false, Instant.now().plusSeconds(600), null, Instant.now(),
                0L, null);
        when(orders.findByNo(TENANT, "M1")).thenReturn(Optional.of(order));
        when(credits.balance(TENANT, USER)).thenReturn(new ChatBalanceView(
                new java.math.BigDecimal("5.00"), java.math.BigDecimal.ZERO, "CNY"));
        when(payment.findByBiz(MallService.BIZ_TYPE, "M1")).thenReturn(Optional.of(
                new PaymentOperations.PaymentView("P-1", MallService.BIZ_TYPE, "M1", "PAYING",
                        999, "CNY", "MOCK", null, null)));
        when(confirmation.accept(any())).thenReturn(
                new PaymentConfirmationService.ConfirmationResult(false, false, "支付单状态 CLOSED 不允许确认",
                        "P-1", "M1", "CLOSED", 999, "MOCK", null));

        assertThatThrownBy(() -> service.mockPay(TENANT, USER, "M1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("CLOSED");
        verify(outbox, never()).dispatchOnce();
    }

    @Test
    void mockPayPaidOrderReturnsWithoutConfirmingAgain() {
        MallOrder order = new MallOrder(1, TENANT, "M1", USER, SKU_ID, "额度包", 999, 1200,
                MallOrder.STATUS_PAID, "P-1", false, Instant.now().plusSeconds(600), Instant.now(),
                Instant.now(), 0L, null);
        when(orders.findByNo(TENANT, "M1")).thenReturn(Optional.of(order));
        when(credits.balance(TENANT, USER)).thenReturn(new ChatBalanceView(
                new java.math.BigDecimal("17.00"), java.math.BigDecimal.ZERO, "CNY"));

        PayResponse response = service.mockPay(TENANT, USER, "M1");

        assertThat(response.status()).isEqualTo(MallOrder.STATUS_PAID);
        assertThat(response.balance()).isEqualByComparingTo("17.00");
        verify(confirmation, never()).accept(any());
        verify(outbox, never()).dispatchOnce();
    }

    @Test
    void flashOutsideWindowIsRejected() {
        MallSku sku = new MallSku(SKU_ID, TENANT, "FLASH", "秒杀", null, 3000, 3000, 10, 1,
                true, 190L, Instant.now().minus(2, ChronoUnit.HOURS),
                Instant.now().minus(1, ChronoUnit.HOURS), "ON_SALE", 1);
        when(skus.findById(TENANT, SKU_ID)).thenReturn(Optional.of(sku));

        assertThatThrownBy(() -> service.createOrder(TENANT, USER, SKU_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("秒杀未开始或已结束");
        verify(skus, never()).decrementStock(anyLong(), anyLong());
    }

    private MallSku flashSku(int stock, int perUserLimit) {
        return new MallSku(SKU_ID, TENANT, "FLASH_30", "限时秒杀", "秒杀",
                3000, 3000, stock, perUserLimit, true, 190L,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS),
                "ON_SALE", 1);
    }
}
