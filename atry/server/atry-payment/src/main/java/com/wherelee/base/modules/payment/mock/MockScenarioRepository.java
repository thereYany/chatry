package com.wherelee.base.modules.payment.mock;

import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Mock 剧本存储：一台机器上的测试数据，不参与生产。 */
@Repository
public class MockScenarioRepository {

    private final JdbcTemplate jdbc;

    public MockScenarioRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String paymentNo, MockScenario scenario, int delayMs) {
        jdbc.update("""
                INSERT INTO t_payment_mock_scenario (tenant_id, payment_no, scenario, delay_ms)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE scenario = VALUES(scenario), delay_ms = VALUES(delay_ms)
                """, TenantContext.current(), paymentNo, scenario.name(), Math.max(0, delayMs));
    }

    public Optional<MockScenarioRow> find(String paymentNo) {
        return jdbc.query("""
                SELECT payment_no, scenario, delay_ms FROM t_payment_mock_scenario
                WHERE payment_no = ?
                """, (rs, rowNum) -> new MockScenarioRow(
                rs.getString("payment_no"),
                rs.getString("scenario"),
                rs.getInt("delay_ms")), paymentNo).stream().findFirst();
    }

    public void clear(String paymentNo) {
        jdbc.update("DELETE FROM t_payment_mock_scenario WHERE payment_no = ?", paymentNo);
    }

    public record MockScenarioRow(String paymentNo, String scenario, int delayMs) {
    }
}
