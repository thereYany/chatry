package com.wherelee.base.modules.identity.auth.dto;

import com.wherelee.base.modules.platform.common.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 自助注册请求：用户名/密码/昵称/邮箱（可选，暂不验证邮箱）。
 *
 * <p>不接验证码与短信/邮件服务商，防刷依赖 IP 限流（`@RateLimit`）+ 用户名/邮箱唯一键 + 注册开关。</p>
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度需为 3-32 位")
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "用户名只能包含字母、数字、下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @StrongPassword
        String password,

        @NotBlank(message = "昵称不能为空")
        @Size(min = 2, max = 32, message = "昵称长度需为 2-32 位")
        String nickname,

        @Email(message = "邮箱格式不正确")
        @Size(max = 120, message = "邮箱过长")
        String email
) {
}
