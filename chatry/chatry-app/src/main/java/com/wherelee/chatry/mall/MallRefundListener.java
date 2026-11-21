package com.wherelee.chatry.mall;

import com.wherelee.base.modules.payment.api.PaymentEventTypes;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.api.PaymentSettledListener;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 退款结果 → 售后逆向。
 *
 * <p>投递语义 at-least-once：扣额度走 metering 的 bizNo 幂等、订单更新按退款单号覆盖，
 * 重复事件不会重复扣减。</p>
 */
@Component
public class MallRefundListener implements PaymentSettledListener {

    private static final Logger log = LoggerFactory.getLogger(MallRefundListener.class);

    private final MallRefundService refunds;

    public MallRefundListener(MallRefundService refunds) {
        this.refunds = refunds;
    }

    @Override
    public void onSettled(PaymentSettledEvent event) {
        if (!MallService.BIZ_TYPE.equals(event.bizType())) {
            return;
        }
        long tenantId = TenantContext.current();
        if (PaymentEventTypes.REFUND_SUCCEEDED.equals(event.eventType())) {
            log.info("mall.refund.event tenantId={} orderNo={} amountCents={}",
                    tenantId, event.bizNo(), event.amountCents());
            String refundNo = event.providerTradeNo() == null ? event.bizNo() : event.providerTradeNo();
            refunds.onRefundSucceeded(tenantId, event.bizNo(), event.amountCents(), refundNo);
        }
    }
}
