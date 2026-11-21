package com.wherelee.base.modules.payment;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付基础设施测试：Mock 回调签名、支付单号、配置安全夹紧、渠道安全护栏。
 *
 * <p>不变式：回调签名覆盖全部业务字段（防篡改金额/单号）；同一业务单号只能生成一张支付单；
 * 生产环境禁止 Mock 渠道；退款重试参数有安全上下界。</p>
 */
class PaymentInfrastructureTest {

    private static PaymentProperties properties(String provider, boolean mockEnabled, String secret) {
        return new PaymentProperties(provider, mockEnabled, secret, 15, 15, 20,
                15_000, 10_000, 5, 30);
    }

    private static PaymentNotification notification(long amountCents) {
        return new PaymentNotification("MOCK", "EVT-1", "BIZ-1", "PAYMENT_SUCCESS",
                amountCents, "TRADE-1", null);
    }

    // ---------- 签名 ----------

    @Test
    void 签名确定且可验签() {
        MockSignatureService service = new MockSignatureService(
                properties("mock", true, "secret-key"));
        PaymentNotification notification = notification(1000);

        String signature = service.sign(notification);
        assertThat(signature).hasSize(64).matches("[0-9a-f]+");
        assertThat(service.sign(notification)).isEqualTo(signature);

        PaymentNotification signed = new PaymentNotification(notification.provider(),
                notification.eventKey(), notification.orderNo(), notification.eventType(),
                notification.amountCents(), notification.tradeNo(), signature);
        assertThat(service.verify(signed)).isTrue();
    }

    @Test
    void 金额或单号被篡改时验签失败() {
        MockSignatureService service = new MockSignatureService(
                properties("mock", true, "secret-key"));
        String signature = service.sign(notification(1000));

        PaymentNotification tamperedAmount = new PaymentNotification("MOCK", "EVT-1", "BIZ-1",
                "PAYMENT_SUCCESS", 1, "TRADE-1", signature);
        assertThat(service.verify(tamperedAmount)).isFalse();

        PaymentNotification tamperedOrder = new PaymentNotification("MOCK", "EVT-1", "BIZ-OTHER",
                "PAYMENT_SUCCESS", 1000, "TRADE-1", signature);
        assertThat(service.verify(tamperedOrder)).isFalse();
    }

    @Test
    void 缺少签名或密钥不一致时验签失败() {
        MockSignatureService service = new MockSignatureService(
                properties("mock", true, "secret-key"));
        assertThat(service.verify(notification(1000))).isFalse();

        String signature = service.sign(notification(1000));
        MockSignatureService other = new MockSignatureService(
                properties("mock", true, "another-secret"));
        assertThat(other.verify(new PaymentNotification("MOCK", "EVT-1", "BIZ-1",
                "PAYMENT_SUCCESS", 1000, "TRADE-1", signature))).isFalse();
    }

    // ---------- 支付单号 ----------

    @Test
    void 确定性支付单号由业务单号派生() {
        assertThat(PaymentNo.of("ORDER-1")).isEqualTo("PORDER-1");
        assertThat(PaymentNo.of("ORDER-1")).isEqualTo(PaymentNo.of("ORDER-1"));
    }

    @Test
    void issue生成唯一且带前缀的支付单号() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String paymentNo = PaymentNo.issue();
            assertThat(paymentNo).startsWith("P").hasSizeGreaterThan(24).hasSizeLessThanOrEqualTo(40);
            assertThat(values.add(paymentNo)).isTrue();
        }
    }

    // ---------- 配置夹紧 ----------

    @Test
    void 退款重试参数被夹紧到安全区间() {
        assertThat(withRefund(0, 1).safeRefundMaxAttempts()).isEqualTo(1);
        assertThat(withRefund(100, 1).safeRefundMaxAttempts()).isEqualTo(20);
        assertThat(withRefund(5, 1).safeRefundRetryBaseSeconds()).isEqualTo(5);
        assertThat(withRefund(5, 100_000).safeRefundRetryBaseSeconds()).isEqualTo(3600);
    }

    private static PaymentProperties withRefund(int maxAttempts, int baseSeconds) {
        return new PaymentProperties("mock", true, "s", 15, 15, 20,
                15_000, 10_000, maxAttempts, baseSeconds);
    }

    // ---------- 安全护栏 ----------

    @Test
    void 生产环境禁止Mock支付() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        PaymentSafetyGuard guard = new PaymentSafetyGuard(properties("mock", true, "s"), prod);
        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止启用 Mock 支付");

        PaymentSafetyGuard alipayButMockEnabled =
                new PaymentSafetyGuard(properties("alipay", true, "s"), prod);
        assertThatThrownBy(alipayButMockEnabled::verify)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 非生产环境允许Mock且拒绝未知渠道() {
        MockEnvironment dev = new MockEnvironment();
        // 非生产环境不抛错即通过
        new PaymentSafetyGuard(properties("mock", true, "s"), dev).verify();
        new PaymentSafetyGuard(properties("alipay", false, "s"), dev).verify();
        assertThatThrownBy(() -> new PaymentSafetyGuard(properties("wechat", false, "s"), dev).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不支持的支付渠道");
    }
}
