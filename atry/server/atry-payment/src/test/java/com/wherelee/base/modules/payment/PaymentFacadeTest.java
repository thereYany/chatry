package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.contracts.payment.PaymentOperations;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

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

/**
 * 支付门面测试：幂等发起、状态终态短路、事务边界。
 *
 * <p>业务不变式：同一 (bizType,bizNo) 只有一张支付单；已支付/已关闭不再新建尝试；
 * 参数非法在触达渠道前拒绝；支付单/尝试的落库归属当前租户。</p>
 */
class PaymentFacadeTest {

    private PaymentOrderRepository orderRepository;
    private PaymentRepository paymentRepository;
    private PaymentGateway gateway;
    private PaymentFacade facade;

    @BeforeEach
    void setUp() {
        orderRepository = mock(PaymentOrderRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        gateway = mock(PaymentGateway.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        facade = new PaymentFacade(orderRepository, paymentRepository, gateway,
                transactionManager, "atry-it");
        when(gateway.channel()).thenReturn("MOCK");
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private static PaymentOperations.InitiateCommand command() {
        return new PaymentOperations.InitiateCommand(null, "RESOURCE_PACK", "ORDER-1", 9900,
                "CNY", "资料包", LocalDateTime.now().plusMinutes(15));
    }

    private static PaymentOrderView order(String status) {
        return new PaymentOrderView("PORDER-1", "ORDER-1", "atry-it", "RESOURCE_PACK",
                "资料包", 9900, "CNY", status, "MOCK", "MOCK-ORDER-1-1",
                LocalDateTime.now().plusMinutes(15), null, 0L);
    }

    @Test
    void 参数非法时不触达渠道() {
        assertThatThrownBy(() -> facade.initiate(new PaymentOperations.InitiateCommand(
                null, " ", "ORDER-1", 100, "CNY", "s", LocalDateTime.now().plusMinutes(15))))
                .isInstanceOf(com.wherelee.base.modules.platform.common.BizException.class);
        assertThatThrownBy(() -> facade.initiate(new PaymentOperations.InitiateCommand(
                null, "T", "", 100, "CNY", "s", LocalDateTime.now().plusMinutes(15))))
                .isInstanceOf(com.wherelee.base.modules.platform.common.BizException.class);
        assertThatThrownBy(() -> facade.initiate(new PaymentOperations.InitiateCommand(
                null, "T", "B", 0, "CNY", "s", LocalDateTime.now().plusMinutes(15))))
                .isInstanceOf(com.wherelee.base.modules.platform.common.BizException.class)
                .hasMessageContaining("金额必须大于 0");
        assertThatThrownBy(() -> facade.initiate(new PaymentOperations.InitiateCommand(
                null, "T", "B", 100, " ", "s", LocalDateTime.now().plusMinutes(15))))
                .isInstanceOf(com.wherelee.base.modules.platform.common.BizException.class);
        verify(gateway, never()).initiate(any(), any());
    }

    @Test
    void 首次发起创建支付单与尝试并返回收银台地址() {
        TenantContext.set(5L);
        when(orderRepository.findByBiz("RESOURCE_PACK", "ORDER-1")).thenReturn(Optional.of(order("CREATED")));
        // 第一次查询尚无尝试（触发创建），创建后再次查询返回 PROCESSING 尝试
        when(paymentRepository.findLatestAttemptByPaymentNo("PORDER-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new PaymentAttemptRecord(9L, null, 1, "MOCK",
                        "PROCESSING", "MOCK-ORDER-1-1", "CREATED", 9900, null, null, null,
                        null, LocalDateTime.now(), null)));
        when(paymentRepository.nextAttemptNoByPaymentNo("PORDER-1")).thenReturn(1);
        when(gateway.outTradeNo(any(), eq(1))).thenReturn("MOCK-ORDER-1-1");
        when(orderRepository.findByPaymentNo("PORDER-1")).thenReturn(Optional.of(order("PAYING")));
        when(gateway.initiate(any(), any())).thenReturn(
                new PaymentGateway.PaymentAction("/mock-pay/ORDER-1"));

        PaymentOperations.InitiateResult result = facade.initiate(command());

        assertThat(result.paymentNo()).isEqualTo("PORDER-1");
        assertThat(result.status()).isEqualTo("PAYING");
        assertThat(result.cashierUrl()).isEqualTo("/mock-pay/ORDER-1");
        // 支付单创建必须写入当前租户
        verify(orderRepository).insertIfAbsent(any(), eq(5L), any());
        verify(paymentRepository).createAttempt(any(), eq("PORDER-1"), eq(1), eq("MOCK"),
                eq("MOCK-ORDER-1-1"), eq(9900L), anyString());
        verify(orderRepository).markPaying("PORDER-1", "MOCK", "MOCK-ORDER-1-1");
        verify(gateway).initiate(any(), any());
    }

    @Test
    void 已支付或已关闭的支付单直接返回终态且不重复下单() {
        when(orderRepository.findByBiz("RESOURCE_PACK", "ORDER-1"))
                .thenReturn(Optional.of(order("PAID")));

        PaymentOperations.InitiateResult result = facade.initiate(command());

        assertThat(result.status()).isEqualTo("PAID");
        assertThat(result.cashierUrl()).isNull();
        verify(paymentRepository, never()).createAttempt(any(), any(), any(Integer.class),
                any(), any(), anyLong(), any());
        verify(gateway, never()).initiate(any(), any());
    }

    @Test
    void 存在处理中的尝试时复用而不是重复建单() {
        when(orderRepository.findByBiz("RESOURCE_PACK", "ORDER-1"))
                .thenReturn(Optional.of(order("PAYING")));
        when(paymentRepository.findLatestAttemptByPaymentNo("PORDER-1")).thenReturn(Optional.of(
                new PaymentAttemptRecord(9L, null, 1, "MOCK", "PROCESSING", "MOCK-ORDER-1-1",
                        "CREATED", 9900, null, null, null, null, LocalDateTime.now(), null)));
        when(orderRepository.findByPaymentNo("PORDER-1")).thenReturn(Optional.of(order("PAYING")));
        when(gateway.initiate(any(), any())).thenReturn(
                new PaymentGateway.PaymentAction("/mock-pay/ORDER-1"));

        facade.initiate(command());

        verify(paymentRepository, never()).createAttempt(any(), any(), any(Integer.class),
                any(), any(), anyLong(), any());
        verify(gateway).initiate(any(), any());
    }

    @Test
    void 按业务单查询返回统一视图() {
        when(orderRepository.findByBiz("RESOURCE_PACK", "ORDER-1"))
                .thenReturn(Optional.of(new PaymentOrderView("P1", "ORDER-1", "atry", "RESOURCE_PACK",
                        "资料包", 9900, "CNY", "PAID", "MOCK", "TRADE", LocalDateTime.now(),
                        LocalDateTime.now(), 3L)));

        Optional<PaymentOperations.PaymentView> view = facade.findByBiz("RESOURCE_PACK", "ORDER-1");
        assertThat(view).isPresent();
        assertThat(view.get().paymentNo()).isEqualTo("P1");
        assertThat(view.get().status()).isEqualTo("PAID");
        assertThat(view.get().paidAt()).isNotNull();
        assertThat(view.get().providerTradeNo()).isEqualTo("TRADE");

        when(orderRepository.findByBiz("RESOURCE_PACK", "MISSING")).thenReturn(Optional.empty());
        assertThat(facade.findByBiz("RESOURCE_PACK", "MISSING")).isEmpty();
    }
}
