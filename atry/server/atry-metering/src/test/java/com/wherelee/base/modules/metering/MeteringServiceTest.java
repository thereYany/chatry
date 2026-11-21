package com.wherelee.base.modules.metering;

import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.ReleaseCommand;
import com.wherelee.base.modules.contracts.metering.ReserveCommand;
import com.wherelee.base.modules.contracts.metering.SettleCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import com.wherelee.base.modules.contracts.metering.UsageQuery;
import com.wherelee.base.modules.metering.store.MeteringStore;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计量核心测试（MeteringService，仓储用 mock 隔离 SQL）。
 *
 * <p>业务不变式：</p>
 * <ul>
 *   <li>用量事件幂等（重复 eventId 不重复计价）；</li>
 *   <li>金额 = 数量 × 生效价格，6 位小数 CEIL（不因舍入漏计）；</li>
 *   <li>预扣以 balance - frozen 为可用额，恰好扣完允许、差一分拒绝；</li>
 *   <li>结算/释放只允许 PENDING，重复结算/释放幂等，非法状态拒绝；</li>
 *   <li>账本 balance_after 按实际扣减写入，余额由流水派生。</li>
 * </ul>
 */
class MeteringServiceTest {

    private MeteringStore store;
    private MeteringService service;

    private static final SubjectRef SUBJECT = new SubjectRef(1L, 0L, 2L);

    @BeforeEach
    void setUp() {
        store = mock(MeteringStore.class);
        service = new MeteringService(store);
    }

    private static UsageCommand usage(String eventId, String meter, String quantity) {
        return new UsageCommand(eventId, meter, SUBJECT, new BigDecimal(quantity), "token",
                Map.of("model", "mock-small"), Instant.now().minusSeconds(1), "R1");
    }

    private static MeteringStore.AccountRow account(String balance, String frozen, String status) {
        return new MeteringStore.AccountRow(5L, new BigDecimal(balance), new BigDecimal(frozen),
                "CNY", status);
    }

    private static MeteringStore.ReservationRow reservation(String status) {
        return new MeteringStore.ReservationRow(11L, "RES-1", 5L, new BigDecimal("1.000000"),
                "CNY", status);
    }

    // ---------- recordUsage ----------

    @Test
    void 用量上报参数校验() {
        assertThatThrownBy(() -> service.recordUsage(null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.recordUsage(usage(" ", "llm.tokens", "1")))
                .isInstanceOf(BizException.class).hasMessageContaining("eventId");
        assertThatThrownBy(() -> service.recordUsage(usage("E1", " ", "1")))
                .isInstanceOf(BizException.class).hasMessageContaining("meterKey");
        assertThatThrownBy(() -> service.recordUsage(usage("E1", "llm.tokens", "-1")))
                .isInstanceOf(BizException.class).hasMessageContaining("quantity");
        // 0 合法（用于清零/占位），不应抛错
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.recordUsage(usage("E1", "llm.tokens", "0")));
        assertThatThrownBy(() -> service.recordUsage(new UsageCommand("E1", "llm.tokens",
                null, BigDecimal.ONE, "token", Map.of(), Instant.now(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("subject");
        assertThatThrownBy(() -> service.recordUsage(new UsageCommand("E1", "llm.tokens",
                SUBJECT, BigDecimal.ONE, "token", Map.of(), null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("occurredAt");
    }

    @Test
    void 重复事件不重复计价() {
        when(store.insertUsage(any())).thenReturn(false);
        service.recordUsage(usage("E1", "llm.tokens", "10"));
        verify(store, never()).findPrice(anyString(), any(), any());
        verify(store, never()).insertCharge(any(), anyString(), any(), any(), anyString(),
                anyIntValue(), anyLong(), anyString());
    }

    @Test
    void 计价金额使用生效价格并CEIL到6位小数() {
        when(store.insertUsage(any())).thenReturn(true);
        when(store.findPrice(eq("llm.input_tokens"), any(), eq("mock-small")))
                .thenReturn(Optional.of(new MeteringStore.PriceRow(3L, new BigDecimal("0.0000004"),
                        "CNY", 2)));
        when(store.insertCharge(anyString(), anyString(), any(), any(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyLong(), anyString())).thenReturn(77L);

        service.recordUsage(usage("E1", "llm.input_tokens", "1"));

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(store).insertCharge(eq("E1"), eq("llm.input_tokens"), any(), amount.capture(),
                eq("CNY"), eq(2), eq(3L), snapshot.capture());
        // 0.0000004 CEIL 到 6 位 = 0.000001（不允许因舍入漏计）
        assertThat(amount.getValue()).isEqualByComparingTo("0.000001");
        assertThat(snapshot.getValue()).contains("\"unitPrice\":0.0000004")
                .contains("\"priceVersion\":2").contains("\"rateCardId\":3");
        verify(store).markEventRated("E1", 77L);
    }

    @Test
    void 没有生效价格时拒绝入账() {
        when(store.insertUsage(any())).thenReturn(true);
        when(store.findPrice(anyString(), any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recordUsage(usage("E1", "unknown.meter", "1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有生效价格");
        verify(store, never()).insertCharge(any(), anyString(), any(), any(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyLong(), anyString());
    }

    // ---------- estimateCost ----------

    @Test
    void 估价参数校验与价格缺失() {
        assertThatThrownBy(() -> service.estimateCost(null, BigDecimal.ONE, Instant.now()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.estimateCost("m", null, Instant.now()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.estimateCost("m", BigDecimal.ONE, null))
                .isInstanceOf(BizException.class);
        when(store.findPrice(anyString(), any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.estimateCost("m", BigDecimal.ONE, Instant.now()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有生效价格");
    }

    @Test
    void 带维度估价使用model作为定价选择器() {
        when(store.findPrice(eq("llm.output_tokens"), any(), eq("deepseek-flash")))
                .thenReturn(Optional.of(new MeteringStore.PriceRow(9L, new BigDecimal("0.000008"),
                        "CNY", 1)));
        BigDecimal cost = service.estimateCost("llm.output_tokens", new BigDecimal("1000"),
                Instant.now(), Map.of("model", "deepseek-flash"));
        assertThat(cost).isEqualByComparingTo("0.008000");
        verify(store).findPrice(eq("llm.output_tokens"), any(), eq("deepseek-flash"));
    }

    // ---------- reserve ----------

    @Test
    void 预扣参数校验() {
        assertThatThrownBy(() -> service.reserve(new ReserveCommand(" ", SUBJECT,
                BigDecimal.ONE, "CNY", Instant.now().plusSeconds(10), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("reservationId");
        assertThatThrownBy(() -> service.reserve(new ReserveCommand("R", SUBJECT,
                BigDecimal.ZERO, "CNY", Instant.now().plusSeconds(10), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("预扣金额");
        assertThatThrownBy(() -> service.reserve(new ReserveCommand("R", SUBJECT,
                BigDecimal.ONE, " ", Instant.now().plusSeconds(10), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("currency");
        assertThatThrownBy(() -> service.reserve(new ReserveCommand("R", SUBJECT,
                BigDecimal.ONE, "CNY", Instant.now().minusSeconds(1), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("expiresAt");
        assertThatThrownBy(() -> service.reserve(new ReserveCommand("R", null,
                BigDecimal.ONE, "CNY", Instant.now().plusSeconds(10), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("subject");
    }

    @Test
    void 账户不存在或不可用时拒绝预扣() {
        when(store.findAccount(any(), anyString(), anyBoolean())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reserve(command("1.00")))
                .isInstanceOf(BizException.class).hasMessageContaining("账户不存在");

        when(store.findAccount(any(), anyString(), anyBoolean()))
                .thenReturn(Optional.of(account("10", "0", "FROZEN")));
        assertThatThrownBy(() -> service.reserve(command("1.00")))
                .isInstanceOf(BizException.class).hasMessageContaining("账户不可用");
    }

    @Test
    void 可用额恰好等于预扣金额允许_差一分钱拒绝() {
        when(store.findAccount(any(), anyString(), anyBoolean()))
                .thenReturn(Optional.of(account("10.000000", "7.000000", "ACTIVE")));
        // 可用 3.000000，恰好扣完
        assertThat(service.reserve(command("3.000000")).reservationId()).isEqualTo("RES-1");
        verify(store).addFrozen(5L, new BigDecimal("3.000000"));
        verify(store).insertReservation(eq("RES-1"), eq(5L), eq(new BigDecimal("3.000000")),
                eq("CNY"), any(), eq("biz-1"));

        // 差 0.000001 必须拒绝
        assertThatThrownBy(() -> service.reserve(command("3.000001")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("可用额度不足");
    }

    @Test
    void 账户被禁用时拒绝预扣() {
        when(store.findAccount(any(), anyString(), anyBoolean()))
                .thenReturn(Optional.of(account("100", "0", "DISABLED")));
        assertThatThrownBy(() -> service.reserve(command("1"))).isInstanceOf(BizException.class);
    }

    private static ReserveCommand command(String amount) {
        return new ReserveCommand("RES-1", SUBJECT, new BigDecimal(amount), "CNY",
                Instant.now().plusSeconds(60), "biz-1");
    }

    // ---------- settle ----------

    @Test
    void 结算参数与状态校验() {
        assertThatThrownBy(() -> service.settle(new SettleCommand(" ", BigDecimal.ONE, "CNY", "b")))
                .isInstanceOf(BizException.class).hasMessageContaining("reservationId");
        assertThatThrownBy(() -> service.settle(new SettleCommand("R", new BigDecimal("-1"), "CNY", "b")))
                .isInstanceOf(BizException.class).hasMessageContaining("不能为负");

        when(store.findReservationForUpdate("R")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.settle(new SettleCommand("R", BigDecimal.ONE, "CNY", "b")))
                .isInstanceOf(BizException.class).hasMessageContaining("预扣记录不存在");

        when(store.findReservationForUpdate("R")).thenReturn(Optional.of(reservation("RELEASED")));
        assertThatThrownBy(() -> service.settle(new SettleCommand("R", BigDecimal.ONE, "CNY", "b")))
                .isInstanceOf(BizException.class).hasMessageContaining("不可结算");
    }

    @Test
    void 已结算重复调用幂等() {
        when(store.findReservationForUpdate("RES-1")).thenReturn(Optional.of(reservation("SETTLED")));
        service.settle(new SettleCommand("RES-1", BigDecimal.ONE, "CNY", "biz"));
        verify(store, never()).settleAccount(anyLong(), any(), any());
        verify(store, never()).insertLedger(anyLong(), anyString(), any(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void 结算成功扣减余额并写不可变流水() {
        when(store.findReservationForUpdate("RES-1")).thenReturn(Optional.of(reservation("PENDING")));
        when(store.findAccountById(5L, true)).thenReturn(Optional.of(account("10", "1", "ACTIVE")));
        when(store.settleAccount(5L, new BigDecimal("1.000000"), new BigDecimal("0.600000")))
                .thenReturn(true);

        service.settle(new SettleCommand("RES-1", new BigDecimal("0.600000"), "CNY", "biz-1"));

        verify(store).settleAccount(5L, new BigDecimal("1.000000"), new BigDecimal("0.600000"));
        verify(store).insertLedger(5L, "DEBIT", new BigDecimal("0.600000"), "CNY",
                "SETTLE", "settle:RES-1", new BigDecimal("9.400000"));
        verify(store).markReservation("RES-1", "SETTLED");
    }

    @Test
    void 结算时余额或冻结不足则拒绝() {
        when(store.findReservationForUpdate("RES-1")).thenReturn(Optional.of(reservation("PENDING")));
        when(store.findAccountById(5L, true)).thenReturn(Optional.of(account("0.5", "1", "ACTIVE")));
        when(store.settleAccount(anyLong(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.settle(
                new SettleCommand("RES-1", new BigDecimal("0.6"), "CNY", "biz")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("结算失败");
        verify(store, never()).insertLedger(anyLong(), anyString(), any(), anyString(),
                anyString(), anyString(), any());
    }

    // ---------- release ----------

    @Test
    void 释放已释放或已过期的预扣幂等() {
        when(store.findReservationForUpdate("RES-1"))
                .thenReturn(Optional.of(reservation("RELEASED")));
        service.release(new ReleaseCommand("RES-1", "test"));
        verify(store, never()).releaseAccount(anyLong(), any());

        when(store.findReservationForUpdate("RES-1"))
                .thenReturn(Optional.of(reservation("EXPIRED")));
        service.release(new ReleaseCommand("RES-1", "test"));
        verify(store, never()).releaseAccount(anyLong(), any());
    }

    @Test
    void 已结算的预扣不可释放() {
        when(store.findReservationForUpdate("RES-1")).thenReturn(Optional.of(reservation("SETTLED")));
        assertThatThrownBy(() -> service.release(new ReleaseCommand("RES-1", "test")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可释放");
    }

    @Test
    void 释放成功解冻并标记状态() {
        when(store.findReservationForUpdate("RES-1")).thenReturn(Optional.of(reservation("PENDING")));
        service.release(new ReleaseCommand("RES-1", "失败"));
        verify(store).releaseAccount(5L, new BigDecimal("1.000000"));
        verify(store).markReservation("RES-1", "RELEASED");
    }

    @Test
    void 释放参数校验与记录不存在() {
        assertThatThrownBy(() -> service.release(new ReleaseCommand(" ", "r")))
                .isInstanceOf(BizException.class);
        when(store.findReservationForUpdate("R")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.release(new ReleaseCommand("R", "r")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("预扣记录不存在");
    }

    // ---------- 查询 ----------

    @Test
    void 无账户时余额返回零而不报错() {
        when(store.findAnyAccount(any())).thenReturn(Optional.empty());
        BalanceView view = service.queryBalance(SUBJECT);
        assertThat(view.balance()).isEqualByComparingTo("0");
        assertThat(view.frozen()).isEqualByComparingTo("0");
        assertThat(view.currency()).isEqualTo("CNY");

        assertThatThrownBy(() -> service.queryBalance(null)).isInstanceOf(BizException.class);
    }

    @Test
    void 有账户时余额与冻结额透传() {
        when(store.findAnyAccount(any())).thenReturn(Optional.of(account("9.5", "3", "ACTIVE")));
        BalanceView view = service.queryBalance(SUBJECT);
        assertThat(view.balance()).isEqualByComparingTo("9.5");
        assertThat(view.frozen()).isEqualByComparingTo("3");
    }

    @Test
    void 用量查询默认时间范围与limit夹紧() {
        when(store.queryUsage(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        service.queryUsage(new UsageQuery(SUBJECT, null, null, 0));
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(store).queryUsage(eq(SUBJECT), from.capture(), any(), limit.capture());
        assertThat(from.getValue()).isEqualTo(Instant.EPOCH);
        assertThat(limit.getValue()).isEqualTo(100);

        service.queryUsage(new UsageQuery(SUBJECT, Instant.EPOCH, Instant.now(), 10_000));
        ArgumentCaptor<Integer> clamped = ArgumentCaptor.forClass(Integer.class);
        verify(store, org.mockito.Mockito.times(2)).queryUsage(eq(SUBJECT), any(), any(),
                clamped.capture());
        assertThat(clamped.getValue()).isEqualTo(500);

        assertThatThrownBy(() -> service.queryUsage(null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.queryUsage(new UsageQuery(null, null, null, 10)))
                .isInstanceOf(BizException.class);
    }

    private static int anyIntValue() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
