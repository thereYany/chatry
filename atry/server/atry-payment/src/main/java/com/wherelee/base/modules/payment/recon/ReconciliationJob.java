package com.wherelee.base.modules.payment.recon;

import com.wherelee.base.modules.platform.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * 每日资金对账巡检：默认对昨天的账单跑一次。
 *
 * <p>对账失败不自动补偿、不自动改单，只落差异并告警——资金问题必须有人看。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.recon.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private final ReconciliationService service;
    private final String channels;

    public ReconciliationJob(ReconciliationService service,
                             @Value("${app.recon.channels:MOCK}") String channels) {
        this.service = service;
        this.channels = channels;
    }

    @Scheduled(cron = "${app.recon.cron:0 30 3 * * *}")
    public void dailyReconcile() {
        // 对账也是平台级任务：账单核对必须覆盖全部租户，不能只看默认租户 0。
        TenantContext.runAsSystem(this::reconcileAll);
    }

    private void reconcileAll() {
        LocalDate billDate = LocalDate.now().minusDays(1);
        Arrays.stream(channels.split(","))
                .map(String::trim)
                .filter(channel -> !channel.isEmpty())
                .forEach(channel -> {
                    try {
                        service.run(channel, billDate);
                    } catch (Exception e) {
                        log.error("对账任务失败 channel={} billDate={}", channel, billDate, e);
                    }
                });
    }
}
