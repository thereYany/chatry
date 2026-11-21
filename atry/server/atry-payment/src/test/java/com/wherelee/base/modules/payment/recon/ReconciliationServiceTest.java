package com.wherelee.base.modules.payment.recon;

import com.wherelee.base.modules.contracts.payment.AdminReconciliationOperations;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tracing.TraceSpans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资金对账测试。
 *
 * <p>业务不变式：逐笔比对本地支付单与渠道账单，各类差异都必须落库；
 * 差异不自动补偿；金额不一致/状态不一致各算一种差异；重跑幂等覆盖。</p>
 */
class ReconciliationServiceTest {

    private ChannelBillProvider provider;
    private ReconRepository repository;
    private ReconciliationService service;

    private static final LocalDate BILL_DATE = LocalDate.of(2026, 11, 20);

    @BeforeEach
    void setUp() {
        provider = mock(ChannelBillProvider.class);
        when(provider.supports("MOCK")).thenReturn(true);
        when(provider.sourceKey("MOCK", BILL_DATE)).thenReturn("mock://bill/MOCK/2026-11-20");
        repository = mock(ReconRepository.class);
        service = new ReconciliationService(List.of(provider), repository, TraceSpans.noop());
    }

    private static ChannelBillLine line(String tradeNo, long amount, String status) {
        return new ChannelBillLine(tradeNo, amount, status, java.time.LocalDateTime.now());
    }

    private static ReconRepository.LocalPayment local(String paymentNo, String tradeNo, long amount) {
        return new ReconRepository.LocalPayment(paymentNo, tradeNo, amount, "PAID",
                java.time.LocalDateTime.now());
    }

    @Test
    void 未知渠道报业务异常() {
        assertThatThrownBy(() -> service.run("WECHAT", BILL_DATE))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未接入对账数据源");
    }

    @Test
    void 全部匹配时无差异并落账平任务() {
        when(provider.fetch("MOCK", BILL_DATE))
                .thenReturn(List.of(line("T1", 1000, ChannelBillLine.STATUS_PAID)));
        when(repository.beginTask("MOCK", BILL_DATE)).thenReturn("RECON-MOCK-2026-11-20");
        when(repository.findLocalPaid("MOCK", BILL_DATE))
                .thenReturn(List.of(local("P1", "T1", 1000)));

        AdminReconciliationOperations.ReconSummary summary = service.run("MOCK", BILL_DATE);

        assertThat(summary.totalCount()).isEqualTo(1);
        assertThat(summary.matchedCount()).isEqualTo(1);
        assertThat(summary.diffCount()).isZero();
        assertThat(summary.diffByType()).isEmpty();
        verify(repository).finishTask("RECON-MOCK-2026-11-20", 1, 1, 0);
        verify(repository, never()).insertDiff(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 各类差异都被分类落库() {
        when(provider.fetch("MOCK", BILL_DATE)).thenReturn(List.of(
                line("T1", 1000, ChannelBillLine.STATUS_PAID),          // 正常匹配
                line("T2", 900, ChannelBillLine.STATUS_PAID),           // 金额不一致
                line("T3", 500, ChannelBillLine.STATUS_FAILED),         // 状态不一致
                line("T9", 700, ChannelBillLine.STATUS_PAID)));         // 渠道多单
        when(repository.beginTask("MOCK", BILL_DATE)).thenReturn("TASK-1");
        when(repository.findLocalPaid("MOCK", BILL_DATE)).thenReturn(List.of(
                local("P1", "T1", 1000),
                local("P2", "T2", 1000),
                local("P3", "T3", 500),
                local("P4", null, 300),                                 // 缺渠道流水号
                local("P5", "T-UNKNOWN", 400)));                        // 渠道账单无此单

        AdminReconciliationOperations.ReconSummary summary = service.run("MOCK", BILL_DATE);

        assertThat(summary.matchedCount()).isEqualTo(1);
        assertThat(summary.diffByType())
                .containsEntry("LOCAL_ONLY", 2)
                .containsEntry("AMOUNT", 1)
                .containsEntry("STATUS", 1)
                .containsEntry("PROVIDER_ONLY", 1);
        assertThat(summary.diffCount()).isEqualTo(5);
        assertThat(summary.totalCount()).isEqualTo(6);
        verify(repository).finishTask("TASK-1", 6, 1, 5);

        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        verify(repository, org.mockito.Mockito.times(5))
                .insertDiff(eq("TASK-1"), any(), any(), types.capture(), any(), any(), any());
        assertThat(types.getAllValues())
                .contains("LOCAL_ONLY", "AMOUNT", "STATUS", "PROVIDER_ONLY");
    }

    @Test
    void 未指定账期时默认对昨天() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(provider.fetch("MOCK", yesterday)).thenReturn(List.of());
        when(provider.sourceKey("MOCK", yesterday)).thenReturn("key");
        when(repository.beginTask("MOCK", yesterday)).thenReturn("TASK-2");
        when(repository.findLocalPaid("MOCK", yesterday)).thenReturn(List.of());

        service.run("mock", null);

        // 渠道名归一化为大写
        verify(provider).fetch("MOCK", yesterday);
        verify(repository).upsertBill(eq("MOCK"), eq(yesterday), eq("key"), any(), eq(0));
    }

    @Test
    void 同一账单重跑校验和一致且清空旧差异() {
        when(provider.fetch("MOCK", BILL_DATE))
                .thenReturn(List.of(line("T1", 1000, ChannelBillLine.STATUS_PAID)));
        when(repository.beginTask(anyString(), any())).thenReturn("SAME-TASK");
        when(repository.findLocalPaid(anyString(), any())).thenReturn(List.of());

        service.run("MOCK", BILL_DATE);
        service.run("MOCK", BILL_DATE);

        ArgumentCaptor<String> checksums = ArgumentCaptor.forClass(String.class);
        verify(repository, org.mockito.Mockito.times(2))
                .upsertBill(eq("MOCK"), eq(BILL_DATE), anyString(), checksums.capture(), eq(1));
        assertThat(checksums.getAllValues().get(0)).isEqualTo(checksums.getAllValues().get(1));
        assertThat(checksums.getValue()).hasSize(64);
        // 重跑复用同一任务号（幂等覆盖）
        verify(repository, org.mockito.Mockito.times(2)).beginTask("MOCK", BILL_DATE);
    }

    @Test
    void 人工处理差异只接受HANDLED或IGNORED且必须存在() {
        assertThatThrownBy(() -> service.handle(1L, 7L, "OPEN"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HANDLED 或 IGNORED");
        when(repository.handleDiff(1L, 7L, "HANDLED")).thenReturn(1);
        service.handle(1L, 7L, " handled ");
        verify(repository).handleDiff(1L, 7L, "HANDLED");

        when(repository.handleDiff(2L, 7L, "IGNORED")).thenReturn(0);
        assertThatThrownBy(() -> service.handle(2L, 7L, "IGNORED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在或已处理");
    }

    @Test
    void 差异分页夹紧参数且空结果不查列表() {
        when(repository.countDiffs(null)).thenReturn(0);
        assertThat(service.pageDiffs(null, -1, 999).size()).isEqualTo(100);
        verify(repository, never()).pageDiffs(any(), anyInt(), anyInt());

        when(repository.countDiffs("OPEN")).thenReturn(1);
        when(repository.pageDiffs(eq("OPEN"), anyInt(), anyInt()))
                .thenReturn(List.of(new ReconRepository.ReconDiffView(1L, "TASK", "P1", "B1",
                        "AMOUNT", 100L, 90L, "金额不一致", "OPEN", null, null,
                        java.time.LocalDateTime.now())));
        assertThat(service.pageDiffs("OPEN", 1, 20).records()).hasSize(1);
    }
}
