package com.wherelee.base.modules.identity.admin.dto;

import com.wherelee.base.modules.platform.common.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
        @NotBlank(message = "临时密码不能为空")
        @StrongPassword
        String newPassword
) {
}
