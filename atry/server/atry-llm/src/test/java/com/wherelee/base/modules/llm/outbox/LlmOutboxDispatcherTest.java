package com.wherelee.base.modules.llm.outbox;

import com.wherelee.base.modules.contracts.llm.LlmCallCompleted;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 发件箱投递测试：完成事件 → 通用用量事件。
 *
 * <p>业务不变式：输入、输出、缓存命中分别上报；非缓存输入 = 输入 - 缓存命中；
 * 数量为 0 的计量不上报；eventId 确定性（重复投递由 metering 唯一键幂等）；
 * 投递失败必须重试而不是标记已发送。</p>
 */
class LlmOutboxDispatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmOutboxRepository.OutboxRow row(long id, LlmCallCompleted event) {
        return new LlmOutboxRepository.OutboxRow(id, event.requestId(),
                objectMapper.writeValueAsString(event), 0);
    }

    private static LlmCallCompleted event(String requestId) {
        return new LlmCallCompleted(requestId, 5L, 0L, 9L, "mock", "mock-small",
                new LlmUsage(10, 4, 3, 0, 0, LlmUsage.Source.PROVIDER), 12L,
                Instant.parse("2026-11-20T10:00:00Z"));
    }

    @Test
    void 成功投递上报三类用量且缓存命中从输入中扣除() {
        LlmOutboxRepository repository = mock(LlmOutboxRepository.class);
        MeteringOperations metering = mock(MeteringOperations.class);
        LlmCallCompleted event = event("R1");
        when(repository.findDue(50)).thenReturn(List.of(row(1L, event)));

        new LlmOutboxDispatcher(repository, metering, objectMapper).dispatch();

        ArgumentCaptor<UsageCommand> captor = ArgumentCaptor.forClass(UsageCommand.class);
        verify(metering, times(3)).recordUsage(captor.capture());
        List<UsageCommand> commands = captor.getAllValues();

        UsageCommand input = commands.stream()
                .filter(c -> c.meterKey().equals("llm.input_tokens")).findFirst().orElseThrow();
        assertThat(input.quantity()).isEqualByComparingTo("7"); // 10 - 3 缓存命中
        assertThat(input.eventId()).isEqualTo("llm-usage:R1:in");
        assertThat(input.subject().tenantId()).isEqualTo(5L);
        assertThat(input.subject().userId()).isEqualTo(9L);
        assertThat(input.dimensions()).containsEntry("provider", "mock")
                .containsEntry("model", "mock-small");
        assertThat(input.occurredAt()).isEqualTo(Instant.parse("2026-11-20T10:00:00Z"));

        UsageCommand output = commands.stream()
                .filter(c -> c.meterKey().equals("llm.output_tokens")).findFirst().orElseThrow();
        assertThat(output.quantity()).isEqualByComparingTo("4");
        assertThat(output.eventId()).isEqualTo("llm-usage:R1:out");

        UsageCommand cache = commands.stream()
                .filter(c -> c.meterKey().equals("llm.cached_read_tokens")).findFirst().orElseThrow();
        assertThat(cache.quantity()).isEqualByComparingTo("3");
        assertThat(cache.eventId()).isEqualTo("llm-usage:R1:cache");

        verify(repository).markSent(1L);
    }

    @Test
    void 数量为0的计量不上报() {
        LlmOutboxRepository repository = mock(LlmOutboxRepository.class);
        MeteringOperations metering = mock(MeteringOperations.class);
        LlmCallCompleted event = new LlmCallCompleted("R0", 5L, 0L, 9L, "mock", "mock-small",
                new LlmUsage(2, 0, 2, 0, 0, LlmUsage.Source.PROVIDER), 5L, Instant.now());
        when(repository.findDue(50)).thenReturn(List.of(row(2L, event)));

        new LlmOutboxDispatcher(repository, metering, objectMapper).dispatch();

        // 非缓存输入 0、输出 0，只剩缓存命中 2 一条
        verify(metering, times(1)).recordUsage(any());
        verify(repository).markSent(2L);
    }

    @Test
    void 投递失败按退避重试且不标记已发送() {
        LlmOutboxRepository repository = mock(LlmOutboxRepository.class);
        MeteringOperations metering = mock(MeteringOperations.class);
        when(repository.findDue(50)).thenReturn(List.of(row(3L, event("R3"))));
        org.mockito.Mockito.doThrow(new IllegalStateException("metering down"))
                .when(metering).recordUsage(any());

        new LlmOutboxDispatcher(repository, metering, objectMapper).dispatch();

        verify(repository, never()).markSent(anyLong());
        verify(repository).scheduleRetry(3L, 0, "metering down");
    }

    @Test
    void 非法payload重试而不是抛出到调度线程() {
        LlmOutboxRepository repository = mock(LlmOutboxRepository.class);
        MeteringOperations metering = mock(MeteringOperations.class);
        when(repository.findDue(50)).thenReturn(List.of(new LlmOutboxRepository.OutboxRow(
                4L, "R4", "not-json", 1)));

        new LlmOutboxDispatcher(repository, metering, objectMapper).dispatch();

        verify(repository).scheduleRetry(eq(4L), eq(1), anyString());
        verify(metering, never()).recordUsage(any());
    }

    @Test
    void 投递在系统作用域执行且不影响调用线程租户上下文() {
        LlmOutboxRepository repository = mock(LlmOutboxRepository.class);
        MeteringOperations metering = mock(MeteringOperations.class);
        when(repository.findDue(50)).thenReturn(List.of());
        TenantContext.set(3L);

        new LlmOutboxDispatcher(repository, metering, objectMapper).dispatch();

        assertThat(TenantContext.currentOrNull()).isEqualTo(3L);
        assertThat(TenantContext.isSystemScope()).isFalse();
        TenantContext.clear();
    }
}
