package com.wherelee.base.modules.payment.alipay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import com.wherelee.base.modules.platform.alert.AlertPublisher;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import com.wherelee.base.modules.payment.PaymentOrderRepository;
import com.wherelee.base.modules.payment.PaymentOrderView;
import com.wherelee.base.modules.payment.PaymentAttemptRecord;
import com.wherelee.base.modules.payment.PaymentConfirmationService;
import com.wherelee.base.modules.payment.PaymentMetrics;
import com.wherelee.base.modules.payment.PaymentNotification;
import com.wherelee.base.modules.payment.PaymentRepository;
import com.wherelee.base.modules.payment.PaymentCallbackService;
import com.wherelee.base.modules.platform.tracing.TraceSpans;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 支付宝异步通知处理。
 * 顺序固定：验签 → 校验 app_id/seller_id → 定位本地支付尝试 → 金额换算 → 幂等确认 → 履约。
 * 验签或归属校验失败返回 failure 让支付宝重投；已入库但业务拒绝（未知订单、金额不符）返回 success
 * 停止重投，由告警与人工核对兜底，避免无意义的 24 小时重试。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "alipay")
public class AlipayNotifyService {

    private static final String CHANNEL = "ALIPAY";

    private final AlipayProperties properties;
    private final PaymentRepository paymentRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentCallbackService paymentCallbackService;
    private final PaymentMetrics metrics;
    private final AlertPublisher alertPublisher;
    private final TraceSpans traceSpans;

    public AlipayNotifyService(AlipayProperties properties,
                               PaymentRepository paymentRepository,
                               PaymentOrderRepository paymentOrderRepository,
                               PaymentCallbackService paymentCallbackService,
                               PaymentMetrics metrics,
                               AlertPublisher alertPublisher,
                               TraceSpans traceSpans) {
        this.properties = properties;
        this.paymentRepository = paymentRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentCallbackService = paymentCallbackService;
        this.metrics = metrics;
        this.alertPublisher = alertPublisher;
        this.traceSpans = traceSpans;
    }

    public String handle(byte[] rawBody) {
        return traceSpans.trace("payment.callback.alipay",
                TraceSpans.tags("payment.provider", "ALIPAY"),
                // 公开回调线程没有登录租户：先按系统作用域跨租户定位支付单，
                // 确认阶段再由 PaymentConfirmationService 回填支付单自己的租户。
                () -> TenantContext.runAsSystem(() -> handleInternal(rawBody)));
    }

    private String handleInternal(byte[] rawBody) {
        Map<String, String> params;
        try {
            params = AlipayNotifyParser.parse(rawBody);
        } catch (Exception e) {
            metrics.callbackInvalid();
            log.warn("支付宝回调报文解析失败", e);
            return "failure";
        }

        String charset = params.getOrDefault("charset", properties.charset());
        boolean verified;
        try {
            verified = AlipaySignature.rsaCheckV1(params, properties.alipayPublicKey(), charset, properties.signType());
        } catch (AlipayApiException e) {
            log.warn("支付宝回调验签异常 notifyId={}", params.get("notify_id"), e);
            verified = false;
        }
        if (!verified) {
            metrics.callbackInvalid();
            log.warn("支付宝回调验签失败 notifyId={} outTradeNo={}", params.get("notify_id"), params.get("out_trade_no"));
            return "failure";
        }

        if (!properties.appId().equals(params.get("app_id"))) {
            metrics.callbackInvalid();
            alert("PAYMENT_NOTIFY_APP_MISMATCH", "支付宝回调 app_id 不匹配：" + params.get("app_id"));
            return "failure";
        }
        String sellerId = properties.sellerId();
        if (sellerId != null && !sellerId.isBlank() && !sellerId.equals(params.get("seller_id"))) {
            metrics.callbackInvalid();
            alert("PAYMENT_NOTIFY_SELLER_MISMATCH", "支付宝回调 seller_id 不匹配：" + params.get("seller_id"));
            return "failure";
        }

        String tradeStatus = params.get("trade_status");
        String eventType = mapEvent(tradeStatus);
        if (eventType == null) {
            // WAIT_BUYER_PAY 等中间态不需要落库，确认收到即可
            return "success";
        }

        String outTradeNo = params.get("out_trade_no");
        PaymentAttemptRecord attempt = outTradeNo == null || outTradeNo.isBlank()
                ? null
                : paymentRepository.findAttemptByProviderTradeNo(outTradeNo).orElse(null);
        if (attempt == null) {
            alert("PAYMENT_UNKNOWN_ORDER", "支付宝回调找不到支付尝试 outTradeNo=" + outTradeNo);
            return "success";
        }
        PaymentOrderView payment = paymentOrderRepository
                .findByProviderTradeNo(CHANNEL, outTradeNo).orElse(null);
        if (payment == null) {
            alert("PAYMENT_UNKNOWN_ORDER", "支付宝回调找不到支付单 outTradeNo=" + outTradeNo);
            return "success";
        }

        long amountCents;
        try {
            amountCents = AlipayAmounts.toCents(params.get("total_amount"));
        } catch (Exception e) {
            alert("PAYMENT_NOTIFY_MALFORMED", "支付宝回调金额无法解析 paymentNo=" + payment.paymentNo()
                    + " totalAmount=" + params.get("total_amount"));
            return "failure";
        }

        paymentRepository.updateProviderState(attempt.id(), tradeStatus, amountCents, null);

        String eventKey = params.get("notify_id");
        if (eventKey == null || eventKey.isBlank()) {
            eventKey = params.get("trade_no") + ":" + tradeStatus;
        }
        log.info("payment_callback_verified notifyId={} outTradeNo={} tradeNo={} tradeStatus={} amountCents={} paymentNo={} attemptNo={}",
                eventKey, outTradeNo, params.get("trade_no"), tradeStatus,
                amountCents, payment.paymentNo(), attempt.attemptNo());
        PaymentNotification notification = new PaymentNotification(
                CHANNEL, eventKey, payment.bizNo(), eventType, amountCents, params.get("trade_no"), null);
        try {
            PaymentConfirmationService.ConfirmationResult result = paymentCallbackService.acceptVerified(
                    notification, payment.paymentNo());
            log.info("payment_callback_processed notifyId={} orderNo={} success={} duplicate={} paymentStatus={}",
                    eventKey, payment.bizNo(), result.success(), result.duplicate(), result.status());
        } catch (Exception e) {
            log.error("支付宝回调处理异常 paymentNo={} notifyId={} outTradeNo={} tradeNo={}",
                    payment.paymentNo(), eventKey, outTradeNo, params.get("trade_no"), e);
            return "failure";
        }
        log.info("支付宝回调已处理 paymentNo={} tradeStatus={} notifyId={}",
                payment.paymentNo(), tradeStatus, eventKey);
        return "success";
    }

    private String mapEvent(String tradeStatus) {
        if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
            return "PAYMENT_SUCCESS";
        }
        if ("TRADE_CLOSED".equals(tradeStatus)) {
            return "PAYMENT_FAILED";
        }
        return null;
    }

    private void alert(String type, String message) {
        log.error(message);
        try {
            alertPublisher.publish(type, "ERROR", message, false,
                    "webhook delivery not configured for payment alerts");
        } catch (Exception e) {
            log.warn("支付告警入库失败 type={}", type, e);
        }
    }
}
