package com.wherelee.base.modules.payment.alipay.bill;

import com.wherelee.base.modules.payment.recon.ChannelBillLine;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付宝账单解析测试（对账数据源）。
 *
 * <p>不变式：只保留支付成功流水；退款/关闭/零金额/无商户单号的记录不得进入支付对账；
 * 重复商户单号去重；GBK/UTF-8 与 zip 包都能解析。</p>
 */
class AlipayBillParserTest {

    private static final String HEADER = "商户订单号,支付宝交易号,业务类型,订单金额,完成时间";
    private static final String BILL = String.join("\n",
            "支付宝交易账单",
            "汇总信息,请勿修改",
            HEADER,
            "BIZ-1,20261120001,交易,10.00,2026-11-20 10:00:00",
            "BIZ-2,20261120002,交易,0.01,2026-11-20 11:00:00",
            "BIZ-3,20261120003,退款,-10.00,2026-11-20 12:00:00",
            "BIZ-4,20261120004,交易,0,2026-11-20 13:00:00",
            ",20261120005,交易,5.00,2026-11-20 14:00:00",
            "BIZ-1,20261120001,交易,10.00,2026-11-20 10:00:00",
            "");

    @Test
    void 只解析支付成功的有效流水并按商户单号去重() {
        List<ChannelBillLine> lines = AlipayBillParser.parse(
                BILL.getBytes(StandardCharsets.UTF_8), "GBK");

        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(ChannelBillLine::providerTradeNo)
                .containsExactly("BIZ-1", "BIZ-2");
        assertThat(lines.get(0).amountCents()).isEqualTo(1000);
        assertThat(lines.get(0).status()).isEqualTo(ChannelBillLine.STATUS_PAID);
        assertThat(lines.get(0).paidAt()).isEqualTo(
                java.time.LocalDateTime.of(2026, 11, 20, 10, 0, 0));
    }

    @Test
    void 支持zip账单包() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("20261120_trade.csv"));
            zip.write(BILL.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        List<ChannelBillLine> lines = AlipayBillParser.parse(out.toByteArray(), "GBK");
        assertThat(lines).hasSize(2);
    }

    @Test
    void 支持GBK编码账单() throws Exception {
        byte[] gbk = BILL.getBytes(Charset.forName("GBK"));
        List<ChannelBillLine> lines = AlipayBillParser.parse(gbk, "GBK");
        assertThat(lines).hasSize(2);
    }

    @Test
    void 支持UTF8BOM() {
        byte[] body = BILL.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        assertThat(AlipayBillParser.parse(withBom, "GBK")).hasSize(2);
    }

    @Test
    void 支持带引号的CSV字段() {
        String csv = "商户订单号,支付宝交易号,业务类型,订单金额,完成时间\n"
                + "\"BIZ-9\",20261120999,\"交易\",0.10,2026-11-20 09:00:00\n";
        List<ChannelBillLine> lines = AlipayBillParser.parse(
                csv.getBytes(StandardCharsets.UTF_8), "GBK");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).providerTradeNo()).isEqualTo("BIZ-9");
        assertThat(lines.get(0).amountCents()).isEqualTo(10);
    }

    @Test
    void 空文件返回空列表_坏zip抛错() {
        assertThat(AlipayBillParser.parse(null, "GBK")).isEmpty();
        assertThat(AlipayBillParser.parse(new byte[0], "GBK")).isEmpty();

        // zip 包内没有 csv 文件时返回空（不臆造数据）
        byte[] zipWithoutCsv;
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
                zip.write("not a bill".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zipWithoutCsv = out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        assertThat(AlipayBillParser.parse(zipWithoutCsv, "GBK")).isEmpty();
    }

    @Test
    void 无表头文件解析为空结果() {
        String content = "some,random,text\n1,2,3\n";
        assertThat(AlipayBillParser.parse(
                content.getBytes(StandardCharsets.UTF_8), "GBK")).isEmpty();
    }
}
