package com.wherelee.base.modules.payment.alipay;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付宝金额换算测试。
 *
 * <p>不变式：内部统一「分」，与支付宝「元」的两位小数字符串互转必须精确；
 * 负数金额拒绝；四舍五入使用 HALF_UP（与支付宝对账口径一致）。</p>
 */
class AlipayAmountsTest {

    @Test
    void 分转元始终两位小数() {
        assertThat(AlipayAmounts.toYuan(0)).isEqualTo("0.00");
        assertThat(AlipayAmounts.toYuan(1)).isEqualTo("0.01");
        assertThat(AlipayAmounts.toYuan(100)).isEqualTo("1.00");
        assertThat(AlipayAmounts.toYuan(199)).isEqualTo("1.99");
        assertThat(AlipayAmounts.toYuan(123456)).isEqualTo("1234.56");
    }

    @Test
    void 负数金额拒绝换算() {
        assertThatThrownBy(() -> AlipayAmounts.toYuan(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("负数");
    }

    @Test
    void 元转分精确到分() {
        assertThat(AlipayAmounts.toCents("0.01")).isEqualTo(1);
        assertThat(AlipayAmounts.toCents("1.23")).isEqualTo(123);
        assertThat(AlipayAmounts.toCents(" 1.20 ")).isEqualTo(120);
        assertThat(AlipayAmounts.toCents("1234.56")).isEqualTo(123456);
    }

    @Test
    void 多余小数位按HALF_UP处理() {
        // 0.005 元 -> 0.5 分 -> 1 分（HALF_UP）
        assertThat(AlipayAmounts.toCents("0.005")).isEqualTo(1);
        assertThat(AlipayAmounts.toCents("0.004")).isZero();
    }

    @Test
    void 缺失或非法金额拒绝() {
        assertThatThrownBy(() -> AlipayAmounts.toCents(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlipayAmounts.toCents("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlipayAmounts.toCents("abc"))
                .isInstanceOf(NumberFormatException.class);
    }
}
