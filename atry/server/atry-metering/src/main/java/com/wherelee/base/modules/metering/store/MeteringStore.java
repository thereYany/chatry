package com.wherelee.base.modules.metering.store;

import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import com.wherelee.base.modules.contracts.metering.UsageView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 计量计费持久化（首期 MySQL/JdbcTemplate；表按 t_metering_* 归属 metering）。 */
@Repository
public class MeteringStore {

    private final JdbcTemplate jdbc;

    public MeteringStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PriceRow(long rateCardId, BigDecimal unitPrice, String currency, int priceVersion) {
    }

    public record AccountRow(long id, BigDecimal balance, BigDecimal frozen, String currency, String status) {
    }

    public record ReservationRow(long id, String reservationId, long accountId, BigDecimal amount,
                                 String currency, String status) {
    }

    public Optional<PriceRow> findPrice(String meterKey, Instant at) {
        return findPrice(meterKey, at, null);
    }

    public Optional<PriceRow> findPrice(String meterKey, Instant at, String subjectSelector) {
        List<PriceRow> rows = jdbc.query("""
                        SELECT rc.id, rc.unit_price, rc.currency, pb.version
                        FROM t_metering_price_book pb
                        JOIN t_metering_rate_card rc ON rc.book_id = pb.id
                        WHERE rc.meter_key = ? AND pb.status = 'ACTIVE'
                          AND pb.effective_from <= ?
                          AND (pb.effective_to IS NULL OR pb.effective_to > ?)
                          AND (rc.subject_selector IS NULL OR rc.subject_selector = ?)
                        ORDER BY (rc.subject_selector IS NULL) ASC, pb.effective_from DESC, pb.version DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> new PriceRow(rs.getLong("id"), rs.getBigDecimal("unit_price"),
                        rs.getString("currency"), rs.getInt("version")),
                meterKey, java.sql.Timestamp.from(at), java.sql.Timestamp.from(at),
                subjectSelector == null ? "" : subjectSelector);
        return rows.stream().findFirst();
    }

    public boolean insertUsage(UsageCommand command) {
        try {
            jdbc.update("""
                            INSERT INTO t_metering_usage_event
                                (event_id, meter_key, tenant_id, app_id, user_id, quantity, unit,
                                 dimensions_json, occurred_at, source_ref, status)
                            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, 'RECEIVED')
                            """,
                    command.eventId(), command.meterKey(), command.subject().tenantId(),
                    command.subject().appId(), command.subject().userId(), command.quantity(),
                    command.unit(), dimensionsJson(command.dimensions()),
                    java.sql.Timestamp.from(command.occurredAt()), command.sourceRef());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public long insertCharge(String eventId, String meterKey, BigDecimal quantity, BigDecimal amount,
                             String currency, int priceVersion, long rateCardId, String snapshot) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_metering_charge
                        (event_id, meter_key, quantity, amount, currency, price_version, rate_card_id,
                         price_snapshot_json, rated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), CURRENT_TIMESTAMP(3))
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, eventId);
            ps.setString(2, meterKey);
            ps.setBigDecimal(3, quantity);
            ps.setBigDecimal(4, amount);
            ps.setString(5, currency);
            ps.setString(6, Integer.toString(priceVersion));
            ps.setLong(7, rateCardId);
            ps.setString(8, snapshot);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void markEventRated(String eventId, long chargeId) {
        jdbc.update("UPDATE t_metering_usage_event SET status = 'RATED', charge_id = ? WHERE event_id = ?",
                chargeId, eventId);
    }

    public Optional<AccountRow> findAccountById(long accountId, boolean forUpdate) {
        String sql = """
                SELECT id, balance, frozen, currency, status
                FROM t_metering_account WHERE id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        List<AccountRow> rows = jdbc.query(sql,
                (rs, rowNum) -> new AccountRow(rs.getLong("id"), rs.getBigDecimal("balance"),
                        rs.getBigDecimal("frozen"), rs.getString("currency"), rs.getString("status")),
                accountId);
        return rows.stream().findFirst();
    }

    public Optional<AccountRow> findAnyAccount(SubjectRef subject) {
        List<AccountRow> rows = jdbc.query("""
                        SELECT id, balance, frozen, currency, status
                        FROM t_metering_account
                        WHERE tenant_id = ? AND app_id = ? AND user_id = ?
                        ORDER BY id LIMIT 1
                        """,
                (rs, rowNum) -> new AccountRow(rs.getLong("id"), rs.getBigDecimal("balance"),
                        rs.getBigDecimal("frozen"), rs.getString("currency"), rs.getString("status")),
                subject.tenantId(), subject.appId(), subject.userId());
        return rows.stream().findFirst();
    }

    public Optional<AccountRow> findAccount(SubjectRef subject, String currency, boolean forUpdate) {
        String sql = """
                SELECT id, balance, frozen, currency, status
                FROM t_metering_account
                WHERE tenant_id = ? AND app_id = ? AND user_id = ? AND currency = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        List<AccountRow> rows = jdbc.query(sql,
                (rs, rowNum) -> new AccountRow(rs.getLong("id"), rs.getBigDecimal("balance"),
                        rs.getBigDecimal("frozen"), rs.getString("currency"), rs.getString("status")),
                subject.tenantId(), subject.appId(), subject.userId(), currency);
        return rows.stream().findFirst();
    }

    public long insertAccount(SubjectRef subject, String currency) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_metering_account (tenant_id, app_id, user_id, currency, balance, frozen, status)
                    VALUES (?, ?, ?, ?, 0, 0, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, subject.tenantId());
            ps.setLong(2, subject.appId());
            ps.setLong(3, subject.userId());
            ps.setString(4, currency);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    public void addFrozen(long accountId, BigDecimal amount) {
        jdbc.update("UPDATE t_metering_account SET frozen = frozen + ?, version = version + 1 WHERE id = ?",
                amount, accountId);
    }

    public boolean settleAccount(long accountId, BigDecimal reserved, BigDecimal actual) {
        int rows = jdbc.update("""
                        UPDATE t_metering_account
                        SET frozen = frozen - ?, balance = balance - ?, version = version + 1
                        WHERE id = ? AND frozen >= ? AND balance >= ?
                        """,
                reserved, actual, accountId, reserved, actual);
        return rows == 1;
    }

    public void releaseAccount(long accountId, BigDecimal amount) {
        jdbc.update("""
                        UPDATE t_metering_account
                        SET frozen = frozen - ?, version = version + 1
                        WHERE id = ? AND frozen >= ?
                        """,
                amount, accountId, amount);
    }

    public void insertReservation(String reservationId, long accountId, BigDecimal amount,
                                  String currency, Instant expiresAt, String bizRef) {
        jdbc.update("""
                        INSERT INTO t_metering_reservation
                            (reservation_id, account_id, amount, currency, status, biz_ref, expires_at)
                        VALUES (?, ?, ?, ?, 'PENDING', ?, ?)
                        """,
                reservationId, accountId, amount, currency, bizRef,
                java.sql.Timestamp.from(expiresAt));
    }

    public Optional<ReservationRow> findReservationForUpdate(String reservationId) {
        List<ReservationRow> rows = jdbc.query("""
                        SELECT id, reservation_id, account_id, amount, currency, status
                        FROM t_metering_reservation WHERE reservation_id = ? FOR UPDATE
                        """,
                (rs, rowNum) -> new ReservationRow(rs.getLong("id"), rs.getString("reservation_id"),
                        rs.getLong("account_id"), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getString("status")),
                reservationId);
        return rows.stream().findFirst();
    }

    public void markReservation(String reservationId, String status) {
        jdbc.update("""
                UPDATE t_metering_reservation
                SET status = ?, settled_at = CASE WHEN ? = 'SETTLED' THEN CURRENT_TIMESTAMP(3) ELSE settled_at END
                WHERE reservation_id = ?
                """, status, status, reservationId);
    }

    public void insertLedger(long accountId, String direction, BigDecimal amount, String currency,
                             String bizType, String bizNo, BigDecimal balanceAfter) {
        jdbc.update("""
                        INSERT INTO t_metering_ledger
                            (account_id, direction, amount, currency, biz_type, biz_no, balance_after)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                accountId, direction, amount, currency, bizType, bizNo, balanceAfter);
    }

    public List<UsageView> queryUsage(SubjectRef subject, Instant from, Instant to, int limit) {
        return jdbc.query("""
                        SELECT event_id, meter_key, quantity, unit, CAST(dimensions_json AS CHAR) AS dimensions_json,
                               occurred_at
                        FROM t_metering_usage_event
                        WHERE tenant_id = ? AND app_id = ? AND user_id = ? AND occurred_at >= ? AND occurred_at <= ?
                        ORDER BY occurred_at DESC LIMIT ?
                        """,
                (rs, rowNum) -> new UsageView(rs.getString("event_id"), rs.getString("meter_key"),
                        rs.getBigDecimal("quantity"), rs.getString("unit"), rs.getString("dimensions_json"),
                        rs.getTimestamp("occurred_at").toInstant()),
                subject.tenantId(), subject.appId(), subject.userId(),
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), limit);
    }

    private String dimensionsJson(java.util.Map<String, String> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : dimensions.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey().replace("\"", "\\\"")).append("\":\"")
                    .append(entry.getValue() == null ? "" : entry.getValue().replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
