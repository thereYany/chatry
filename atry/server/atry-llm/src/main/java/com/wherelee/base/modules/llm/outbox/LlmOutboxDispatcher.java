package com.wherelee.base.modules.llm.outbox;

import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * LLM 调用完成事件投递器：轮询 t_llm_outbox，把用量转换为通用 UsageCommand 交给 metering。
 *
 * <p>事件幂等键 = {@code llm-usage:<requestId>}，与 metering 的事件唯一键配合保证不重复计费。
 * P4 单实例轮询；多实例/更高吞吐后续换 MQ 或加抢占锁。</p>
 */
@Component
@ConditionalOnProperty(name = "atry.llm.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class LlmOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LlmOutboxDispatcher.class);
    private final LlmOutboxRepository repository;
    private final MeteringOperations metering;
    private final ObjectMapper objectMapper;

    public LlmOutboxDispatcher(LlmOutboxRepository repository, MeteringOperations metering,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.metering = metering;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${atry.llm.outbox.poll-ms:1000}")
    public void dispatch() {
        // LLM 表当前不在内核租户表清单内，但投递器同样是后台线程：
        // 统一按系统作用域执行，避免未来表加入清单后被默认租户 0 收窄。
        TenantContext.runAsSystem(this::dispatchInternal);
    }

    private void dispatchInternal() {
        for (LlmOutboxRepository.OutboxRow row : repository.findDue(50)) {
            try {
                dispatchOne(row);
                repository.markSent(row.id());
            } catch (RuntimeException e) {
                log.warn("llm.outbox.dispatch_failed id={} aggregateId={} error={}",
                        row.id(), row.aggregateId(), e.getClass().getSimpleName());
                repository.scheduleRetry(row.id(), row.attempts(), e.getMessage());
            }
        }
    }

    private void dispatchOne(LlmOutboxRepository.OutboxRow row) {
        JsonNode event = objectMapper.readTree(row.payload());
        String requestId = event.get("requestId").asString();
        JsonNode usage = event.get("usage");
        long input = usage == null ? 0 : usage.path("inputTokens").asLong(0);
        long output = usage == null ? 0 : usage.path("outputTokens").asLong(0);
        long cachedRead = usage == null ? 0 : usage.path("cachedReadTokens").asLong(0);
        long nonCachedInput = Math.max(0, input - cachedRead);
        SubjectRef subject = new SubjectRef(event.path("tenantId").asLong(0),
                event.path("appId").asLong(0), event.path("userId").asLong(0));
        Instant occurredAt = event.has("occurredAt")
                ? Instant.parse(event.get("occurredAt").asString()) : Instant.now();
        String provider = event.path("provider").asString("unknown");
        String model = event.path("modelServed").asString("unknown");
        Map<String, String> dimensions = Map.of("provider", provider, "model", model);

        emit(requestId, "in", "llm.input_tokens", nonCachedInput, subject, dimensions, occurredAt);
        emit(requestId, "out", "llm.output_tokens", output, subject, dimensions, occurredAt);
        emit(requestId, "cache", "llm.cached_read_tokens", cachedRead, subject, dimensions, occurredAt);
    }

    private void emit(String requestId, String suffix, String meterKey, long quantity, SubjectRef subject,
                      Map<String, String> dimensions, Instant occurredAt) {
        if (quantity <= 0) {
            return;
        }
        metering.recordUsage(new UsageCommand(
                "llm-usage:" + requestId + ":" + suffix,
                meterKey,
                subject,
                BigDecimal.valueOf(quantity),
                "token",
                dimensions,
                occurredAt,
                requestId));
    }
}
