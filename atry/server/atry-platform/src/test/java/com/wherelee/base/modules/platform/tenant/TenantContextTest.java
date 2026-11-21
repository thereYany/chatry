package com.wherelee.base.modules.platform.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户上下文单元测试。
 *
 * <p>覆盖业务不变式：未登录/无租户默认租户 0；系统作用域可嵌套、异常安全、只影响「是否跨租户」
 * 不影响显式租户值；ThreadLocal 不跨线程串租户。</p>
 */
class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void 未设置租户时默认值为0且currentOrNull为空() {
        assertThat(TenantContext.current()).isZero();
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void set与clear生效() {
        TenantContext.set(42L);
        assertThat(TenantContext.current()).isEqualTo(42L);
        assertThat(TenantContext.currentOrNull()).isEqualTo(42L);

        TenantContext.set(null);
        assertThat(TenantContext.currentOrNull()).isNull();
        assertThat(TenantContext.current()).isZero();

        TenantContext.set(7L);
        TenantContext.clear();
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void 系统作用域内标记为真且返回值透传() {
        TenantContext.runAsSystem(() -> {
            assertThat(TenantContext.isSystemScope()).isTrue();
            return null;
        });
        assertThat(TenantContext.isSystemScope()).isFalse();

        String value = TenantContext.runAsSystem(() -> "done");
        assertThat(value).isEqualTo("done");
    }

    @Test
    void 系统作用域嵌套时内层退出不丢失外层作用域() {
        TenantContext.runAsSystem(() -> {
            TenantContext.runAsSystem(() -> {
                assertThat(TenantContext.isSystemScope()).isTrue();
                return null;
            });
            // 内层结束后外层作用域必须仍然有效
            assertThat(TenantContext.isSystemScope()).isTrue();
            return null;
        });
        assertThat(TenantContext.isSystemScope()).isFalse();
    }

    @Test
    void 系统作用域异常退出后恢复原状态() {
        assertThatThrownBy(() -> TenantContext.runAsSystem(() -> {
            throw new IllegalStateException("模拟后台任务异常");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.isSystemScope()).isFalse();
    }

    @Test
    void 系统作用域不改变显式租户值() {
        TenantContext.set(9L);
        TenantContext.runAsSystem(() -> {
            // hybrid 语义：系统作用域内已回填租户时，SQL 仍应按该租户限定
            assertThat(TenantContext.current()).isEqualTo(9L);
            return null;
        });
        assertThat(TenantContext.current()).isEqualTo(9L);
    }

    @Test
    void ThreadLocal不跨线程传递租户() throws Exception {
        TenantContext.set(11L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> other = executor.submit(TenantContext::current);
            assertThat(other.get()).isZero();
        } finally {
            executor.shutdownNow();
        }
        assertThat(TenantContext.current()).isEqualTo(11L);
    }
}
