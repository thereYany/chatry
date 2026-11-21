package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.platform.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 支付主动查单任务（结算兜底）。
 *
 * <p>有公网回调域名时，回调是主链路，本任务只是补偿；没有域名/HTTPS 回调时（例如按量计费、
 * 未备案的测试服务器），把 {@code app.payment.query.enabled} 保持默认 true，本任务会定时轮询
 * 渠道状态并完成结算，无需对外暴露 notify 入口。</p>
 *
 * <p>垂直应用如果已有自己的查单调度（例如把它挂在业务自己的 job 里），可以设置
 * {@code app.payment.query.enabled=false} 避免重复扫描。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payment.query.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentQueryJob {

    private final PaymentQueryService queryService;
    private final int batchHint;

    public PaymentQueryJob(PaymentQueryService queryService,
                           @Value("${app.payment.query.batch-size:20}") int batchHint) {
        this.queryService = queryService;
        this.batchHint = Math.max(1, batchHint);
        log.info("支付主动查单任务已启用 batchSize={}", this.batchHint);
    }

    @Scheduled(
            fixedDelayString = "${app.payment.query.interval-ms:30000}",
            initialDelayString = "${app.payment.query.initial-delay-ms:15000}")
    public void run() {
        try {
            // 主动查单扫描的是全租户 PAYING 单，必须在系统作用域内执行。
            int handled = TenantContext.runAsSystem(queryService::queryPending);
            if (handled > 0) {
                log.info("支付主动查单完成 handled={}", handled);
            }
        } catch (Exception e) {
            log.error("支付主动查单任务异常", e);
        }
    }
}
