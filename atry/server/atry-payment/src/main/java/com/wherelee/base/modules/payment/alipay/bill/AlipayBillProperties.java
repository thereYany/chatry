package com.wherelee.base.modules.payment.alipay.bill;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 支付宝账单下载与对账配置。
 *
 * <p>只有开通了对账单接口、且服务器能访问外网时才需要开启；开启后
 * {@code ReconciliationService} 才能对 {@code ALIPAY} 渠道跑 T+1 对账。</p>
 */
@ConfigurationProperties(prefix = "app.payment.alipay.bill")
public record AlipayBillProperties(
        boolean enabled,
        String billType,
        String smid,
        String charset,
        int connectTimeoutMs,
        int readTimeoutMs
) {
    public String safeBillType() {
        return billType == null || billType.isBlank() ? "trade" : billType;
    }

    public String safeCharset() {
        return charset == null || charset.isBlank() ? "GBK" : charset;
    }

    public int safeConnectTimeoutMs() {
        return connectTimeoutMs <= 0 ? 5000 : connectTimeoutMs;
    }

    public int safeReadTimeoutMs() {
        return readTimeoutMs <= 0 ? 30000 : readTimeoutMs;
    }
}
