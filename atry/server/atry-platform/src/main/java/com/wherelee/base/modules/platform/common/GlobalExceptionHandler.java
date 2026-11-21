package com.wherelee.base.modules.platform.common;

import com.wherelee.base.modules.platform.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

/**
 * 全局异常处理：所有错误统一成 {@link ErrorResponse}，并带上 traceId 方便对日志。
 *
 * <p>安全底线：未知异常/数据库异常只返回兜底文案，绝不把堆栈、SQL、内部类名泄给调用方；
 * 细节只进日志。校验类错误返回字段列表（最多 8 条），前端可以直接标红。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final int MAX_FIELD_ERRORS = 8;

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorResponse> handleBiz(BizException e, HttpServletRequest request) {
        return build(e.status(), e.code(), e.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request) {
        List<ErrorResponse.FieldViolation> details = e.getBindingResult().getFieldErrors().stream()
                .limit(MAX_FIELD_ERRORS)
                .map(error -> new ErrorResponse.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        String message = details.isEmpty() ? "请求参数不正确" : details.get(0).message();
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, message, request, details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleMalformed(Exception e, HttpServletRequest request) {
        log.debug("请求格式错误 path={} error={}", request.getRequestURI(), e.getClass().getSimpleName());
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.MALFORMED_REQUEST, "请求格式不正确", request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                                HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.METHOD_NOT_ALLOWED,
                "请求方法不支持", request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "请求的资源不存在", request, List.of());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException e, HttpServletRequest request) {
        log.error("数据库访问异常 traceId={} path={}", traceId(), request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.DATA_ACCESS_ERROR,
                "服务暂时不可用，请稍后再试", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未处理异常 traceId={} path={}", traceId(), request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                "服务暂时不可用，请稍后再试", request, List.of());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                HttpServletRequest request, List<ErrorResponse.FieldViolation> details) {
        ErrorResponse body = new ErrorResponse(code, message, traceId(),
                request == null ? null : request.getRequestURI(), Instant.now(), details);
        return ResponseEntity.status(status).body(body);
    }

    private String traceId() {
        return TraceIdFilter.currentTraceId();
    }
}
