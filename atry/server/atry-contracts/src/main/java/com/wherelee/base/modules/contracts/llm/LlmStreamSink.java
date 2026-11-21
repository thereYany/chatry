package com.wherelee.base.modules.contracts.llm;

/** 流式回调；实现方负责背压/取消，不得在回调中阻塞上游读取。 */
public interface LlmStreamSink {

    void onChunk(String delta);

    void onComplete(LlmUsage usage, String finishReason);

    void onError(LlmError error);
}
