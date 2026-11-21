package com.wherelee.base.modules.llm.provider;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import org.springframework.stereotype.Component;

import java.util.List;

/** provider 注册表：P1 按 model 选择唯一支持的 provider；P2 扩展路由/权重/fallback。 */
@Component
public class LlmProviderRegistry {

    private final List<LlmProvider> providers;

    public LlmProviderRegistry(List<LlmProvider> providers) {
        this.providers = providers;
    }

    public LlmProvider resolve(String model) {
        return providers.stream()
                .filter(provider -> provider.supports(model))
                .findFirst()
                .orElseThrow(() -> new LlmException(new LlmError(
                        LlmErrorCategory.INVALID_REQUEST, null, false, false,
                        "没有可用的 provider 支持模型：" + model)));
    }
}
