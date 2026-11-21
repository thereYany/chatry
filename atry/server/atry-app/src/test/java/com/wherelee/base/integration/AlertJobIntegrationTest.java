package com.wherelee.base.integration;

import com.wherelee.base.app.alert.AlertNotifier;
import com.wherelee.base.app.alert.AlertProperties;
import com.wherelee.base.app.alert.PaymentAlertJob;
import com.wherelee.base.modules.contracts.payment.PaymentAlertQueries;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.platform.alert.AlertPublisher;
import com.wherelee.base.modules.platform.resilience.LocalFallbackCache;
import com.wherelee.base.modules.contracts.payment.PaymentOperations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付异常巡检 job 集成测试（真实 MySQL）。
 *
 * <p>覆盖业务不变式：已支付但发件箱未投递、PAYING 卡单、退款重试耗尽三类异常都会被巡检发现；
 * 同类型告警在去重窗口内只发一次；巡检跨租户统计（平台级任务）。</p>
 */
class AlertJobIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PaymentAlertQueries paymentAlertQueries;
    @Autowired
    private AlertNotifier alertNotifier;
    @Autowired
    private AlertPublisher alertPublisher;

    @AfterEach
    void cleanAlerts() {
        asSystem(() -> {
            jdbc.update("DELETE FROM t_alert_log WHERE alert_type IN "
                    + "('PAYMENT_SETTLEMENT_UNDELIVERED','PAYMENT_PAYING_STUCK',"
                    + "'PAYMENT_REFUND_RETRY_EXHAUSTED','PAYMENT_AMOUNT_MISMATCH','PAYMENT_UNKNOWN_ORDER')");
            return null;
        });
    }

    private PaymentAlertJob job(int paidUnfulfilledMinutes, int payingStuckMinutes,
                                int refundThreshold, int dedupMinutes) {
        AlertProperties properties = new AlertProperties(true, null, dedupMinutes,
                300000, 60000, paidUnfulfilledMinutes, payingStuckMinutes, refundThreshold);
        return new PaymentAlertJob(properties, paymentAlertQueries, alertNotifier,
                alertPublisher, new LocalFallbackCache());
    }

    private String initiate(long tenant, String bizNo) {
        return asTenant(tenant, () -> paymentOperations.initiate(
                new PaymentOperations.InitiateCommand(null, "ALERT_ORDER", bizNo, 1000, "CNY",
                        "告警测试", LocalDateTime.now().plusMinutes(15)))).paymentNo();
    }

    private void markPaid(long tenant, String paymentNo) {
        String bizNo = queryOne("SELECT biz_no FROM t_payment_order WHERE payment_no = ?",
                String.class, paymentNo);
        var notification = signedNotification("ALERT-EVT-" + paymentNo, bizNo,
                "PAYMENT_SUCCESS", 1000, "TRADE-" + paymentNo);
        asSystem(() -> paymentConfirmationService.accept(notification, paymentNo));
    }

    @Test
    void 已支付但发件箱未投递的支付单触发告警且去重窗口内只发一次() {
        long tenant = newTenant();
        String paymentNo = initiate(tenant, unique("ALERT-PAID"));
        markPaid(tenant, paymentNo);
        // 制造「已支付超过阈值但发件箱仍未投递」
        asSystem(() -> {
            jdbc.update("UPDATE t_payment_order SET paid_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 5 MINUTE) "
                    + "WHERE payment_no = ?", paymentNo);
            return null;
        });

        PaymentAlertJob alertJob = job(1, 15, 1, 30);
        alertJob.evaluate();
        assertThat(countRows("SELECT COUNT(*) FROM t_alert_log "
                + "WHERE alert_type = 'PAYMENT_SETTLEMENT_UNDELIVERED'")).isEqualTo(1);
        // 告警内容说明未投递笔数，且 delivered=0（未配置 webhook）
        assertThat(queryOne("SELECT CONCAT(delivered, '|', message) FROM t_alert_log "
                + "WHERE alert_type = 'PAYMENT_SETTLEMENT_UNDELIVERED'", String.class))
                .startsWith("0|").contains("未投递");

        // 去重窗口内重复巡检不再重复告警
        alertJob.evaluate();
        assertThat(countRows("SELECT COUNT(*) FROM t_alert_log "
                + "WHERE alert_type = 'PAYMENT_SETTLEMENT_UNDELIVERED'")).isEqualTo(1);
    }

    @Test
    void 长时间PAYING的支付单触发卡单告警() {
        long tenant = newTenant();
        String paymentNo = initiate(tenant, unique("ALERT-PAYING"));
        asSystem(() -> {
            jdbc.update("UPDATE t_payment_order SET updated_at = DATE_SUB(CURRENT_TIMESTAMP(3), "
                    + "INTERVAL 30 MINUTE) WHERE payment_no = ?", paymentNo);
            return null;
        });

        job(60, 15, 5, 30).evaluate();
        assertThat(countRows("SELECT COUNT(*) FROM t_alert_log "
                + "WHERE alert_type = 'PAYMENT_PAYING_STUCK'")).isEqualTo(1);
    }

    @Test
    void 退款重试耗尽触发告警() {
        long tenant = newTenant();
        asSystem(() -> {
            jdbc.update("""
                    INSERT INTO t_refund (tenant_id, refund_no, order_id, amount_cents, status,
                                          attempt_count, reason, requested_by)
                    VALUES (?, ?, NULL, 100, 'FAILED', 3, '测试退款', 1)
                    """, tenant, "ALERT-REFUND-" + System.nanoTime());
            return null;
        });

        // 告警阈值语义是「数量 >= 阈值」，这里构造 1 笔重试耗尽 + 阈值 1
        job(60, 15, 1, 30).evaluate();
        assertThat(countRows("SELECT COUNT(*) FROM t_alert_log "
                + "WHERE alert_type = 'PAYMENT_REFUND_RETRY_EXHAUSTED'")).isEqualTo(1);
    }

    @Test
    void 巡检跨租户统计_两个租户异常都计入同一告警() {
        long tenantA = newTenant();
        long tenantB = newTenant();
        String paymentA = initiate(tenantA, unique("ALERT-A"));
        String paymentB = initiate(tenantB, unique("ALERT-B"));
        markPaid(tenantA, paymentA);
        markPaid(tenantB, paymentB);
        asSystem(() -> {
            jdbc.update("UPDATE t_payment_order SET paid_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 5 MINUTE) "
                    + "WHERE payment_no IN (?, ?)", paymentA, paymentB);
            return null;
        });

        job(1, 15, 1, 30).evaluate();
        String message = queryOne("SELECT message FROM t_alert_log "
                + "WHERE alert_type = 'PAYMENT_SETTLEMENT_UNDELIVERED'", String.class);
        assertThat(message).contains("2 笔");
    }

    @Test
    void 无异常时不产生告警() {
        newTenant();
        job(1, 15, 1, 30).evaluate();
        assertThat(countRows("SELECT COUNT(*) FROM t_alert_log "
                + "WHERE alert_type LIKE 'PAYMENT_%'")).isZero();
    }

    @Test
    void 发件箱投递成功后同一支付单不再告警() {
        long tenant = newTenant();
        String paymentNo = initiate(tenant, unique("ALERT-SENT"));
        markPaid(tenant, paymentNo);
        asSystem(() -> {
            jdbc.update("UPDATE t_payment_order SET paid_at = DATE_SUB(CURRENT_TIMESTAMP(3), "
                    + "INTERVAL 5 MINUTE) WHERE payment_no = ?", paymentNo);
            // 标记发件箱已投递
            jdbc.update("UPDATE t_payment_outbox SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(3) "
                    + "WHERE aggregate_no = ?", paymentNo);
            return null;
        });

        job(1, 15, 1, 30).evaluate();
        assertThat(countRows("SELECT COUNT(*) FROM t_alert_log "
                + "WHERE alert_type = 'PAYMENT_SETTLEMENT_UNDELIVERED'")).isZero();
    }

    @Test
    void 告警事件落库包含事件类型与载荷_供外部告警消费() {
        long tenant = newTenant();
        String paymentNo = initiate(tenant, unique("ALERT-EVENT"));
        markPaid(tenant, paymentNo);
        asSystem(() -> {
            jdbc.update("UPDATE t_payment_order SET paid_at = DATE_SUB(CURRENT_TIMESTAMP(3), "
                    + "INTERVAL 5 MINUTE) WHERE payment_no = ?", paymentNo);
            return null;
        });
        job(1, 15, 1, 30).evaluate();
        List<String> rows = asSystem(() -> jdbc.queryForList(
                "SELECT CONCAT(severity, '|', delivered) FROM t_alert_log "
                        + "WHERE alert_type = 'PAYMENT_SETTLEMENT_UNDELIVERED'", String.class));
        assertThat(rows).containsExactly("HIGH|0");
    }
}
