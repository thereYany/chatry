package com.wherelee.base.modules.payment.recon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Mock 渠道的账单数据源：把「支付尝试表里渠道回报的结果」当作渠道账单。
 *
 * <p>这不是循环论证：支付尝试里的 provider_status / provider_amount_cents 记录的是
 * **渠道回调/查单时对方告诉我们的事实**，与我方支付单状态是两条独立数据来源。
 * 真实渠道接入时替换为账单文件解析或账单接口拉取。</p>
 */
@Component
public class MockChannelBillProvider implements ChannelBillProvider {

    private final JdbcTemplate jdbc;

    public MockChannelBillProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean supports(String channel) {
        return ChannelBillProvider.CHANNEL_MOCK.equalsIgnoreCase(channel);
    }

    @Override
    public List<ChannelBillLine> fetch(String channel, LocalDate billDate) {
        return jdbc.query("""
                SELECT provider_trade_no, provider_amount_cents, status, created_at
                FROM t_payment_attempt
                WHERE channel = ?
                  AND status IN ('SUCCESS', 'FAILED')
                  AND created_at >= ? AND created_at < TIMESTAMPADD(DAY, 1, ?)
                ORDER BY id
                """, (rs, rowNum) -> new ChannelBillLine(
                rs.getString("provider_trade_no"),
                rs.getLong("provider_amount_cents"),
                "SUCCESS".equals(rs.getString("status"))
                        ? ChannelBillLine.STATUS_PAID
                        : ChannelBillLine.STATUS_FAILED,
                rs.getTimestamp("created_at").toLocalDateTime()
        ), channel, billDate.atStartOfDay(), billDate.atStartOfDay());
    }
}
