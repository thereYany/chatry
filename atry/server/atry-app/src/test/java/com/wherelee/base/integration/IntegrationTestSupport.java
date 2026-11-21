package com.wherelee.base.integration;

import com.wherelee.base.modules.identity.security.JwtService;
import com.wherelee.base.modules.identity.user.User;
import com.wherelee.base.modules.metering.store.MeteringBillingStore;
import com.wherelee.base.modules.payment.PaymentConfirmationService;
import com.wherelee.base.modules.payment.PaymentNotification;
import com.wherelee.base.modules.contracts.payment.PaymentOperations;
import com.wherelee.base.modules.payment.PaymentOrderRepository;
import com.wherelee.base.modules.payment.PaymentRepository;
import com.wherelee.base.modules.payment.MockSignatureService;
import com.wherelee.base.modules.payment.outbox.PaymentOutboxDispatcher;
import com.wherelee.base.modules.payment.outbox.PaymentOutboxRepository;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 集成测试基座：真实 MySQL + Redis(db15) 全链路 Spring 上下文与数据隔离工具。
 *
 * <p>数据隔离约定：每个用例使用独立租户 id（静态自增），用例结束按租户级联清理；
 * 不依赖执行顺序，不修改 main 代码。所有跨租户的测试准备都显式带 tenant_id，
 * 避免被租户拦截器重写导致误伤。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Tag("integration")
public abstract class IntegrationTestSupport {

    private static final AtomicLong TENANT_SEQ = new AtomicLong(1_000_000);
    private static final AtomicLong USER_SEQ = new AtomicLong(1);

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JwtService jwtService;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected MeteringBillingStore meteringBillingStore;
    @Autowired
    protected PaymentOperations paymentOperations;
    @Autowired
    protected PaymentRepository paymentRepository;
    @Autowired
    protected PaymentOrderRepository paymentOrderRepository;
    @Autowired
    protected PaymentConfirmationService paymentConfirmationService;
    @Autowired
    protected MockSignatureService mockSignatureService;
    @Autowired
    protected PaymentOutboxRepository paymentOutboxRepository;
    @Autowired
    protected PaymentOutboxDispatcher paymentOutboxDispatcher;

    private final List<Long> createdTenants = new ArrayList<>();

    @BeforeEach
    void cleanRedisBefore() {
        clearRedisKeys();
    }

    @AfterEach
    void cleanupCreatedTenants() {
        createdTenants.forEach(this::cleanupTenant);
        createdTenants.clear();
        TenantContext.clear();
    }

    // ---------- 数据构造 ----------

    /** 申请一个全新租户 id（不依赖执行顺序，避免与其它用例冲突）。 */
    protected long newTenant() {
        long tenantId = TENANT_SEQ.incrementAndGet();
        createdTenants.add(tenantId);
        return tenantId;
    }

    protected String unique(String prefix) {
        return prefix + Long.toString(System.nanoTime(), 36).replace('-', 'x')
                + USER_SEQ.incrementAndGet();
    }

    /** 创建角色（不存在则建）并授予权限码；权限码不存在则创建。 */
    protected long seedRole(long tenantId, String roleCode, String... permissionCodes) {
        jdbc.update("INSERT IGNORE INTO t_role (tenant_id, code, name, description, built_in) "
                + "VALUES (?, ?, ?, '集成测试角色', 0)", tenantId, roleCode, roleCode);
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM t_role WHERE tenant_id = ? AND code = ?",
                Long.class, tenantId, roleCode);
        for (String permissionCode : permissionCodes) {
            jdbc.update("INSERT IGNORE INTO t_permission (tenant_id, code, name) VALUES (?, ?, ?)",
                    tenantId, permissionCode, permissionCode);
            Long permissionId = jdbc.queryForObject(
                    "SELECT id FROM t_permission WHERE tenant_id = ? AND code = ?",
                    Long.class, tenantId, permissionCode);
            jdbc.update("INSERT IGNORE INTO t_role_permission (tenant_id, role_id, permission_id) "
                    + "VALUES (?, ?, ?)", tenantId, roleId, permissionId);
        }
        return roleId;
    }

    /** 创建一个可直接登录的账号（bcrypt 编码），绑定给定角色码。 */
    protected long seedUser(long tenantId, String username, String rawPassword, String roleCode) {
        jdbc.update("""
                INSERT INTO t_user (tenant_id, username, password_hash, nickname, role, status,
                                    must_change_password, token_version)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, 0)
                """, tenantId, username, passwordEncoder.encode(rawPassword), "用户" + username, roleCode);
        Long userId = jdbc.queryForObject(
                "SELECT id FROM t_user WHERE tenant_id = ? AND username = ?",
                Long.class, tenantId, username);
        Long roleId = jdbc.query("SELECT id FROM t_role WHERE tenant_id = ? AND code = ?",
                (rs, rowNum) -> rs.getLong("id"), tenantId, roleCode).stream().findFirst().orElse(null);
        if (roleId != null) {
            jdbc.update("INSERT IGNORE INTO t_user_role (tenant_id, user_id, role_id) VALUES (?, ?, ?)",
                    tenantId, userId, roleId);
        }
        return userId;
    }

    /** 创建“首次登录必须改密”的账号。 */
    protected long seedMustChangeUser(long tenantId, String username, String rawPassword, String roleCode) {
        long userId = seedUser(tenantId, username, rawPassword, roleCode);
        // 显式带 tenant_id：租户表上的无租户条件 UPDATE 会被重写器注入默认租户 0 而更新不到数据
        jdbc.update("UPDATE t_user SET must_change_password = 1 WHERE id = ? AND tenant_id = ?",
                userId, tenantId);
        return userId;
    }

    /** 直接签发 token（绕过登录节流，用于聚焦鉴权/业务链路）。 */
    protected String tokenFor(long tenantId, long userId, String username, String roleCode) {
        Integer version = jdbc.queryForObject(
                "SELECT token_version FROM t_user WHERE id = ? AND tenant_id = ?",
                Integer.class, userId, tenantId);
        User user = new User(userId, tenantId, username, "hash", "昵称", roleCode, "ACTIVE",
                false, version == null ? 0 : version);
        return jwtService.createToken(user);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 直接在指定租户作用域执行（业务服务写入/查询用）。 */
    protected <T> T asTenant(long tenantId, java.util.function.Supplier<T> action) {
        TenantContext.clear();
        TenantContext.set(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    protected void asTenant(long tenantId, Runnable action) {
        asTenant(tenantId, () -> {
            action.run();
            return null;
        });
    }

    protected <T> T asSystem(java.util.function.Supplier<T> action) {
        return TenantContext.runAsSystem(action);
    }

    // ---------- 清理 ----------

    /** 清理 Redis 中 atry 前缀的键（db15 是测试专用库）。 */
    protected void clearRedisKeys() {
        try {
            var keys = redis.keys("atry:*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException ignored) {
            // Redis 未就绪时不影响非 Redis 用例
        }
    }

    /** 按租户级联清理本套件写入的数据（FK 安全顺序）。 */
    protected void cleanupTenant(long tenantId) {
        jdbc.update("DELETE FROM t_llm_outbox WHERE aggregate_id IN "
                + "(SELECT request_id FROM t_llm_call_record WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM t_llm_call_record WHERE tenant_id = ?", tenantId);

        jdbc.update("DELETE FROM t_metering_invoice_item WHERE invoice_id IN "
                + "(SELECT id FROM t_metering_invoice WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM t_metering_invoice WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM t_metering_charge WHERE event_id IN "
                + "(SELECT event_id FROM t_metering_usage_event WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM t_metering_usage_event WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM t_metering_ledger WHERE account_id IN "
                + "(SELECT id FROM t_metering_account WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM t_metering_reservation WHERE account_id IN "
                + "(SELECT id FROM t_metering_account WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM t_metering_account WHERE tenant_id = ?", tenantId);

        jdbc.update("DELETE FROM t_payment_outbox WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM t_payment_event WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM t_refund WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM t_payment_attempt WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM t_payment_order WHERE tenant_id = ?", tenantId);

        jdbc.update("DELETE FROM t_user_role WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM t_role_permission WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM t_role WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM t_permission WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM t_user_invitation WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM t_user WHERE tenant_id = ?", tenantId);

        jdbc.update("DELETE FROM t_alert_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM t_admin_audit_log WHERE tenant_id = ?", tenantId);
    }

    // ---------- 常用断言辅助 ----------

    protected BigDecimal balanceOf(long tenantId, long appId, long userId) {
        return asSystem(() -> {
            List<BigDecimal> rows = jdbc.query(
                    "SELECT balance FROM t_metering_account WHERE tenant_id = ? AND app_id = ? AND user_id = ?",
                    (rs, rowNum) -> rs.getBigDecimal("balance"), tenantId, appId, userId);
            return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
        });
    }

    /** 系统作用域计数（测试断言跨租户/指定租户条件时不受默认租户 0 影响）。 */
    protected int countRows(String sql, Object... args) {
        Integer count = asSystem(() -> jdbc.queryForObject(sql, Integer.class, args));
        return count == null ? 0 : count;
    }

    /** 系统作用域取单值（跑在系统作用域，避免无 tenant_id 条件被重写为租户 0）。 */
    protected <T> T queryOne(String sql, Class<T> type, Object... args) {
        return asSystem(() -> jdbc.queryForObject(sql, type, args));
    }

    /** 构造 mock 支付通知并签名（等价于渠道回调验签通过）。 */
    protected PaymentNotification signedNotification(String eventKey, String orderNo, String eventType,
                                                     long amountCents, String tradeNo) {
        PaymentNotification unsigned = new PaymentNotification("MOCK", eventKey, orderNo, eventType,
                amountCents, tradeNo, null);
        String signature = mockSignatureService.sign(unsigned);
        return new PaymentNotification("MOCK", eventKey, orderNo, eventType, amountCents, tradeNo,
                signature);
    }

    protected String newRequestId() {
        return "it" + UUID.randomUUID().toString().replace("-", "");
    }

    protected void wipeMeteringTables() {
        asSystem(() -> {
            jdbc.update("DELETE FROM t_metering_invoice_item");
            jdbc.update("DELETE FROM t_metering_invoice");
            jdbc.update("DELETE FROM t_metering_recon_diff");
            jdbc.update("DELETE FROM t_metering_recon_run");
            jdbc.update("DELETE FROM t_metering_charge");
            jdbc.update("DELETE FROM t_metering_usage_event");
            jdbc.update("DELETE FROM t_metering_ledger");
            jdbc.update("DELETE FROM t_metering_reservation");
            jdbc.update("DELETE FROM t_metering_account");
            return null;
        });
    }

    protected void wipePaymentTables() {
        asSystem(() -> {
            jdbc.update("DELETE FROM t_payment_outbox");
            jdbc.update("DELETE FROM t_payment_event");
            jdbc.update("DELETE FROM t_refund");
            jdbc.update("DELETE FROM t_payment_attempt");
            jdbc.update("DELETE FROM t_payment_order");
            jdbc.update("DELETE FROM t_recon_diff");
            jdbc.update("DELETE FROM t_recon_task");
            jdbc.update("DELETE FROM t_channel_bill");
            return null;
        });
    }
}
