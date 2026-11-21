package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.InvoiceCommand;
import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.ReleaseCommand;
import com.wherelee.base.modules.contracts.metering.ReserveCommand;
import com.wherelee.base.modules.contracts.metering.SettleCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import com.wherelee.base.modules.contracts.metering.UsageQuery;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 计量计费全链路集成测试（真实 MySQL）：充值 → 用量计价 → 预扣/结算/释放 → 账本 → 账单 → 对账。
 *
 * <p>覆盖业务不变式：充值 bizNo 幂等；用量事件幂等且按发生时间取价格版本（含 model 维度覆盖价）；
 * 金额 6 位小数 CEIL；预扣以 balance-frozen 准入；结算按实际金额扣减并写不可变流水；
 * 账平（charge 合计 = 结算流水合计、账户余额 = 账本派生）时对账无差异，人为破坏后必须暴露差异。</p>
 */
class MeteringIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MeteringOperations metering;
    @Autowired
    private MeteringAdminOperations meteringAdmin;

    private long tenant;
    private SubjectRef subject;

    @BeforeEach
    void prepare() {
        wipeMeteringTables();
        tenant = newTenant();
        subject = new SubjectRef(tenant, 0, 0);
    }

    private static UsageCommand usage(String eventId, String meter, String quantity,
                                      Map<String, String> dimensions) {
        return new UsageCommand(eventId, meter, null, new BigDecimal(quantity), "token",
                dimensions, Instant.now(), "it");
    }

    private void recordUsage(String eventId, String meter, String quantity, SubjectRef target,
                             Map<String, String> dimensions) {
        metering.recordUsage(new UsageCommand(eventId, meter, target, new BigDecimal(quantity),
                "token", dimensions, Instant.now(), "it"));
    }

    private static ReserveCommand reserve(String reservationId, SubjectRef subject, String amount) {
        return new ReserveCommand(reservationId, subject, new BigDecimal(amount), "CNY",
                Instant.now().plusSeconds(120), "it-biz");
    }

    @Test
    void 充值幂等_重复bizNo不重复入账() {
        BalanceView first = meteringAdmin.recharge(new com.wherelee.base.modules.contracts.metering
                .RechargeCommand(subject, new BigDecimal("10.000000"), "CNY", "IT-RECHARGE-1"));
        BalanceView second = meteringAdmin.recharge(new com.wherelee.base.modules.contracts.metering
                .RechargeCommand(subject, new BigDecimal("10.000000"), "CNY", "IT-RECHARGE-1"));

        assertThat(first.balance()).isEqualByComparingTo("10.000000");
        assertThat(second.balance()).isEqualByComparingTo("10.000000");
        assertThat(countRows("SELECT COUNT(*) FROM t_metering_ledger WHERE biz_no = 'IT-RECHARGE-1'"))
                .isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_metering_ledger WHERE biz_type = 'RECHARGE'"))
                .isEqualTo(1);
    }

    @Test
    void 用量事件幂等_重复eventId只计价一次() {
        recordUsage("IT-EVENT-1", "llm.input_tokens", "500000", subject, Map.of());
        recordUsage("IT-EVENT-1", "llm.input_tokens", "500000", subject, Map.of());

        assertThat(countRows("SELECT COUNT(*) FROM t_metering_usage_event WHERE event_id = 'IT-EVENT-1'"))
                .isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_metering_charge WHERE event_id = 'IT-EVENT-1'"))
                .isEqualTo(1);
        BigDecimal amount = queryOne("SELECT amount FROM t_metering_charge WHERE event_id = 'IT-EVENT-1'",
                BigDecimal.class);
        // 500000 * 0.000001 = 0.5
        assertThat(amount).isEqualByComparingTo("0.500000");
        assertThat(queryOne("SELECT status FROM t_metering_usage_event WHERE event_id = 'IT-EVENT-1'",
                String.class)).isEqualTo("RATED");
    }

    @Test
    void 按model维度命中专用价_缓存命中价更低() {
        Map<String, String> deepseek = Map.of("model", "deepseek-flash");
        recordUsage("IT-EVENT-IN", "llm.input_tokens", "1000000", subject, deepseek);
        recordUsage("IT-EVENT-OUT", "llm.output_tokens", "1000000", subject, deepseek);
        recordUsage("IT-EVENT-CACHE", "llm.cached_read_tokens", "1000000", subject, deepseek);

        assertThat(queryOne("SELECT amount FROM t_metering_charge WHERE event_id = 'IT-EVENT-IN'",
                BigDecimal.class)).isEqualByComparingTo("2.000000");
        assertThat(queryOne("SELECT amount FROM t_metering_charge WHERE event_id = 'IT-EVENT-OUT'",
                BigDecimal.class)).isEqualByComparingTo("8.000000");
        assertThat(queryOne("SELECT amount FROM t_metering_charge WHERE event_id = 'IT-EVENT-CACHE'",
                BigDecimal.class)).isEqualByComparingTo("0.040000");
    }

    @Test
    void 估价按发生时间取价格版本_早于生效时间无价格() {
        BigDecimal cost = metering.estimateCost("llm.input_tokens", new BigDecimal("1000000"),
                Instant.now(), Map.of());
        assertThat(cost).isEqualByComparingTo("1.000000");

        // 价格书 2026-01-01 生效：更早的发生时间没有生效价格
        Instant beforePriceBook = Instant.parse("2025-01-01T00:00:00Z");
        assertThatThrownBy(() -> metering.estimateCost("llm.input_tokens",
                BigDecimal.ONE, beforePriceBook, Map.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有生效价格");
    }

    @Test
    void 预扣结算释放在真实库上闭环_余额与账本一致() {
        meteringAdmin.recharge(new com.wherelee.base.modules.contracts.metering.RechargeCommand(
                subject, new BigDecimal("10.000000"), "CNY", "IT-LOOP-RECHARGE"));

        metering.reserve(reserve("IT-RES-1", subject, "3.000000"));
        BalanceView afterReserve = metering.queryBalance(subject);
        assertThat(afterReserve.balance()).isEqualByComparingTo("10.000000");
        assertThat(afterReserve.frozen()).isEqualByComparingTo("3.000000");

        metering.settle(new SettleCommand("IT-RES-1", new BigDecimal("2.000000"), "CNY", "it-biz"));
        BalanceView afterSettle = metering.queryBalance(subject);
        assertThat(afterSettle.balance()).isEqualByComparingTo("8.000000");
        assertThat(afterSettle.frozen()).isEqualByComparingTo("0.000000");
        assertThat(countRows("SELECT COUNT(*) FROM t_metering_ledger WHERE biz_no = 'settle:IT-RES-1' "
                + "AND direction = 'DEBIT' AND amount = 2.000000 AND balance_after = 8.000000")).isEqualTo(1);
        assertThat(queryOne("SELECT status FROM t_metering_reservation WHERE reservation_id = 'IT-RES-1'",
                String.class)).isEqualTo("SETTLED");

        // 结算幂等
        metering.settle(new SettleCommand("IT-RES-1", new BigDecimal("2.000000"), "CNY", "it-biz"));
        assertThat(countRows("SELECT COUNT(*) FROM t_metering_ledger WHERE biz_no = 'settle:IT-RES-1'"))
                .isEqualTo(1);

        // 释放
        metering.reserve(reserve("IT-RES-2", subject, "1.000000"));
        metering.release(new ReleaseCommand("IT-RES-2", "测试"));
        BalanceView afterRelease = metering.queryBalance(subject);
        assertThat(afterRelease.frozen()).isEqualByComparingTo("0.000000");
        assertThat(afterRelease.balance()).isEqualByComparingTo("8.000000");
        assertThat(queryOne("SELECT status FROM t_metering_reservation WHERE reservation_id = 'IT-RES-2'",
                String.class)).isEqualTo("RELEASED");

        // 账本派生余额 = 账户余额
        assertThat(meteringBillingStore.findBalanceMismatches()).isEmpty();
    }

    @Test
    void 过期预扣由回收任务解冻并标记EXPIRED() throws Exception {
        meteringAdmin.recharge(new com.wherelee.base.modules.contracts.metering.RechargeCommand(
                subject, new BigDecimal("10.000000"), "CNY", "IT-EXP-RECHARGE"));
        metering.reserve(new ReserveCommand("IT-RES-EXP", subject, new BigDecimal("2.000000"),
                "CNY", Instant.now().plusMillis(100), "it-biz"));
        Thread.sleep(200);

        int expired = meteringAdmin.expireReservations(Instant.now());
        assertThat(expired).isEqualTo(1);
        assertThat(metering.queryBalance(subject).frozen()).isEqualByComparingTo("0.000000");
        assertThat(queryOne("SELECT status FROM t_metering_reservation WHERE reservation_id = 'IT-RES-EXP'",
                String.class)).isEqualTo("EXPIRED");

        // 已回收的再次回收不重复解冻
        assertThat(meteringAdmin.expireReservations(Instant.now())).isZero();
    }

    @Test
    void 用量查询按主体过滤并支持时间窗口() {
        recordUsage("IT-Q1", "llm.input_tokens", "10", subject, Map.of());
        List<com.wherelee.base.modules.contracts.metering.UsageView> views = metering.queryUsage(
                new UsageQuery(subject, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), 10));
        assertThat(views).extracting("eventId").contains("IT-Q1");
        // 不存在的自增租户查不到别的租户数据
        assertThat(metering.queryUsage(new UsageQuery(new SubjectRef(tenant + 999999, 0, 0),
                Instant.EPOCH, Instant.now(), 10))).isEmpty();
    }

    @Test
    void 账单按账期汇总且同账期幂等() {
        recordUsage("IT-INV-1", "llm.input_tokens", "1000000", subject, Map.of());
        recordUsage("IT-INV-2", "llm.output_tokens", "500000", subject, Map.of());
        // 账期边界对齐到毫秒：DB 列是 DATETIME(3)，纳秒精度会导致同账期匹配失败（缺陷见下方用例）
        Instant from = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant to = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        var invoice = meteringAdmin.generateInvoice(new InvoiceCommand(subject, from, to, "CNY"));
        assertThat(invoice).isNotNull();
        // 1.0 + 0.5 = 1.5
        assertThat(invoice.totalAmount()).isEqualByComparingTo("1.500000");
        assertThat(invoice.items()).hasSize(2);

        var again = meteringAdmin.generateInvoice(new InvoiceCommand(subject, from, to, "CNY"));
        assertThat(again.invoiceNo()).isEqualTo(invoice.invoiceNo());
        assertThat(meteringAdmin.queryInvoices(subject, 10)).hasSize(1);
    }

    @Test
    void 账单幂等_纳秒精度账期也应幂等() {
        recordUsage("IT-INV-NS-1", "llm.input_tokens", "1000000", subject, Map.of());
        Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
        var first = meteringAdmin.generateInvoice(new InvoiceCommand(subject, from, to, "CNY"));
        var second = meteringAdmin.generateInvoice(new InvoiceCommand(subject, from, to, "CNY"));
        assertThat(second.invoiceNo()).isEqualTo(first.invoiceNo());
    }

    @Test
    void 对账账平时无差异_人为破坏数据必须暴露差异() {
        meteringAdmin.recharge(new com.wherelee.base.modules.contracts.metering.RechargeCommand(
                subject, new BigDecimal("10.000000"), "CNY", "IT-RECON-RECHARGE"));
        recordUsage("IT-RECON-EVENT", "llm.input_tokens", "500000", subject, Map.of());
        metering.reserve(reserve("IT-RECON-RES", subject, "1.000000"));
        metering.settle(new SettleCommand("IT-RECON-RES", new BigDecimal("0.500000"), "CNY", "it-biz"));

        Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
        var balanced = meteringAdmin.reconcile(from, to);
        assertThat(balanced.balanced()).isTrue();
        assertThat(balanced.checkedEvents()).isEqualTo(1);
        assertThat(balanced.checkedCharges()).isEqualTo(1);

        // 破坏 1：已计价事件没有 charge
        asSystem(() -> {
            jdbc.update("DELETE FROM t_metering_charge WHERE event_id = 'IT-RECON-EVENT'");
            jdbc.update("UPDATE t_metering_usage_event SET status = 'RATED' WHERE event_id = 'IT-RECON-EVENT'");
            return null;
        });
        // 破坏 2：账户余额与账本派生值不一致
        asSystem(() -> {
            jdbc.update("UPDATE t_metering_account SET balance = balance + 1 WHERE tenant_id = ?", tenant);
            return null;
        });

        var report = meteringAdmin.reconcile(from, to);
        assertThat(report.diffs()).extracting("type")
                .contains("EVENT_WITHOUT_CHARGE", "BALANCE_MISMATCH");
        assertThat(report.balanced()).isFalse();
    }
}
