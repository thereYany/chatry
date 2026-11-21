package com.wherelee.base.modules.payment.alipay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.payment.PaymentOrderView;
import com.wherelee.base.modules.payment.PaymentAttemptRecord;
import com.wherelee.base.modules.payment.PaymentGateway;
import com.wherelee.base.modules.payment.RefundRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付宝电脑网站支付渠道。
 * 下单用 pageExecute GET，返回收银台 URL 由浏览器整页跳转，避免自提交表单被 CSP form-action 拦截。
 * 查单与退款失败时一律不臆断资金状态：查不到交易按未支付处理，退款未确认按失败交给补偿任务重试。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "alipay")
public class AlipayPaymentGateway implements PaymentGateway {

    private static final String CHANNEL = "ALIPAY";
    private static final String PRODUCT_CODE = "FAST_INSTANT_TRADE_PAY";

    private final AlipayClient client;
    private final AlipayProperties properties;
    private final ObjectMapper objectMapper;

    public AlipayPaymentGateway(AlipayClient client, AlipayProperties properties, ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public String outTradeNo(PaymentOrderView order, int attemptNo) {
        return order.bizNo() + "_" + attemptNo;
    }

    @Override
    public PaymentAction initiate(PaymentOrderView order, PaymentAttemptRecord attempt) {
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        if (hasText(properties.notifyUrl())) {
            request.setNotifyUrl(properties.notifyUrl());
        }
        if (hasText(properties.returnUrl())) {
            request.setReturnUrl(properties.returnUrl() + "/" + order.bizNo());
        }

        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", attempt.providerTradeNo());
        biz.put("total_amount", AlipayAmounts.toYuan(order.amountCents()));
        biz.put("subject", order.subject());
        biz.put("product_code", PRODUCT_CODE);
        String timeout = timeoutExpress(order);
        if (timeout != null) {
            biz.put("timeout_express", timeout);
        }
        request.setBizContent(toJson(biz));

        try {
            AlipayTradePagePayResponse response = client.pageExecute(request, "GET");
            if (!response.isSuccess() || response.getBody() == null || response.getBody().isBlank()) {
                log.error("支付宝下单失败 orderNo={} code={} subCode={} subMsg={}",
                        order.bizNo(), response.getCode(), response.getSubCode(), response.getSubMsg());
                throw new BizException(HttpStatus.BAD_GATEWAY,
                        "支付宝下单失败：" + message(response.getSubCode(), response.getSubMsg()));
            }
            return new PaymentAction(response.getBody());
        } catch (AlipayApiException e) {
            log.error("支付宝下单异常 orderNo={}", order.bizNo(), e);
            throw new BizException(HttpStatus.BAD_GATEWAY, "支付宝下单异常，请稍后重试");
        }
    }

    @Override
    public PaymentQueryResult query(PaymentOrderView order, PaymentAttemptRecord attempt) {
        if (attempt == null || attempt.providerTradeNo() == null || attempt.providerTradeNo().isBlank()) {
            return new PaymentQueryResult("PENDING", order.amountCents(), null, "尚无支付尝试");
        }
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        request.setBizContent(toJson(Map.of("out_trade_no", attempt.providerTradeNo())));
        try {
            AlipayTradeQueryResponse response = client.execute(request);
            if (!response.isSuccess()) {
                // 交易不存在是明确事实；其余接口错误仍按未知处理，不能把故障当成支付失败
                if ("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) {
                    return new PaymentQueryResult("NOT_FOUND", order.amountCents(), attempt.providerTradeNo(),
                            message(response.getSubCode(), response.getSubMsg()));
                }
                return new PaymentQueryResult("PENDING", order.amountCents(), attempt.providerTradeNo(),
                        message(response.getSubCode(), response.getSubMsg()));
            }
            long amount = response.getTotalAmount() == null
                    ? order.amountCents()
                    : AlipayAmounts.toCents(response.getTotalAmount());
            return new PaymentQueryResult(mapTradeStatus(response.getTradeStatus()), amount,
                    response.getTradeNo(), response.getTradeStatus());
        } catch (AlipayApiException e) {
            log.warn("支付宝查单异常 orderNo={} errCode={}", order.bizNo(), e.getErrCode(), e);
            return new PaymentQueryResult("PENDING", order.amountCents(), attempt.providerTradeNo(),
                    "查单异常：" + e.getErrCode());
        }
    }

    @Override
    public CloseResult close(PaymentOrderView order, PaymentAttemptRecord attempt) {
        if (attempt == null || !hasText(attempt.providerTradeNo())) {
            return CloseResult.notFound("缺少商户单号，无需关单");
        }
        AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
        request.setBizContent(toJson(Map.of("out_trade_no", attempt.providerTradeNo())));
        try {
            AlipayTradeCloseResponse response = client.execute(request);
            if (response.isSuccess()) {
                return CloseResult.closedOk();
            }
            if ("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) {
                return CloseResult.notFound(message(response.getSubCode(), response.getSubMsg()));
            }
            log.warn("支付宝关单失败 orderNo={} outTradeNo={} subCode={} subMsg={}",
                    order.bizNo(), attempt.providerTradeNo(), response.getSubCode(), response.getSubMsg());
            return CloseResult.failed(message(response.getSubCode(), response.getSubMsg()));
        } catch (AlipayApiException e) {
            log.warn("支付宝关单异常 orderNo={} outTradeNo={} errCode={}",
                    order.bizNo(), attempt.providerTradeNo(), e.getErrCode(), e);
            return CloseResult.failed("关单异常：" + e.getErrCode());
        }
    }

    @Override
    public RefundResult refund(PaymentOrderView order, RefundRecord refund, boolean simulateFailure) {
        if (simulateFailure) {
            return new RefundResult(false, null, "支付宝渠道不支持模拟退款失败");
        }
        if (order.providerTradeNo() == null || order.providerTradeNo().isBlank()) {
            return new RefundResult(false, null, "缺少支付宝交易号，无法退款");
        }
        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("trade_no", order.providerTradeNo());
        biz.put("refund_amount", AlipayAmounts.toYuan(refund.amountCents()));
        biz.put("out_request_no", refund.refundNo());
        if (refund.reason() != null && !refund.reason().isBlank()) {
            biz.put("refund_reason", refund.reason());
        }
        request.setBizContent(toJson(biz));
        try {
            AlipayTradeRefundResponse response = client.execute(request);
            if (!response.isSuccess()) {
                log.warn("支付宝退款失败 orderNo={} refundNo={} subCode={} subMsg={}",
                        order.bizNo(), refund.refundNo(), response.getSubCode(), response.getSubMsg());
                return new RefundResult(false, null, message(response.getSubCode(), response.getSubMsg()));
            }
            if ("Y".equals(response.getFundChange())) {
                return new RefundResult(true, response.getTradeNo(), null);
            }
            // 受理成功但资金未变动，必须查退款单确认，不能直接当成功
            return confirmRefund(order, refund);
        } catch (AlipayApiException e) {
            log.error("支付宝退款异常 orderNo={} refundNo={}", order.bizNo(), refund.refundNo(), e);
            return new RefundResult(false, null, "支付宝退款异常：" + e.getErrCode());
        }
    }

    private RefundResult confirmRefund(PaymentOrderView order, RefundRecord refund) throws AlipayApiException {
        AlipayTradeFastpayRefundQueryRequest request = new AlipayTradeFastpayRefundQueryRequest();
        request.setBizContent(toJson(Map.of(
                "trade_no", order.providerTradeNo(),
                "out_request_no", refund.refundNo())));
        AlipayTradeFastpayRefundQueryResponse response = client.execute(request);
        if (response.isSuccess() && "REFUND_SUCCESS".equals(response.getRefundStatus())) {
            return new RefundResult(true, response.getOutRequestNo(), null);
        }
        return new RefundResult(false, null,
                "退款未确认：" + message(response.getSubCode(), response.getRefundStatus()));
    }

    private String mapTradeStatus(String tradeStatus) {
        if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
            return "PAID";
        }
        if ("TRADE_CLOSED".equals(tradeStatus)) {
            return "FAILED";
        }
        return "PENDING";
    }

    /**
     * 让支付宝侧的超时与本地订单有效期一致，避免本地关单后用户仍能付款。
     */
    private String timeoutExpress(PaymentOrderView order) {
        if (order.expireAt() == null) {
            return null;
        }
        long minutes = Duration.between(LocalDateTime.now(), order.expireAt()).toMinutes();
        if (minutes < 1) {
            minutes = 1;
        }
        return minutes + "m";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String message(String code, String text) {
        if (text != null && !text.isBlank()) {
            return code == null || code.isBlank() ? text : code + " " + text;
        }
        return code == null || code.isBlank() ? "支付宝未返回原因" : code;
    }

    private String toJson(Map<String, ?> biz) {
        try {
            return objectMapper.writeValueAsString(biz);
        } catch (JacksonException e) {
            throw new IllegalStateException("支付宝业务参数序列化失败", e);
        }
    }
}
