package com.wherelee.base.modules.platform.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MybatisTenantGuardTest {

    private final MybatisTenantGuard guard =
            new MybatisTenantGuard(new TenantSqlRewriter(List.of()));

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void allowsSqlWithExplicitTenantCondition() {
        assertThatCode(() -> guard.validate(
                "SELECT COUNT(*) FROM t_user WHERE tenant_id = ? AND status = 'ACTIVE'"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsNonTenantTables() {
        assertThatCode(() -> guard.validate("SELECT 1")).doesNotThrowAnyException();
    }

    @Test
    void rejectsTenantTableWithoutTenantCondition() {
        assertThatThrownBy(() -> guard.validate("SELECT * FROM t_user WHERE status = 'ACTIVE'"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id");
    }

    @Test
    void rejectsUnsafeJoinWithoutTenantCondition() {
        assertThatThrownBy(() -> guard.validate(
                "SELECT u.id FROM t_user u JOIN t_role r ON r.id = u.role WHERE u.status = 'ACTIVE'"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id");
    }

    @Test
    void systemScopeBypassesGuard() {
        TenantContext.runAsSystem(() -> {
            assertThatCode(() -> guard.validate("SELECT * FROM t_user WHERE status = 'ACTIVE'"))
                    .doesNotThrowAnyException();
            return null;
        });
    }
}
