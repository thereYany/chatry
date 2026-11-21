package com.wherelee.chatry.mall;

import com.wherelee.base.modules.payment.api.PaymentEventTypes;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.api.PaymentSettledListener;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 支付成功 → 额度履约。
 *
 * <p>投递语义 at-least-once，因此充值用业务单号幂等（bizNo = mall:orderNo），
 * 重复事件不会重复加额度。</p>
 */
@Component
public class MallFulfillmentListener implements PaymentSettledListener {

    private static final Logger log = LoggerFactory.getLogger(MallFulfillmentListener.class);

    private final MallFulfillmentService fulfilment;

    public MallFulfillmentListener(MallFulfillmentService fulfilment) {
        this.fulfilment = fulfilment;
    }

    @Override
    public void onSettled(PaymentSettledEvent event) {
        if (!MallService.BIZ_TYPE.equals(event.bizType())
                || !PaymentEventTypes.PAYMENT_SUCCEEDED.equals(event.eventType())) {
            return;
        }
        long tenantId = TenantContext.current();
        log.info("mall.fulfil.event tenantId={} orderNo={} amountCents={}",
                tenantId, event.bizNo(), event.amountCents());
        fulfilment.fulfil(tenantId, event.bizNo());
    }
}
