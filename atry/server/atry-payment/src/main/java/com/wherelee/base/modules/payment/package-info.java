/**
 * 通用支付模块：支付单、支付尝试、回调事件、事务性发件箱、退款与渠道对账。
 *
 * <p>支付域不认识任何业务：业务侧用 {@code bizType + bizNo} 发起支付，
 * 支付成功后发出 {@code PaymentSettledEvent}，由业务侧监听并自行履约。
 * 依赖方向：payment -> platform、contracts；禁止依赖任何垂直业务模块。</p>
 */
@org.springframework.modulith.ApplicationModule(displayName = "Payment", allowedDependencies = {"platform", "contracts"})

package com.wherelee.base.modules.payment;
