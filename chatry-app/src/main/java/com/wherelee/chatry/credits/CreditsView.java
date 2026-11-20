package com.wherelee.chatry.credits;

import java.math.BigDecimal;

/**
 * 额度页视图。
 *
 * @param balance          可用余额（CNY）
 * @param frozen           预扣冻结中（对话进行中会短暂非零）
 * @param currency         币种
 * @param initialGrant     新手赠送额度
 * @param estimatedChats   按默认模型单价估算还能对话的次数
 */
public record CreditsView(BigDecimal balance, BigDecimal frozen, String currency,
                          BigDecimal initialGrant, long estimatedChats) {
}
