package com.wherelee.chatry.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时做一次期初回填；之后新交易正常记账。 */
@Component
public class FundLedgerBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FundLedgerBackfillRunner.class);

    private final FundLedgerService service;

    public FundLedgerBackfillRunner(FundLedgerService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            service.backfillOpening();
        } catch (RuntimeException e) {
            log.error("资金账本期初回填失败，将在下次启动重试", e);
        }
    }
}
