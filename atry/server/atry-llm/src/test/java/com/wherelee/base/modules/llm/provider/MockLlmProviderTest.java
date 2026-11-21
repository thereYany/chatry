package com.wherelee.base.modules.llm;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.provider.MockLlmProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mock provider 测试：确定性 token、失败/抖动/超时/流中断等测试模型。
 *
 * <p>这些模型是 LLM 可靠性测试的地基，行为必须确定：同一 requestId 同一输出、
 * 首次失败后重试成功、流式中途失败时已输出部分 chunk。</p>
 */
class MockLlmProviderTest {

    private static MockLlmProvider provider(LlmProperties.Mock mock) {
        return new MockLlmProvider(new LlmProperties(mock, LlmTestSupport.timeouts(),
                LlmTestSupport.retry(), LlmTestSupport.circuit(), LlmTestSupport.rateLimit()));
    }

    private static LlmRequest request(String requestId, String model, int maxTokens) {
        return new LlmRequest(requestId, 1, 0, 2, model,
                List.of(new ChatMessage("user", "hello world")), maxTokens, false,
                Instant.now().plusSeconds(5), null);
    }

    @Test
    void 支持所有mock前缀模型_拒绝其他模型() {
        MockLlmProvider provider = provider(LlmTestSupport.mock());
        assertThat(provider.id()).isEqualTo("mock");
        assertThat(provider.supports("mock-small")).isTrue();
        assertThat(provider.supports("mock-anything")).isTrue();
        assertThat(provider.supports("deepseek-flash")).isFalse();
        assertThat(provider.supports(null)).isFalse();
    }

    @Test
    void 同一请求输出确定且token估算符合字符数除以4() {
        MockLlmProvider provider = provider(LlmTestSupport.mock());
        LlmRequest request = request("req-fixed", "mock-small", 128);
        var first = provider.invoke(request);
        var second = provider.invoke(request);

        assertThat(first.content()).isEqualTo(second.content());
        assertThat(first.usage().inputTokens()).isEqualTo(second.usage().inputTokens());
        // "hello world" = 11 字符 -> ceil(11/4) = 3
        assertThat(first.usage().inputTokens()).isEqualTo(3);
        assertThat(first.usage().outputTokens()).isBetween(1L, 128L);
        assertThat(first.usage().source()).isEqualTo(LlmUsage.Source.PROVIDER);
        assertThat(first.content()).startsWith("mock reply: hello world");
    }

    @Test
    void 输出token不超过maxTokens() {
        MockLlmProvider provider = provider(LlmTestSupport.mock());
        assertThat(provider.invoke(request("req-max", "mock-small", 1)).usage().outputTokens())
                .isEqualTo(1);
        assertThat(provider.invoke(request("req-max2", "mock-small", 5)).usage().outputTokens())
                .isLessThanOrEqualTo(5);
    }

    @Test
    void 超长输入内容被截断到80字符() {
        MockLlmProvider provider = provider(LlmTestSupport.mock());
        LlmRequest request = new LlmRequest("req-long", 1, 0, 2, "mock-small",
                List.of(new ChatMessage("user", "x".repeat(200))), 30, false,
                Instant.now().plusSeconds(5), null);
        assertThat(provider.invoke(request).content()).hasSize("mock reply: ".length() + 80);
    }

    @Test
    void 失败模型抛可重试的UPSTREAM_5XX() {
        MockLlmProvider provider = provider(LlmTestSupport.mock());
        assertThatThrownBy(() -> provider.invoke(request("req-fail", "mock-fail", 16)))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> {
                    LlmException llm = (LlmException) e;
                    assertThat(llm.error().category()).isEqualTo(LlmErrorCategory.UPSTREAM_5XX);
                    assertThat(llm.error().retryable()).isTrue();
                    assertThat(llm.error().billable()).isFalse();
                });
    }

    @Test
    void 抖动模型同一requestId首次失败重试成功_不同请求独立计数() {
        MockLlmProvider provider = provider(LlmTestSupport.mock());
        assertThatThrownBy(() -> provider.invoke(request("req-flaky", "mock-flaky", 16)))
                .isInstanceOf(LlmException.class);
        assertThat(provider.invoke(request("req-flaky", "mock-flaky", 16)).content())
                .startsWith("mock reply");

        // 新的 requestId 仍然走“首次失败”
        assertThatThrownBy(() -> provider.invoke(request("req-flaky-2", "mock-flaky", 16)))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void 流式产出配置数量的chunk并以完成事件结束() {
        MockLlmProvider provider = provider(LlmTestSupport.mock());
        RecordingSink sink = new RecordingSink();
        provider.stream(new LlmRequest("req-stream", 1, 0, 2, "mock-small",
                List.of(new ChatMessage("user", "hello")), 16, true,
                Instant.now().plusSeconds(5), null), sink);

        assertThat(sink.chunks).hasSize(4);
        assertThat(String.join("", sink.chunks)).isEqualTo(
                "mock-chunk-0 mock-chunk-1 mock-chunk-2 mock-chunk-3 ");
        assertThat(sink.usage).isNotNull();
        assertThat(sink.usage.outputTokens()).isEqualTo(8);
        assertThat(sink.finishReason).isEqualTo("stop");
        assertThat(sink.error).isNull();
    }

    @Test
    void 流式中途失败时已输出部分chunk且错误标记可计费() {
        MockLlmProvider provider = provider(LlmTestSupport.mock());
        RecordingSink sink = new RecordingSink();
        assertThatThrownBy(() -> provider.stream(new LlmRequest("req-mid", 1, 0, 2,
                "mock-stream-mid-fail", List.of(new ChatMessage("user", "hi")), 16, true,
                Instant.now().plusSeconds(5), null), sink))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> {
                    LlmException llm = (LlmException) e;
                    assertThat(llm.error().category()).isEqualTo(LlmErrorCategory.STREAM_INTERRUPTED);
                    assertThat(llm.error().billable()).isTrue();
                });
        // chunkCount=4，midFailAt=2：已输出 0、1 两个 chunk
        assertThat(sink.chunks).hasSize(2);
        assertThat(sink.completed).isFalse();
    }

    @Test
    void 流式失败模型在首token前抛错() {
        MockLlmProvider provider = provider(LlmTestSupport.mock());
        RecordingSink sink = new RecordingSink();
        assertThatThrownBy(() -> provider.stream(new LlmRequest("req-fail", 1, 0, 2, "mock-fail",
                List.of(new ChatMessage("user", "hi")), 16, true,
                Instant.now().plusSeconds(5), null), sink))
                .isInstanceOf(LlmException.class);
        assertThat(sink.chunks).isEmpty();
    }

    static class RecordingSink implements LlmStreamSink {
        final List<String> chunks = new ArrayList<>();
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
