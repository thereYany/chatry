package com.wherelee.base.modules.llm;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.ReservationView;
import com.wherelee.base.modules.contracts.metering.ReserveCommand;
import com.wherelee.base.modules.contracts.metering.SettleCommand;
import com.wherelee.base.modules.llm.call.LlmCallRecord;
import com.wherelee.base.modules.llm.call.LlmCallRepository;
import com.wherelee.base.modules.llm.call.LlmCallWriter;
import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.execution.LlmCallExecutor;
import com.wherelee.base.modules.llm.execution.LlmStreamExecutor;
import com.wherelee.base.modules.llm.observability.LlmEventLogger;
import com.wherelee.base.modules.llm.observability.LlmMetrics;
import com.wherelee.base.modules.llm.provider.LlmProviderRegistry;
import com.wherelee.base.modules.llm.provider.MockLlmProvider;
import com.wherelee.base.modules.llm.resilience.LlmCircuitBreaker;
import com.wherelee.base.modules.llm.resilience.LlmRateLimiter;
import com.wherelee.base.modules.llm.resilience.LlmRetryBudget;
import com.wherelee.base.modules.llm.resilience.LlmRetryPolicy;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tracing.TraceSpans;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 调用编排测试（LlmService）。
 *
 * <p>核心业务不变式：</p>
 * <ul>
 *   <li>请求先校验（空/越界/过期 deadline 一律 INVALID_REQUEST，不触达 provider）；</li>
 *   <li>requestId 幂等，重复调用拒绝；</li>
 *   <li>重试只发生在可重试错误上，且受最大次数与 deadline 约束；</li>
 *   <li>预算预扣：成功按实际 usage 结算、失败释放、价格缺失不按 0 结算；</li>
 *   <li>流式中断/取消按部分 usage 结算，绝不吞掉已产生的 token 成本。</li>
 * </ul>
 */
class LlmServiceTest {

    private LlmProperties properties;
    private MockLlmProvider provider;
    private LlmCallRepository callRepository;
    private LlmCallWriter callWriter;
    private LlmEventLogger events;
    private LlmMetrics metrics;
    private MeteringOperations metering;
    private ExecutorService executorService;
    private LlmService service;

    @BeforeEach
    void setUp() {
        properties = LlmTestSupport.properties();
        provider = new MockLlmProvider(properties);
        callRepository = mock(LlmCallRepository.class);
        callWriter = mock(LlmCallWriter.class);
        events = mock(LlmEventLogger.class);
        metrics = mock(LlmMetrics.class);
        metering = mock(MeteringOperations.class);
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        LlmCallExecutor callExecutor = new LlmCallExecutor(executorService);
        LlmStreamExecutor streamExecutor = new LlmStreamExecutor(executorService);
        service = new LlmService(new LlmProviderRegistry(List.of(provider)), callRepository, callWriter,
                TraceSpans.noop(), callExecutor, streamExecutor,
                new LlmRetryPolicy(properties), new LlmRetryBudget(), new LlmCircuitBreaker(properties),
                new LlmRateLimiter(properties), properties, events, metrics, metering);
        // 价格：所有 meter 统一 0.000001/unit，便于按 quantity 断言
        when(metering.estimateCost(anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((BigDecimal) invocation.getArgument(1))
                        .multiply(new BigDecimal("0.000001")));
        when(metering.reserve(any())).thenAnswer(invocation -> {
            ReserveCommand command = invocation.getArgument(0);
            return new ReservationView(command.reservationId(), "PENDING", command.amount(),
                    command.currency());
        });
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    private static LlmRequest request(String requestId, String model, int maxTokens, String budgetRef) {
        return new LlmRequest(requestId, 1, 0, 2, model,
                List.of(new ChatMessage("user", "hello world")), maxTokens, false,
                Instant.now().plusSeconds(5), budgetRef);
    }

    private static LlmRequest streamRequest(String requestId, String model, String budgetRef) {
        return new LlmRequest(requestId, 1, 0, 2, model,
                List.of(new ChatMessage("user", "hello")), 16, true,
                Instant.now().plusSeconds(5), budgetRef);
    }

    @Nested
    class 同步调用 {

        @Test
        void 成功时落调用记录与完成事件并返回响应() {
            LlmResponse response = service.invoke(request("R1", "mock-small", 16, null));

            assertThat(response.content()).startsWith("mock reply");
            assertThat(response.provider()).isEqualTo("mock");

            ArgumentCaptor<LlmCallRecord> record = ArgumentCaptor.forClass(LlmCallRecord.class);
            verify(callWriter).writeSuccess(record.capture(), any());
            assertThat(record.getValue().status()).isEqualTo("SUCCESS");
            assertThat(record.getValue().stream()).isFalse();
            assertThat(record.getValue().attemptCount()).isEqualTo(1);
            assertThat(record.getValue().provider()).isEqualTo("mock");
            assertThat(record.getValue().modelServed()).isEqualTo("mock-small");
            assertThat(record.getValue().inputTokens()).isEqualTo(3);
            assertThat(record.getValue().outputTokens()).isPositive();

            // 无预算引用：不得预扣/结算
            verify(metering, never()).reserve(any());
            verify(metering, never()).settle(any());
            verify(metrics).success(eq("mock"), eq("mock-small"), eq(false), anyLong(), any(), any());
        }

        @Test
        void 重复requestId幂等拒绝() {
            when(callRepository.existsByRequestId("R1")).thenReturn(true);
            assertThatThrownBy(() -> service.invoke(request("R1", "mock-small", 16, null)))
                    .isInstanceOf(LlmException.class)
                    .satisfies(e -> assertThat(((LlmException) e).error().category())
                            .isEqualTo(LlmErrorCategory.INVALID_REQUEST));
            verify(callWriter, never()).writeSuccess(any(), any());
        }

        @Test
        void 请求校验矩阵全部为INVALID_REQUEST且不落库() {
            assertThatThrownBy(() -> service.invoke(null)).isInstanceOf(LlmException.class);
            assertThatThrownBy(() -> service.invoke(request(" ", "mock-small", 16, null)))
                    .isInstanceOf(LlmException.class);
            assertThatThrownBy(() -> service.invoke(request("R1", null, 16, null)))
                    .isInstanceOf(LlmException.class);
            assertThatThrownBy(() -> service.invoke(new LlmRequest("R1", 1, 0, 2, "mock-small",
                    List.of(), 16, false, Instant.now().plusSeconds(5), null)))
                    .isInstanceOf(LlmException.class);
            assertThatThrownBy(() -> service.invoke(request("R1", "mock-small", 0, null)))
                    .isInstanceOf(LlmException.class);
            assertThatThrownBy(() -> service.invoke(new LlmRequest("R1", 1, 0, 2, "mock-small",
                    List.of(new ChatMessage("user", "hi")), 16, false,
                    Instant.now().minusSeconds(1), null)))
                    .isInstanceOf(LlmException.class);

            verify(callWriter, never()).writeFailure(any());
            verify(callWriter, never()).writeSuccess(any(), any());
        }

        @Test
        void 没有provider支持模型时返回INVALID_REQUEST且不落失败记录() {
            assertThatThrownBy(() -> service.invoke(request("R1", "deepseek-unknown", 16, null)))
                    .isInstanceOf(LlmException.class)
                    .satisfies(e -> assertThat(((LlmException) e).error().category())
                            .isEqualTo(LlmErrorCategory.INVALID_REQUEST));
            verify(callWriter, never()).writeFailure(any());
        }

        @Test
        void 可重试错误按最大次数重试后失败并落失败记录() {
            properties = LlmTestSupport.withRetry(new LlmProperties.Retry(2, 1, 5, 0));
            LlmService retrying = serviceWith(properties);

            assertThatThrownBy(() -> retrying.invoke(request("R-fail", "mock-fail", 16, null)))
                    .isInstanceOf(LlmException.class)
                    .satisfies(e -> assertThat(((LlmException) e).error().category())
                            .isEqualTo(LlmErrorCategory.UPSTREAM_5XX));

            ArgumentCaptor<LlmCallRecord> record = ArgumentCaptor.forClass(LlmCallRecord.class);
            verify(callWriter).writeFailure(record.capture());
            assertThat(record.getValue().attemptCount()).isEqualTo(2);
            assertThat(record.getValue().status()).isEqualTo("FAILED");
            assertThat(record.getValue().errorCategory()).isEqualTo("UPSTREAM_5XX");
            verify(metrics, atLeastOnce()).retry(eq("mock"), eq(true));
        }

        @Test
        void 抖动错误重试一次后成功记录尝试次数() {
            properties = LlmTestSupport.withRetry(new LlmProperties.Retry(3, 1, 5, 0));
            LlmService retrying = serviceWith(properties);

            LlmResponse response = retrying.invoke(request("R-flaky", "mock-flaky", 16, null));
            assertThat(response.content()).startsWith("mock reply");

            ArgumentCaptor<LlmCallRecord> record = ArgumentCaptor.forClass(LlmCallRecord.class);
            verify(callWriter).writeSuccess(record.capture(), any());
            assertThat(record.getValue().attemptCount()).isEqualTo(2);
        }

        @Test
        void deadline耗尽映射TIMEOUT_TOTAL且不重试() {
            properties = LlmTestSupport.withTimeouts(new LlmProperties.Timeouts(1000, 500, 500, 500));
            LlmService deadlineService = serviceWith(properties);
            LlmRequest shortDeadline = new LlmRequest("R-deadline", 1, 0, 2, "mock-slow",
                    List.of(new ChatMessage("user", "hello")), 16, false,
                    Instant.now().plusMillis(40), null);

            assertThatThrownBy(() -> deadlineService.invoke(shortDeadline))
                    .isInstanceOf(LlmException.class)
                    .satisfies(e -> assertThat(((LlmException) e).error().category())
                            .isEqualTo(LlmErrorCategory.TIMEOUT_TOTAL));

            ArgumentCaptor<LlmCallRecord> record = ArgumentCaptor.forClass(LlmCallRecord.class);
            verify(callWriter).writeFailure(record.capture());
            assertThat(record.getValue().attemptCount()).isEqualTo(1);
        }

        @Test
        void 预算预扣按估算金额_成功按实际usage结算() {
            LlmResponse response = service.invoke(request("R-budget", "mock-small", 16, "budget-1"));

            ArgumentCaptor<ReserveCommand> reserve = ArgumentCaptor.forClass(ReserveCommand.class);
            verify(metering).reserve(reserve.capture());
            // 估算：input ceil(11/4)=3 tokens + maxTokens 16 -> 19 * 0.000001
            assertThat(reserve.getValue().reservationId()).isEqualTo("llm:R-budget");
            assertThat(reserve.getValue().amount()).isEqualByComparingTo("0.000019");
            assertThat(reserve.getValue().currency()).isEqualTo("CNY");
            assertThat(reserve.getValue().bizRef()).isEqualTo("R-budget");

            ArgumentCaptor<SettleCommand> settle = ArgumentCaptor.forClass(SettleCommand.class);
            verify(metering).settle(settle.capture());
            long totalTokens = response.usage().totalTokens();
            assertThat(settle.getValue().reservationId()).isEqualTo("llm:R-budget");
            assertThat(settle.getValue().actualAmount())
                    .isEqualByComparingTo(BigDecimal.valueOf(totalTokens)
                            .multiply(new BigDecimal("0.000001")));
            assertThat(settle.getValue().bizNo()).isEqualTo("R-budget");
            verify(metering, never()).release(any());
        }

        @Test
        void 预扣失败映射QUOTA_EXHAUSTED且不调用provider() {
            org.mockito.Mockito.doThrow(BizException.badRequest("余额不足"))
                    .when(metering).reserve(any());
            AtomicInteger calls = new AtomicInteger();
            MockLlmProvider countingProvider = new MockLlmProvider(properties) {
                @Override
                public LlmResponse invoke(LlmRequest request) {
                    calls.incrementAndGet();
                    return super.invoke(request);
                }
            };
            LlmService quotaService = new LlmService(
                    new LlmProviderRegistry(List.of(countingProvider)), callRepository, callWriter,
                    TraceSpans.noop(), new LlmCallExecutor(executorService),
                    new LlmStreamExecutor(executorService), new LlmRetryPolicy(properties),
                    new LlmRetryBudget(), new LlmCircuitBreaker(properties),
                    new LlmRateLimiter(properties), properties, events, metrics, metering);

            assertThatThrownBy(() -> quotaService.invoke(request("R-quota", "mock-small", 16, "b")))
                    .isInstanceOf(LlmException.class)
                    .satisfies(e -> assertThat(((LlmException) e).error().category())
                            .isEqualTo(LlmErrorCategory.QUOTA_EXHAUSTED));
            assertThat(calls.get()).isZero();
            verify(callWriter).writeFailure(any());
            verify(metering, never()).release(any());
        }

        @Test
        void provider失败时释放预扣() {
            properties = LlmTestSupport.withRetry(new LlmProperties.Retry(1, 1, 5, 0));
            LlmService failing = serviceWith(properties);

            assertThatThrownBy(() -> failing.invoke(request("R-release", "mock-fail", 16, "b")))
                    .isInstanceOf(LlmException.class);
            verify(metering).release(any());
            verify(metering, never()).settle(any());
        }

        @Test
        void 结算异常不影响调用结果返回且不重复释放() {
            org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                    .when(metering).settle(any());

            LlmResponse response = service.invoke(request("R-settle-fail", "mock-small", 16, "b"));
            assertThat(response.content()).startsWith("mock reply");
            verify(metering, never()).release(any());
        }

        @Test
        void 价格不可用时不按0结算也不释放预扣() {
            org.mockito.Mockito.doThrow(BizException.badRequest("没有生效价格"))
                    .when(metering).estimateCost(anyString(), any(), any(), any());
            LlmResponse response = service.invoke(request("R-no-price", "mock-small", 16, null));
            assertThat(response.content()).startsWith("mock reply");
            verify(metering, never()).settle(any());
            verify(metering, never()).release(any());
        }

        @Test
        void 成本指标只记录正数金额() {
            service.invoke(request("R-cost", "mock-small", 16, null));
            ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
            verify(metrics).cost(eq(1L), eq("mock-small"), cost.capture());
            assertThat(cost.getValue()).isPositive();
        }
    }

    @Nested
    class 流式调用 {

        private RecordingSink sink;

        @BeforeEach
        void initSink() {
            sink = new RecordingSink();
        }

        @Test
        void 成功流式调用落记录并按usage结算() {
            service.stream(streamRequest("S1", "mock-small", "b"), sink);

            assertThat(String.join("", sink.chunks)).isEqualTo(
                    "mock-chunk-0 mock-chunk-1 mock-chunk-2 mock-chunk-3 ");
            assertThat(sink.completed).isTrue();
            assertThat(sink.usage).isNotNull();

            ArgumentCaptor<LlmCallRecord> record = ArgumentCaptor.forClass(LlmCallRecord.class);
            verify(callWriter).writeSuccess(record.capture(), any());
            assertThat(record.getValue().stream()).isTrue();
            assertThat(record.getValue().status()).isEqualTo("SUCCESS");
            verify(metering).settle(any());
            verify(metering, never()).release(any());
        }

        @Test
        void 重复requestId拒绝() {
            when(callRepository.existsByRequestId("S1")).thenReturn(true);
            assertThatThrownBy(() -> service.stream(streamRequest("S1", "mock-small", null), sink))
                    .isInstanceOf(LlmException.class);
            verify(callWriter, never()).writeSuccess(any(), any());
        }

        @Test
        void 流中断记录状态并按部分usage计费() {
            RecordingSink partial = new RecordingSink();
            assertThatThrownBy(() -> service.stream(
                    streamRequest("S-mid2", "mock-stream-mid-fail", "b"), partial))
                    .isInstanceOf(LlmException.class)
                    .satisfies(e -> assertThat(((LlmException) e).error().category())
                            .isEqualTo(LlmErrorCategory.STREAM_INTERRUPTED));

            assertThat(partial.chunks).hasSize(2);
            assertThat(partial.completed).isFalse();

            ArgumentCaptor<LlmCallRecord> record = ArgumentCaptor.forClass(LlmCallRecord.class);
            verify(callWriter).writeFailure(record.capture());
            assertThat(record.getValue().status()).isEqualTo("INTERRUPTED");
            assertThat(record.getValue().billable()).isTrue();

            // 已产生 token 必须结算，不能直接释放
            ArgumentCaptor<SettleCommand> settle = ArgumentCaptor.forClass(SettleCommand.class);
            verify(metering).settle(settle.capture());
            assertThat(settle.getValue().actualAmount()).isPositive();
            verify(metering, never()).release(any());
        }

        @Test
        void 首token前失败按估算输入成本结算而非释放预扣() {
            // 现有实现：首 token 前失败仍返回估算 usage（含输入 token），
            // 因此按部分用量结算；这是保守计费口径（上游可能已计输入 token）。
            assertThatThrownBy(() -> service.stream(
                    streamRequest("S-fail", "mock-fail", "b"), new RecordingSink()))
                    .isInstanceOf(LlmException.class)
                    .satisfies(e -> assertThat(((LlmException) e).error().category())
                            .isEqualTo(LlmErrorCategory.UPSTREAM_5XX));
            ArgumentCaptor<SettleCommand> settle = ArgumentCaptor.forClass(SettleCommand.class);
            verify(metering).settle(settle.capture());
            assertThat(settle.getValue().actualAmount()).isPositive();
            verify(metering, never()).release(any());
        }

        @Test
        void 客户端取消时释放预扣不继续计费() {
            RecordingSink cancelling = new RecordingSink() {
                @Override
                public void onChunk(String delta) {
                    super.onChunk(delta);
                    throw new LlmException(new com.wherelee.base.modules.contracts.llm.LlmError(
                            LlmErrorCategory.CANCELLED, null, false, false, "客户端已断开"));
                }
            };
            assertThatThrownBy(() -> service.stream(
                    streamRequest("S-cancel", "mock-small", "b"), cancelling))
                    .isInstanceOf(LlmException.class)
                    .satisfies(e -> assertThat(((LlmException) e).error().category())
                            .isEqualTo(LlmErrorCategory.CANCELLED));

            ArgumentCaptor<LlmCallRecord> record = ArgumentCaptor.forClass(LlmCallRecord.class);
            verify(callWriter).writeFailure(record.capture());
            // 取消路径的 errorCategory 正确；status 字段当前写 FAILED（见报告观察项）
            assertThat(record.getValue().errorCategory()).isEqualTo("CANCELLED");
            verify(metering).release(any());
            verify(metering, never()).settle(any());
        }
    }

    private LlmService serviceWith(LlmProperties newProperties) {
        return new LlmService(new LlmProviderRegistry(List.of(new MockLlmProvider(newProperties))),
                callRepository, callWriter, TraceSpans.noop(), new LlmCallExecutor(executorService),
                new LlmStreamExecutor(executorService), new LlmRetryPolicy(newProperties),
                new LlmRetryBudget(), new LlmCircuitBreaker(newProperties),
                new LlmRateLimiter(newProperties), newProperties, events, metrics, metering);
    }

    static class RecordingSink implements LlmStreamSink {
        final List<String> chunks = new java.util.ArrayList<>();
        LlmUsage usage;
        String finishReason;
        com.wherelee.base.modules.contracts.llm.LlmError error;
        boolean completed;

        @Override
        public void onChunk(String delta) {
            chunks.add(delta);
        }

        @Override
        public void onComplete(LlmUsage usage, String finishReason) {
            this.usage = usage;
            this.finishReason = finishReason;
            this.completed = true;
        }

        @Override
        public void onError(com.wherelee.base.modules.contracts.llm.LlmError error) {
            this.error = error;
        }
    }
}
