package com.wherelee.base.modules.llm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** LLM 调用的虚拟线程执行器（不池化虚拟线程；并发上限由 bulkhead 控制）。 */
@Configuration
public class LlmInfrastructureConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService llmCallThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
