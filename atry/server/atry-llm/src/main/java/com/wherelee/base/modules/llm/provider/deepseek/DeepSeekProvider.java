package com.wherelee.base.modules.llm.provider.deepseek;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.llm.provider.LlmProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * DeepSeek OpenAI 兼容适配器（真实 HTTP/SSE）。
 *
 * <p>只做协议转换、usage 提取与错误映射；不重试/不限流/不熔断（框架统一负责）。
 * 支持 deepseek-flash / deepseek-v4-pro；默认关闭 thinking 以降低延迟与成本（可配）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "atry.llm.deepseek", name = "enabled", havingValue = "true")
public class DeepSeekProvider implements LlmProvider {

    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DeepSeekProvider(DeepSeekProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, properties.connectTimeoutMs())))
                .build();
    }

    @Override
    public String id() {
        return "deepseek";
    }

    @Override
    public boolean supports(String model) {
        return model != null && model.startsWith("deepseek");
    }

    @Override
    public LlmResponse invoke(LlmRequest request) {
        requireApiKey();
        HttpRequest httpRequest = baseRequest(request, false).build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw mapHttpError(response.statusCode(), response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            String content = choice.path("message").path("content").asString("");
            String finishReason = choice.path("finish_reason").asString("stop");
            JsonNode usageNode = root.get("usage");
            LlmUsage usage = usage(usageNode);
            String modelServed = root.path("model").asString(request.model());
            return new LlmResponse(request.requestId(), id(), modelServed, content, finishReason, usage, false);
        } catch (java.io.IOException e) {
            throw new LlmException(new LlmError(LlmErrorCategory.TIMEOUT_CONNECT, "IO_ERROR", true, false,
                    "DeepSeek 网络错误：" + e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(new LlmError(LlmErrorCategory.CANCELLED, null, false, false,
                    "DeepSeek 调用被中断"), e);
        }
    }

    @Override
    public void stream(LlmRequest request, LlmStreamSink sink) {
        requireApiKey();
        HttpRequest httpRequest = baseRequest(request, true)
                .header("Accept", "text/event-stream")
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.io.IOException e) {
            throw new LlmException(new LlmError(LlmErrorCategory.TIMEOUT_CONNECT, "IO_ERROR", true, false,
                    "DeepSeek 流式连接失败：" + e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(new LlmError(LlmErrorCategory.CANCELLED, null, false, false,
                    "DeepSeek 流式连接被中断"), e);
        }
        if (response.statusCode() / 100 != 2) {
            try (InputStream body = response.body()) {
                throw mapHttpError(response.statusCode(),
                        new String(body.readAllBytes(), StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw mapHttpError(response.statusCode(), "");
            }
        }
        LlmUsage usage = null;
        String finishReason = "stop";
        int chars = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode event = objectMapper.readTree(data);
                if (event.has("usage") && !event.get("usage").isNull()) {
                    usage = usage(event.get("usage"));
                }
                JsonNode choice = event.path("choices").path(0);
                String delta = choice.path("delta").path("content").asString("");
                if (!delta.isEmpty()) {
                    chars += delta.length();
                    sink.onChunk(delta);
                }
                if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                    finishReason = choice.get("finish_reason").asString("stop");
                }
            }
        } catch (java.io.IOException e) {
            throw new LlmException(new LlmError(LlmErrorCategory.STREAM_INTERRUPTED, "STREAM_IO", false, true,
                    "DeepSeek 流式读取失败：" + e.getClass().getSimpleName()), e);
        }
        if (usage == null) {
            int input = estimateInputTokens(request);
            usage = new LlmUsage(input, Math.max(1, (chars + 3) / 4), 0, 0, 0, LlmUsage.Source.ESTIMATED);
        }
        sink.onComplete(usage, finishReason);
    }

    private HttpRequest.Builder baseRequest(LlmRequest request, boolean stream) {
        String base = properties.baseUrl() == null || properties.baseUrl().isBlank()
                ? "https://api.deepseek.com" : properties.baseUrl();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model() == null || request.model().isBlank()
                ? properties.defaultModel() : request.model());
        ArrayNode messages = body.putArray("messages");
        request.messages().forEach(message -> {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            node.put("content", message.content());
        });
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        body.put("stream", stream);
        if (stream) {
            body.putObject("stream_options").put("include_usage", true);
        }
        if (properties.thinkingDisabled()) {
            body.putObject("thinking").put("type", "disabled");
        }
        return HttpRequest.newBuilder(URI.create(stripTrailingSlash(base) + "/chat/completions"))
                .timeout(Duration.ofMillis(Math.max(1, properties.totalTimeoutMs())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
    }

    private LlmUsage usage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return new LlmUsage(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED);
        }
        long input = usage.path("prompt_tokens").asLong(usage.path("input_tokens").asLong(0));
        long output = usage.path("completion_tokens").asLong(usage.path("output_tokens").asLong(0));
        long cached = usage.path("prompt_cache_hit_tokens")
                .asLong(usage.path("prompt_tokens_details").path("cached_tokens").asLong(0));
        long reasoning = usage.path("completion_tokens_details").path("reasoning_tokens").asLong(0);
        return new LlmUsage(input, output, cached, 0, reasoning, LlmUsage.Source.PROVIDER);
    }

    private LlmException mapHttpError(int status, String body) {
        String providerCode = providerCode(body);
        if (status == 401 || status == 403) {
            return error(LlmErrorCategory.AUTH, providerCode, false, false, "DeepSeek 鉴权失败");
        }
        if (status == 400 || status == 413 || status == 422) {
            return error(LlmErrorCategory.INVALID_REQUEST, providerCode, false, false, "DeepSeek 请求参数错误");
        }
        if (status == 402) {
            return error(LlmErrorCategory.QUOTA_EXHAUSTED, providerCode, false, false, "DeepSeek 余额/配额不足");
        }
        if (status == 429) {
            return error(LlmErrorCategory.RATE_LIMITED, providerCode, true, false, "DeepSeek 限流");
        }
        if (status >= 500) {
            return error(LlmErrorCategory.UPSTREAM_5XX, providerCode, true, false, "DeepSeek 服务异常");
        }
        return error(LlmErrorCategory.INTERNAL, providerCode, false, false, "DeepSeek 未分类错误");
    }

    private LlmException error(LlmErrorCategory category, String code, boolean retryable, boolean billable,
                                String message) {
        return new LlmException(new LlmError(category, code, retryable, billable, message));
    }

    private String providerCode(String body) {
        try {
            JsonNode root = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            JsonNode error = root.path("error");
            String code = error.path("code").asString("");
            return code.isBlank() ? error.path("type").asString("") : code;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private int estimateInputTokens(LlmRequest request) {
        int chars = request.messages().stream()
                .mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum();
        return Math.max(1, (chars + 3) / 4);
    }

    private void requireApiKey() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new LlmException(new LlmError(LlmErrorCategory.AUTH, "MISSING_API_KEY", false, false,
                    "DeepSeek API key 未配置"));
        }
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
