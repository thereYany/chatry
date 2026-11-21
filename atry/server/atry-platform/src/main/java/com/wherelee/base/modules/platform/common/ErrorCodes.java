package com.wherelee.base.modules.platform.common;

import org.springframework.http.HttpStatus;

/**
 * 稳定错误码目录。
 *
 * <p>错误码是接口契约的一部分：只增不改、与 HTTP 状态解耦（同一个 400 可以有多个业务码）。
 * 垂直业务可以在自己的包内定义扩展码，但前缀建议带上域（如 {@code CHAT_QUOTA_EXHAUSTED}）。</p>
 */
public final class ErrorCodes {

    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String CONFLICT = "CONFLICT";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String DATA_ACCESS_ERROR = "DATA_ACCESS_ERROR";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private ErrorCodes() {
    }

    /** 未显式指定错误码时，按 HTTP 状态给一个稳定的兜底码。 */
    public static String fromStatus(HttpStatus status) {
        if (status == null) {
            return INTERNAL_ERROR;
        }
        return switch (status.value()) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 429 -> RATE_LIMITED;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status.is5xxServerError() ? INTERNAL_ERROR : BAD_REQUEST;
        };
    }
}
