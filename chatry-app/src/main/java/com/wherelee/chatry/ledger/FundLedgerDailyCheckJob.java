package com.wherelee.chatry.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 日终资金对账快照：给运营看趋势，差异仍以实时 summary 为准。 */
@Component
public class FundLedgerDailyCheckJob {

    private static final Logger log = LoggerFactory.getLogger(FundLedgerDailyCheckJob.class);

    private final FundLedgerService service;

    public FundLedgerDailyCheckJob(FundLedgerService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.fund-ledger.check-cron:0 30 2 * * *}")
    public void run() {
        try {
            for (Long tenantId : service.tenantIds()) {
                FundLedgerSummary summary = service.check(tenantId);
                log.info("fund.ledger.daily_check tenantId={} liabilityDiff={} cashDiff={} unbalanced={}",
                        tenantId, summary.liabilityDiff(), summary.cashDiff(), summary.unbalanced());
            }
        } catch (RuntimeException e) {
            log.warn("资金账本日终对账失败：{}", e.getClass().getSimpleName());
        }
    }
}
