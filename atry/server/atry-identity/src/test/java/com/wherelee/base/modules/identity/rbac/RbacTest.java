package com.wherelee.base.modules.identity.rbac;

import com.wherelee.base.modules.contracts.identity.RoleAdminOperations;
import com.wherelee.base.modules.identity.user.UserRepository;
import com.wherelee.base.modules.platform.aop.RequirePermission;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RBAC 测试：权限缓存（含租户隔离）、权限切面、角色运营。
 *
 * <p>业务不变式：权限缓存 key 必须含租户，避免串号；角色变更必须失效缓存；
 * 管理员不能把账号角色清空；未知角色码拒绝。</p>
 */
class RbacTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------- PermissionService 缓存 ----------

    @Test
    void 权限解析按租户与用户缓存() {
        RbacRepository repository = mock(RbacRepository.class);
        when(repository.findPermissionCodesByUser(1L))
                .thenReturn(List.of("admin:user:manage", "admin:role:manage"));
        PermissionService service = new PermissionService(repository);

        TenantContext.set(1L);
        assertThat(service.has(1L, "admin:user:manage")).isTrue();
        service.permissionsOf(1L);
        service.permissionsOf(1L);
        verify(repository, times(1)).findPermissionCodesByUser(1L);

        // 不同租户必须重新解析（缓存 key 含租户）
        TenantContext.set(2L);
        service.permissionsOf(1L);
        verify(repository, times(2)).findPermissionCodesByUser(1L);
    }

    @Test
    void hasAny为或语义且空集合安全() {
        RbacRepository repository = mock(RbacRepository.class);
        when(repository.findPermissionCodesByUser(1L)).thenReturn(List.of());
        PermissionService service = new PermissionService(repository);

        assertThat(service.hasAny(1L, "a", "b")).isFalse();
        assertThat(service.hasAny(1L)).isFalse();
        assertThat(service.has(1L, "a")).isFalse();
    }

    @Test
    void 失效后重新加载且invalidateAll清空全部() {
        RbacRepository repository = mock(RbacRepository.class);
        when(repository.findPermissionCodesByUser(1L)).thenReturn(List.of("a"));
        PermissionService service = new PermissionService(repository);
        TenantContext.set(1L);

        assertThat(service.has(1L, "a")).isTrue();
        service.invalidate(1L);
        when(repository.findPermissionCodesByUser(1L)).thenReturn(List.of("b"));
        assertThat(service.has(1L, "b")).isTrue();
        verify(repository, times(2)).findPermissionCodesByUser(1L);

        service.invalidateAll();
        service.permissionsOf(1L);
        verify(repository, times(3)).findPermissionCodesByUser(1L);
    }

    // ---------- PermissionAspect ----------

    @Test
    void 无请求上下文或用户未登录时拒绝() {
        PermissionService permissionService = mock(PermissionService.class);
        AspectJProxyFactory factory = new AspectJProxyFactory(new GuardedTarget());
        factory.addAspect(new PermissionAspect(permissionService));
        GuardedTarget proxy = factory.getProxy();

        assertThatThrownBy(proxy::guarded).isInstanceOf(BizException.class);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThatThrownBy(proxy::guarded).isInstanceOf(BizException.class);
    }

    @Test
    void 无权限拒绝_有权限放行() {
        PermissionService permissionService = mock(PermissionService.class);
        AspectJProxyFactory factory = new AspectJProxyFactory(new GuardedTarget());
        factory.addAspect(new PermissionAspect(permissionService));
        GuardedTarget proxy = factory.getProxy();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin");
        request.setAttribute("userId", 7L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        when(permissionService.hasAny(eq(7L), any(String[].class))).thenReturn(false);
        assertThatThrownBy(proxy::guarded)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权限");

        when(permissionService.hasAny(eq(7L), any(String[].class))).thenReturn(true);
        assertThat(proxy.guarded()).isEqualTo("ok");
    }

    // ---------- RoleAdminService ----------

    private static RbacRepository.RoleRow adminRole() {
        return new RbacRepository.RoleRow(1L, "ADMIN", "管理员", "全部权限", true);
    }

    @Test
    void 创建角色校验收字符格式与唯一性() {
        RbacRepository repository = mock(RbacRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        UserRepository userRepository = mock(UserRepository.class);
        RoleAdminService service = new RoleAdminService(repository, permissionService, userRepository);

        assertThatThrownBy(() -> service.createRole("bad", "名", null, List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("角色码");
        assertThatThrownBy(() -> service.createRole(null, "名", null, List.of()))
                .isInstanceOf(BizException.class);

        when(repository.findRoleId("ADMIN")).thenReturn(Optional.of(1L));
        assertThatThrownBy(() -> service.createRole("ADMIN", "名", null, List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("角色已存在");
    }

    @Test
    void 创建角色成功绑定权限并返回视图() {
        RbacRepository repository = mock(RbacRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        UserRepository userRepository = mock(UserRepository.class);
        RoleAdminService service = new RoleAdminService(repository, permissionService, userRepository);

        when(repository.findRoleId("TEACHER")).thenReturn(Optional.empty());
        when(repository.insertRole("TEACHER", "教师", "desc")).thenReturn(5L);
        when(repository.listRoles()).thenReturn(List.of(
                new RbacRepository.RoleRow(5L, "TEACHER", "教师", "desc", false)));
        when(repository.findPermissionCodesByRole(5L)).thenReturn(List.of("course:manage"));

        RoleAdminOperations.RoleView view = service.createRole("TEACHER", "教师", "desc",
                List.of("course:manage"));
        assertThat(view.code()).isEqualTo("TEACHER");
        assertThat(view.permissions()).containsExactly("course:manage");
        verify(repository).replaceRolePermissions(5L, List.of("course:manage"));
    }

    @Test
    void 更新角色权限失效权限缓存() {
        RbacRepository repository = mock(RbacRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleAdminService service = new RoleAdminService(repository,
                permissionService, mock(UserRepository.class));

        when(repository.listRoles()).thenReturn(List.of(adminRole()));
        when(repository.findPermissionCodesByRole(1L)).thenReturn(List.of("admin:user:manage"));

        RoleAdminOperations.RoleView view =
                service.updateRolePermissions("ADMIN", List.of("admin:user:manage"));
        assertThat(view.permissions()).containsExactly("admin:user:manage");
        verify(repository).replaceRolePermissions(1L, List.of("admin:user:manage"));
        verify(permissionService).invalidateAll();

        when(repository.listRoles()).thenReturn(List.of());
        assertThatThrownBy(() -> service.updateRolePermissions("GHOST", List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("角色不存在");
    }

    @Test
    void 分配角色校验用户存在且不能为空且角色必须已知() {
        RbacRepository repository = mock(RbacRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        UserRepository userRepository = mock(UserRepository.class);
        RoleAdminService service = new RoleAdminService(repository, permissionService, userRepository);

        assertThatThrownBy(() -> service.assignRoles(1L, List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少保留一个角色");

        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assignRoles(1L, List.of("ADMIN")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户不存在");

        when(userRepository.findById(1L)).thenReturn(Optional.of(
                new com.wherelee.base.modules.identity.user.User(1L, 0L, "alice", "h",
                        "n", "ADMIN", "ACTIVE", false, 0)));
        when(repository.findRoleIdsByCodes(List.of("ADMIN", "GHOST"))).thenReturn(List.of(1L));
        assertThatThrownBy(() -> service.assignRoles(1L, List.of("ADMIN", "GHOST")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知角色");

        when(repository.findRoleIdsByCodes(List.of("ADMIN"))).thenReturn(List.of(1L));
        service.assignRoles(1L, List.of("ADMIN"));
        verify(repository).replaceUserRoles(1L, List.of(1L));
        verify(permissionService).invalidate(1L);
    }

    @Test
    void 绑定主角色时同步userRole并失效缓存() {
        RbacRepository repository = mock(RbacRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleAdminService service = new RoleAdminService(repository,
                permissionService, mock(UserRepository.class));

        service.bindPrimaryRole(8L, "USER");
        verify(repository).bindRole(8L, "USER");
        verify(permissionService).invalidate(8L);
        verify(repository, never()).insertRole(any(), any(), any());
    }

    public static class GuardedTarget {
        @RequirePermission({"admin:user:manage", "admin:role:manage"})
        public String guarded() {
            return "ok";
        }
    }
}
