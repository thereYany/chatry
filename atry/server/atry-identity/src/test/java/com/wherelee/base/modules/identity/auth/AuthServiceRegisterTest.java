package com.wherelee.base.modules.identity.auth;

import com.wherelee.base.modules.identity.auth.dto.LoginResponse;
import com.wherelee.base.modules.identity.auth.dto.RegisterRequest;
import com.wherelee.base.modules.identity.security.JwtService;
import com.wherelee.base.modules.identity.session.SessionService;
import com.wherelee.base.modules.identity.user.User;
import com.wherelee.base.modules.identity.user.UserRepository;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceRegisterTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private SessionService sessionService;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, passwordEncoder, jwtService,
                new AuthProperties(true, "USER"), sessionService);
    }

    @Test
    void registersWithHashedPasswordDefaultRoleAndNormalizedEmail() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Passw0rd!")).thenReturn("$hash$");
        when(userRepository.create(eq("alice"), eq("$hash$"), eq("爱丽丝"), eq("USER"),
                eq(false), eq("alice@example.com"))).thenReturn(7L);
        when(sessionService.create(0L, 7L, "127.0.0.1", "junit")).thenReturn("sid-7");
        when(jwtService.createToken(any(User.class), eq("sid-7"))).thenReturn("token-7");
        when(userRepository.findById(7L)).thenReturn(Optional.of(
                new User(7L, 0L, "alice", "$hash$", "爱丽丝", "USER", "ACTIVE", false, 0)));

        LoginResponse response = service.register(
                new RegisterRequest("alice", "Passw0rd!", "爱丽丝", "Alice@Example.com "),
                "127.0.0.1", "junit");

        assertThat(response.token()).isEqualTo("token-7");
        assertThat(response.user().id()).isEqualTo(7L);
        assertThat(response.user().username()).isEqualTo("alice");
        assertThat(response.mustChangePassword()).isFalse();
    }

    @Test
    void duplicateUsernameIsRejectedWithStableCode() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(
                new User(1L, 0L, "alice", "h", "n", "USER", "ACTIVE", false, 0)));

        assertThatThrownBy(() -> service.register(
                new RegisterRequest("alice", "Passw0rd!", "爱丽丝", null), "127.0.0.1", "junit"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("USERNAME_TAKEN");
                });
        verify(userRepository, never()).create(anyString(), anyString(), anyString(), anyString(),
                anyBoolean(), any());
    }

    @Test
    void duplicateEmailIsRejected() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.existsByEmail("a@b.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(
                new RegisterRequest("alice", "Passw0rd!", "爱丽丝", "a@b.com"), "127.0.0.1", "junit"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("EMAIL_TAKEN");
                });
    }

    @Test
    void registrationSwitchOffReturnsForbidden() {
        AuthService closed = new AuthService(userRepository, passwordEncoder, jwtService,
                new AuthProperties(false, "USER"), sessionService);

        assertThatThrownBy(() -> closed.register(
                new RegisterRequest("alice", "Passw0rd!", "爱丽丝", null), "127.0.0.1", "junit"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.code()).isEqualTo("REGISTRATION_DISABLED");
                });
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    void blankEmailIsStoredAsNull() {
        when(userRepository.findByUsername("bob")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("$h$");
        when(userRepository.create(eq("bob"), eq("$h$"), eq("鲍勃"), eq("USER"), eq(false), isNull()))
                .thenReturn(8L);
        when(sessionService.create(0L, 8L, "127.0.0.1", "junit")).thenReturn("sid-8");
        when(jwtService.createToken(any(User.class), eq("sid-8"))).thenReturn("t");
        when(userRepository.findById(8L)).thenReturn(Optional.of(
                new User(8L, 0L, "bob", "$h$", "鲍勃", "USER", "ACTIVE", false, 0)));

        service.register(new RegisterRequest("bob", "Passw0rd!", "鲍勃", "  "), "127.0.0.1", "junit");

        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        verify(userRepository).create(eq("bob"), eq("$h$"), eq("鲍勃"), eq("USER"), eq(false), email.capture());
        assertThat(email.getValue()).isNull();
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    void concurrentUniqueKeyClashReturnsAccountTaken() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("$h$");
        when(userRepository.create(eq("alice"), eq("$h$"), eq("爱丽丝"), eq("USER"), eq(false), isNull()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk"));

        assertThatThrownBy(() -> service.register(
                new RegisterRequest("alice", "Passw0rd!", "爱丽丝", null), "127.0.0.1", "junit"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.code()).isEqualTo("ACCOUNT_TAKEN");
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(jwtService, never()).createToken(any(), any());
    }
}
