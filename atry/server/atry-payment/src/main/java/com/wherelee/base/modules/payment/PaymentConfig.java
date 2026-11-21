package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.payment.alipay.AlipayProperties;
import com.wherelee.base.modules.payment.alipay.bill.AlipayBillProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 支付模块的自装配入口：注册支付配置属性，应用只依赖 jar 即可。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PaymentProperties.class, AlipayProperties.class, AlipayBillProperties.class})
public class PaymentConfig {
}
