package com.wherelee.chatry.mall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 未支付订单关单任务：超时后关单并回补库存，避免秒杀库存被白占。 */
@Component
public class MallOrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(MallOrderExpiryJob.class);

    private final MallService mall;

    public MallOrderExpiryJob(MallService mall) {
        this.mall = mall;
    }

    @Scheduled(fixedDelayString = "${chatry.mall.expiry-poll-ms:30000}", initialDelayString = "15000")
    public void closeExpired() {
        try {
            mall.closeExpiredOrders();
        } catch (RuntimeException e) {
            log.error("mall.order.expiry_failed", e);
        }
    }
}
