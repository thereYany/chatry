package com.wherelee.base.modules.llm.provider;

import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;

/**
 * Provider 适配 SPI：只做协议转换/调用/usage 提取/错误映射；
 * 不负责重试、限流、熔断（由框架统一负责，避免重试叠加）。
 */
public interface LlmProvider {

    String id();

    boolean supports(String model);

    LlmResponse invoke(LlmRequest request);

    /** 流式调用；实现通过 sink 输出 chunk/完成/错误，禁止自行重试。 */
    void stream(LlmRequest request, LlmStreamSink sink);
}
