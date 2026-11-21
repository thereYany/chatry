package com.wherelee.base.modules.identity.invitation.dto;

import com.wherelee.base.modules.platform.aop.AuditedResult;
import com.wherelee.base.modules.platform.common.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class UserInvitationDtos {

    public record CreateRequest(
            @NotBlank(message = "用户名不能为空")
            @Pattern(regexp = "^[a-zA-Z0-9_]{4,20}$", message = "用户名需为 4-20 位字母、数字或下划线")
            String username,
            @NotBlank(message = "昵称不能为空")
            @Size(max = 20, message = "昵称不能超过 20 字")
            String nickname,
            @NotBlank(message = "请选择受邀角色")
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$", message = "角色码格式不正确")
            String roleCode
    ) {
    }

    public record CreatedResponse(
            Long id,
            String username,
            String nickname,
            String roleCode,
            String inviteUrl,
            LocalDateTime expiresAt
    ) implements AuditedResult {
        @Override
        public Long auditTargetId() {
            return id;
        }
    }

    public record PreviewRequest(
            @NotBlank(message = "邀请链接无效") String token
    ) {
    }

    public record PreviewResponse(
            String username,
            String nickname,
            String roleCode,
            LocalDateTime expiresAt
    ) {
    }

    public record AcceptRequest(
            @NotBlank(message = "邀请链接无效") String token,
            @NotBlank(message = "请输入密码")
            @StrongPassword
            String password,
            @NotBlank(message = "请再次输入密码") String confirmPassword
    ) {
    }

    private UserInvitationDtos() {
    }
}
