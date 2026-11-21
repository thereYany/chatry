package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.payment.PaymentOperations;
import com.wherelee.base.modules.payment.PaymentConfirmationService;
import com.wherelee.base.modules.payment.PaymentNotification;
import com.wherelee.base.modules.payment.PaymentQueryService;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.outbox.PaymentOutboxRepository;
import com.wherelee.base.modules.payment.recon.ReconciliationService;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付全链路集成测试（真实 MySQL）：下单 → 回调 → 发件箱 → 主动查单 → 关单 → 对账。
 *
 * <p>覆盖业务不变式：支付单/尝试幂等；回调验签后状态推进且金额不一致绝不置为已支付；
 * 重复回调幂等；发件箱与支付状态同事务且归属支付单租户；查单兜底可结算/关单；
 * 对账逐笔比对并落差异、失败不自动补偿。</p>
 */
class PaymentFlowIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PaymentQueryService paymentQueryService;
    @Autowired
    private ReconciliationService reconciliationService;

    private static final String BIZ_TYPE = "IT_ORDER";
    private static final long AMOUNT = 9900L;

    private String initiate(long tenantId, String bizNo) {
        PaymentOperations.InitiateResult result = asTenant(tenantId,
                () -> paymentOperations.initiate(new PaymentOperations.InitiateCommand(
                        null, BIZ_TYPE, bizNo, AMOUNT, "CNY", "集成测试商品",
                        LocalDateTime.now().plusMinutes(15))));
        assertThat(result.paymentNo()).isNotBlank();
        assertThat(result.status()).isEqualTo("PAYING");
        return result.paymentNo();
    }

    private PaymentConfirmationService.ConfirmationResult deliverCallback(long tenantId, String paymentNo,
                                                                          String eventKey, long amount) {
        String bizNo = asSystem(() -> jdbc.queryForObject(
                "SELECT biz_no FROM t_payment_order WHERE payment_no = ? AND tenant_id = ?",
                String.class, paymentNo, tenantId));
        PaymentNotification signed = signedNotification(eventKey, bizNo, "PAYMENT_SUCCESS",
                amount, "TRADE-" + paymentNo);
        // 模拟公开回调线程：系统作用域无登录租户
        return TenantContext.runAsSystem(() -> paymentConfirmationService.accept(signed, paymentNo));
    }

    @Test
    void 下单幂等_回调查单与重复回调全链路() {
        long tenant = newTenant();
        String bizNo = unique("BIZ");
        String paymentNo = initiate(tenant, bizNo);

        // 幂等：再次发起返回同一支付单，不新增尝试
        String again = initiate(tenant, bizNo);
        assertThat(again).isEqualTo(paymentNo);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_attempt WHERE payment_no = ?", paymentNo))
                .isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_order WHERE payment_no = ? AND tenant_id = ?",
                paymentNo, tenant)).isEqualTo(1);

        // 回调：支付成功
        PaymentConfirmationService.ConfirmationResult confirmed =
                deliverCallback(tenant, paymentNo, "EVT-" + paymentNo, AMOUNT);
        assertThat(confirmed.success()).isTrue();
        assertThat(confirmed.status()).isEqualTo("PAID");

        assertThat(countRows("SELECT COUNT(*) FROM t_payment_order WHERE payment_no = ? AND status = 'PAID'",
                paymentNo)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_event WHERE payment_no = ? "
                + "AND process_status = 'PROCESSED'", paymentNo)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ? "
                + "AND event_type = 'PAYMENT_SUCCEEDED' AND tenant_id = ?", paymentNo, tenant)).isEqualTo(1);

        // 重复回调：幂等，不重复写事件/发件箱
        PaymentConfirmationService.ConfirmationResult duplicate =
                deliverCallback(tenant, paymentNo, "EVT-" + paymentNo, AMOUNT);
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_event WHERE payment_no = ?", paymentNo))
                .isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ?", paymentNo))
                .isEqualTo(1);

        // 发件箱投递：标记 SENT
        int delivered = paymentOutboxDispatcher.dispatchOnce();
        assertThat(delivered).isGreaterThanOrEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ? AND status = 'SENT'",
                paymentNo)).isEqualTo(1);
    }

    @Test
    void 金额不一致回调拒绝置为已支付并告警归属支付单租户() {
        long tenant = newTenant();
        String bizNo = unique("BIZ");
        String paymentNo = initiate(tenant, bizNo);

        String bizFromDb = queryOne(
                "SELECT biz_no FROM t_payment_order WHERE payment_no = ?", String.class, paymentNo);
        PaymentNotification signed = signedNotification("EVT-MISMATCH-" + paymentNo, bizFromDb,
                "PAYMENT_SUCCESS", AMOUNT - 1, "TRADE-" + paymentNo);
        PaymentConfirmationService.ConfirmationResult result = TenantContext.runAsSystem(
                () -> paymentConfirmationService.accept(signed, paymentNo));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("金额不一致");
        // 绝不推进为已支付
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_order WHERE payment_no = ? AND status = 'PAID'",
                paymentNo)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_event WHERE payment_no = ? "
                + "AND process_status = 'FAILED'", paymentNo)).isEqualTo(1);
        // 告警必须归属支付单自己的租户（系统作用域不能写租户 0）
        assertThat(countRows("SELECT COUNT(*) FROM t_alert_log WHERE alert_type = 'PAYMENT_AMOUNT_MISMATCH' "
                + "AND tenant_id = ?", tenant)).isEqualTo(1);
    }

    @Test
    void 未知支付单回调被拒绝并告警() {
        long tenant = newTenant();
        String bizNo = unique("UNKNOWN");
        PaymentNotification signed = signedNotification("EVT-UNKNOWN-" + System.nanoTime(), bizNo,
                "PAYMENT_SUCCESS", 1000, "TRADE-X");
        PaymentConfirmationService.ConfirmationResult result = TenantContext.runAsSystem(
                () -> paymentConfirmationService.accept(signed));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(countRows("SELECT COUNT(*) FROM t_alert_log WHERE alert_type = 'PAYMENT_UNKNOWN_ORDER'"))
                .isGreaterThanOrEqualTo(1);
        // 清理全局告警（job/告警测试可能复用）
        asSystem(() -> jdbc.update("DELETE FROM t_alert_log WHERE alert_type = 'PAYMENT_UNKNOWN_ORDER'"));
        cleanupTenant(tenant);
    }

    @Test
    void 主动查单兜底_渠道已支付时完成结算且幂等() {
        long tenant = newTenant();
        String bizNo = unique("QUERY");
        String paymentNo = initiate(tenant, bizNo);
        // 模拟渠道回报已支付（支付尝试的 provider_status 是渠道事实；MockPaymentGateway
        // 原样返回 provider_status，因此这里用网关约定的 PAID 值驱动查单兜底）
        asSystem(() -> jdbc.update("""
                UPDATE t_payment_attempt SET provider_status = 'PAID', provider_amount_cents = ?
                WHERE payment_no = ?
                """, AMOUNT, paymentNo));

        boolean advanced = TenantContext.runAsSystem(
                () -> paymentQueryService.queryByPaymentNo(paymentNo));
        assertThat(advanced).isTrue();
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_order WHERE payment_no = ? AND status = 'PAID'",
                paymentNo)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ? "
                + "AND tenant_id = ?", paymentNo, tenant)).isEqualTo(1);

        // 再查一次：不得重复写事件/发件箱（查询本身会返回 true，见报告观察项）
        TenantContext.runAsSystem(() -> paymentQueryService.queryByPaymentNo(paymentNo));
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_event WHERE payment_no = ?", paymentNo))
                .isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ?", paymentNo))
                .isEqualTo(1);
    }

    @Test
    void 主动查单_渠道回报SUCCESS应识别为已支付() {
        long tenant = newTenant();
        String bizNo = unique("SUCCESS-MAP");
        String paymentNo = initiate(tenant, bizNo);
        asSystem(() -> jdbc.update("""
                UPDATE t_payment_attempt SET provider_status = 'SUCCESS', provider_amount_cents = ?
                WHERE payment_no = ?
                """, AMOUNT, paymentNo));

        boolean advanced = TenantContext.runAsSystem(
                () -> paymentQueryService.queryByPaymentNo(paymentNo));
        assertThat(advanced).isTrue();
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_order WHERE payment_no = ? AND status = 'PAID'",
                paymentNo)).isEqualTo(1);
    }

    @Test
    void 主动查单兜底_渠道无单且已过期时先关单再发过期事件_租户归属正确() {
        long tenant = newTenant();
        String bizNo = unique("EXPIRE");
        String paymentNo = initiate(tenant, bizNo);
        // 过期 + 渠道明确失败（FAILED 表示渠道侧交易已关闭）
        asSystem(() -> jdbc.update(
                "UPDATE t_payment_order SET expire_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE) "
                        + "WHERE payment_no = ?", paymentNo));
        asSystem(() -> jdbc.update(
                "UPDATE t_payment_attempt SET provider_status = 'FAILED' WHERE payment_no = ?", paymentNo));

        boolean advanced = TenantContext.runAsSystem(
                () -> paymentQueryService.queryByPaymentNo(paymentNo));
        assertThat(advanced).isTrue();
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_order WHERE payment_no = ? AND status = 'CLOSED'",
                paymentNo)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ? "
                + "AND event_type = 'PAYMENT_EXPIRED' AND tenant_id = ?", paymentNo, tenant)).isEqualTo(1);
    }

    @Test
    void 资金对账_全部匹配无差异_金额不一致落差异且可人工处理() {
        // 对账是平台级校验：先清空支付数据，构造确定性的账单/本地支付
        wipePaymentTables();
        long tenant = newTenant();
        LocalDate today = LocalDate.now();

        String matchedBiz = unique("RECON-OK");
        String matchedPaymentNo = initiate(tenant, matchedBiz);
        asSystem(() -> jdbc.update("""
                UPDATE t_payment_attempt SET provider_status = 'SUCCESS', provider_amount_cents = ?
                WHERE payment_no = ?
                """, AMOUNT, matchedPaymentNo));
        asSystem(() -> {
            authenticatePaid(matchedPaymentNo);
            return null;
        });

        // 对账是平台级任务：生产由 job 在系统作用域执行，测试同样显式进入系统作用域
        var clean = asSystem(() -> reconciliationService.run("MOCK", today));
        assertThat(clean.totalCount()).isEqualTo(1);
        assertThat(clean.matchedCount()).isEqualTo(1);
        assertThat(clean.diffCount()).isZero();

        // 构造金额不一致：本地支付单金额与渠道账单金额不同
        String diffBiz = unique("RECON-DIFF");
        String diffPaymentNo = initiate(tenant, diffBiz);
        asSystem(() -> jdbc.update("""
                UPDATE t_payment_attempt SET provider_status = 'SUCCESS', provider_amount_cents = ?
                WHERE payment_no = ?
                """, AMOUNT - 100, diffPaymentNo));
        asSystem(() -> {
            authenticatePaid(diffPaymentNo);
            return null;
        });

        var report = asSystem(() -> reconciliationService.run("MOCK", today));
        assertThat(report.diffCount()).isGreaterThanOrEqualTo(1);
        assertThat(report.diffByType()).containsKey("AMOUNT");

        // 差异可查询并人工处理（处理状态只允许 HANDLED/IGNORED）
        var diffs = reconciliationService.pageDiffs("OPEN", 1, 50);
        assertThat(diffs.records()).isNotEmpty();
        long diffId = diffs.records().get(0).id();
        reconciliationService.handle(diffId, 1L, "HANDLED");
        assertThat(reconciliationService.pageDiffs("HANDLED", 1, 50).total()).isGreaterThanOrEqualTo(1);
        assertThat(reconciliationService.pageDiffs("OPEN", 1, 50).records())
                .noneMatch(diff -> diff.id() == diffId);
    }


    @Test
    void 关闭支付单收到成功回调时拒绝且不写发件箱() {
        long tenant = newTenant();
        String bizNo = unique("CLOSED");
        String paymentNo = initiate(tenant, bizNo);
        jdbc.update("UPDATE t_payment_order SET status = 'CLOSED' WHERE payment_no = ? AND tenant_id = ?",
                paymentNo, tenant);
        int outboxBefore = countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ?", paymentNo);

        PaymentConfirmationService.ConfirmationResult result = deliverCallback(
                tenant, paymentNo, "EVT-CLOSED-" + paymentNo, AMOUNT);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("CLOSED");
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_order WHERE payment_no = ? AND status = 'CLOSED'",
                paymentNo)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ?", paymentNo))
                .isEqualTo(outboxBefore);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_event WHERE payment_no = ? "
                + "AND process_status = 'FAILED'", paymentNo)).isEqualTo(1);
    }

    /** 绕过渠道，直接把支付单标记为 PAID（模拟历史数据/渠道对账场景）。 */
    private void authenticatePaid(String paymentNo) {
        // 渠道账单数据源读取支付尝试的 status/provider 事实：必须同步置为成功
        jdbc.update("""
                UPDATE t_payment_attempt
                SET status = 'SUCCESS', provider_status = 'PAID', finished_at = CURRENT_TIMESTAMP(3)
                WHERE payment_no = ?
                """, paymentNo);
        // 支付单的渠道流水号与「渠道账单」（支付尝试里的渠道事实）必须一致，否则对账会判 LOCAL_ONLY
        jdbc.update("""
                UPDATE t_payment_order o
                SET status = 'PAID', paid_at = CURRENT_TIMESTAMP(3),
                    provider_trade_no = (SELECT a.provider_trade_no FROM t_payment_attempt a
                                         WHERE a.payment_no = o.payment_no
                                         ORDER BY a.attempt_no DESC LIMIT 1)
                WHERE o.payment_no = ?
                """, paymentNo);
    }

    @Test
    void 发件箱事件载荷含业务单号与金额_供业务侧履约() {
        long tenant = newTenant();
        String bizNo = unique("EVENT");
        String paymentNo = initiate(tenant, bizNo);
        deliverCallback(tenant, paymentNo, "EVT-EVENT-" + paymentNo, AMOUNT);

        String payload = queryOne(
                "SELECT payload FROM t_payment_outbox WHERE aggregate_no = ?", String.class, paymentNo);
        PaymentSettledEvent event = objectMapper.readValue(payload, PaymentSettledEvent.class);
        assertThat(event.eventType()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(event.paymentNo()).isEqualTo(paymentNo);
        assertThat(event.bizType()).isEqualTo(BIZ_TYPE);
        assertThat(event.bizNo()).isEqualTo(bizNo);
        assertThat(event.amountCents()).isEqualTo(AMOUNT);
        assertThat(event.currency()).isEqualTo("CNY");
    }

    @Test
    void 发件箱投递失败按退避重试_超过上限进死信() {
        wipePaymentTables();
        long tenant = newTenant();
        var repo = paymentOutboxRepository;
        asSystem(() -> {
            repo.append(new PaymentSettledEvent("PAYMENT_SUCCEEDED", "P-DEAD", BIZ_TYPE, "BIZ-DEAD",
                    100, "CNY", "MOCK", null, LocalDateTime.now()));
            // 手工把 attempts 推到上限前
            jdbc.update("UPDATE t_payment_outbox SET attempts = 2 WHERE aggregate_no = 'P-DEAD'");
            return null;
        });
        var dispatcher = new com.wherelee.base.modules.payment.outbox.PaymentOutboxDispatcher(
                repo, List.of(event -> {
                    throw new IllegalStateException("履约失败");
                }), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                10, 3, 5, 60);
        int delivered = TenantContext.runAsSystem(dispatcher::dispatchOnce);
        assertThat(delivered).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = 'P-DEAD' "
                + "AND status = 'DEAD'")).isEqualTo(1);
        asSystem(() -> jdbc.update("DELETE FROM t_payment_outbox WHERE aggregate_no = 'P-DEAD'"));
    }

    @Test
    void 发件箱重试未达上限时回到PENDING并退避() {
        wipePaymentTables();
        long tenant = newTenant();
        var repo = paymentOutboxRepository;
        asSystem(() -> {
            repo.append(new PaymentSettledEvent("PAYMENT_SUCCEEDED", "P-RETRY", BIZ_TYPE, "BIZ-RETRY",
                    100, "CNY", "MOCK", null, LocalDateTime.now()));
            return null;
        });
        var dispatcher = new com.wherelee.base.modules.payment.outbox.PaymentOutboxDispatcher(
                repo, List.of(event -> {
                    throw new IllegalStateException("履约失败");
                }), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                10, 5, 5, 60);
        TenantContext.runAsSystem(dispatcher::dispatchOnce);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = 'P-RETRY' "
                + "AND status = 'PENDING' AND attempts = 1 AND next_at > CURRENT_TIMESTAMP(3)")).isEqualTo(1);
        asSystem(() -> jdbc.update("DELETE FROM t_payment_outbox WHERE aggregate_no = 'P-RETRY'"));
    }

    @Test
    void 已支付支付单再次发起返回终态不新建尝试() {
        long tenant = newTenant();
        String bizNo = unique("PAID");
        String paymentNo = initiate(tenant, bizNo);
        deliverCallback(tenant, paymentNo, "EVT-PAID-" + paymentNo, AMOUNT);

        PaymentOperations.InitiateResult result = asTenant(tenant,
                () -> paymentOperations.initiate(new PaymentOperations.InitiateCommand(
                        null, BIZ_TYPE, bizNo, AMOUNT, "CNY", "集成测试商品",
                        LocalDateTime.now().plusMinutes(15))));
        assertThat(result.status()).isEqualTo("PAID");
        assertThat(result.cashierUrl()).isNull();
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_attempt WHERE payment_no = ?", paymentNo))
                .isEqualTo(1);
    }
}
