package com.wherelee.chatry.mall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 关单后支付（PAID_AFTER_CLOSE）自动退款：钱已收到、额度未发，按实付全额退回。
 *
 * <p>这是 P6 留下的处置入口：延迟到 P8 才具备退款能力。</p>
 */
@Component
public class PaidAfterCloseRefundJob {

    private static final Logger log = LoggerFactory.getLogger(PaidAfterCloseRefundJob.class);

    private final MallRefundService refunds;

    public PaidAfterCloseRefundJob(MallRefundService refunds) {
        this.refunds = refunds;
    }

    @Scheduled(fixedDelayString = "${app.mall.auto-refund-interval-ms:60000}",
            initialDelayString = "${app.mall.auto-refund-initial-delay-ms:45000}")
    public void run() {
        List<MallOrder> pending = refunds.pendingAfterCloseRefunds(0L, 20);
        for (MallOrder order : pending) {
            try {
                refunds.requestRefund(0L, order.orderNo(), order.amountCents(),
                        "关单后收到支付，自动全额退款", 0L);
                log.info("mall.refund.auto_requested orderNo={} amountCents={}",
                        order.orderNo(), order.amountCents());
            } catch (RuntimeException e) {
                log.warn("关单后自动退款失败 orderNo={}", order.orderNo(), e);
            }
        }
    }
}
