/**
 * 通用计量计费模块：用量事件、计量规则、价格版本、预扣/结算、账本、账单与对账。
 *
 * <p>业务无关：只认 metric/quantity/unit/price；token 只是其中一种单位。
 * 依赖方向：metering -> platform、contracts；不依赖 llm/identity/payment 实现。</p>
 */
@org.springframework.modulith.ApplicationModule(displayName = "Metering", allowedDependencies = {"platform", "contracts"})
package com.wherelee.base.modules.metering;
