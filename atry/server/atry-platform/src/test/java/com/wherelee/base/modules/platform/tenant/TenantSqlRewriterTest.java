package com.wherelee.base.modules.platform.tenant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户 SQL 重写单元测试。
 *
 * <p>业务不变式：单表语句必须被强制注入 tenant_id；已手写 tenant_id 的语句不得重复注入；
 * 跨表/子查询无法安全注入时必须抛错（enforce 语义），绝不静默放行。</p>
 */
class TenantSqlRewriterTest {

    private final TenantSqlRewriter rewriter = new TenantSqlRewriter(List.of());

    @Test
    void 空SQL原样返回() {
        assertThat(rewriter.rewrite(null).changed()).isFalse();
        assertThat(rewriter.rewrite("   ").changed()).isFalse();
        assertThat(rewriter.rewrite(null).sql()).isNull();
    }

    @Test
    void 单表select注入where租户条件() {
        TenantSqlRewriter.RewriteResult result = rewriter.rewrite("SELECT * FROM t_user");
        assertThat(result.changed()).isTrue();
        assertThat(result.sql()).contains("WHERE tenant_id = ?");
        assertThat(result.argIndex()).isZero();
    }

    @Test
    void 已有where时追加and且占位符下标正确() {
        TenantSqlRewriter.RewriteResult result =
                rewriter.rewrite("SELECT * FROM t_user WHERE status = ?");
        assertThat(result.sql()).contains("WHERE status = ? AND tenant_id = ?");
        // 租户占位符要插入到原有参数之后
        assertThat(result.argIndex()).isEqualTo(1);
    }

    @Test
    void 注入位置在orderBy之前() {
        TenantSqlRewriter.RewriteResult result =
                rewriter.rewrite("SELECT * FROM t_payment_order ORDER BY id LIMIT 10");
        assertThat(result.sql()).contains("WHERE tenant_id = ?");
        assertThat(result.sql().indexOf("tenant_id")).isLessThan(result.sql().indexOf("ORDER BY"));
        assertThat(result.argIndex()).isZero();
    }

    @Test
    void update与delete注入条件() {
        TenantSqlRewriter.RewriteResult update =
                rewriter.rewrite("UPDATE t_user SET status = ? WHERE id = ?");
        assertThat(update.sql()).contains("AND tenant_id = ?");
        assertThat(update.argIndex()).isEqualTo(2);

        TenantSqlRewriter.RewriteResult delete =
                rewriter.rewrite("DELETE FROM t_payment_order WHERE payment_no = ?");
        assertThat(delete.sql()).contains("AND tenant_id = ?");
        assertThat(delete.argIndex()).isEqualTo(1);
    }

    @Test
    void insert注入tenantId列与占位符() {
        TenantSqlRewriter.RewriteResult result = rewriter.rewrite(
                "INSERT INTO t_alert_log (alert_type, message) VALUES (?, ?)");
        assertThat(result.changed()).isTrue();
        assertThat(result.sql()).isEqualTo(
                "INSERT INTO t_alert_log (alert_type, message, tenant_id) VALUES (?, ?, ?)");
        // 租户占位符追加在原有参数之后
        assertThat(result.argIndex()).isEqualTo(2);
    }

    @Test
    void insert缺少values时抛错而不是放行() {
        assertThatThrownBy(() -> rewriter.rewrite(
                "INSERT INTO t_alert_log (alert_type, message) SELECT 'A', 'B'"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INSERT");
    }

    @Test
    void insert尾部含占位符时拒绝自动注入() {
        assertThatThrownBy(() -> rewriter.rewrite(
                "INSERT INTO t_alert_log (alert_type, message) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE message = ?"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("占位符");
    }

    @Test
    void 已含tenantId的SQL不重复注入() {
        TenantSqlRewriter.RewriteResult result = rewriter.rewrite(
                "SELECT * FROM t_payment_order WHERE tenant_id = ? AND biz_no = ?");
        assertThat(result.changed()).isFalse();
        assertThat(result.sql()).contains("tenant_id = ?");
    }

    @Test
    void 非租户表原样返回() {
        TenantSqlRewriter.RewriteResult result = rewriter.rewrite(
                "SELECT * FROM t_metering_account WHERE tenant_id = 1");
        assertThat(result.changed()).isFalse();

        TenantSqlRewriter.RewriteResult unknown = rewriter.rewrite("SELECT * FROM some_other_table");
        assertThat(unknown.changed()).isFalse();
    }

    @Test
    void join查询必须手写租户条件否则抛错() {
        assertThatThrownBy(() -> rewriter.rewrite(
                "SELECT * FROM t_user u JOIN t_user_role ur ON ur.user_id = u.id"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("手写 tenant_id");
    }

    @Test
    void 子查询无法安全注入时抛错() {
        assertThatThrownBy(() -> rewriter.rewrite(
                "SELECT * FROM t_user WHERE id IN (SELECT user_id FROM t_user_role)"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exists子查询同样拒绝() {
        assertThatThrownBy(() -> rewriter.rewrite(
                "SELECT * FROM t_user WHERE EXISTS(SELECT 1 FROM t_user_role r WHERE r.user_id = t_user.id)"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 垂直应用可通过extraTables追加租户表() {
        TenantSqlRewriter vertical = new TenantSqlRewriter(java.util.Arrays.asList("t_resource_pack_order", " ", null));
        assertThat(vertical.tenantTables()).contains("t_resource_pack_order", "t_user");
        assertThat(vertical.rewrite("SELECT * FROM t_resource_pack_order").changed()).isTrue();
        assertThat(vertical.rewrite("SELECT * FROM T_RESOURCE_PACK_ORDER").changed()).isTrue();
    }

    @Test
    void 表名大小写不敏感() {
        assertThat(rewriter.rewrite("SELECT * FROM T_USER").changed()).isTrue();
        assertThat(rewriter.rewrite("update T_PAYMENT_ORDER set status = 'X'").changed()).isTrue();
    }
}
