package com.wherelee.base.modules.payment.alipay;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付宝异步通知表单解析测试。
 *
 * <p>不变式：按报文自带 charset 解码（不依赖容器默认编码），URL 解码正确，
 * 非法 charset 回退 UTF-8 而不是抛错。</p>
 */
class AlipayNotifyParserTest {

    @Test
    void 解析普通表单并URL解码() {
        byte[] raw = "out_trade_no=BIZ-1&total_amount=10.00&charset=UTF-8&subject=%E4%B8%AD%E6%96%87"
                .getBytes(StandardCharsets.UTF_8);

        Map<String, String> params = AlipayNotifyParser.parse(raw);
        assertThat(params).containsEntry("out_trade_no", "BIZ-1")
                .containsEntry("total_amount", "10.00")
                .containsEntry("charset", "UTF-8")
                .containsEntry("subject", "中文");
    }

    @Test
    void 按报文声明charset解码GBK中文() throws Exception {
        String encoded = URLEncoder.encode("中文标题", Charset.forName("GBK"));
        byte[] raw = ("charset=GBK&subject=" + encoded).getBytes(StandardCharsets.US_ASCII);

        Map<String, String> params = AlipayNotifyParser.parse(raw);
        assertThat(params.get("subject")).isEqualTo("中文标题");
    }

    @Test
    void 非法charset回退UTF8不抛错() {
        byte[] raw = "charset=NOT-A-CHARSET&subject=%E4%B8%AD%E6%96%87".getBytes(StandardCharsets.US_ASCII);
        Map<String, String> params = AlipayNotifyParser.parse(raw);
        assertThat(params.get("subject")).isEqualTo("中文");
    }

    @Test
    void 无值参数按空串处理_重复键后者覆盖() {
        Map<String, String> params = AlipayNotifyParser.parse(
                "flag&a=1&a=2&&".getBytes(StandardCharsets.UTF_8));
        assertThat(params).containsEntry("flag", "").containsEntry("a", "2");
    }

    @Test
    void 空报文解析为空map() {
        assertThat(AlipayNotifyParser.parse(new byte[0])).isEmpty();
    }
}
