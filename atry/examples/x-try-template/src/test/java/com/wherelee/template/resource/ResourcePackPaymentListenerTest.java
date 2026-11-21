package com.wherelee.template.resource;

import com.wherelee.base.modules.payment.api.PaymentEventTypes;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模板垂直履约幂等测试（真实 MySQL，无 Spring 上下文：示例工程不在内核 reactor 内）。
 *
 * <p>业务不变式：消费 PAYMENT_SUCCEEDED 时只履约 RESOURCE_PACK 事件；重复投递
 * （发件箱 at-least-once 语义）不得重复发放授权；未知订单必须显式失败以触发重试/死信，
 * 不能静默吞掉。</p>
 */
@Tag("integration")
class ResourcePackPaymentListenerTest {

    private JdbcTemplate jdbc;
    private ResourcePackPaymentListener listener;
    private long packId;
    private String orderNo;

    @BeforeEach
    void setUp() {
        String url = System.getenv().getOrDefault("IT_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/atry_it?allowPublicKeyRetrieval=true&useSSL=false"
                        + "&allowMultiQueries=true");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(System.getenv().getOrDefault("IT_DB_USER", "atry"));
        dataSource.setPassword(System.getenv().getOrDefault("IT_DB_PASSWORD", "atrypass"));
        jdbc = new JdbcTemplate(dataSource);
        listener = new ResourcePackPaymentListener(jdbc);

        // 模板迁移 V900 的三张表（若已存在则复用）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS t_resource_pack (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id BIGINT NOT NULL DEFAULT 0,
                    title VARCHAR(120) NOT NULL,
                    description VARCHAR(500) NULL,
                    price_cents BIGINT NOT NULL DEFAULT 0,
                    status VARCHAR(20) NOT NULL DEFAULT 'ON_SALE',
                    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS t_resource_pack_order (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id BIGINT NOT NULL DEFAULT 0,
                    order_no VARCHAR(40) NOT NULL,
                    pack_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    amount_cents BIGINT NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
                    payment_no VARCHAR(40) NULL,
                    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    paid_at DATETIME(3) NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_rp_order_tenant_no (tenant_id, order_no)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS t_resource_pack_grant (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id BIGINT NOT NULL DEFAULT 0,
                    user_id BIGINT NOT NULL,
                    pack_id BIGINT NOT NULL,
                    order_no VARCHAR(40) NOT NULL,
                    granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_rp_ent (tenant_id, user_id, pack_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        orderNo = "IT-RP-" + System.nanoTime();
        jdbc.update("INSERT INTO t_resource_pack (tenant_id, title, price_cents) VALUES (0, ?, 9900)",
                "集成测试资料包");
        packId = jdbc.queryForObject("SELECT MAX(id) FROM t_resource_pack", Long.class);
        jdbc.update("""
                INSERT INTO t_resource_pack_order (tenant_id, order_no, pack_id, user_id,
                                                   amount_cents, status)
                VALUES (0, ?, ?, 42, 9900, 'PAYING')
                """, orderNo, packId);
    }

    private static PaymentSettledEvent event(String eventType, String bizType, String bizNo) {
        return new PaymentSettledEvent(eventType, "P" + bizNo, bizType, bizNo, 9900, "CNY",
                "MOCK", "TRADE-1", LocalDateTime.now());
    }

    private void cleanup() {
        jdbc.update("DELETE FROM t_resource_pack_grant WHERE order_no = ?", orderNo);
        jdbc.update("DELETE FROM t_resource_pack_order WHERE order_no = ?", orderNo);
        jdbc.update("DELETE FROM t_resource_pack WHERE id = ?", packId);
    }

    @Test
    void 重复投递只履约一次_授权唯一() {
        try {
            // 发件箱 at-least-once：同一事件投递两次
            listener.onSettled(event(PaymentEventTypes.PAYMENT_SUCCEEDED, "RESOURCE_PACK", orderNo));
            listener.onSettled(event(PaymentEventTypes.PAYMENT_SUCCEEDED, "RESOURCE_PACK", orderNo));

            assertThat(jdbc.queryForObject(
                    "SELECT status FROM t_resource_pack_order WHERE order_no = ?", String.class, orderNo))
                    .isEqualTo("PAID");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_resource_pack_grant WHERE order_no = ?", Integer.class, orderNo))
                    .isEqualTo(1);
            // 订单已支付时间只写一次（状态机条件更新）
            assertThat(jdbc.queryForObject(
                    "SELECT paid_at FROM t_resource_pack_order WHERE order_no = ?",
                    java.sql.Timestamp.class, orderNo)).isNotNull();
        } finally {
            cleanup();
        }
    }

    @Test
    void 非本业务事件与支付失败事件不履约() {
        try {
            listener.onSettled(event(PaymentEventTypes.PAYMENT_SUCCEEDED, "OTHER_BIZ", orderNo));
            listener.onSettled(event(PaymentEventTypes.PAYMENT_FAILED, "RESOURCE_PACK", orderNo));

            assertThat(jdbc.queryForObject(
                    "SELECT status FROM t_resource_pack_order WHERE order_no = ?", String.class, orderNo))
                    .isEqualTo("PAYING");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_resource_pack_grant WHERE order_no = ?", Integer.class, orderNo))
                    .isZero();
        } finally {
            cleanup();
        }
    }

    @Test
    void 未知订单显式抛错_交给发件箱重试或死信() {
        try {
            assertThatThrownBy(() -> listener.onSettled(
                    event(PaymentEventTypes.PAYMENT_SUCCEEDED, "RESOURCE_PACK", orderNo + "-MISSING")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("资料包订单不存在");
        } finally {
            cleanup();
        }
    }

    @Test
    void 授权表唯一键保证同一用户同一资料包只有一条授权() {
        try {
            listener.onSettled(event(PaymentEventTypes.PAYMENT_SUCCEEDED, "RESOURCE_PACK", orderNo));
            // 模拟同用户第二个订单购买同一资料包：授权仍只有一条（uk_rp_ent 兜底）
            String secondOrder = orderNo + "-2";
            jdbc.update("""
                    INSERT INTO t_resource_pack_order (tenant_id, order_no, pack_id, user_id,
                                                       amount_cents, status)
                    VALUES (0, ?, ?, 42, 9900, 'PAYING')
                    """, secondOrder, packId);
            listener.onSettled(event(PaymentEventTypes.PAYMENT_SUCCEEDED, "RESOURCE_PACK", secondOrder));

            List<Map<String, Object>> grants = jdbc.queryForList(
                    "SELECT pack_id, user_id FROM t_resource_pack_grant WHERE user_id = 42");
            assertThat(grants).hasSize(1);
            jdbc.update("DELETE FROM t_resource_pack_order WHERE order_no = ?", secondOrder);
        } finally {
            cleanup();
        }
    }
}
