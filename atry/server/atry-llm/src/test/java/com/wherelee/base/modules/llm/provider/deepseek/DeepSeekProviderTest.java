package com.wherelee.base.modules.llm.provider.deepseek;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DeepSeek provider 协议/错误映射测试（本地 stub HTTP server，不访问真实 API）。
 *
 * <p>业务不变式：OpenAI 兼容协议映射正确（内容/finish_reason/usage 分项）；
 * HTTP 状态到稳定错误分类映射正确（可重试标记决定上层是否重试）；未配置 key 时明确 AUTH；
 * 流式 SSE 解析出增量、usage 与 finish_reason，缺 usage 时标记 ESTIMATED。</p>
 */
class DeepSeekProviderTest {

    private StubServer server;
    private DeepSeekProvider provider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new StubServer();
        server.start();
        provider = new DeepSeekProvider(properties("test-api-key"), objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private DeepSeekProperties properties(String apiKey) {
        return new DeepSeekProperties(true, server.url(), apiKey, "deepseek-flash",
                true, 1000, 5000);
    }

    private static LlmRequest request(String model) {
        return new LlmRequest("R1", 1, 0, 2, model,
                List.of(new ChatMessage("system", "你是助手"), new ChatMessage("user", "你好")),
                64, false, Instant.now().plusSeconds(5), null);
    }

    private static LlmRequest streamRequest() {
        return new LlmRequest("R1", 1, 0, 2, "deepseek-flash",
                List.of(new ChatMessage("user", "你好")), 64, true,
                Instant.now().plusSeconds(5), null);
    }

    // ---------- 非流式 ----------

    @Test
    void invoke成功解析内容与usage分项() {
        server.respond(200, "application/json", """
                {"id":"x","model":"deepseek-flash",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"你好，我是模型"},
                             "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5,
                          "prompt_cache_hit_tokens":3,
                          "completion_tokens_details":{"reasoning_tokens":2}}}
                """);

        LlmResponse response = provider.invoke(request("deepseek-flash"));

        assertThat(response.content()).isEqualTo("你好，我是模型");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.modelServed()).isEqualTo("deepseek-flash");
        assertThat(response.usage().inputTokens()).isEqualTo(10);
        assertThat(response.usage().outputTokens()).isEqualTo(5);
        assertThat(response.usage().cachedReadTokens()).isEqualTo(3);
        assertThat(response.usage().reasoningTokens()).isEqualTo(2);
        assertThat(response.usage().source()).isEqualTo(LlmUsage.Source.PROVIDER);

        // 协议请求体断言：模型/消息/max_tokens/thinking 关闭
        String sent = server.lastBody();
        assertThat(sent).contains("\"model\":\"deepseek-flash\"")
                .contains("\"max_tokens\":64")
                .contains("\"stream\":false")
                .contains("\"type\":\"disabled\"")
                .contains("你是助手").contains("你好");
        assertThat(server.lastHeader("Authorization")).isEqualTo("Bearer test-api-key");
        assertThat(server.lastPath()).isEqualTo("/chat/completions");
    }

    @Test
    void 请求模型为空或缺失响应模型时使用默认模型回退() {
        server.respond(200, "application/json", """
                {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}
                """);
        LlmResponse response = provider.invoke(request(" "));
        // 响应缺 model 时回退取请求模型（当前请求模型是空白串）
        assertThat(response.modelServed()).isEqualTo(" ");
        assertThat(server.lastBody()).contains("\"model\":\"deepseek-flash\"");
        // 响应缺 usage 时仍返回 ESTIMATED 空用量
        assertThat(response.usage().source()).isEqualTo(LlmUsage.Source.ESTIMATED);
        assertThat(response.usage().totalTokens()).isZero();
    }

    @Test
    void HTTP状态映射稳定错误分类() {
        assertMapped(401, "{\"error\":{\"code\":\"invalid_api_key\"}}",
                LlmErrorCategory.AUTH, false);
        assertMapped(403, "{}", LlmErrorCategory.AUTH, false);
        assertMapped(400, "{}", LlmErrorCategory.INVALID_REQUEST, false);
        assertMapped(402, "{}", LlmErrorCategory.QUOTA_EXHAUSTED, false);
        assertMapped(429, "{}", LlmErrorCategory.RATE_LIMITED, true);
        assertMapped(500, "{}", LlmErrorCategory.UPSTREAM_5XX, true);
        assertMapped(503, "{}", LlmErrorCategory.UPSTREAM_5XX, true);
        assertMapped(418, "{}", LlmErrorCategory.INTERNAL, false);
    }

    private void assertMapped(int status, String body, LlmErrorCategory category, boolean retryable) {
        server.respond(status, "application/json", body);
        assertThatThrownBy(() -> provider.invoke(request("deepseek-flash")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> {
                    LlmException llm = (LlmException) e;
                    assertThat(llm.error().category()).isEqualTo(category);
                    assertThat(llm.error().retryable()).isEqualTo(retryable);
                    assertThat(llm.error().billable()).isFalse();
                });
    }

    @Test
    void 状态401映射保留上游providerCode() {
        server.respond(401, "application/json",
                "{\"error\":{\"code\":\"invalid_api_key\",\"type\":\"authentication_error\"}}");
        assertThatThrownBy(() -> provider.invoke(request("deepseek-flash")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).error().providerCode())
                        .isEqualTo("invalid_api_key"));
    }

    @Test
    void 未配置apiKey时明确AUTH且不发请求() {
        DeepSeekProvider noKey = new DeepSeekProvider(properties("  "), objectMapper);
        assertThatThrownBy(() -> noKey.invoke(request("deepseek-flash")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> {
                    LlmException llm = (LlmException) e;
                    assertThat(llm.error().category()).isEqualTo(LlmErrorCategory.AUTH);
                    assertThat(llm.error().providerCode()).isEqualTo("MISSING_API_KEY");
                });
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void 连接失败映射TIMEOUT_CONNECT且可重试() {
        DeepSeekProvider unreachable = new DeepSeekProvider(new DeepSeekProperties(
                true, "http://127.0.0.1:1", "key", "deepseek-flash", true, 300, 1000),
                objectMapper);
        assertThatThrownBy(() -> unreachable.invoke(request("deepseek-flash")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> {
                    LlmException llm = (LlmException) e;
                    assertThat(llm.error().category()).isEqualTo(LlmErrorCategory.TIMEOUT_CONNECT);
                    assertThat(llm.error().retryable()).isTrue();
                });
    }

    // ---------- 流式 ----------

    @Test
    void stream解析SSE增量与usage() {
        server.respond(200, "text/event-stream", """
                data: {"choices":[{"delta":{"content":"你"}}]}

                data: {"choices":[{"delta":{"content":"好"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2}}

                data: [DONE]

                """);

        RecordingSink sink = new RecordingSink();
        provider.stream(streamRequest(), sink);

        assertThat(sink.chunks).containsExactly("你", "好");
        assertThat(sink.completed).isTrue();
        assertThat(sink.finishReason).isEqualTo("stop");
        assertThat(sink.usage.inputTokens()).isEqualTo(3);
        assertThat(sink.usage.outputTokens()).isEqualTo(2);
        assertThat(sink.usage.source()).isEqualTo(LlmUsage.Source.PROVIDER);
        assertThat(server.lastBody()).contains("\"stream\":true")
                .contains("\"include_usage\":true");
    }

    @Test
    void stream缺少usage时按字符估算并标记ESTIMATED() {
        server.respond(200, "text/event-stream", """
                data: {"choices":[{"delta":{"content":"abcdefgh"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"length"}]}

                data: [DONE]

                """);

        RecordingSink sink = new RecordingSink();
        provider.stream(streamRequest(), sink);

        assertThat(sink.chunks).containsExactly("abcdefgh");
        assertThat(sink.finishReason).isEqualTo("length");
        assertThat(sink.usage.source()).isEqualTo(LlmUsage.Source.ESTIMATED);
        assertThat(sink.usage.outputTokens()).isEqualTo(2); // ceil(8/4)
    }

    @Test
    void streamHTTP错误映射为对应分类() {
        server.respond(500, "application/json", "{\"error\":{\"type\":\"server_error\"}}");
        assertThatThrownBy(() -> provider.stream(streamRequest(), new RecordingSink()))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).error().category())
                        .isEqualTo(LlmErrorCategory.UPSTREAM_5XX));

        server.respond(429, "application/json", "{}");
        assertThatThrownBy(() -> provider.stream(streamRequest(), new RecordingSink()))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).error().category())
                        .isEqualTo(LlmErrorCategory.RATE_LIMITED));
    }

    @Test
    void stream连接失败映射TIMEOUT_CONNECT() {
        DeepSeekProvider unreachable = new DeepSeekProvider(new DeepSeekProperties(
                true, "http://127.0.0.1:1", "key", "deepseek-flash", true, 300, 1000),
                objectMapper);
        assertThatThrownBy(() -> unreachable.stream(streamRequest(), new RecordingSink()))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).error().category())
                        .isEqualTo(LlmErrorCategory.TIMEOUT_CONNECT));
    }

    static class RecordingSink implements LlmStreamSink {
        final List<String> chunks = new ArrayList<>();
        LlmUsage usage;
        String finishReason;
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
            throw new AssertionError("stub 场景不应回调 onError: " + error);
        }
    }

    /** 本地 stub HTTP server：记录最后一次请求，返回脚本化响应。 */
    static class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<Recorded> last = new AtomicReference<>();
        private volatile int status = 200;
        private volatile String contentType = "application/json";
        private volatile String body = "{}";
        private int count;

        StubServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", this::handle);
        }

        void start() {
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void respond(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            count++;
            last.set(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(requestBody, StandardCharsets.UTF_8)));
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        }

        String lastBody() {
            return last.get().body();
        }

        String lastHeader(String name) {
            return "Authorization".equalsIgnoreCase(name) ? last.get().authorization() : null;
        }

        String lastPath() {
            return last.get().path();
        }

        int requestCount() {
            return count;
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private record Recorded(String method, String path, String authorization, String body) {
        }
    }
}
