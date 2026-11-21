package com.wherelee.base.modules.identity.user;

import com.wherelee.base.modules.contracts.identity.UserAccount;
import com.wherelee.base.modules.identity.rbac.RbacRepository;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号写操作测试。
 *
 * <p>业务不变式：管理员建号必须角色存在、用户名唯一、强制首登改密；不能改自己状态/重置自己密码；
 * 禁用必须递增 token_version（让旧 token 立即失效）。</p>
 */
class UserAccountOperationsServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private RbacRepository rbacRepository;
    private UserAccountOperationsService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        rbacRepository = mock(RbacRepository.class);
        service = new UserAccountOperationsService(userRepository, passwordEncoder, rbacRepository);
    }

    private static User user(long id, String status, int tokenVersion) {
        return new User(id, 0L, "u" + id, "h", "昵称", "USER", status, false, tokenVersion);
    }

    @Test
    void 创建账号成功时强制首次改密并编码密码() {
        when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(rbacRepository.findRoleId("USER")).thenReturn(Optional.of(10L));
        when(passwordEncoder.encode("RawPass123")).thenReturn("$enc$");
        when(userRepository.create(eq("newuser"), eq("$enc$"), eq("昵称"), eq("USER"), eq(true)))
                .thenReturn(99L);

        Long id = service.createUser("newuser", "RawPass123", "昵称", "USER");
        assertThat(id).isEqualTo(99L);
    }

    @Test
    void 创建账号拒绝重复用户名() {
        when(userRepository.findByUsername("dup")).thenReturn(Optional.of(user(1L, "ACTIVE", 0)));
        assertThatThrownBy(() -> service.createUser("dup", "RawPass123", "n", "USER"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名已存在");
        verify(userRepository, never()).create(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void 创建账号拒绝不存在的角色() {
        when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(rbacRepository.findRoleId("GHOST")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createUser("newuser", "RawPass123", "n", "GHOST"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("角色不存在");
    }

    @Test
    void 不能修改自己的账号状态() {
        assertThatThrownBy(() -> service.changeStatus(1L, 1L, "DISABLED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能修改自己");
    }

    @Test
    void 状态相同时为空操作且不写库() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "ACTIVE", 0)));
        service.changeStatus(1L, 2L, "ACTIVE");
        verify(userRepository, never()).disable(2L);
        verify(userRepository, never()).enable(2L);
    }

    @Test
    void 禁用账号递增token版本() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "ACTIVE", 0)));
        service.changeStatus(1L, 2L, "DISABLED");
        verify(userRepository).disable(2L); // disable SQL 内部 token_version = token_version + 1

        when(userRepository.findById(3L)).thenReturn(Optional.of(user(3L, "DISABLED", 1)));
        service.changeStatus(1L, 3L, "ACTIVE");
        verify(userRepository).enable(3L);
    }

    @Test
    void 非法状态值被拒绝() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "ACTIVE", 0)));
        assertThatThrownBy(() -> service.changeStatus(1L, 2L, "DELETED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("ACTIVE 或 DISABLED");
    }

    @Test
    void 不能重置自己的密码() {
        assertThatThrownBy(() -> service.resetPassword(1L, 1L, "NewPass123"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("修改密码");
    }

    @Test
    void 重置密码校验用户存在并编码写入() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "ACTIVE", 0)));
        when(passwordEncoder.encode("NewPass123")).thenReturn("$new$");
        service.resetPassword(1L, 2L, "NewPass123");
        verify(userRepository).resetPassword(2L, "$new$");

        when(userRepository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resetPassword(1L, 404L, "NewPass123"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户不存在");
    }

    @Test
    void require返回账号快照() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "ACTIVE", 0)));
        UserAccount account = service.require(2L);
        assertThat(account.id()).isEqualTo(2L);
        assertThat(account.role()).isEqualTo("USER");
        assertThat(account.status()).isEqualTo("ACTIVE");
    }
}
