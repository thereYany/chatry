package com.wherelee.base.modules.contracts.metering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 通用计量计费契约：业务/框架只通过它上报用量、预扣、结算与查询。
 *
 * <p>实现负责：事件幂等、计量/计价、额度与余额、账本与对账；
 * 不暴露任何垂直业务概念。金额/数量使用 BigDecimal，单位由 meter 定义。</p>
 */
public interface MeteringOperations {

    /** 上报用量（幂等键 eventId；重复上报不重复入账）。 */
    void recordUsage(UsageCommand command);

    /** 按发生时间取价格版本估算金额（用于预扣/预算检查）。 */
    BigDecimal estimateCost(String meterKey, BigDecimal quantity, Instant occurredAt);

    /** 带维度（如 model）的价格匹配；rate card 的 subject_selector 可覆盖默认价。 */
    BigDecimal estimateCost(String meterKey, BigDecimal quantity, Instant occurredAt,
                            Map<String, String> dimensions);

    /** 预扣（准入）：余额/额度不足时抛出业务异常。 */
    ReservationView reserve(ReserveCommand command);

    /** 按实际金额结算预扣。 */
    void settle(SettleCommand command);

    /** 释放预扣（失败/未使用）。 */
    void release(ReleaseCommand command);

    BalanceView queryBalance(SubjectRef subject);

    List<UsageView> queryUsage(UsageQuery query);
}
