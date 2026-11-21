package com.wherelee.base.modules.identity.auth;

import com.wherelee.base.modules.identity.auth.dto.ChangePasswordRequest;
import com.wherelee.base.modules.identity.auth.dto.LoginRequest;
import com.wherelee.base.modules.identity.auth.dto.LoginResponse;
import com.wherelee.base.modules.identity.auth.dto.UserInfo;
import com.wherelee.base.modules.identity.security.JwtService;
import com.wherelee.base.modules.identity.session.SessionService;
import com.wherelee.base.modules.identity.user.User;
import com.wherelee.base.modules.identity.user.UserRepository;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录/改密应用服务测试。
 *
 * <p>业务不变式：账号或密码错误统一 401 且不泄露哪个字段错；禁用账号不可登录；
 * 改密必须校验原密码且新旧不能相同；用户名输入做 trim。</p>
 */
class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private SessionService sessionService;
    private AuthService service;

    private final User activeUser =
            new User(1L, 0L, "alice", "$encoded$", "爱丽丝", "USER", "ACTIVE", true, 4);

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        sessionService = mock(SessionService.class);
        service = new AuthService(userRepository, passwordEncoder, jwtService,
                new AuthProperties(true, "USER"), sessionService);
    }

    @Test
    void 登录成功返回token与用户信息() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("raw", "$encoded$")).thenReturn(true);
        when(sessionService.create(0L, 1L, "127.0.0.1", "junit")).thenReturn("sid-1");
        when(jwtService.createToken(activeUser, "sid-1")).thenReturn("token-1");

        LoginResponse response = service.login(new LoginRequest(" alice ", "raw"), "127.0.0.1", "junit");

        assertThat(response.token()).isEqualTo("token-1");
        assertThat(response.mustChangePassword()).isTrue();
        assertThat(response.user()).isEqualTo(new UserInfo(1L, "alice", "爱丽丝", "USER"));
    }

    @Test
    void 密码错误与账号不存在都返回401且消息一致() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "bad"), "127.0.0.1", "junit"))
                .isInstanceOf(BizException.class)
                .hasMessage("账号或密码不正确");

        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login(new LoginRequest("nobody", "bad"), "127.0.0.1", "junit"))
                .isInstanceOf(BizException.class)
                .hasMessage("账号或密码不正确");
    }

    @Test
    void 禁用账号不可登录() {
        User disabled = new User(2L, 0L, "bob", "h", "鲍勃", "USER", "DISABLED", false, 0);
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.login(new LoginRequest("bob", "raw"), "127.0.0.1", "junit"))
                .isInstanceOf(BizException.class);
        verify(jwtService, never()).createToken(disabled);
    }

    @Test
    void me在账号禁用后返回401() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser));
        assertThat(service.me(1L).username()).isEqualTo("alice");

        when(userRepository.findById(1L)).thenReturn(Optional.of(
                new User(1L, 0L, "alice", "h", "n", "USER", "DISABLED", false, 0)));
        assertThatThrownBy(() -> service.me(1L)).isInstanceOf(BizException.class);

        when(userRepository.findById(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.me(2L)).isInstanceOf(BizException.class);
    }

    @Test
    void 改密校验原密码() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("wrong", "$encoded$")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(1L,
                new ChangePasswordRequest("wrong", "NewPass123")))
                .isInstanceOf(BizException.class)
                .hasMessage("原密码不正确");
        verify(userRepository, never()).updatePassword(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 改密不允许新旧密码相同() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("OldPass123", "$encoded$")).thenReturn(true);
        when(passwordEncoder.matches("NewPass123", "$encoded$")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(1L,
                new ChangePasswordRequest("OldPass123", "NewPass123")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能与原密码相同");
    }

    @Test
    void 改密成功时写入新哈希并递增token版本() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("OldPass123", "$encoded$")).thenReturn(true);
        when(passwordEncoder.matches("NewPass123", "$encoded$")).thenReturn(false);
        when(passwordEncoder.encode("NewPass123")).thenReturn("$new$");

        service.changePassword(1L, new ChangePasswordRequest("OldPass123", "NewPass123"));
        verify(userRepository).updatePassword(1L, "$new$");
    }
}
