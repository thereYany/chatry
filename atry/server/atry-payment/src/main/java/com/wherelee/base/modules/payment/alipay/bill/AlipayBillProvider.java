package com.wherelee.base.modules.payment.alipay.bill;

import com.wherelee.base.modules.payment.recon.ChannelBillLine;
import com.wherelee.base.modules.payment.recon.ChannelBillProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 支付宝渠道账单数据源：下载 T+1 交易账单并解析成渠道流水，供对账核心使用。
 *
 * <p>只有 {@code app.payment.alipay.bill.enabled=true} 时才会注册；未开启时对账服务会对
 * ALIPAY 渠道报“未接入对账数据源”，与旧行为一致。</p>
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.payment.provider:mock}' == 'alipay' "
        + "and '${app.payment.alipay.bill.enabled:false}' == 'true'")
public class AlipayBillProvider implements ChannelBillProvider {

    public static final String CHANNEL_ALIPAY = "ALIPAY";

    private final AlipayBillDownloader downloader;
    private final AlipayBillProperties properties;

    public AlipayBillProvider(AlipayBillDownloader downloader, AlipayBillProperties properties) {
        this.downloader = downloader;
        this.properties = properties;
    }

    @Override
    public boolean supports(String channel) {
        return CHANNEL_ALIPAY.equalsIgnoreCase(channel);
    }

    @Override
    public List<ChannelBillLine> fetch(String channel, LocalDate billDate) {
        byte[] raw = downloader.download(billDate);
        List<ChannelBillLine> lines = AlipayBillParser.parse(raw, properties.safeCharset());
        if (lines.isEmpty()) {
            // 当天没有支付流水是正常情况（本地如有已支付流水，对账会报 LOCAL_ONLY，正好暴露差异）。
            log.warn("支付宝账单解析出 0 条支付流水 billDate={}，若本地有支付请核对账单类型/日期/文件格式", billDate);
        }
        return lines;
    }

    @Override
    public String sourceKey(String channel, LocalDate billDate) {
        return "alipay://bill/" + properties.safeBillType() + "/" + billDate;
    }
}
