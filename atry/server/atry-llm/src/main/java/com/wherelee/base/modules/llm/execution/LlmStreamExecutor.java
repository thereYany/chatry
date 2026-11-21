package com.wherelee.base.modules.llm.execution;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.llm.provider.LlmProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 流式执行器：provider 在虚拟线程生产事件，调用线程消费并施加两阶段超时。
 *
 * <p>首事件前用 firstTokenTimeout；之后用 chunkIdleTimeout；total 为墙钟兜底。
 * 队列有界（32）形成对上游的背压；取消/超时会 cancel 上游任务并尽量估算部分 usage。</p>
 */
@Component
public class LlmStreamExecutor {

    private static final int QUEUE_CAPACITY = 32;

    private final ExecutorService executor;

    public LlmStreamExecutor(@Qualifier("llmCallThreadExecutor") ExecutorService executor) {
        this.executor = executor;
    }

    public StreamOutcome stream(LlmProvider provider, LlmRequest request, LlmStreamSink callerSink,
                                long firstTokenMs, long idleMs, long totalMs, long deadlineAt) {
        BlockingQueue<Event> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        long startedAt = System.currentTimeMillis();
        long totalDeadline = Math.min(deadlineAt, startedAt + totalMs);
        Future<?> worker = executor.submit(() -> produce(provider, request, queue, startedAt));
        int chunks = 0;
        int chars = 0;
        Long ttftMs = null;
        try {
            Event first = queue.poll(Math.max(1, Math.min(firstTokenMs, totalDeadline - System.currentTimeMillis())),
                    TimeUnit.MILLISECONDS);
            if (first == null) {
                LlmError error = System.currentTimeMillis() >= totalDeadline
                        ? timeout(LlmErrorCategory.TIMEOUT_TOTAL, "流式总超时（首 token 前）")
                        : timeout(LlmErrorCategory.TIMEOUT_FIRST_TOKEN, "首 token 超时");
                callerSink.onError(error);
                return StreamOutcome.failed(error, estimate(request, 0, 0), 0,
                        System.currentTimeMillis() - startedAt);
            }
            if (first instanceof ErrorEvent errorEvent) {
                callerSink.onError(errorEvent.error());
                return StreamOutcome.failed(errorEvent.error(), estimate(request, 0, 0), 0,
                        System.currentTimeMillis() - startedAt);
            }
            if (first instanceof CompleteEvent complete) {
                callerSink.onComplete(complete.usage(), complete.finishReason());
                return StreamOutcome.completed(complete.usage(), complete.finishReason(), 0,
                        System.currentTimeMillis() - startedAt);
            }
            chunks++;
            ttftMs = System.currentTimeMillis() - startedAt;
            String delta = ((ChunkEvent) first).delta();
            chars += delta.length();
            callerSink.onChunk(delta);

            while (true) {
                long now = System.currentTimeMillis();
                if (now >= totalDeadline) {
                    LlmError error = timeout(LlmErrorCategory.TIMEOUT_TOTAL, "流式总超时");
                    callerSink.onError(error);
                    return StreamOutcome.failed(error, estimate(request, chunks, chars), chunks, now - startedAt);
                }
                Event event = queue.poll(Math.min(idleMs, totalDeadline - now), TimeUnit.MILLISECONDS);
                if (event == null) {
                    LlmError error = timeout(LlmErrorCategory.TIMEOUT_IDLE, "流式 chunk 空闲超时");
                    callerSink.onError(error);
                    return StreamOutcome.failed(error, estimate(request, chunks, chars), chunks,
                            System.currentTimeMillis() - startedAt);
                }
                if (event instanceof ChunkEvent chunk) {
                    chunks++;
                    chars += chunk.delta().length();
                    callerSink.onChunk(chunk.delta());
                } else if (event instanceof CompleteEvent complete) {
                    callerSink.onComplete(complete.usage(), complete.finishReason());
                    return StreamOutcome.completed(complete.usage(), complete.finishReason(), chunks,
                            System.currentTimeMillis() - startedAt, ttftMs);
                } else if (event instanceof ErrorEvent errorEvent) {
                    callerSink.onError(errorEvent.error());
                    return StreamOutcome.failed(errorEvent.error(), estimate(request, chunks, chars), chunks,
                            System.currentTimeMillis() - startedAt);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LlmError error = new LlmError(LlmErrorCategory.CANCELLED, null, false, false, "流式调用被取消");
            return StreamOutcome.failed(error, estimate(request, chunks, chars), chunks,
                    System.currentTimeMillis() - startedAt);
        } finally {
            worker.cancel(true);
        }
    }

    private void produce(LlmProvider provider, LlmRequest request, BlockingQueue<Event> queue, long startedAt) {
        try {
            provider.stream(request, new LlmStreamSink() {
                @Override
                public void onChunk(String delta) {
                    put(queue, new ChunkEvent(delta == null ? "" : delta));
                }

                @Override
                public void onComplete(LlmUsage usage, String finishReason) {
                    put(queue, new CompleteEvent(usage, finishReason));
                }

                @Override
                public void onError(LlmError error) {
                    put(queue, new ErrorEvent(error));
                }
            });
        } catch (LlmException e) {
            put(queue, new ErrorEvent(e.error()));
        } catch (RuntimeException e) {
            put(queue, new ErrorEvent(new LlmError(LlmErrorCategory.INTERNAL, "STREAM_UNEXPECTED",
                    false, false, "流式 provider 异常：" + e.getClass().getSimpleName())));
        }
    }

    private void put(BlockingQueue<Event> queue, Event event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private LlmUsage estimate(LlmRequest request, int chunks, int chars) {
        int input = request.messages().stream()
                .mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum();
        input = Math.max(1, (input + 3) / 4);
        int output = chars > 0 ? Math.max(1, (chars + 3) / 4) : chunks;
        return new LlmUsage(input, output, 0, 0, 0, LlmUsage.Source.ESTIMATED);
    }

    private LlmError timeout(LlmErrorCategory category, String message) {
        return new LlmError(category, null, true, false, message);
    }

    private sealed interface Event permits ChunkEvent, CompleteEvent, ErrorEvent {
    }

    private record ChunkEvent(String delta) implements Event {
    }

    private record CompleteEvent(LlmUsage usage, String finishReason) implements Event {
    }

    private record ErrorEvent(LlmError error) implements Event {
    }
}
