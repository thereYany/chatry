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
import com.wherelee.base.modules.payment.PaymentAttemptRecord;
import com.wherelee.base.modules.payment.PaymentGateway;
import com.wherelee.base.modules.payment.PaymentOrderView;
import com.wherelee.base.modules.payment.RefundRecord;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 支付宝渠道适配器测试（协议/状态映射，不访问真实网络）。
 *
 * <p>业务不变式：查单失败不能臆断资金状态；交易不存在是明确事实（NOT_FOUND），
 * 其他接口异常按未知（PENDING）处理；关单失败不能本地关单；退款「受理成功但资金未变动」
 * 必须查退款单确认，不能直接当成功。</p>
 */
class AlipayPaymentGatewayTest {

    private AlipayClient client;
    private AlipayPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        client = mock(AlipayClient.class);
        AlipayProperties properties = new AlipayProperties("app-id", "private-key", "public-key",
                "seller-id", "https://gateway", null, null, "UTF-8", "RSA2", "json", 5000, 15000);
        gateway = new AlipayPaymentGateway(client, properties, new ObjectMapper());
    }

    private static PaymentOrderView order() {
        return new PaymentOrderView("P1", "BIZ-1", "atry", "ORDER", "商品", 1000, "CNY",
                "PAYING", "ALIPAY", "20261120001", LocalDateTime.now().plusMinutes(15), null, 0L);
    }

    private static PaymentAttemptRecord attempt() {
        return new PaymentAttemptRecord(1L, null, 1, "ALIPAY", "PROCESSING", "BIZ-1_1",
                "CREATED", 1000, null, null, null, null, LocalDateTime.now(), null);
    }

    private static RefundRecord refund() {
        return new RefundRecord(1L, "RF-1", null, "P1", 1000, "PROCESSING", 0, null, null,
                "退款", 7L, LocalDateTime.now(), null, null);
    }

    private static AlipayTradeQueryResponse queryResponse(String code, String tradeStatus,
                                                          String totalAmount, String tradeNo) {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode(code);
        response.setTradeStatus(tradeStatus);
        response.setTotalAmount(totalAmount);
        response.setTradeNo(tradeNo);
        return response;
    }

    @Test
    void 查单成功映射已支付并换算金额() throws Exception {
        doReturn(queryResponse("10000", "TRADE_SUCCESS", "10.00", "ALI-TRADE-1"))
                .when(client).execute(any(AlipayTradeQueryRequest.class));

        PaymentGateway.PaymentQueryResult result = gateway.query(order(), attempt());

        assertThat(result.status()).isEqualTo("PAID");
        assertThat(result.amountCents()).isEqualTo(1000);
        assertThat(result.tradeNo()).isEqualTo("ALI-TRADE-1");
    }

    @Test
    void 查单交易关闭映射失败_等待付款映射待定() throws Exception {
        doReturn(queryResponse("10000", "TRADE_CLOSED", "10.00", "T"))
                .when(client).execute(any(AlipayTradeQueryRequest.class));
        assertThat(gateway.query(order(), attempt()).status()).isEqualTo("FAILED");

        doReturn(queryResponse("10000", "WAIT_BUYER_PAY", "10.00", "T"))
                .when(client).execute(any(AlipayTradeQueryRequest.class));
        assertThat(gateway.query(order(), attempt()).status()).isEqualTo("PENDING");
    }

    @Test
    void 交易不存在是明确事实_其他接口错误按未知处理() throws Exception {
        AlipayTradeQueryResponse notExist = new AlipayTradeQueryResponse();
        notExist.setCode("40004");
        notExist.setSubCode("ACQ.TRADE_NOT_EXIST");
        doReturn(notExist).when(client).execute(any(AlipayTradeQueryRequest.class));
        assertThat(gateway.query(order(), attempt()).status()).isEqualTo("NOT_FOUND");

        AlipayTradeQueryResponse systemError = new AlipayTradeQueryResponse();
        systemError.setCode("20000");
        systemError.setSubCode("aop.system-error");
        doReturn(systemError).when(client).execute(any(AlipayTradeQueryRequest.class));
        assertThat(gateway.query(order(), attempt()).status()).isEqualTo("PENDING");
    }

    @Test
    void 查单异常不臆断资金状态() throws Exception {
        doThrow(new AlipayApiException("network")).when(client)
                .execute(any(AlipayTradeQueryRequest.class));
        PaymentGateway.PaymentQueryResult result = gateway.query(order(), attempt());
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.message()).contains("查单异常");
    }

    @Test
    void 没有支付尝试时查单直接返回待定() {
        assertThat(gateway.query(order(), null).status()).isEqualTo("PENDING");
        PaymentAttemptRecord noTrade = new PaymentAttemptRecord(1L, null, 1, "ALIPAY", "PROCESSING",
                null, "CREATED", 1000, null, null, null, null, LocalDateTime.now(), null);
        assertThat(gateway.query(order(), noTrade).status()).isEqualTo("PENDING");
    }

    @Test
    void 关单成功与交易不存在都视为已关闭() throws Exception {
        AlipayTradeCloseResponse success = new AlipayTradeCloseResponse();
        success.setCode("10000");
        doReturn(success).when(client).execute(any(AlipayTradeCloseRequest.class));
        assertThat(gateway.close(order(), attempt()).closed()).isTrue();

        AlipayTradeCloseResponse gone = new AlipayTradeCloseResponse();
        gone.setCode("40004");
        gone.setSubCode("ACQ.TRADE_NOT_EXIST");
        doReturn(gone).when(client).execute(any(AlipayTradeCloseRequest.class));
        PaymentGateway.CloseResult result = gateway.close(order(), attempt());
        assertThat(result.closed()).isFalse();
        assertThat(result.notFound()).isTrue();
    }

    @Test
    void 关单失败与异常都不能当成已关闭() throws Exception {
        AlipayTradeCloseResponse failed = new AlipayTradeCloseResponse();
        failed.setCode("40004");
        failed.setSubCode("ACQ.SYSTEM_ERROR");
        doReturn(failed).when(client).execute(any(AlipayTradeCloseRequest.class));
        PaymentGateway.CloseResult result = gateway.close(order(), attempt());
        assertThat(result.closed()).isFalse();
        assertThat(result.notFound()).isFalse();

        doThrow(new AlipayApiException("boom")).when(client)
                .execute(any(AlipayTradeCloseRequest.class));
        assertThat(gateway.close(order(), attempt()).closed()).isFalse();
        // 没有渠道单号时无需关单
        assertThat(gateway.close(order(), null).notFound()).isTrue();
    }

    @Test
    void 退款资金已变动视为成功() throws Exception {
        AlipayTradeRefundResponse refunded = new AlipayTradeRefundResponse();
        refunded.setCode("10000");
        refunded.setFundChange("Y");
        refunded.setTradeNo("ALI-TRADE-1");
        doReturn(refunded).when(client).execute(any(AlipayTradeRefundRequest.class));

        PaymentGateway.RefundResult result = gateway.refund(order(), refund(), false);
        assertThat(result.success()).isTrue();
        assertThat(result.providerRefundNo()).isEqualTo("ALI-TRADE-1");
    }

    @Test
    void 退款受理但资金未变动时必须查退款单确认() throws Exception {
        AlipayTradeRefundResponse accepted = new AlipayTradeRefundResponse();
        accepted.setCode("10000");
        accepted.setFundChange("N");
        doReturn(accepted).when(client).execute(any(AlipayTradeRefundRequest.class));

        AlipayTradeFastpayRefundQueryResponse confirmed = new AlipayTradeFastpayRefundQueryResponse();
        confirmed.setCode("10000");
        confirmed.setRefundStatus("REFUND_SUCCESS");
        confirmed.setOutRequestNo("RF-1");
        doReturn(confirmed).when(client).execute(any(AlipayTradeFastpayRefundQueryRequest.class));

        PaymentGateway.RefundResult result = gateway.refund(order(), refund(), false);
        assertThat(result.success()).isTrue();

        AlipayTradeFastpayRefundQueryResponse pending = new AlipayTradeFastpayRefundQueryResponse();
        pending.setCode("10000");
        pending.setRefundStatus("REFUND_PROCESSING");
        doReturn(pending).when(client).execute(any(AlipayTradeFastpayRefundQueryRequest.class));
        PaymentGateway.RefundResult unclear = gateway.refund(order(), refund(), false);
        assertThat(unclear.success()).isFalse();
        assertThat(unclear.message()).contains("退款未确认");
    }

    @Test
    void 退款前置校验与异常都返回失败() {
        assertThat(gateway.refund(order(), refund(), true).success()).isFalse();
        assertThat(gateway.refund(order(), refund(), true).message()).contains("不支持");
    }

    @Test
    void 退款异常返回失败而不是成功() throws Exception {
        doThrow(new AlipayApiException("network")).when(client)
                .execute(any(AlipayTradeRefundRequest.class));
        PaymentGateway.RefundResult result = gateway.refund(order(), refund(), false);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("退款异常");
    }

    @Test
    void 下单成功返回收银台地址_失败抛502() throws Exception {
        AlipayTradePagePayResponse success = new AlipayTradePagePayResponse();
        success.setCode("10000");
        success.setBody("https://openapi.alipay.com/gateway.do?token=1");
        doReturn(success).when(client).pageExecute(any(AlipayTradePagePayRequest.class), eq("GET"));

        PaymentGateway.PaymentAction action = gateway.initiate(order(), attempt());
        assertThat(action.cashierUrl()).contains("gateway.do");

        AlipayTradePagePayResponse failed = new AlipayTradePagePayResponse();
        failed.setCode("40004");
        failed.setSubMsg("业务处理失败");
        doReturn(failed).when(client).pageExecute(any(AlipayTradePagePayRequest.class), eq("GET"));
        assertThatThrownBy(() -> gateway.initiate(order(), attempt()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("支付宝下单失败");

        doThrow(new AlipayApiException("boom")).when(client)
                .pageExecute(any(AlipayTradePagePayRequest.class), eq("GET"));
        assertThatThrownBy(() -> gateway.initiate(order(), attempt()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("下单异常");
    }
}
