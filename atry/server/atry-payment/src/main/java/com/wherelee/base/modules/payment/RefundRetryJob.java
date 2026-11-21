package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 退款失败退避重试（系统作用域跨租户扫描），超过上限留在 FAILED 并触发告警。 */
@Component
@ConditionalOnProperty(name = "app.payment.refund-retry-enabled", havingValue = "true",
        matchIfMissing = true)
public class RefundRetryJob {

    private static final Logger log = LoggerFactory.getLogger(RefundRetryJob.class);

    private final RefundService refundService;

    public RefundRetryJob(RefundService refundService) {
        this.refundService = refundService;
    }

    @Scheduled(fixedDelayString = "${app.payment.refund-retry-interval-ms:30000}",
            initialDelayString = "${app.payment.refund-retry-initial-delay-ms:20000}")
    public void run() {
        int retried = TenantContext.runAsSystem(refundService::retryDue);
        if (retried > 0) {
            log.info("payment.refund.retry retried={}", retried);
        }
    }
}
