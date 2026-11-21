package com.wherelee.base.modules.payment.recon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账持久化：任务、差异、渠道账单登记。
 */
@Repository
public class ReconRepository {

    private final JdbcTemplate jdbc;

    public ReconRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertBill(String channel, LocalDate billDate, String fileKey,
                           String checksum, int lineCount) {
        jdbc.update("""
                INSERT INTO t_channel_bill (channel, bill_date, file_key, checksum, line_count)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    file_key = VALUES(file_key),
                    checksum = VALUES(checksum),
                    line_count = VALUES(line_count),
                    imported_at = CURRENT_TIMESTAMP(3)
                """, channel, Timestamp.valueOf(billDate.atStartOfDay()), fileKey, checksum, lineCount);
    }

    /** 同一渠道同一账期只有一个任务：重跑时复用任务号，清空旧差异后重算。 */
    public String beginTask(String channel, LocalDate billDate) {
        String taskNo = "RECON-" + channel + "-" + billDate;
        jdbc.update("""
                INSERT INTO t_recon_task (task_no, channel, bill_date, status, started_at)
                VALUES (?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE
                    status = 'RUNNING',
                    started_at = CURRENT_TIMESTAMP(3),
                    finished_at = NULL,
                    total_count = 0, match_count = 0, diff_count = 0
                """, taskNo, channel, Timestamp.valueOf(billDate.atStartOfDay()));
        jdbc.update("DELETE FROM t_recon_diff WHERE task_no = ?", taskNo);
        return taskNo;
    }

    public void insertDiff(String taskNo, String paymentNo, String bizNo, String diffType,
                           Long expectedAmount, Long actualAmount, String detail) {
        jdbc.update("""
                INSERT INTO t_recon_diff
                    (task_no, payment_no, biz_no, diff_type, expected_amount, actual_amount, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, taskNo, paymentNo, bizNo, diffType, expectedAmount, actualAmount, detail);
    }

    public void finishTask(String taskNo, int total, int matched, int diffCount) {
        jdbc.update("""
                UPDATE t_recon_task
                SET status = 'DONE', finished_at = CURRENT_TIMESTAMP(3),
                    total_count = ?, match_count = ?, diff_count = ?
                WHERE task_no = ?
                """, total, matched, diffCount, taskNo);
    }

    /** 我方视角：指定渠道、指定账期已支付的支付单。 */
    public List<LocalPayment> findLocalPaid(String channel, LocalDate billDate) {
        return jdbc.query("""
                SELECT payment_no, provider_trade_no, amount_cents, status, paid_at
                FROM t_payment_order
                WHERE channel = ? AND status = 'PAID'
                  AND paid_at >= ? AND paid_at < TIMESTAMPADD(DAY, 1, ?)
                """, (rs, rowNum) -> new LocalPayment(
                rs.getString("payment_no"),
                rs.getString("provider_trade_no"),
                rs.getLong("amount_cents"),
                rs.getString("status"),
                rs.getTimestamp("paid_at").toLocalDateTime()
        ), channel, Timestamp.valueOf(billDate.atStartOfDay()), Timestamp.valueOf(billDate.atStartOfDay()));
    }

    public int countDiffs(String status) {
        Integer value = status == null || status.isBlank()
                ? jdbc.queryForObject("SELECT COUNT(*) FROM t_recon_diff", Integer.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM t_recon_diff WHERE status = ?", Integer.class, status);
        return value == null ? 0 : value;
    }

    public List<ReconDiffView> pageDiffs(String status, int offset, int limit) {
        boolean filtered = status != null && !status.isBlank();
        String where = filtered ? " WHERE status = ?" : "";
        Object[] args = filtered
                ? new Object[]{status, offset, limit}
                : new Object[]{offset, limit};
        return jdbc.query("""
                SELECT id, task_no, payment_no, biz_no, diff_type, expected_amount, actual_amount,
                       detail, status, handled_by, handled_at, created_at
                FROM t_recon_diff
                """ + where + " ORDER BY id DESC LIMIT ?, ?",
                (rs, rowNum) -> new ReconDiffView(
                        rs.getLong("id"),
                        rs.getString("task_no"),
                        rs.getString("payment_no"),
                        rs.getString("biz_no"),
                        rs.getString("diff_type"),
                        rs.getObject("expected_amount") == null ? null : rs.getLong("expected_amount"),
                        rs.getObject("actual_amount") == null ? null : rs.getLong("actual_amount"),
                        rs.getString("detail"),
                        rs.getString("status"),
                        rs.getObject("handled_by") == null ? null : rs.getLong("handled_by"),
                        rs.getTimestamp("handled_at") == null ? null : rs.getTimestamp("handled_at").toLocalDateTime(),
                        rs.getTimestamp("created_at").toLocalDateTime()
                ), args);
    }

    public int handleDiff(Long diffId, Long operatorId, String status) {
        return jdbc.update("""
                UPDATE t_recon_diff
                SET status = ?, handled_by = ?, handled_at = CURRENT_TIMESTAMP(3)
                WHERE id = ? AND status = 'OPEN'
                """, status, operatorId, diffId);
    }

    public List<Long> recentOpenDiffIds(int limit) {
        return jdbc.queryForList(
                "SELECT id FROM t_recon_diff WHERE status = 'OPEN' ORDER BY id DESC LIMIT ?",
                Long.class, limit);
    }

    /** 我方视角的一笔支付。 */
    public record LocalPayment(String paymentNo, String providerTradeNo,
                               long amountCents, String status, LocalDateTime paidAt) {
    }

    /** 差异视图（管理端展示）。 */
    public record ReconDiffView(Long id, String taskNo, String paymentNo, String bizNo,
                                String diffType, Long expectedAmount, Long actualAmount,
                                String detail, String status, Long handledBy,
                                LocalDateTime handledAt, LocalDateTime createdAt) {
    }
}
