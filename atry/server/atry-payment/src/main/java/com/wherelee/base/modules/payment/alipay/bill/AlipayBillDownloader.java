package com.wherelee.base.modules.payment.alipay.bill;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayDataDataserviceBillDownloadurlQueryRequest;
import com.alipay.api.response.AlipayDataDataserviceBillDownloadurlQueryResponse;
import com.wherelee.base.modules.platform.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付宝账单下载：先调 {@code alipay.data.dataservice.bill.downloadurl.query} 拿下载地址，再 HTTP 下载账单文件。
 *
 * <p>账单文件是 T+1 数据，通常由定时对账任务在前一天之后拉取；下载地址有时效，拿到后立即下载。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "alipay")
public class AlipayBillDownloader {

    private final AlipayClient client;
    private final AlipayBillProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AlipayBillDownloader(AlipayClient client, AlipayBillProperties properties, ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.safeConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public byte[] download(LocalDate billDate) {
        if (!properties.enabled()) {
            throw BizException.badRequest("支付宝账单对账未启用（app.payment.alipay.bill.enabled=false）");
        }
        String url = queryDownloadUrl(billDate);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(properties.safeReadTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new BizException(HttpStatus.BAD_GATEWAY,
                        "支付宝账单下载失败 HTTP " + response.statusCode());
            }
            log.info("支付宝账单下载完成 billDate={} bytes={}", billDate, response.body().length);
            return response.body();
        } catch (IOException e) {
            throw new BizException(HttpStatus.BAD_GATEWAY, "支付宝账单下载异常：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(HttpStatus.BAD_GATEWAY, "支付宝账单下载被中断");
        }
    }

    private String queryDownloadUrl(LocalDate billDate) {
        AlipayDataDataserviceBillDownloadurlQueryRequest request =
                new AlipayDataDataserviceBillDownloadurlQueryRequest();
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("bill_type", properties.safeBillType());
        biz.put("bill_date", billDate.toString());
        if (properties.smid() != null && !properties.smid().isBlank()) {
            biz.put("smid", properties.smid());
        }
        try {
            request.setBizContent(objectMapper.writeValueAsString(biz));
        } catch (JacksonException e) {
            throw new IllegalStateException("支付宝账单参数序列化失败", e);
        }
        try {
            AlipayDataDataserviceBillDownloadurlQueryResponse response = client.execute(request);
            if (!response.isSuccess() || response.getBillDownloadUrl() == null
                    || response.getBillDownloadUrl().isBlank()) {
                log.warn("支付宝账单下载地址获取失败 billDate={} code={} subCode={} subMsg={}",
                        billDate, response.getCode(), response.getSubCode(), response.getSubMsg());
                throw new BizException(HttpStatus.BAD_GATEWAY,
                        "支付宝账单下载地址获取失败：" + response.getSubCode() + " " + response.getSubMsg());
            }
            return response.getBillDownloadUrl();
        } catch (AlipayApiException e) {
            throw new BizException(HttpStatus.BAD_GATEWAY, "支付宝账单接口异常：" + e.getErrCode());
        }
    }
}
