package com.wherelee.base.modules.llm.call;

import com.wherelee.base.modules.contracts.llm.LlmCallCompleted;
import com.wherelee.base.modules.llm.outbox.LlmOutboxRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调用记录与 outbox 的写边界：同一事务内落库，避免"记录成功但事件丢失"或反之。
 * 外部 provider 调用不在事务内（由 LlmService 控制）。
 */
@Component
public class LlmCallWriter {

    private static final String COMPLETED_EVENT = "LLM_CALL_COMPLETED";

    private final LlmCallRepository callRepository;
    private final LlmOutboxRepository outboxRepository;

    public LlmCallWriter(LlmCallRepository callRepository, LlmOutboxRepository outboxRepository) {
        this.callRepository = callRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public void writeSuccess(LlmCallRecord record, LlmCallCompleted event) {
        callRepository.insert(record);
        outboxRepository.append(record.requestId(), COMPLETED_EVENT, event);
    }

    @Transactional
    public void writeFailure(LlmCallRecord record) {
        callRepository.insert(record);
    }
}
