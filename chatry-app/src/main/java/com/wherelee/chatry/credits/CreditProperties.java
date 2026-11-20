package com.wherelee.chatry.credits;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 额度（余额）配置。
 *
 * <p>v1 的「额度」直接复用 atry 计量账户余额：购买/赠送 = 幂等充值，消耗 = 按 token 实际成本扣减。
 * 不另造一套点数账本，避免两套账、两套对账。</p>
 *
 * @param initialGrant 新用户首次对话时自动赠送的额度（CNY）
 * @param currency     币种
 * @param estimateInputTokens  估算「一次对话」用的输入 token（用于额度 → 可用次数展示）
 * @param estimateOutputTokens 估算「一次对话」用的输出 token
 */
@ConfigurationProperties(prefix = "chatry.credits")
public record CreditProperties(
        BigDecimal initialGrant,
        String currency,
        Integer estimateInputTokens,
        Integer estimateOutputTokens
) {

    public CreditProperties {
        initialGrant = initialGrant == null
                ? new BigDecimal("5.000000") : initialGrant.setScale(6, RoundingMode.HALF_UP);
        currency = currency == null || currency.isBlank() ? "CNY" : currency.trim();
        estimateInputTokens = estimateInputTokens == null || estimateInputTokens <= 0 ? 400 : estimateInputTokens;
        estimateOutputTokens = estimateOutputTokens == null || estimateOutputTokens <= 0 ? 600 : estimateOutputTokens;
    }
}
