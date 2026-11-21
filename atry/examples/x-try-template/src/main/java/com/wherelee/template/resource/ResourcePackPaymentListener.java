package com.wherelee.template.resource;

import com.wherelee.base.modules.payment.api.PaymentEventTypes;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.api.PaymentSettledListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 资料包垂直域的履约：消费支付成功事件，幂等发放授权。 */
@Slf4j
@Component
public class ResourcePackPaymentListener implements PaymentSettledListener {

    private final JdbcTemplate jdbc;

    public ResourcePackPaymentListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void onSettled(PaymentSettledEvent event) {
        if (!"RESOURCE_PACK".equals(event.bizType())
                || !PaymentEventTypes.PAYMENT_SUCCEEDED.equals(event.eventType())) {
            return;
        }
        Long packId = jdbc.query("SELECT pack_id FROM t_resource_pack_order WHERE order_no = ? AND tenant_id = 0",
                (rs, rowNum) -> rs.getLong("pack_id"), event.bizNo()).stream().findFirst().orElse(null);
        if (packId == null) {
            throw new IllegalStateException("资料包订单不存在: " + event.bizNo());
        }
        jdbc.update("UPDATE t_resource_pack_order SET status = 'PAID', paid_at = CURRENT_TIMESTAMP(3) "
                + "WHERE order_no = ? AND tenant_id = 0 AND status <> 'PAID'", event.bizNo());
        jdbc.update("""
                INSERT IGNORE INTO t_resource_pack_grant (tenant_id, user_id, pack_id, order_no)
                SELECT 0, user_id, pack_id, order_no FROM t_resource_pack_order
                WHERE order_no = ? AND tenant_id = 0
                """, event.bizNo());
        log.info("resource pack fulfilled bizNo={} packId={}", event.bizNo(), packId);
    }
}
