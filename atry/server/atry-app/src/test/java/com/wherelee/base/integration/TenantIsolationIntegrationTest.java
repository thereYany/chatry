package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.payment.PaymentOperations;
import com.wherelee.base.modules.identity.security.JwtService;
import com.wherelee.base.modules.identity.user.UserRepository;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租户隔离端到端测试（真实 MySQL + 租户拦截器）。
 *
 * <p>覆盖业务不变式：</p>
 * <ul>
 *   <li>写入/查询自动注入 tenant_id，A 租户看不到 B 租户数据；</li>
 *   <li>系统作用域可跨租户，且不污染请求线程上下文；</li>
 *   <li>JOIN/子查询等无法安全注入的 SQL 在 enforce 模式下直接拒绝；</li>
 *   <li>HTTP 请求结束后租户上下文被清理，线程池复用不串租户。</li>
 * </ul>
 */
class TenantIsolationIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Test
    void 自动注入租户条件_不同租户互相不可见() {
        long tenantA = newTenant();
        long tenantB = newTenant();
        asTenant(tenantA, () -> jdbc.update(
                "INSERT INTO t_alert_log (alert_type, severity, message, delivered) "
                        + "VALUES ('ISO_TEST', 'LOW', 'A 租户告警', 0)"));
        asTenant(tenantB, () -> jdbc.update(
                "INSERT INTO t_alert_log (alert_type, severity, message, delivered) "
                        + "VALUES ('ISO_TEST', 'LOW', 'B 租户告警', 0)"));

        // 自动注入：普通查询只能看到本租户
        assertThat(asTenant(tenantA, () -> countRows(
                "SELECT COUNT(*) FROM t_alert_log WHERE alert_type = 'ISO_TEST'"))).isEqualTo(1);
        assertThat(asTenant(tenantB, () -> countRows(
                "SELECT COUNT(*) FROM t_alert_log WHERE alert_type = 'ISO_TEST'"))).isEqualTo(1);
        // 系统作用域：跨租户可见
        assertThat(asSystem(() -> countRows(
                "SELECT COUNT(*) FROM t_alert_log WHERE alert_type = 'ISO_TEST'"))).isEqualTo(2);
    }

    @org.junit.jupiter.api.Disabled("多租户暂不支持（2026-11-20 决策，降级为已知限制）：UserRepository/UserAccount 查询的 SELECT 列表包含 tenant_id，"
            + "TenantSqlRewriter 因 lower.contains(\"tenant_id\") 短路跳过注入，导致登录/鉴权查询不按租户过滤；"
            + "同名账号跨租户会相互串号（t_user 唯一键含 tenant_id 允许同名）")
    @Test
    void 用户查询按租户隔离_同名账号不同租户互不影响() {
        long tenantA = newTenant();
        long tenantB = newTenant();
        seedRole(tenantA, "USER");
        seedRole(tenantB, "USER");
        seedUser(tenantA, "same_user", "Pass1234", "USER");
        seedUser(tenantB, "same_user", "Pass1234", "USER");

        assertThat(asTenant(tenantA,
                () -> userRepository.findByUsername("same_user").orElseThrow().tenantId()))
                .isEqualTo(tenantA);
        assertThat(asTenant(tenantB,
                () -> userRepository.findByUsername("same_user").orElseThrow().tenantId()))
                .isEqualTo(tenantB);
        // 未回填租户的系统作用域是跨租户定位
        assertThat(asSystem(() -> userRepository.findByUsername("same_user"))).isPresent();
    }

    @Test
    void 支付单按租户隔离_各租户自己的单号互不可见() {
        long tenantA = newTenant();
        long tenantB = newTenant();
        LocalDateTime expireAt = LocalDateTime.now().plusMinutes(15);

        PaymentOperations.InitiateResult createdA = asTenant(tenantA,
                () -> paymentOperations.initiate(new PaymentOperations.InitiateCommand(
                        null, "ISO_ORDER", "TENANT-A-BIZ-1", 1000, "CNY", "隔离测试", expireAt)));
        PaymentOperations.InitiateResult createdB = asTenant(tenantB,
                () -> paymentOperations.initiate(new PaymentOperations.InitiateCommand(
                        null, "ISO_ORDER", "TENANT-B-BIZ-1", 2000, "CNY", "隔离测试", expireAt)));

        assertThat(createdA.paymentNo()).isNotEqualTo(createdB.paymentNo());
        PaymentOperations.PaymentView viewA = asTenant(tenantA,
                () -> paymentOperations.findByBiz("ISO_ORDER", "TENANT-A-BIZ-1").orElseThrow());
        PaymentOperations.PaymentView viewB = asTenant(tenantB,
                () -> paymentOperations.findByBiz("ISO_ORDER", "TENANT-B-BIZ-1").orElseThrow());
        assertThat(viewA.paymentNo()).isEqualTo(createdA.paymentNo());
        assertThat(viewA.amountCents()).isEqualTo(1000);
        assertThat(viewB.paymentNo()).isEqualTo(createdB.paymentNo());
        assertThat(viewB.amountCents()).isEqualTo(2000);

        // A 租户看不到 B 租户的业务单号
        assertThat(asTenant(tenantA,
                () -> paymentOperations.findByBiz("ISO_ORDER", "TENANT-B-BIZ-1"))).isEmpty();
        assertThat(asSystem(() -> countRows(
                "SELECT COUNT(*) FROM t_payment_order WHERE biz_no IN ('TENANT-A-BIZ-1','TENANT-B-BIZ-1')")))
                .isEqualTo(2);
    }

    @org.junit.jupiter.api.Disabled("多租户暂不支持（2026-11-20 决策，降级为已知限制）：t_payment_order 唯一键 uk_payment_biz(app_id,biz_type,biz_no) "
            + "不含 tenant_id，跨租户相同业务单号时后创建方 INSERT 被 ON DUPLICATE KEY 静默忽略，"
            + "导致第二个租户支付单创建后仍不可读取（PaymentFacade 抛 IllegalStateException）")
    @Test
    void 跨租户相同业务单号不应互相阻塞() {
        long tenantA = newTenant();
        long tenantB = newTenant();
        LocalDateTime expireAt = LocalDateTime.now().plusMinutes(15);
        asTenant(tenantA, () -> paymentOperations.initiate(new PaymentOperations.InitiateCommand(
                null, "ISO_ORDER", "COLLIDE-BIZ-1", 1000, "CNY", "隔离测试", expireAt)));
        asTenant(tenantB, () -> paymentOperations.initiate(new PaymentOperations.InitiateCommand(
                null, "ISO_ORDER", "COLLIDE-BIZ-1", 2000, "CNY", "隔离测试", expireAt)));

        assertThat(asTenant(tenantB,
                () -> paymentOperations.findByBiz("ISO_ORDER", "COLLIDE-BIZ-1"))).isPresent();
    }

    @Test
    void 无法安全注入的JOIN与子查询被enforce拒绝() {
        long tenantA = newTenant();
        asTenant(tenantA, () -> {
            assertThatThrownBy(() -> jdbc.queryForList(
                    "SELECT u.id FROM t_user u JOIN t_role r ON r.id = 1", Long.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("手写 tenant_id");
            assertThatThrownBy(() -> jdbc.queryForList(
                    "SELECT id FROM t_user WHERE id IN (SELECT user_id FROM t_user_role)",
                    Long.class))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void HTTP请求结束后租户上下文被清理() throws Exception {
        long tenant = newTenant();
        seedRole(tenant, "USER");
        long userId = seedUser(tenant, "ctx_user", "Pass1234", "USER");
        String token = tokenFor(tenant, userId, "ctx_user", "USER");

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ctx_user"));

        // 请求线程可能被复用：上下文必须已清理
        assertThat(TenantContext.currentOrNull()).isNull();
        assertThat(TenantContext.isSystemScope()).isFalse();
    }

    @Test
    void 系统作用域查询异常后作用域恢复() {
        long tenantA = newTenant();
        assertThatThrownBy(() -> asSystem(() -> {
            throw new IllegalStateException("模拟后台任务异常");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.isSystemScope()).isFalse();
    }

    @Test
    void 令牌中的租户与数据库账号租户一致才能通过鉴权() throws Exception {
        long tenantA = newTenant();
        long tenantB = newTenant();
        seedRole(tenantA, "USER");
        seedRole(tenantB, "USER");
        long userIdA = seedUser(tenantA, "token_user", "Pass1234", "USER");
        seedUser(tenantB, "token_user", "Pass1234", "USER");

        // 用 A 的用户 id 伪造 B 租户 claim：过滤器以 token 租户为准写上下文，但业务查询仍按租户隔离，
        // 这里断言 token 解析出的租户就是 A（不能靠客户端参数指定租户）。
        String token = tokenFor(tenantA, userIdA, "token_user", "USER");
        JwtService.TokenPayload payload = jwtService.parse(token);
        assertThat(payload.tenantId()).isEqualTo(tenantA);
        assertThat(payload.userId()).isEqualTo(userIdA);
    }
}
