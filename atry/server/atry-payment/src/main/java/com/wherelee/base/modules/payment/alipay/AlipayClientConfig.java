package com.wherelee.base.modules.payment.alipay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.DefaultAlipayClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付宝客户端装配。只在 provider=alipay 时生效，缺关键配置直接启动失败，
 * 避免带着空密钥上线后在支付环节才暴露问题。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "alipay")
public class AlipayClientConfig {

    @Bean
    public AlipayClient alipayClient(AlipayProperties properties) throws AlipayApiException {
        require(properties.appId(), "ALIPAY_APP_ID");
        require(properties.privateKey(), "ALIPAY_PRIVATE_KEY");
        require(properties.alipayPublicKey(), "ALIPAY_PUBLIC_KEY");
        require(properties.gatewayUrl(), "ALIPAY_GATEWAY_URL");
        // 异步通知/同步回跳不是启动必需项：没有公网域名时可以不配，由内核主动查单（轮询）完成结算。
        if (!hasText(properties.notifyUrl())) {
            log.warn("未配置 ALIPAY_NOTIFY_URL：外部支付只能依赖主动查单（轮询）确认结果");
        }
        if (!hasText(properties.returnUrl())) {
            log.warn("未配置 ALIPAY_RETURN_URL：支付完成后不会自动回到业务页面");
        }

        AlipayConfig config = new AlipayConfig();
        config.setServerUrl(properties.gatewayUrl());
        config.setAppId(properties.appId());
        config.setPrivateKey(properties.privateKey());
        config.setAlipayPublicKey(properties.alipayPublicKey());
        config.setFormat(properties.format());
        config.setCharset(properties.charset());
        config.setSignType(properties.signType());
        config.setConnectTimeout(properties.connectTimeoutMs());
        config.setReadTimeout(properties.readTimeoutMs());
        return new DefaultAlipayClient(config);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("支付宝支付缺少必要配置：" + name);
        }
    }
}
