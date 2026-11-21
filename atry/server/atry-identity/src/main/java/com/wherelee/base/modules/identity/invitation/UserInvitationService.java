package com.wherelee.base.modules.identity.invitation;

import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import com.wherelee.base.modules.platform.common.PageResult;
import com.wherelee.base.modules.identity.invitation.dto.UserInvitationDtos;
import com.wherelee.base.modules.identity.user.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserInvitationService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE = 10_000;

    private final UserInvitationMapper mapper;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final InvitationTokenService tokenService;
    private final com.wherelee.base.modules.identity.rbac.RbacRepository rbacRepository;
    private final InvitationProperties properties;

    public UserInvitationService(UserInvitationMapper mapper,
                                    UserRepository userRepository,
                                    PasswordEncoder passwordEncoder,
                                    InvitationTokenService tokenService,
                                    com.wherelee.base.modules.identity.rbac.RbacRepository rbacRepository,
                                    InvitationProperties properties) {
        this.mapper = mapper;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.rbacRepository = rbacRepository;
        this.properties = properties;
    }

    @Transactional
    public UserInvitationDtos.CreatedResponse create(UserInvitationDtos.CreateRequest request, Long adminId) {
        String username = request.username().trim();
        String nickname = request.nickname().trim();
        String roleCode = request.roleCode().trim();
        if ("ADMIN".equals(roleCode)) {
            throw BizException.badRequest("不允许通过邀请授予管理员角色");
        }
        if (rbacRepository.findRoleId(roleCode).isEmpty()) {
            throw BizException.badRequest("角色不存在：" + roleCode);
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw BizException.badRequest("用户名已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.findPendingByUsername(username, now, TenantContext.current()) != null) {
            throw BizException.badRequest("该用户名已有未过期的用户邀请");
        }

        String token = tokenService.generate();
        LocalDateTime expiresAt = now.plusHours(properties.safeExpiryHours());
        UserInvitation invitation = new UserInvitation();
        invitation.setTokenHash(tokenService.hash(token));
        invitation.setUsername(username);
        invitation.setNickname(nickname);
        invitation.setRoleCode(roleCode);
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(expiresAt);
        invitation.setCreatedBy(adminId);
        invitation.setTenantId(TenantContext.current());
        mapper.insert(invitation);

        return new UserInvitationDtos.CreatedResponse(
                invitation.getId(), username, nickname, roleCode,
                properties.normalizedBaseUrl() + "/invite/" + token, expiresAt);
    }

    public PageResult<UserInvitationVO> page(String status, int page, int size) {
        int safePage = Math.min(Math.max(1, page), MAX_PAGE);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim();
        long total = mapper.count(normalizedStatus, TenantContext.current());
        if (total == 0) {
            return new PageResult<>(List.of(), 0, safePage, safeSize);
        }
        return new PageResult<>(
                mapper.findPage(normalizedStatus, LocalDateTime.now(),
                        (safePage - 1) * safeSize, safeSize, TenantContext.current()),
                total, safePage, safeSize);
    }

    @Transactional
    public void revoke(Long id) {
        UserInvitation invitation = mapper.findById(id, TenantContext.current());
        if (invitation == null) {
            throw BizException.notFound("邀请不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!"PENDING".equals(invitation.getStatus()) || !invitation.getExpiresAt().isAfter(now)) {
            throw BizException.badRequest("邀请已失效，不能撤销");
        }
        if (mapper.revoke(id, now, TenantContext.current()) != 1) {
            throw BizException.badRequest("邀请状态已变化，请刷新后重试");
        }
    }

    public UserInvitationDtos.PreviewResponse preview(String token) {
        UserInvitation invitation = findByToken(token);
        requireUsable(invitation);
        return new UserInvitationDtos.PreviewResponse(
                invitation.getUsername(), invitation.getNickname(),
                invitation.getRoleCode(), invitation.getExpiresAt());
    }

    @Transactional
    public Long accept(UserInvitationDtos.AcceptRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw BizException.badRequest("两次输入的密码不一致");
        }
        String tokenHash = tokenService.hash(request.token().trim());
        UserInvitation invitation = mapper.findByTokenHashForUpdate(tokenHash, TenantContext.current());
        requireUsable(invitation);
        if (userRepository.findByUsername(invitation.getUsername()).isPresent()) {
            throw BizException.badRequest("用户名已被占用");
        }
        Long userId;
        try {
            userId = userRepository.create(
                    invitation.getUsername(),
                    passwordEncoder.encode(request.password()),
                    invitation.getNickname(),
                    invitation.getRoleCode(),
                    false);
        } catch (DuplicateKeyException e) {
            throw BizException.badRequest("用户名已被占用");
        }
        if (mapper.markUsed(invitation.getId(), userId, LocalDateTime.now(), TenantContext.current()) != 1) {
            throw BizException.badRequest("邀请已失效，请重新获取");
        }
        return userId;
    }

    private UserInvitation findByToken(String token) {
        return mapper.findByTokenHash(tokenService.hash(token.trim()), TenantContext.current());
    }

    private void requireUsable(UserInvitation invitation) {
        if (invitation == null
                || !"PENDING".equals(invitation.getStatus())
                || !invitation.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw BizException.badRequest("邀请无效或已过期");
        }
    }
}
