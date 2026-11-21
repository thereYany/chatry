package com.wherelee.base.modules.metering.store;

import com.wherelee.base.modules.contracts.metering.InvoiceItemView;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 账单/对账/充值的持久化。 */
@Repository
public class MeteringBillingStore {

    private final JdbcTemplate jdbc;

    public MeteringBillingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ChargeRow(long chargeId, String eventId, String meterKey, BigDecimal quantity,
                            BigDecimal amount, String currency) {
    }

    public record InvoiceRow(long id, String invoiceNo, SubjectRef subject, Instant from, Instant to,
                             String currency, BigDecimal totalAmount, String status) {
    }

    public boolean ledgerExists(String bizNo) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_metering_ledger WHERE biz_no = ?",
                Integer.class, bizNo);
        return count != null && count > 0;
    }

    public void addBalance(long accountId, BigDecimal amount) {
        jdbc.update("UPDATE t_metering_account SET balance = balance + ?, version = version + 1 WHERE id = ?",
                amount, accountId);
    }

    public void insertInvoiceItem(long invoiceId, long chargeId, String eventId, String meterKey,
                                  BigDecimal quantity, BigDecimal amount, String currency) {
        jdbc.update("""
                        INSERT INTO t_metering_invoice_item
                            (invoice_id, charge_id, event_id, meter_key, quantity, amount, currency)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, invoiceId, chargeId, eventId, meterKey, quantity, amount, currency);
    }

    public long insertInvoice(String invoiceNo, SubjectRef subject, Instant from, Instant to,
                              String currency, BigDecimal totalAmount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_metering_invoice
                        (invoice_no, tenant_id, app_id, user_id, currency, period_start, period_end,
                         total_amount, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'FINALIZED')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, invoiceNo);
            ps.setLong(2, subject.tenantId());
            ps.setLong(3, subject.appId());
            ps.setLong(4, subject.userId());
            ps.setString(5, currency);
            ps.setTimestamp(6, Timestamp.from(from));
            ps.setTimestamp(7, Timestamp.from(to));
            ps.setBigDecimal(8, totalAmount);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    public Optional<InvoiceRow> findInvoiceByPeriod(SubjectRef subject, String currency,
                                                    Instant from, Instant to) {
        List<InvoiceRow> rows = jdbc.query("""
                        SELECT id, invoice_no, tenant_id, app_id, user_id, currency, period_start, period_end,
                               total_amount, status
                        FROM t_metering_invoice
                        WHERE tenant_id = ? AND app_id = ? AND user_id = ? AND currency = ?
                          AND period_start = ? AND period_end = ?
                        """,
                (rs, rowNum) -> invoiceRow(rs),
                subject.tenantId(), subject.appId(), subject.userId(), currency,
                Timestamp.from(from), Timestamp.from(to));
        return rows.stream().findFirst();
    }

    public List<InvoiceRow> queryInvoices(SubjectRef subject, int limit) {
        return jdbc.query("""
                        SELECT id, invoice_no, tenant_id, app_id, user_id, currency, period_start, period_end,
                               total_amount, status
                        FROM t_metering_invoice
                        WHERE tenant_id = ? AND app_id = ? AND user_id = ?
                        ORDER BY id DESC LIMIT ?
                        """,
                (rs, rowNum) -> invoiceRow(rs),
                subject.tenantId(), subject.appId(), subject.userId(), Math.max(1, limit));
    }

    public List<InvoiceItemView> queryInvoiceItems(long invoiceId) {
        return jdbc.query("""
                        SELECT event_id, meter_key, quantity, amount FROM t_metering_invoice_item
                        WHERE invoice_id = ? ORDER BY id
                        """,
                (rs, rowNum) -> new InvoiceItemView(rs.getString("event_id"), rs.getString("meter_key"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("amount")),
                invoiceId);
    }

    public List<ChargeRow> findCharges(SubjectRef subject, Instant from, Instant to, String currency) {
        return jdbc.query("""
                        SELECT c.id, c.event_id, c.meter_key, c.quantity, c.amount, c.currency
                        FROM t_metering_charge c
                        JOIN t_metering_usage_event e ON e.event_id = c.event_id
                        WHERE e.tenant_id = ? AND e.app_id = ? AND e.user_id = ?
                          AND e.occurred_at >= ? AND e.occurred_at <= ?
                          AND c.currency = ?
                        ORDER BY e.occurred_at
                        """,
                (rs, rowNum) -> new ChargeRow(rs.getLong("id"), rs.getString("event_id"),
                        rs.getString("meter_key"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("amount"), rs.getString("currency")),
                subject.tenantId(), subject.appId(), subject.userId(),
                Timestamp.from(from), Timestamp.from(to), currency);
    }

    public int countRatedEvents(Instant from, Instant to) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_metering_usage_event
                WHERE status = 'RATED' AND occurred_at BETWEEN ? AND ?
                """, Integer.class, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0 : count;
    }

    public int countCharges(Instant from, Instant to) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_metering_charge c
                JOIN t_metering_usage_event e ON e.event_id = c.event_id
                WHERE e.occurred_at BETWEEN ? AND ?
                """, Integer.class, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0 : count;
    }

    public List<String> eventsWithoutCharge(Instant from, Instant to, int limit) {
        return jdbc.queryForList("""
                SELECT e.event_id FROM t_metering_usage_event e
                WHERE e.status = 'RATED' AND e.occurred_at BETWEEN ? AND ?
                  AND NOT EXISTS (SELECT 1 FROM t_metering_charge c WHERE c.event_id = e.event_id)
                LIMIT ?
                """, String.class, Timestamp.from(from), Timestamp.from(to), limit);
    }

    public List<String> chargesWithoutEvent(Instant from, Instant to, int limit) {
        return jdbc.queryForList("""
                SELECT c.event_id FROM t_metering_charge c
                LEFT JOIN t_metering_usage_event e ON e.event_id = c.event_id
                WHERE e.id IS NULL LIMIT ?
                """, String.class, limit);
    }

    public BigDecimal sumCharges(Instant from, Instant to) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(c.amount), 0) FROM t_metering_charge c
                JOIN t_metering_usage_event e ON e.event_id = c.event_id
                WHERE e.occurred_at BETWEEN ? AND ?
                """, BigDecimal.class, Timestamp.from(from), Timestamp.from(to));
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal sumSettleLedger(Instant from, Instant to) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM t_metering_ledger
                WHERE biz_type = 'SETTLE' AND created_at BETWEEN ? AND ?
                """, BigDecimal.class, Timestamp.from(from), Timestamp.from(to));
        return value == null ? BigDecimal.ZERO : value;
    }

    public record BalanceRow(long accountId, BigDecimal balance, BigDecimal derived) {
    }

    public List<BalanceRow> findBalanceMismatches() {
        return jdbc.query("""
                        SELECT a.id, a.balance,
                               COALESCE(SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount ELSE -l.amount END), 0) AS derived
                        FROM t_metering_account a
                        LEFT JOIN t_metering_ledger l ON l.account_id = a.id
                        GROUP BY a.id, a.balance
                        HAVING ABS(a.balance - derived) > 0.000001
                        """,
                (rs, rowNum) -> new BalanceRow(rs.getLong("id"), rs.getBigDecimal("balance"),
                        rs.getBigDecimal("derived")));
    }

    public record ExpiringReservation(long id, String reservationId, long accountId, BigDecimal amount) {
    }

    public List<ExpiringReservation> findExpiredPendingReservations(Instant now) {
        return jdbc.query("""
                        SELECT id, reservation_id, account_id, amount FROM t_metering_reservation
                        WHERE status = 'PENDING' AND expires_at <= ? FOR UPDATE
                        """,
                (rs, rowNum) -> new ExpiringReservation(rs.getLong("id"), rs.getString("reservation_id"),
                        rs.getLong("account_id"), rs.getBigDecimal("amount")),
                Timestamp.from(now));
    }

    public void markReservationExpired(String reservationId) {
        jdbc.update("UPDATE t_metering_reservation SET status = 'EXPIRED' WHERE reservation_id = ?", reservationId);
    }

    public long insertReconRun(Instant from, Instant to, boolean balanced, int events, int charges, int diffs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_metering_recon_run
                        (period_start, period_end, status, checked_events, checked_charges, diff_count)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setTimestamp(1, Timestamp.from(from));
            ps.setTimestamp(2, Timestamp.from(to));
            ps.setString(3, balanced ? "BALANCED" : "DIFF");
            ps.setInt(4, events);
            ps.setInt(5, charges);
            ps.setInt(6, diffs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    public void insertReconDiff(long runId, String type, String ref, String expected, String actual,
                                String detail) {
        jdbc.update("""
                        INSERT INTO t_metering_recon_diff (run_id, diff_type, ref, expected, actual, detail)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, runId, type, ref, expected, actual, detail);
    }

    private InvoiceRow invoiceRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InvoiceRow(rs.getLong("id"), rs.getString("invoice_no"),
                new SubjectRef(rs.getLong("tenant_id"), rs.getLong("app_id"), rs.getLong("user_id")),
                rs.getTimestamp("period_start").toInstant(), rs.getTimestamp("period_end").toInstant(),
                rs.getString("currency"), rs.getBigDecimal("total_amount"), rs.getString("status"));
    }
}
