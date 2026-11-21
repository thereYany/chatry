package com.wherelee.base.modules.llm.observability;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import io.micrometer.tracing.Span;

/** OTel GenAI 语义约定的导出适配：内部字段稳定，这里集中做属性映射。 */
public final class LlmSpanTags {

    private LlmSpanTags() {
    }

    public static void request(Span span, LlmRequest request, String providerId) {
        tag(span, "gen_ai.operation.name", "chat");
        tag(span, "gen_ai.provider.name", providerId);
        tag(span, "gen_ai.request.model", request.model());
        tag(span, "gen_ai.request.stream", Boolean.toString(request.stream()));
        if (request.maxTokens() != null) {
            tag(span, "gen_ai.request.max_tokens", Integer.toString(request.maxTokens()));
        }
    }

    public static void response(Span span, LlmResponse response, LlmUsage usage) {
        if (response != null) {
            tag(span, "gen_ai.response.model", response.modelServed());
            tag(span, "gen_ai.response.finish_reasons", response.finishReason());
        }
        if (usage != null) {
            tag(span, "gen_ai.usage.input_tokens", Long.toString(usage.inputTokens()));
            tag(span, "gen_ai.usage.output_tokens", Long.toString(usage.outputTokens()));
        }
    }

    public static void error(Span span, LlmError error) {
        if (error == null) {
            return;
        }
        tag(span, "error.type", error.category().name().toLowerCase());
        tag(span, "atry.llm.provider_code", error.providerCode());
    }

    private static void tag(Span span, String key, String value) {
        if (span != null && value != null && !value.isBlank()) {
            span.tag(key, value);
        }
    }
}
