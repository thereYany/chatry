package com.wherelee.base.modules.llm;

import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.llm.execution.LlmCallExecutor;
import com.wherelee.base.modules.llm.provider.LlmProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非流式调用执行器测试。
 *
 * <p>业务不变式：provider 异常语义原样透传（LLM 错误分类不能被吞）；超时映射为 TIMEOUT_TOTAL
 * 并取消上游任务；未知运行时异常统一归 INTERNAL，避免把框架异常当上游错误重试。</p>
 */
class LlmCallExecutorTest {

    private ExecutorService executorService;
    private LlmCallExecutor executor;

    @BeforeEach
    void setUp() {
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        executor = new LlmCallExecutor(executorService);
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    private static LlmProvider provider(java.util.function.Function<LlmProviderRequest, LlmResponse> behavior) {
        return new LlmProvider() {
            @Override
            public String id() {
                return "stub";
            }

            @Override
            public boolean supports(String model) {
                return true;
            }

            @Override
            public LlmResponse invoke(com.wherelee.base.modules.contracts.llm.LlmRequest request) {
                return behavior.apply(new LlmProviderRequest(request));
            }

            @Override
            public void stream(com.wherelee.base.modules.contracts.llm.LlmRequest request,
                               com.wherelee.base.modules.contracts.llm.LlmStreamSink sink) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private record LlmProviderRequest(com.wherelee.base.modules.contracts.llm.LlmRequest request) {
    }

    private static com.wherelee.base.modules.contracts.llm.LlmRequest request() {
        return new com.wherelee.base.modules.contracts.llm.LlmRequest("R1", 1, 0, 2, "mock-small",
                java.util.List.of(new com.wherelee.base.modules.contracts.llm.ChatMessage("user", "hi")),
                16, false, java.time.Instant.now().plusSeconds(5), null);
    }

    @Test
    void 正常返回响应() {
        LlmResponse response = new LlmResponse("R1", "stub", "mock-small", "ok", "stop",
                LlmTestSupport.usage(1, 2, 0), false);
        assertThat(executor.invoke(provider(ignored -> response), request(), 1000)).isSameAs(response);
    }

    @Test
    void provider的LLM异常原样透传() {
        LlmException failure = new LlmException(new com.wherelee.base.modules.contracts.llm.LlmError(
                LlmErrorCategory.AUTH, "BAD_KEY", false, false, "鉴权失败"));
        assertThatThrownBy(() -> executor.invoke(provider(ignored -> {
            throw failure;
        }), request(), 1000)).isSameAs(failure);
    }

    @Test
    void 未知运行时异常归为INTERNAL() {
        assertThatThrownBy(() -> executor.invoke(provider(ignored -> {
            throw new IllegalStateException("provider bug");
        }), request(), 1000))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> {
                    LlmException llm = (LlmException) e;
                    assertThat(llm.error().category()).isEqualTo(LlmErrorCategory.INTERNAL);
                    assertThat(llm.error().retryable()).isFalse();
                    assertThat(llm.getCause()).isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void 超时映射TIMEOUT_TOTAL并中断上游任务() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        LlmProvider slow = provider(ignored -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return null;
        });

        assertThatThrownBy(() -> executor.invoke(slow, request(), 50))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).error().category())
                        .isEqualTo(LlmErrorCategory.TIMEOUT_TOTAL));

        // 等待取消信号传播到 provider 虚拟线程
        long deadline = System.currentTimeMillis() + 1000;
        while (!interrupted.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(interrupted.get()).isTrue();
    }

    @Test
    void 调用线程被中断时映射CANCELLED并恢复中断位() {
        LlmProvider slow = provider(ignored -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> executor.invoke(slow, request(), 5000))
                    .isInstanceOf(LlmException.class)
                    .satisfies(e -> assertThat(((LlmException) e).error().category())
                            .isEqualTo(LlmErrorCategory.CANCELLED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 超时参数被夹紧为至少1毫秒() {
        LlmResponse response = new LlmResponse("R1", "stub", "mock-small", "ok", "stop",
                LlmTestSupport.usage(1, 1, 0), false);
        // timeoutMs=0/负数不能被当成无限等待
        assertThat(executor.invoke(provider(ignored -> response), request(), 0)).isSameAs(response);
    }

    @Test
    void 用量估算来源被保留() {
        LlmResponse response = new LlmResponse("R1", "stub", "mock-small", "ok", "stop",
                new LlmUsage(1, 1, 0, 0, 0, LlmUsage.Source.ESTIMATED), false);
        assertThat(executor.invoke(provider(ignored -> response), request(), 1000).usage().source())
                .isEqualTo(LlmUsage.Source.ESTIMATED);
    }
}
