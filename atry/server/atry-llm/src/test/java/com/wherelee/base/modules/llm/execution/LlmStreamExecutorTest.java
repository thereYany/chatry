package com.wherelee.base.modules.llm.execution;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.llm.LlmTestSupport;
import com.wherelee.base.modules.llm.provider.LlmProvider;
import com.wherelee.base.modules.llm.provider.MockLlmProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式执行器测试：两阶段超时、背压、取消与部分用量估算。
 *
 * <p>业务不变式：首 token 与 chunk 空闲分别超时并映射到不同错误分类（可运维定位）；
 * 超时/取消时向上游 cancel 且返回估算的部分 usage（已输出 token 仍可计费）。</p>
 */
class LlmStreamExecutorTest {

    private ExecutorService executorService;
    private LlmStreamExecutor executor;
    private MockLlmProvider provider;

    @BeforeEach
    void setUp() {
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        executor = new LlmStreamExecutor(executorService);
        provider = new MockLlmProvider(LlmTestSupport.properties());
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    private static LlmRequest request(String model) {
        return new LlmRequest("R1", 1, 0, 2, model,
                List.of(new ChatMessage("user", "hello")), 16, true,
                Instant.now().plusSeconds(5), null);
    }

    private static long deadline() {
        return System.currentTimeMillis() + 5000;
    }

    @Test
    void 正常流返回完成结果与ttft() {
        RecordingSink sink = new RecordingSink();
        StreamOutcome outcome = executor.stream(provider, request("mock-small"), sink,
                500, 500, 2000, deadline());

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.chunks()).isEqualTo(4);
        assertThat(outcome.ttftMs()).isNotNull();
        assertThat(sink.chunks).hasSize(4);
        assertThat(sink.usage).isNotNull();
    }

    @Test
    void 首个事件就是错误时立即失败() {
        RecordingSink sink = new RecordingSink();
        StreamOutcome outcome = executor.stream(provider, request("mock-fail"), sink,
                500, 500, 2000, deadline());

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.error().category()).isEqualTo(LlmErrorCategory.UPSTREAM_5XX);
        assertThat(sink.error).isNotNull();
    }

    @Test
    void 首token超时映射TIMEOUT_FIRST_TOKEN() {
        // slow 模型先 sleep 400ms，首 token 超时 60ms
        RecordingSink sink = new RecordingSink();
        StreamOutcome outcome = executor.stream(provider, request("mock-slow"), sink,
                60, 500, 5000, deadline());

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.error().category()).isEqualTo(LlmErrorCategory.TIMEOUT_FIRST_TOKEN);
        assertThat(sink.error.category()).isEqualTo(LlmErrorCategory.TIMEOUT_FIRST_TOKEN);
    }

    @Test
    void chunk空闲超时映射TIMEOUT_IDLE并保留部分用量() {
        LlmProvider stalling = new LlmProvider() {
            @Override
            public String id() {
                return "stalling";
            }

            @Override
            public boolean supports(String model) {
                return true;
            }

            @Override
            public com.wherelee.base.modules.contracts.llm.LlmResponse invoke(LlmRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void stream(LlmRequest request, LlmStreamSink sink) {
                sink.onChunk("partial");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sink.onComplete(LlmTestSupport.usage(1, 1, 0), "stop");
            }
        };

        RecordingSink sink = new RecordingSink();
        StreamOutcome outcome = executor.stream(stalling,
                new LlmRequest("R-idle", 1, 0, 2, "stalling",
                        List.of(new ChatMessage("user", "abcdefgh")), 16, true,
                        Instant.now().plusSeconds(5), null),
                sink, 500, 50, 5000, deadline());

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.error().category()).isEqualTo(LlmErrorCategory.TIMEOUT_IDLE);
        assertThat(outcome.chunks()).isEqualTo(1);
        assertThat(outcome.usage().source()).isEqualTo(LlmUsage.Source.ESTIMATED);
        assertThat(outcome.usage().outputTokens()).isPositive();
        assertThat(sink.chunks).containsExactly("partial");
    }

    @Test
    void 总超时优先于首token超时映射TIMEOUT_TOTAL() {
        RecordingSink sink = new RecordingSink();
        StreamOutcome outcome = executor.stream(provider, request("mock-slow"), sink,
                5000, 5000, 100, System.currentTimeMillis() + 100);

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.error().category()).isEqualTo(LlmErrorCategory.TIMEOUT_TOTAL);
    }

    @Test
    void provider抛运行时异常映射INTERNAL() {
        LlmProvider broken = new LlmProvider() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public boolean supports(String model) {
                return true;
            }

            @Override
            public com.wherelee.base.modules.contracts.llm.LlmResponse invoke(LlmRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void stream(LlmRequest request, LlmStreamSink sink) {
                throw new IllegalStateException("provider bug");
            }
        };

        StreamOutcome outcome = executor.stream(broken, request("mock-small"), new RecordingSink(),
                500, 500, 2000, deadline());
        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.error().category()).isEqualTo(LlmErrorCategory.INTERNAL);
    }

    @Test
    void 调用线程被中断时返回CANCELLED() {
        RecordingSink sink = new RecordingSink();
        Thread.currentThread().interrupt();
        try {
            StreamOutcome outcome = executor.stream(provider, request("mock-small"), sink,
                    500, 500, 2000, deadline());
            assertThat(outcome.completed()).isFalse();
            assertThat(outcome.error().category()).isEqualTo(LlmErrorCategory.CANCELLED);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 只有完成事件没有chunk也能完成() {
        LlmProvider empty = new LlmProvider() {
            @Override
            public String id() {
                return "empty";
            }

            @Override
            public boolean supports(String model) {
                return true;
            }

            @Override
            public com.wherelee.base.modules.contracts.llm.LlmResponse invoke(LlmRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void stream(LlmRequest request, LlmStreamSink sink) {
                sink.onComplete(LlmTestSupport.usage(2, 0, 0), "stop");
            }
        };

        StreamOutcome outcome = executor.stream(empty, request("mock-small"), new RecordingSink(),
                500, 500, 2000, deadline());
        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.chunks()).isZero();
        assertThat(outcome.usage().inputTokens()).isEqualTo(2);
    }

    static class RecordingSink implements LlmStreamSink {
        final List<String> chunks = new java.util.ArrayList<>();
        LlmUsage usage;
        LlmError error;
        boolean completed;

        @Override
        public void onChunk(String delta) {
            chunks.add(delta);
        }

        @Override
        public void onComplete(LlmUsage usage, String finishReason) {
            this.usage = usage;
            this.completed = true;
        }

        @Override
        public void onError(LlmError error) {
            this.error = error;
        }
    }
}
