package com.wherelee.base.modules.llm.execution;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.llm.provider.LlmProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 调用执行器：用虚拟线程执行 provider 调用，并用 Future 施加"总超时"。
 *
 * <p>非流式调用以 first-token（≈响应返回）作为主要超时，total 作为墙钟兜底；
 * P2 先用 total/显式 timeout 参数，流式两阶段超时在 P2c 实现。</p>
 */
@Component
public class LlmCallExecutor {

    private final ExecutorService executor;

    public LlmCallExecutor(@Qualifier("llmCallThreadExecutor") ExecutorService executor) {
        this.executor = executor;
    }

    public LlmResponse invoke(LlmProvider provider, LlmRequest request, long timeoutMs) {
        long effective = Math.max(1, timeoutMs);
        Future<LlmResponse> future = executor.submit(() -> provider.invoke(request));
        try {
            return future.get(effective, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmException(new LlmError(LlmErrorCategory.TIMEOUT_TOTAL, null, true, false,
                    "LLM 调用超时（" + effective + "ms）"), e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmException(new LlmError(LlmErrorCategory.CANCELLED, null, false, false,
                    "LLM 调用被中断"), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LlmException llmException) {
                throw llmException;
            }
            throw new LlmException(new LlmError(LlmErrorCategory.INTERNAL, "UNEXPECTED", false, false,
                    "LLM provider 执行失败：" + (cause == null ? "unknown" : cause.getClass().getSimpleName())), cause);
        }
    }
}
