package com.wherelee.chatry.mall;

import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.RechargeCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付成功后的履约：额度入账 + 订单置为已支付。
 *
 * <p>单独成 bean 是为了断开依赖环：商城服务 → 发件箱 → 监听器 → 履约服务，
 * 履约服务只依赖订单仓储与计量，不再反向依赖商城服务。</p>
 */
@Service
public class MallFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(MallFulfillmentService.class);

    private final MallOrderRepository orders;
    private final MeteringAdminOperations meteringAdmin;

    public MallFulfillmentService(MallOrderRepository orders, MeteringAdminOperations meteringAdmin) {
        this.orders = orders;
        this.meteringAdmin = meteringAdmin;
    }

    /**
     * 幂等：充值以 bizNo=mall:orderNo 去重，重复事件不会重复加额度。
     *
     * <p>订单状态 CAS 与充值在同一事务：先 CAS 到 PAID 再入账；如果订单已关闭，
     * 不充值、标记 PAID_AFTER_CLOSE 交给退款/人工处理。CAS 失败而当前已是 PAID
     * 说明另一个投递已完成，直接按幂等返回。</p>
     */
    @Transactional
    public void fulfil(long tenantId, String orderNo) {
        MallOrder order = orders.findByNo(tenantId, orderNo)
                .orElseThrow(() -> new IllegalStateException("支付事件找不到订单: " + orderNo));
        if (MallOrder.STATUS_PAID.equals(order.status())
                || MallOrder.STATUS_PAID_AFTER_CLOSE.equals(order.status())) {
            return;
        }
        if (MallOrder.STATUS_CLOSED.equals(order.status())) {
            markPaidAfterClose(tenantId, orderNo);
            return;
        }
        int updated = orders.markPaid(tenantId, orderNo, Instant.now());
        if (updated == 0) {
            MallOrder current = orders.findByNo(tenantId, orderNo)
                    .orElseThrow(() -> new IllegalStateException("支付事件找不到订单: " + orderNo));
            if (MallOrder.STATUS_PAID.equals(current.status())
                    || MallOrder.STATUS_PAID_AFTER_CLOSE.equals(current.status())) {
                return;
            }
            if (MallOrder.STATUS_CLOSED.equals(current.status())) {
                markPaidAfterClose(tenantId, orderNo);
                return;
            }
            throw new IllegalStateException("订单状态不允许履约: " + current.status());
        }
        BigDecimal credit = BigDecimal.valueOf(order.creditCents(), 2);
        meteringAdmin.recharge(new RechargeCommand(
                new SubjectRef(tenantId, 0L, order.userId()), credit, "CNY", "mall:" + orderNo));
        log.info("mall.order.paid tenantId={} orderNo={} userId={} credit={}",
                tenantId, orderNo, order.userId(), credit);
    }

    private void markPaidAfterClose(long tenantId, String orderNo) {
        int marked = orders.markPaidAfterClose(tenantId, orderNo, Instant.now());
        if (marked > 0) {
            log.warn("mall.order.paid_after_close tenantId={} orderNo={}（不充值，待退款/人工处理）",
                    tenantId, orderNo);
        }
    }
}
