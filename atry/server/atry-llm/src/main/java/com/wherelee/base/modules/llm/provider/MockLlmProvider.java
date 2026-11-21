package com.wherelee.base.modules.llm.provider;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.llm.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * mock provider：确定性 token 数 + 可配置延迟/失败/超时/抖动模型，供测试与压测使用，不依赖外网。
 *
 * <ul>
 *   <li>默认：成功，输入 token = 字符数/4 向上取整，输出 token 由 requestId 哈希决定；</li>
 *   <li>{@code failureModel}：抛 5xx（可重试）；</li>
 *   <li>{@code slowModel}：sleep {@code slowDelayMs}，用于超时/舱壁测试；</li>
 *   <li>{@code flakyModel}：同一 requestId 首次失败、重试成功，用于验证单点重试。</li>
 * </ul>
 */
@Component
public class MockLlmProvider implements LlmProvider {

    private final LlmProperties.Mock properties;
    private final ConcurrentHashMap<String, AtomicInteger> flakyAttempts = new ConcurrentHashMap<>();

    public MockLlmProvider(LlmProperties properties) {
        this.properties = properties.mock();
    }

    @Override
    public String id() {
        return "mock";
    }

    @Override
    public boolean supports(String model) {
        return model != null && model.startsWith("mock");
    }

    @Override
    public LlmResponse invoke(LlmRequest request) {
        String model = request.model();
        if (model.equals(properties.slowModel())) {
            sleep(properties.slowDelayMs());
        } else if (properties.delayMs() > 0) {
            sleep(properties.delayMs());
        }
        if (model.equals(properties.failureModel())) {
            throw new LlmException(new LlmError(LlmErrorCategory.UPSTREAM_5XX, "MOCK_FAILURE",
                    true, false, "mock provider 模拟失败：" + model));
        }
        if (model.equals(properties.flakyModel())) {
            int attempt = flakyAttempts.computeIfAbsent(request.requestId(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (attempt == 1) {
                throw new LlmException(new LlmError(LlmErrorCategory.UPSTREAM_5XX, "MOCK_FLAKY",
                        true, false, "mock provider 首次调用失败（模拟抖动）"));
            }
            flakyAttempts.remove(request.requestId());
        }
        List<String> contents = request.messages().stream().map(m -> m.content() == null ? "" : m.content()).toList();
        int inputTokens = estimateInputTokens(contents);
        int cap = request.maxTokens() == null ? properties.defaultMaxTokens() : request.maxTokens();
        int outputTokens = Math.max(1, Math.min(cap, 20 + Math.floorMod(request.requestId().hashCode(), 100)));
        String first = contents.isEmpty() ? "" : contents.get(0);
        String content = "mock reply: " + (first.length() > 80 ? first.substring(0, 80) : first);
        return new LlmResponse(request.requestId(), id(), model, content, "stop",
                new LlmUsage(inputTokens, outputTokens, 0, 0, 0, LlmUsage.Source.PROVIDER), false);
    }

    @Override
    public void stream(LlmRequest request, LlmStreamSink sink) {
        String model = request.model();
        if (model.equals(properties.failureModel())) {
            throw new LlmException(new LlmError(LlmErrorCategory.UPSTREAM_5XX, "MOCK_FAILURE",
                    true, false, "mock provider 模拟流式失败（首 token 前）：" + model));
        }
        if (model.equals(properties.slowModel())) {
            sleep(properties.slowDelayMs());
        }
        int chunks = Math.max(1, properties.chunkCount());
        int midFailAt = chunks / 2;
        for (int i = 0; i < chunks; i++) {
            if (properties.chunkDelayMs() > 0) {
                sleep(properties.chunkDelayMs());
            }
            if (model.equals(properties.midStreamFailModel()) && i == midFailAt) {
                throw new LlmException(new LlmError(LlmErrorCategory.STREAM_INTERRUPTED, "MOCK_MID_STREAM_FAILURE",
                        false, true, "mock provider 模拟流中断（已输出部分内容）"));
            }
            sink.onChunk("mock-chunk-" + i + " ");
        }
        int input = estimateInputTokens(request.messages().stream()
                .map(m -> m.content() == null ? "" : m.content()).toList());
        int output = chunks * 2L <= Integer.MAX_VALUE ? chunks * 2 : chunks;
        sink.onComplete(new LlmUsage(input, output, 0, 0, 0, LlmUsage.Source.PROVIDER), "stop");
    }

    static int estimateInputTokens(List<String> contents) {
        int chars = contents.stream().mapToInt(s -> s == null ? 0 : s.length()).sum();
        return Math.max(1, (chars + 3) / 4);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(new LlmError(LlmErrorCategory.CANCELLED, null, false, false,
                    "mock provider 被中断"), e);
        }
    }
}
