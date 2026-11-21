package com.wherelee.base.modules.platform.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：HTTP 状态 + 稳定错误码 + 可读消息。
 *
 * <p>构造时未指定错误码的，按状态码兜底（{@link ErrorCodes#fromStatus}）；
 * 需要让调用方精确分支时，用三参构造或 {@link #of(HttpStatus, String, String)} 显式给码。</p>
 */
public class BizException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BizException(HttpStatus status, String message) {
        this(status, ErrorCodes.fromStatus(status), message);
    }

    public BizException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code == null || code.isBlank() ? ErrorCodes.fromStatus(status) : code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static BizException of(HttpStatus status, String code, String message) {
        return new BizException(status, code, message);
    }

    public static BizException badRequest(String message) {
        return new BizException(HttpStatus.BAD_REQUEST, message);
    }

    public static BizException unauthorized(String message) {
        return new BizException(HttpStatus.UNAUTHORIZED, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(HttpStatus.FORBIDDEN, message);
    }

    public static BizException notFound(String message) {
        return new BizException(HttpStatus.NOT_FOUND, message);
    }

    public static BizException tooManyRequests(String message) {
        return new BizException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    public static BizException conflict(String message) {
        return new BizException(HttpStatus.CONFLICT, message);
    }
}
