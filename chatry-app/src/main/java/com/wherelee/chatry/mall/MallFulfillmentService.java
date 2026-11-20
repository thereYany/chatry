package com.wherelee.chatry.mall;

import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.RechargeCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    /** 幂等：充值以 bizNo=mall:orderNo 去重，重复事件不会重复加额度。 */
    public void fulfil(long tenantId, String orderNo) {
        MallOrder order = orders.findByNo(tenantId, orderNo)
                .orElseThrow(() -> new IllegalStateException("支付事件找不到订单: " + orderNo));
        BigDecimal credit = BigDecimal.valueOf(order.creditCents(), 2);
        meteringAdmin.recharge(new RechargeCommand(
                new SubjectRef(tenantId, 0L, order.userId()), credit, "CNY", "mall:" + orderNo));
        orders.markPaid(tenantId, orderNo, Instant.now());
        log.info("mall.order.paid tenantId={} orderNo={} userId={} credit={}",
                tenantId, orderNo, order.userId(), credit);
    }
}
