package com.wherelee.base.modules.contracts.llm;

/**
 * 业务侧发起 LLM 调用的唯一契约入口。
 *
 * <p>实现负责：路由、额度预扣、超时/重试/熔断/降级、流式转发、调用记录与 usage 上报。
 * 契约不暴露 provider 细节；provider 扩展参数通过实现层配置/扩展位处理。</p>
 */
public interface LlmOperations {

    LlmResponse invoke(LlmRequest request);

    void stream(LlmRequest request, LlmStreamSink sink);
}
