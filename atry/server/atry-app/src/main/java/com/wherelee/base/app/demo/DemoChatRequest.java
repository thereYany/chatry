package com.wherelee.base.app.demo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 演示对话请求：只接受业务字段。
 *
 * <p>tenantId / userId 一律取自登录态（JwtAuthFilter 写入 request attribute），
 * 不允许由请求体指定，避免演示端点成为越权入口。</p>
 */
public record DemoChatRequest(
        @NotBlank(message = "message 不能为空")
        @Size(max = 2000, message = "message 不能超过 2000 字符")
        String message,
        String model,
        Integer maxTokens
) {
}
