package com.wherelee.base.modules.contracts.metering;

import java.math.BigDecimal;

/**
 * 额度调整（bizNo 幂等）。
 *
 * @param subject         账户主体
 * @param amount          调整金额，正数加、负数减
 * @param currency        币种
 * @param bizNo           幂等键
 * @param allowNegative   余额不足时是否允许扣到 0（退款扣回场景为 true）
 * @param bizType         账本业务类型（如 REFUND_DEDUCT / MANUAL_ADJUST）
 */
public record AdjustCommand(SubjectRef subject, BigDecimal amount, String currency, String bizNo,
                            boolean allowNegative, String bizType) {
}
