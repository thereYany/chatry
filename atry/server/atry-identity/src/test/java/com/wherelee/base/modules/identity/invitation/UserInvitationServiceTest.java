package com.wherelee.base.modules.identity.invitation;

import com.wherelee.base.modules.identity.invitation.dto.UserInvitationDtos;
import com.wherelee.base.modules.identity.rbac.RbacRepository;
import com.wherelee.base.modules.identity.user.User;
import com.wherelee.base.modules.identity.user.UserRepository;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邀请码全流程测试。
 *
 * <p>业务不变式：不能通过邀请授予 ADMIN；同名未过期邀请唯一；令牌只存哈希；
 * 一次性使用、过期失效、撤销后不可用；接受时密码二次确认且用户名不可被占用。</p>
 */
class UserInvitationServiceTest {

    private UserInvitationMapper mapper;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private InvitationTokenService tokenService;
    private RbacRepository rbacRepository;
    private UserInvitationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserInvitationMapper.class);
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenService = new InvitationTokenService();
        rbacRepository = mock(RbacRepository.class);
        InvitationProperties properties = new InvitationProperties("http://app.example.com/", 72, 20, 10);
        service = new UserInvitationService(mapper, userRepository, passwordEncoder, tokenService,
                rbacRepository, properties);
        TenantContext.set(3L);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private static UserInvitation invitation(String status, LocalDateTime expiresAt) {
        UserInvitation invitation = new UserInvitation();
        invitation.setId(55L);
        invitation.setTenantId(3L);
        invitation.setTokenHash("hash");
        invitation.setUsername("newuser");
        invitation.setNickname("新用户");
        invitation.setRoleCode("USER");
        invitation.setStatus(status);
        invitation.setExpiresAt(expiresAt);
        return invitation;
    }

    @Test
    void 创建邀请成功时只落库令牌哈希并返回一次性链接() {
        when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(rbacRepository.findRoleId("USER")).thenReturn(Optional.of(9L));
        when(mapper.findPendingByUsername(eq("newuser"), any(), eq(3L))).thenReturn(null);

        UserInvitationDtos.CreatedResponse response = service.create(
                new UserInvitationDtos.CreateRequest(" newuser ", " 新用户 ", " USER "), 100L);

        ArgumentCaptor<UserInvitation> captor = ArgumentCaptor.forClass(UserInvitation.class);
        verify(mapper).insert(captor.capture());
        UserInvitation saved = captor.getValue();
        assertThat(saved.getTokenHash()).hasSize(64).doesNotContain(response.inviteUrl().substring(
                response.inviteUrl().lastIndexOf('/') + 1));
        assertThat(saved.getUsername()).isEqualTo("newuser");
        assertThat(saved.getNickname()).isEqualTo("新用户");
        assertThat(saved.getRoleCode()).isEqualTo("USER");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getTenantId()).isEqualTo(3L);
        assertThat(saved.getCreatedBy()).isEqualTo(100L);
        assertThat(response.inviteUrl()).startsWith("http://app.example.com/invite/");
        assertThat(response.expiresAt()).isAfter(LocalDateTime.now().plusHours(70));
    }

    @Test
    void 不允许通过邀请授予管理员角色() {
        assertThatThrownBy(() -> service.create(
                new UserInvitationDtos.CreateRequest("newuser", "n", "ADMIN"), 100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("管理员");
        verify(mapper, never()).insert(any());
    }

    @Test
    void 拒绝未知角色与重复用户名() {
        when(rbacRepository.findRoleId("GHOST")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(
                new UserInvitationDtos.CreateRequest("newuser", "n", "GHOST"), 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("角色不存在");

        when(rbacRepository.findRoleId("USER")).thenReturn(Optional.of(9L));
        when(userRepository.findByUsername("dup")).thenReturn(Optional.of(
                new User(1L, 3L, "dup", "h", "n", "USER", "ACTIVE", false, 0)));
        assertThatThrownBy(() -> service.create(
                new UserInvitationDtos.CreateRequest("dup", "n", "USER"), 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名已存在");
    }

    @Test
    void 同用户名存在未过期邀请时拒绝重复创建() {
        when(rbacRepository.findRoleId("USER")).thenReturn(Optional.of(9L));
        when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(mapper.findPendingByUsername(eq("newuser"), any(), eq(3L)))
                .thenReturn(invitation("PENDING", LocalDateTime.now().plusHours(1)));

        assertThatThrownBy(() -> service.create(
                new UserInvitationDtos.CreateRequest("newuser", "n", "USER"), 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未过期的用户邀请");
    }

    @Test
    void 预览有效邀请返回基本信息_失效邀请统一报错() {
        when(mapper.findByTokenHash(anyString(), eq(3L)))
                .thenReturn(invitation("PENDING", LocalDateTime.now().plusHours(1)));
        UserInvitationDtos.PreviewResponse preview = service.preview(" raw-token ");
        assertThat(preview.username()).isEqualTo("newuser");
        assertThat(preview.roleCode()).isEqualTo("USER");

        when(mapper.findByTokenHash(anyString(), eq(3L)))
                .thenReturn(invitation("PENDING", LocalDateTime.now().minusMinutes(1)));
        assertThatThrownBy(() -> service.preview("token"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("邀请无效或已过期");

        when(mapper.findByTokenHash(anyString(), eq(3L))).thenReturn(null);
        assertThatThrownBy(() -> service.preview("token")).isInstanceOf(BizException.class);
    }

    @Test
    void 接受邀请两步密码必须一致() {
        assertThatThrownBy(() -> service.accept(new UserInvitationDtos.AcceptRequest(
                "token", "GoodPass123", "GoodPass124")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("密码不一致");
    }

    @Test
    void 接受邀请成功创建账号并标记已使用() {
        when(mapper.findByTokenHashForUpdate(anyString(), eq(3L)))
                .thenReturn(invitation("PENDING", LocalDateTime.now().plusHours(1)));
        when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("GoodPass123")).thenReturn("$enc$");
        when(userRepository.create("newuser", "$enc$", "新用户", "USER", false)).thenReturn(77L);
        when(mapper.markUsed(eq(55L), eq(77L), any(), eq(3L))).thenReturn(1);

        Long userId = service.accept(new UserInvitationDtos.AcceptRequest(
                " token ", "GoodPass123", "GoodPass123"));

        assertThat(userId).isEqualTo(77L);
        // 邀请注册的账号不需要强制改密
        verify(userRepository).create("newuser", "$enc$", "新用户", "USER", false);
    }

    @Test
    void 接受邀请时用户名被占用则拒绝() {
        when(mapper.findByTokenHashForUpdate(anyString(), eq(3L)))
                .thenReturn(invitation("PENDING", LocalDateTime.now().plusHours(1)));
        when(userRepository.findByUsername("newuser")).thenReturn(Optional.of(
                new User(1L, 3L, "newuser", "h", "n", "USER", "ACTIVE", false, 0)));

        assertThatThrownBy(() -> service.accept(new UserInvitationDtos.AcceptRequest(
                "token", "GoodPass123", "GoodPass123")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名已被占用");
    }

    @Test
    void 接受邀请并发冲突由唯一键兜底并转成业务异常() {
        when(mapper.findByTokenHashForUpdate(anyString(), eq(3L)))
                .thenReturn(invitation("PENDING", LocalDateTime.now().plusHours(1)));
        when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("$enc$");
        when(userRepository.create(anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenThrow(new DuplicateKeyException("uk_user_tenant_username"));

        assertThatThrownBy(() -> service.accept(new UserInvitationDtos.AcceptRequest(
                "token", "GoodPass123", "GoodPass123")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名已被占用");
    }

    @Test
    void 接受已使用或过期邀请被拒绝() {
        when(mapper.findByTokenHashForUpdate(anyString(), eq(3L)))
                .thenReturn(invitation("USED", LocalDateTime.now().plusHours(1)));
        assertThatThrownBy(() -> service.accept(new UserInvitationDtos.AcceptRequest(
                "token", "GoodPass123", "GoodPass123")))
                .isInstanceOf(BizException.class);

        when(mapper.findByTokenHashForUpdate(anyString(), eq(3L)))
                .thenReturn(invitation("PENDING", LocalDateTime.now().minusHours(1)));
        assertThatThrownBy(() -> service.accept(new UserInvitationDtos.AcceptRequest(
                "token", "GoodPass123", "GoodPass123")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 标记已使用时状态已变化则回滚业务异常() {
        when(mapper.findByTokenHashForUpdate(anyString(), eq(3L)))
                .thenReturn(invitation("PENDING", LocalDateTime.now().plusHours(1)));
        when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("$enc$");
        when(userRepository.create(anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(77L);
        when(mapper.markUsed(anyLong(), anyLong(), any(), anyLong())).thenReturn(0);

        assertThatThrownBy(() -> service.accept(new UserInvitationDtos.AcceptRequest(
                "token", "GoodPass123", "GoodPass123")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("邀请已失效");
    }

    @Test
    void 撤销有效邀请_失效邀请不可撤销() {
        when(mapper.findById(55L, 3L))
                .thenReturn(invitation("PENDING", LocalDateTime.now().plusHours(1)));
        when(mapper.revoke(eq(55L), any(), eq(3L))).thenReturn(1);
        service.revoke(55L);
        verify(mapper).revoke(eq(55L), any(), eq(3L));

        when(mapper.findById(55L, 3L)).thenReturn(null);
        assertThatThrownBy(() -> service.revoke(55L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("邀请不存在");

        when(mapper.findById(55L, 3L))
                .thenReturn(invitation("PENDING", LocalDateTime.now().minusHours(1)));
        assertThatThrownBy(() -> service.revoke(55L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能撤销");
    }

    @Test
    void 分页参数被夹紧且空结果不查明细() {
        when(mapper.count(isNull(), eq(3L))).thenReturn(0L);
        var page = service.page("  ", -5, 500);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(50);
        assertThat(page.records()).isEmpty();
        verify(mapper, never()).findPage(any(), any(), anyInt(), anyInt(), anyLong());

        when(mapper.count(eq("PENDING"), eq(3L))).thenReturn(2L);
        when(mapper.findPage(eq("PENDING"), any(), eq(0), eq(50), eq(3L)))
                .thenReturn(List.of(new UserInvitationVO()));
        assertThat(service.page("PENDING", 0, 999).records()).hasSize(1);
    }
}
