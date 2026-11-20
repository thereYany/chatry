package com.wherelee.chatry.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 用量分录补偿：对话结算后 ChatService 会即时补记；这里扫 metering SETTLE 流水，
 * 兜底进程崩溃/账本瞬时失败。只处理期初回填之后的流水，避免历史重复。
 */
@Component
public class FundLedgerUsageJob {

    private static final Logger log = LoggerFactory.getLogger(FundLedgerUsageJob.class);

    private final FundLedgerService service;

    public FundLedgerUsageJob(FundLedgerService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.fund-ledger.usage-sweep-ms:30000}",
            initialDelayString = "${app.fund-ledger.usage-sweep-initial-delay-ms:30000}")
    public void sweep() {
        LocalDateTime since = service.openingAt();
        if (since == null) {
            return;
        }
        int posted = 0;
        try {
            for (FundLedgerRepository.UsageRow row : service.pendingUsage(since, 100)) {
                if (service.postUsage(row.tenantId(), row.userId(), row.amount(), row.bizNo(),
                        row.currency())) {
                    posted++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("资金账本用量补偿失败", e);
        }
        if (posted > 0) {
            log.info("fund.ledger.usage_sweep posted={}", posted);
        }
    }
}
