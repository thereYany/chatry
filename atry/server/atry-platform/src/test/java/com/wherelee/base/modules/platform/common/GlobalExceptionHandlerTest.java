package com.wherelee.base.modules.platform.common;

import com.wherelee.base.modules.platform.common.web.TraceIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理器测试。
 *
 * <p>不变式：业务异常保留 HTTP 状态、错误码与可读消息；校验错误带字段明细；
 * 未知/数据库异常 500 且**不泄露**内部异常信息；所有错误体都带 traceId 与 path。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/demo");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 业务异常原样映射状态码与错误码() {
        ResponseEntity<ErrorResponse> response =
                handler.handleBiz(BizException.tooManyRequests("请求过于频繁，请稍后再试"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.RATE_LIMITED);
        assertThat(response.getBody().message()).isEqualTo("请求过于频繁，请稍后再试");
        assertThat(response.getBody().path()).isEqualTo("/api/demo");

        assertThat(handler.handleBiz(BizException.notFound("不存在"), request).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handleBiz(BizException.unauthorized("请先登录"), request).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.handleBiz(BizException.badRequest("参数错误"), request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 自定义错误码优先于状态码兜底() {
        BizException exception = BizException.of(HttpStatus.CONFLICT, "PAYMENT_DUPLICATE", "订单已支付");
        ResponseEntity<ErrorResponse> response = handler.handleBiz(exception, request);
        assertThat(response.getBody().code()).isEqualTo("PAYMENT_DUPLICATE");
        assertThat(response.getBody().message()).isEqualTo("订单已支付");
    }

    @Test
    void 参数校验返回字段明细() {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "username", "用户名不能为空"));
        bindingResult.addError(new FieldError("request", "password", "密码不能为空"));
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(exception, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
        assertThat(response.getBody().message()).isEqualTo("用户名不能为空");
        assertThat(response.getBody().details()).extracting(ErrorResponse.FieldViolation::field)
                .containsExactly("username", "password");
    }

    @Test
    void 参数校验无字段错误时给兜底消息() {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "request");
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(null, bindingResult);

        assertThat(handler.handleValidation(exception, request).getBody().message())
                .isEqualTo("请求参数不正确");
    }

    @Test
    void 静态资源不存在返回404() {
        NoResourceFoundException exception = new NoResourceFoundException(HttpMethod.GET, "/missing.js", "missing.js");
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(exception, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("请求的资源不存在");
    }

    @Test
    void 未知异常返回500且不泄露内部信息() {
        ResponseEntity<ErrorResponse> response =
                handler.handleUnexpected(new RuntimeException("jdbc: connection refused secret-token"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.INTERNAL_ERROR);
        assertThat(response.getBody().message())
                .isEqualTo("服务暂时不可用，请稍后再试")
                .doesNotContain("secret-token", "jdbc", "Exception");
    }

    @Test
    void 数据库异常不泄露SQL() {
        ResponseEntity<ErrorResponse> response = handler.handleDataAccess(
                new DataAccessResourceFailureException("select * from t_user where password='x'"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.DATA_ACCESS_ERROR);
        assertThat(response.getBody().message()).doesNotContain("select", "t_user", "password");
    }

    @Test
    void 错误体带traceId() {
        MDC.put(TraceIdFilter.MDC_KEY, "trace-abc");
        ResponseEntity<ErrorResponse> response = handler.handleBiz(BizException.notFound("不存在"), request);
        assertThat(response.getBody().traceId()).isEqualTo("trace-abc");
    }
}
