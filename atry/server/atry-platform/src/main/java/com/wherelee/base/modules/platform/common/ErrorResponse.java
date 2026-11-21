package com.wherelee.base.modules.platform.common;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

/**
 * 统一错误响应体：调用方拿到的错误结构稳定为 code / message / traceId / path / timestamp / details。
 *
 * <p>{@code code} 是给程序判断的稳定错误码（见 {@link ErrorCodes}），{@code message} 是给人看的可读文案；
 * {@code traceId} 与响应头 {@code X-Trace-Id} 一致，排查线上问题直接拿它去日志里搜。</p>
 */
public record ErrorResponse(
        String code,
        String message,
        String traceId,
        String path,
        Instant timestamp,
        List<FieldViolation> details
) {

    /** 字段级校验错误（最多保留前若干条，避免响应体过大）。 */
    public record FieldViolation(String field, String message) {
    }

    /** 兼容旧调用：只有消息时按业务错误处理。 */
    public ErrorResponse(String message) {
        this(ErrorCodes.BAD_REQUEST, message, null, null, Instant.now(), List.of());
    }
}
