package com.wherelee.base.modules.contracts.metering;

import java.time.Instant;
import java.util.List;

/** 计量计费运营/运维契约：充值、出账、对账、过期预扣回收。 */
public interface MeteringAdminOperations {

    /** 充值/调整入账（bizNo 幂等），返回最新余额。 */
    BalanceView recharge(RechargeCommand command);

    /**
     * 额度调整（可为负，bizNo 幂等）：退款扣回、人工冲正、活动补发都用它。
     *
     * <p>余额不足时按「扣到 0 为止」还是「拒绝」由入参决定；退款场景必须显式允许部分扣减，
     * 否则已消费额度会让退款卡死。</p>
     */
    BalanceView adjust(AdjustCommand command);

    /** 按账期生成账单（无计价结果时返回 null）。 */
    InvoiceView generateInvoice(InvoiceCommand command);

    List<InvoiceView> queryInvoices(SubjectRef subject, int limit);

    /** L1–L2 对账：用量事件 vs 计价结果 vs 结算流水。 */
    ReconciliationReport reconcile(Instant from, Instant to);

    /** 回收已过期预扣（解冻），返回处理条数。 */
    int expireReservations(Instant now);
}
