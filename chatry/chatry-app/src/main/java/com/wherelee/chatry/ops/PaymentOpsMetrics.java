package com.wherelee.chatry.ops;

import com.wherelee.chatry.ledger.FundLedgerService;
import com.wherelee.chatry.ledger.FundLedgerSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 支付/账本运营指标：把「需要人盯」的业务状态暴露成 Prometheus gauge。
 *
 * <p>纯读取，不改任何业务状态；每 30 秒刷新一次，失败保留上一次值。</p>
 */
@Component
public class PaymentOpsMetrics {

    private static final int STUCK_MINUTES = 10;

    private final JdbcTemplate jdbc;
    private final FundLedgerService fundLedger;

    private final AtomicReference<Double> stuckPayments = new AtomicReference<>(0.0);
    private final AtomicReference<Double> pendingRefunds = new AtomicReference<>(0.0);
    private final AtomicReference<Double> exhaustedRefunds = new AtomicReference<>(0.0);
    private final AtomicReference<Double> paidAfterClose = new AtomicReference<>(0.0);
    private final AtomicReference<Double> outboxPending = new AtomicReference<>(0.0);
    private final AtomicReference<Double> unbalanced = new AtomicReference<>(0.0);
    private final AtomicReference<Double> liabilityDiff = new AtomicReference<>(0.0);
    private final AtomicReference<Double> cashDiff = new AtomicReference<>(0.0);

    public PaymentOpsMetrics(JdbcTemplate jdbc, FundLedgerService fundLedger, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.fundLedger = fundLedger;
        gauge(registry, "chatry_payment_stuck", "PAYING 超时未结算的支付单数", stuckPayments);
        gauge(registry, "chatry_refund_pending", "处理中/待重试的退款单数", pendingRefunds);
        gauge(registry, "chatry_refund_exhausted", "重试耗尽的退款单数", exhaustedRefunds);
        gauge(registry, "chatry_paid_after_close", "关单后收到支付、待退款订单数", paidAfterClose);
        gauge(registry, "chatry_outbox_pending", "支付发件箱待投递事件数", outboxPending);
        gauge(registry, "chatry_ledger_unbalanced", "借贷不平的资金交易数", unbalanced);
        gauge(registry, "chatry_ledger_liability_diff", "额度负债 − 计量余额 差异", liabilityDiff);
        gauge(registry, "chatry_ledger_cash_diff", "清算账户 − 已付订单 差异", cashDiff);
    }

    @Scheduled(fixedDelayString = "${app.ops.metrics-interval-ms:30000}",
            initialDelayString = "${app.ops.metrics-initial-delay-ms:20000}")
    public void refresh() {
        try {
            stuckPayments.set(count("""
                    SELECT COUNT(*) FROM t_payment_order
                    WHERE status = 'PAYING' AND updated_at < TIMESTAMPADD(MINUTE, -?, CURRENT_TIMESTAMP(3))
                    """, STUCK_MINUTES));
            pendingRefunds.set(count("""
                    SELECT COUNT(*) FROM t_refund WHERE status IN ('PROCESSING', 'FAILED')
                    """));
            exhaustedRefunds.set(count("""
                    SELECT COUNT(*) FROM t_refund WHERE status = 'FAILED' AND attempt_count >= 3
                    """));
            paidAfterClose.set(count("""
                    SELECT COUNT(*) FROM t_mall_order WHERE status = 'PAID_AFTER_CLOSE'
                    """));
            outboxPending.set(count("""
                    SELECT COUNT(*) FROM t_payment_outbox WHERE status IN ('PENDING', 'SENDING')
                    """));
            FundLedgerSummary summary = fundLedger.summary(0L);
            unbalanced.set((double) summary.unbalanced());
            liabilityDiff.set(summary.liabilityDiff().abs().doubleValue());
            cashDiff.set(summary.cashDiff().abs().doubleValue());
        } catch (RuntimeException ignored) {
            // 指标刷新失败不影响业务，保留上一次值
        }
    }

    private double count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static void gauge(MeterRegistry registry, String name, String description,
                              AtomicReference<Double> value) {
        Gauge.builder(name, value, ref -> ref.get() == null ? 0.0 : ref.get())
                .description(description)
                .register(registry);
    }

    /** 单测用：直接读当前值。 */
    public BigDecimal currentLiabilityDiff() {
        return BigDecimal.valueOf(liabilityDiff.get());
    }
}
