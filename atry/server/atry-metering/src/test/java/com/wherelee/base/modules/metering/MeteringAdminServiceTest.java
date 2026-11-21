package com.wherelee.base.modules.metering;

import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.InvoiceCommand;
import com.wherelee.base.modules.contracts.metering.InvoiceView;
import com.wherelee.base.modules.contracts.metering.RechargeCommand;
import com.wherelee.base.modules.contracts.metering.ReconciliationReport;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.metering.store.MeteringBillingStore;
import com.wherelee.base.modules.metering.store.MeteringStore;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计量运营服务测试：充值幂等、账单、对账、过期预扣回收。
 *
 * <p>业务不变式：充值以 bizNo 幂等（重复调用不重复入账）；账单同账期幂等、无计价结果返回 null；
 * 对账必须暴露事件/Charge/流水/余额四类不一致；过期预扣回收解冻并标记 EXPIRED。</p>
 */
class MeteringAdminServiceTest {

    private MeteringStore store;
    private MeteringBillingStore billingStore;
    private MeteringAdminService service;

    private static final SubjectRef SUBJECT = new SubjectRef(1L, 0L, 2L);

    @BeforeEach
    void setUp() {
        store = mock(MeteringStore.class);
        billingStore = mock(MeteringBillingStore.class);
        service = new MeteringAdminService(store, billingStore);
    }

    private static MeteringStore.AccountRow account(long id, String balance, String frozen) {
        return new MeteringStore.AccountRow(id, new BigDecimal(balance), new BigDecimal(frozen),
                "CNY", "ACTIVE");
    }

    // ---------- 充值 ----------

    @Test
    void 充值参数校验() {
        assertThatThrownBy(() -> service.recharge(null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.recharge(new RechargeCommand(null, BigDecimal.ONE, "CNY", "b")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.recharge(new RechargeCommand(SUBJECT, BigDecimal.ZERO, "CNY", "b")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.recharge(new RechargeCommand(SUBJECT, BigDecimal.ONE, " ", "b")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.recharge(new RechargeCommand(SUBJECT, BigDecimal.ONE, "CNY", " ")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 充值以bizNo幂等_重复调用不再入账() {
        when(billingStore.ledgerExists("BIZ-1")).thenReturn(true);
        when(store.findAccount(SUBJECT, "CNY", false))
                .thenReturn(Optional.of(account(5L, "8.000000", "0")));

        BalanceView view = service.recharge(new RechargeCommand(SUBJECT,
                new BigDecimal("10"), "CNY", "BIZ-1"));
        assertThat(view.balance()).isEqualByComparingTo("8.000000");
        verify(billingStore, never()).addBalance(anyLong(), any());
        verify(store, never()).insertLedger(anyLong(), anyString(), any(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void 首次充值自动开户并入账() {
        when(billingStore.ledgerExists("BIZ-2")).thenReturn(false);
        when(store.findAccount(SUBJECT, "CNY", true)).thenReturn(Optional.empty());
        when(store.insertAccount(SUBJECT, "CNY")).thenReturn(9L);

        BalanceView view = service.recharge(new RechargeCommand(SUBJECT,
                new BigDecimal("10.000000"), "CNY", "BIZ-2"));

        assertThat(view.balance()).isEqualByComparingTo("10.000000");
        verify(billingStore).addBalance(9L, new BigDecimal("10.000000"));
        verify(store).insertLedger(9L, "CREDIT", new BigDecimal("10.000000"), "CNY",
                "RECHARGE", "BIZ-2", new BigDecimal("10.000000"));
    }

    @Test
    void 已有账户充值累加余额并写流水() {
        when(billingStore.ledgerExists("BIZ-3")).thenReturn(false);
        when(store.findAccount(SUBJECT, "CNY", true)).thenReturn(Optional.of(account(5L, "5", "2")));
        BalanceView view = service.recharge(new RechargeCommand(SUBJECT,
                new BigDecimal("3"), "CNY", "BIZ-3"));
        // 余额不动冻结额，balance_after 为新余额
        assertThat(view.balance()).isEqualByComparingTo("8");
        assertThat(view.frozen()).isEqualByComparingTo("2");
        verify(store).insertLedger(5L, "CREDIT", new BigDecimal("3"), "CNY",
                "RECHARGE", "BIZ-3", new BigDecimal("8"));
    }

    // ---------- 账单 ----------

    private static Instant from() {
        return Instant.now().minus(1, ChronoUnit.DAYS);
    }

    private static Instant to() {
        return Instant.now();
    }

    @Test
    void 账单参数校验() {
        assertThatThrownBy(() -> service.generateInvoice(null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.generateInvoice(new InvoiceCommand(null, from(), to(), "CNY")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.generateInvoice(new InvoiceCommand(SUBJECT, from(), to(), " ")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.generateInvoice(new InvoiceCommand(SUBJECT, to(), from(), "CNY")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.generateInvoice(new InvoiceCommand(SUBJECT, null, to(), "CNY")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 同账期已出账时直接返回已有账单() {
        when(billingStore.findInvoiceByPeriod(eq(SUBJECT), eq("CNY"), any(), any()))
                .thenReturn(Optional.of(new MeteringBillingStore.InvoiceRow(3L, "INV-OLD",
                        SUBJECT, from(), to(), "CNY", new BigDecimal("1.5"), "FINALIZED")));
        when(billingStore.queryInvoiceItems(3L)).thenReturn(List.of());

        InvoiceView invoice = service.generateInvoice(new InvoiceCommand(SUBJECT, from(), to(), "CNY"));
        assertThat(invoice.invoiceNo()).isEqualTo("INV-OLD");
        assertThat(invoice.totalAmount()).isEqualByComparingTo("1.5");
        verify(billingStore, never()).insertInvoice(anyString(), any(), any(), any(), anyString(), any());
    }

    @Test
    void 无计价结果时不出账() {
        when(billingStore.findInvoiceByPeriod(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(billingStore.findCharges(any(), any(), any(), anyString())).thenReturn(List.of());
        assertThat(service.generateInvoice(new InvoiceCommand(SUBJECT, from(), to(), "CNY"))).isNull();
    }

    @Test
    void 出账按charge汇总并生成明细() {
        when(billingStore.findInvoiceByPeriod(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(billingStore.findCharges(any(), any(), any(), anyString())).thenReturn(List.of(
                new MeteringBillingStore.ChargeRow(1L, "E1", "llm.input_tokens",
                        new BigDecimal("10"), new BigDecimal("0.000010"), "CNY"),
                new MeteringBillingStore.ChargeRow(2L, "E2", "llm.output_tokens",
                        new BigDecimal("5"), new BigDecimal("0.000005"), "CNY")));
        when(billingStore.insertInvoice(anyString(), eq(SUBJECT), any(), any(), eq("CNY"),
                eq(new BigDecimal("0.000015")))).thenReturn(8L);

        InvoiceView invoice = service.generateInvoice(new InvoiceCommand(SUBJECT, from(), to(), "CNY"));

        assertThat(invoice.invoiceNo()).startsWith("INV-");
        assertThat(invoice.totalAmount()).isEqualByComparingTo("0.000015");
        assertThat(invoice.items()).hasSize(2);
        verify(billingStore).insertInvoiceItem(8L, 1L, "E1", "llm.input_tokens",
                new BigDecimal("10"), new BigDecimal("0.000010"), "CNY");
        verify(billingStore).insertInvoiceItem(8L, 2L, "E2", "llm.output_tokens",
                new BigDecimal("5"), new BigDecimal("0.000005"), "CNY");
    }

    @Test
    void 查询账单校验主体() {
        assertThatThrownBy(() -> service.queryInvoices(null, 10)).isInstanceOf(BizException.class);
    }

    // ---------- 对账 ----------

    @Test
    void 对账时间范围非法拒绝() {
        assertThatThrownBy(() -> service.reconcile(null, to())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.reconcile(from(), null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.reconcile(to(), from())).isInstanceOf(BizException.class);
        Instant same = Instant.now();
        assertThatThrownBy(() -> service.reconcile(same, same)).isInstanceOf(BizException.class);
    }

    @Test
    void 账平时无差异并落对账任务() {
        stubBalanced("1.000000", "1.000000");
        ReconciliationReport report = service.reconcile(from(), to());

        assertThat(report.balanced()).isTrue();
        assertThat(report.checkedEvents()).isEqualTo(2);
        assertThat(report.checkedCharges()).isEqualTo(2);
        verify(billingStore).insertReconRun(any(), any(), eq(true), eq(2), eq(2), eq(0));
        verify(billingStore, never()).insertReconDiff(anyLong(), anyString(), any(), any(), any(), any());
    }

    @Test
    void 金额差异在微元容差内不算差异_超出必须报出() {
        stubBalanced("1.000001", "1.000000");
        assertThat(service.reconcile(from(), to()).balanced()).isTrue();

        stubBalanced("1.000002", "1.000000");
        ReconciliationReport report = service.reconcile(from(), to());
        assertThat(report.balanced()).isFalse();
        assertThat(report.diffs()).extracting("type").contains("AMOUNT_MISMATCH");
    }

    @Test
    void 事件缺Charge_Charge缺事件_余额与账本不一致都报差异() {
        when(billingStore.countRatedEvents(any(), any())).thenReturn(3);
        when(billingStore.countCharges(any(), any())).thenReturn(3);
        when(billingStore.eventsWithoutCharge(any(), any(), anyInt())).thenReturn(List.of("E-MISSING"));
        when(billingStore.chargesWithoutEvent(any(), any(), anyInt())).thenReturn(List.of("E-GHOST"));
        when(billingStore.sumCharges(any(), any())).thenReturn(new BigDecimal("1"));
        when(billingStore.sumSettleLedger(any(), any())).thenReturn(new BigDecimal("1"));
        when(billingStore.findBalanceMismatches()).thenReturn(List.of(
                new MeteringBillingStore.BalanceRow(9L, new BigDecimal("10"), new BigDecimal("8"))));

        ReconciliationReport report = service.reconcile(from(), to());

        assertThat(report.diffs()).extracting("type").contains(
                "EVENT_WITHOUT_CHARGE", "CHARGE_WITHOUT_EVENT", "BALANCE_MISMATCH");
        verify(billingStore).insertReconRun(any(), any(), eq(false), eq(3), eq(3), eq(3));
    }

    private void stubBalanced(String chargeSum, String ledgerSum) {
        when(billingStore.countRatedEvents(any(), any())).thenReturn(2);
        when(billingStore.countCharges(any(), any())).thenReturn(2);
        when(billingStore.eventsWithoutCharge(any(), any(), anyInt())).thenReturn(List.of());
        when(billingStore.chargesWithoutEvent(any(), any(), anyInt())).thenReturn(List.of());
        when(billingStore.sumCharges(any(), any())).thenReturn(new BigDecimal(chargeSum));
        when(billingStore.sumSettleLedger(any(), any())).thenReturn(new BigDecimal(ledgerSum));
        when(billingStore.findBalanceMismatches()).thenReturn(List.of());
    }

    // ---------- 过期回收 ----------

    @Test
    void 过期预扣回收解冻并标记EXPIRED() {
        when(billingStore.findExpiredPendingReservations(any())).thenReturn(List.of(
                new MeteringBillingStore.ExpiringReservation(1L, "RES-1", 5L,
                        new BigDecimal("1.000000")),
                new MeteringBillingStore.ExpiringReservation(2L, "RES-2", 6L,
                        new BigDecimal("2.000000"))));

        int expired = service.expireReservations(Instant.now());

        assertThat(expired).isEqualTo(2);
        verify(store).releaseAccount(5L, new BigDecimal("1.000000"));
        verify(store).releaseAccount(6L, new BigDecimal("2.000000"));
        verify(billingStore).markReservationExpired("RES-1");
        verify(billingStore).markReservationExpired("RES-2");
    }

    @Test
    void 没有过期预扣时返回0() {
        when(billingStore.findExpiredPendingReservations(any())).thenReturn(List.of());
        assertThat(service.expireReservations(Instant.now())).isZero();
        verify(store, never()).releaseAccount(anyLong(), any());
    }
}
