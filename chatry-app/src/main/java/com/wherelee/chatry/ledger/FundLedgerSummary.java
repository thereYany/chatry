package com.wherelee.chatry.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 资金账本汇总（管理端展示/日终对账快照）。 */
public record FundLedgerSummary(
        BigDecimal liability,
        BigDecimal metering,
        BigDecimal clearing,
        BigDecimal paidOrders,
        BigDecimal liabilityDiff,
        BigDecimal cashDiff,
        int unbalanced,
        LocalDate checkDate,
        List<FundLedgerRepository.TransactionView> recent
) {
}
