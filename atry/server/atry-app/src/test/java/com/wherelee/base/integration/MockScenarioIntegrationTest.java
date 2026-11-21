package com.wherelee.base.integration;

import com.wherelee.base.modules.payment.PaymentConfirmationService;
import com.wherelee.base.modules.payment.PaymentNotification;
import com.wherelee.base.modules.payment.MockSignatureService;
import com.wherelee.base.modules.contracts.payment.PaymentOperations;
import com.wherelee.base.modules.payment.PaymentStatus;
import com.wherelee.base.modules.payment.mock.MockCashierService;
import com.wherelee.base.modules.payment.mock.MockScenario;
import com.wherelee.base.modules.payment.mock.MockScenarioRepository;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mock 渠道故障剧本：签名、重复、延迟、金额错、关单后回调、查单兜底。
 *
 * <p>验证的是「异常路径走真实链路」：所有回调都要过验签与状态机，不是绕过确认。</p>
 */
class MockScenarioIntegrationTest extends IntegrationTestSupport {

    private static final String BIZ_TYPE = "MOCK_IT";
    private static final long AMOUNT = 1200L;

    @Autowired
    private MockCashierService cashier;
    @Autowired
    private MockScenarioRepository scenarios;
    @Autowired
    private MockSignatureService signatureService;

    @AfterEach
    void clearScenarios() {
        jdbc.update("DELETE FROM t_payment_mock_scenario");
    }

    private String newPayment(long tenant) {
        String bizNo = unique("MOCK");
        return asTenant(tenant, () -> paymentOperations.initiate(new PaymentOperations.InitiateCommand(
                null, BIZ_TYPE, bizNo, AMOUNT, "CNY", "Mock 剧本商品",
                LocalDateTime.now().plusMinutes(15))).paymentNo());
    }

    private String scenarioOf(String paymentNo) {
        return queryOne("SELECT status FROM t_payment_order WHERE payment_no = ?", String.class, paymentNo);
    }

    @Test
    void successGoesThroughSignatureVerification() {
        long tenant = newTenant();
        String paymentNo = newPayment(tenant);

        PaymentConfirmationService.ConfirmationResult result = cashier.simulate(paymentNo);

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(scenarioOf(paymentNo)).isEqualTo("PAID");
        // 渠道事实（支付尝试）也被写入，供查单/对账使用
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_attempt WHERE payment_no = ? "
                + "AND provider_status = 'SUCCESS'", paymentNo)).isEqualTo(1);
    }

    @Test
    void duplicateCallbackIsIdempotent() {
        long tenant = newTenant();
        String paymentNo = newPayment(tenant);
        scenarios.save(paymentNo, MockScenario.DUPLICATE, 0);

        PaymentConfirmationService.ConfirmationResult result = cashier.simulate(paymentNo);

        assertThat(result.success()).isTrue();
        assertThat(result.duplicate()).isTrue();
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_event WHERE payment_no = ?", paymentNo))
                .isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ? "
                + "AND event_type = 'PAYMENT_SUCCEEDED'", paymentNo)).isEqualTo(1);
    }

    @Test
    void amountMismatchIsRejected() {
        long tenant = newTenant();
        String paymentNo = newPayment(tenant);
        scenarios.save(paymentNo, MockScenario.AMOUNT_MISMATCH, 0);

        PaymentConfirmationService.ConfirmationResult result = cashier.simulate(paymentNo);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("金额不一致");
        assertThat(scenarioOf(paymentNo)).isEqualTo("PAYING");
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ?", paymentNo))
                .isZero();
    }

    @Test
    void badSignatureIsRejectedBeforeConfirmation() {
        long tenant = newTenant();
        String paymentNo = newPayment(tenant);
        scenarios.save(paymentNo, MockScenario.BAD_SIGNATURE, 0);

        assertThatThrownBy(() -> cashier.simulate(paymentNo))
                .hasMessageContaining("签名校验失败");
        assertThat(scenarioOf(paymentNo)).isEqualTo("PAYING");
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_event WHERE payment_no = ?", paymentNo))
                .isZero();
    }

    @Test
    void callbackAfterCloseIsRejectedWithoutFulfillment() {
        long tenant = newTenant();
        String paymentNo = newPayment(tenant);
        jdbc.update("UPDATE t_payment_order SET status = 'CLOSED' WHERE payment_no = ? AND tenant_id = ?",
                paymentNo, tenant);
        scenarios.save(paymentNo, MockScenario.AFTER_CLOSE, 0);

        PaymentConfirmationService.ConfirmationResult result = cashier.simulate(paymentNo);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("CLOSED");
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ?", paymentNo))
                .isZero();
    }

    @Test
    void queryOnlyScenarioLeavesCallbackMissingButChannelPaid() {
        long tenant = newTenant();
        String paymentNo = newPayment(tenant);
        scenarios.save(paymentNo, MockScenario.QUERY_ONLY, 0);

        PaymentConfirmationService.ConfirmationResult result = cashier.simulate(paymentNo);

        assertThat(result).isNull();
        assertThat(scenarioOf(paymentNo)).isEqualTo("PAYING");
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_attempt WHERE payment_no = ? "
                + "AND provider_status = 'SUCCESS'", paymentNo)).isEqualTo(1);
    }

    @Test
    void delayedScenarioSchedulesCallback() throws Exception {
        long tenant = newTenant();
        String paymentNo = newPayment(tenant);
        scenarios.save(paymentNo, MockScenario.DELAYED, 300);

        PaymentConfirmationService.ConfirmationResult result = cashier.simulate(paymentNo);
        assertThat(result).isNull();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !"PAID".equals(scenarioOf(paymentNo))) {
            Thread.sleep(100);
        }
        assertThat(scenarioOf(paymentNo)).isEqualTo("PAID");
    }

    @Test
    void signedNotificationFromScratchIsAcceptedOnlyWithValidSignature() {
        long tenant = newTenant();
        String paymentNo = newPayment(tenant);
        String bizNo = queryOne("SELECT biz_no FROM t_payment_order WHERE payment_no = ?",
                String.class, paymentNo);
        PaymentNotification unsigned = new PaymentNotification("MOCK", "MANUAL-" + paymentNo, bizNo,
                "PAYMENT_SUCCESS", AMOUNT, "TRADE-MANUAL", null);
        assertThat(signatureService.verify(unsigned)).isFalse();

        PaymentNotification signed = new PaymentNotification(unsigned.provider(), unsigned.eventKey(),
                unsigned.orderNo(), unsigned.eventType(), unsigned.amountCents(), unsigned.tradeNo(),
                signatureService.sign(unsigned));
        assertThat(signatureService.verify(signed)).isTrue();

        PaymentConfirmationService.ConfirmationResult result = TenantContext.runAsSystem(
                () -> paymentConfirmationService.accept(signed, paymentNo));
        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
    }
}
