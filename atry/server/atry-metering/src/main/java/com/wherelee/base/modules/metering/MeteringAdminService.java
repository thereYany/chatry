package com.wherelee.base.modules.metering;

import com.wherelee.base.modules.contracts.metering.AdjustCommand;
import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.InvoiceCommand;
import com.wherelee.base.modules.contracts.metering.InvoiceItemView;
import com.wherelee.base.modules.contracts.metering.InvoiceView;
import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.ReconDiff;
import com.wherelee.base.modules.contracts.metering.ReconciliationReport;
import com.wherelee.base.modules.contracts.metering.RechargeCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.metering.store.MeteringBillingStore;
import com.wherelee.base.modules.metering.store.MeteringStore;
import com.wherelee.base.modules.platform.common.BizException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** P4 运营侧：充值、出账、对账、过期预扣回收。 */
@Service
public class MeteringAdminService implements MeteringAdminOperations {

    private final MeteringStore store;
    private final MeteringBillingStore billingStore;

    public MeteringAdminService(MeteringStore store, MeteringBillingStore billingStore) {
        this.store = store;
        this.billingStore = billingStore;
    }

    @Override
    @Transactional
    public BalanceView recharge(RechargeCommand command) {
        if (command == null || command.subject() == null || command.amount() == null
                || command.amount().signum() <= 0 || command.currency() == null || command.currency().isBlank()
                || command.bizNo() == null || command.bizNo().isBlank()) {
            throw BizException.badRequest("充值参数不完整");
        }
        if (billingStore.ledgerExists(command.bizNo())) {
            return balanceOf(command.subject(), command.currency());
        }
        MeteringStore.AccountRow account = store.findAccount(command.subject(), command.currency(), true)
                .orElseGet(() -> {
                    long id = store.insertAccount(command.subject(), command.currency());
                    return new MeteringStore.AccountRow(id, BigDecimal.ZERO, BigDecimal.ZERO,
                            command.currency(), "ACTIVE");
                });
        billingStore.addBalance(account.id(), command.amount());
        BigDecimal balanceAfter = account.balance().add(command.amount());
        store.insertLedger(account.id(), "CREDIT", command.amount(), command.currency(),
                "RECHARGE", command.bizNo(), balanceAfter);
        return new BalanceView(command.subject(), balanceAfter, account.frozen(), command.currency());
    }

    @Override
    @Transactional
    public BalanceView adjust(AdjustCommand command) {
        if (command == null || command.subject() == null || command.amount() == null
                || command.amount().signum() == 0 || command.currency() == null
                || command.currency().isBlank() || command.bizNo() == null || command.bizNo().isBlank()) {
            throw BizException.badRequest("额度调整参数不完整");
        }
        SubjectRef subject = command.subject();
        if (billingStore.ledgerExists(command.bizNo())) {
            return balanceOf(subject, command.currency());
        }
        MeteringStore.AccountRow account = store.findAccount(subject, command.currency(), true)
                .orElseThrow(() -> new BizException(HttpStatus.NOT_FOUND, "账户不存在"));
        BigDecimal delta = command.amount();
        BigDecimal applied = delta;
        if (delta.signum() < 0) {
            BigDecimal available = account.balance().subtract(account.frozen());
            BigDecimal required = delta.negate();
            if (required.compareTo(available) > 0) {
                if (!command.allowNegative()) {
                    throw new BizException(HttpStatus.PAYMENT_REQUIRED, "可用额度不足以扣减");
                }
                applied = available.negate();
            }
        }
        if (applied.signum() == 0) {
            return new BalanceView(subject, account.balance(), account.frozen(), command.currency());
        }
        BigDecimal balanceAfter = account.balance().add(applied);
        billingStore.addBalance(account.id(), applied);
        String bizType = command.bizType() == null || command.bizType().isBlank()
                ? "ADJUST" : command.bizType().trim();
        store.insertLedger(account.id(), applied.signum() > 0 ? "CREDIT" : "DEBIT",
                applied.abs(), command.currency(), bizType, command.bizNo(), balanceAfter);
        return new BalanceView(subject, balanceAfter, account.frozen(), command.currency());
    }

    @Override
    @Transactional
    public InvoiceView generateInvoice(InvoiceCommand command) {
        validateInvoice(command);
        // 账期列是 DATETIME(3)：先对齐到毫秒再做幂等匹配与落库，
        // 否则调用方传入纳秒精度 Instant 时第二次生成匹配不到已有账单（触发唯一键异常/重复出账）。
        Instant from = command.from().truncatedTo(ChronoUnit.MILLIS);
        Instant to = command.to().truncatedTo(ChronoUnit.MILLIS);
        if (!from.isBefore(to)) {
            throw new BizException(HttpStatus.BAD_REQUEST, "账单账期非法");
        }
        var existing = billingStore.findInvoiceByPeriod(command.subject(), command.currency(), from, to);
        if (existing.isPresent()) {
            return toView(existing.get());
        }
        List<MeteringBillingStore.ChargeRow> charges = billingStore.findCharges(command.subject(),
                from, to, command.currency());
        if (charges.isEmpty()) {
            return null;
        }
        BigDecimal total = charges.stream().map(MeteringBillingStore.ChargeRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String invoiceNo = "INV-" + Instant.now().toEpochMilli() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        long invoiceId = billingStore.insertInvoice(invoiceNo, command.subject(), from,
                to, command.currency(), total);
        for (MeteringBillingStore.ChargeRow charge : charges) {
            billingStore.insertInvoiceItem(invoiceId, charge.chargeId(), charge.eventId(),
                    charge.meterKey(), charge.quantity(), charge.amount(), charge.currency());
        }
        return new InvoiceView(invoiceNo, command.subject(), command.from(), command.to(),
                command.currency(), total, "FINALIZED",
                charges.stream().map(c -> new InvoiceItemView(c.eventId(), c.meterKey(), c.quantity(), c.amount()))
                        .toList());
    }

    @Override
    public List<InvoiceView> queryInvoices(SubjectRef subject, int limit) {
        if (subject == null) {
            throw BizException.badRequest("subject 不能为空");
        }
        return billingStore.queryInvoices(subject, limit).stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public ReconciliationReport reconcile(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw BizException.badRequest("对账时间范围非法");
        }
        List<ReconDiff> diffs = new ArrayList<>();
        int events = billingStore.countRatedEvents(from, to);
        int charges = billingStore.countCharges(from, to);
        billingStore.eventsWithoutCharge(from, to, 100).forEach(eventId ->
                diffs.add(new ReconDiff("EVENT_WITHOUT_CHARGE", eventId, "1", "0", "已计价事件无对应 Charge")));
        billingStore.chargesWithoutEvent(from, to, 100).forEach(eventId ->
                diffs.add(new ReconDiff("CHARGE_WITHOUT_EVENT", eventId, "1", "0", "Charge 无对应用量事件")));
        BigDecimal chargeSum = billingStore.sumCharges(from, to);
        BigDecimal ledgerSum = billingStore.sumSettleLedger(from, to);
        if (chargeSum.subtract(ledgerSum).abs().compareTo(new BigDecimal("0.000001")) > 0) {
            diffs.add(new ReconDiff("AMOUNT_MISMATCH", "period",
                    chargeSum.toPlainString(), ledgerSum.toPlainString(), "计价总额与结算流水总额不一致"));
        }
        billingStore.findBalanceMismatches().forEach(row ->
                diffs.add(new ReconDiff("BALANCE_MISMATCH", Long.toString(row.accountId()),
                        row.derived().toPlainString(), row.balance().toPlainString(), "余额与账本派生值不一致")));
        long runId = billingStore.insertReconRun(from, to, diffs.isEmpty(), events, charges, diffs.size());
        for (ReconDiff diff : diffs) {
            billingStore.insertReconDiff(runId, diff.type(), diff.ref(), diff.expected(), diff.actual(), diff.detail());
        }
        return new ReconciliationReport(from, to, events, charges, diffs);
    }

    @Override
    @Transactional
    public int expireReservations(Instant now) {
        int expired = 0;
        for (MeteringBillingStore.ExpiringReservation reservation
                : billingStore.findExpiredPendingReservations(now)) {
            store.releaseAccount(reservation.accountId(), reservation.amount());
            billingStore.markReservationExpired(reservation.reservationId());
            expired++;
        }
        return expired;
    }

    private BalanceView balanceOf(SubjectRef subject, String currency) {
        return store.findAccount(subject, currency, false)
                .map(account -> new BalanceView(subject, account.balance(), account.frozen(), account.currency()))
                .orElse(new BalanceView(subject, BigDecimal.ZERO, BigDecimal.ZERO, currency));
    }

    private InvoiceView toView(MeteringBillingStore.InvoiceRow row) {
        List<InvoiceItemView> items = billingStore.queryInvoiceItems(row.id());
        return new InvoiceView(row.invoiceNo(), row.subject(), row.from(), row.to(), row.currency(),
                row.totalAmount(), row.status(), items);
    }

    private void validateInvoice(InvoiceCommand command) {
        if (command == null || command.subject() == null || command.currency() == null
                || command.currency().isBlank() || command.from() == null || command.to() == null
                || !command.from().isBefore(command.to())) {
            throw new BizException(HttpStatus.BAD_REQUEST, "账单参数非法");
        }
    }
}
