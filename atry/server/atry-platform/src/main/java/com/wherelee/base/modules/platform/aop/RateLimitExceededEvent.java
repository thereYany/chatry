package com.wherelee.base.modules.platform.aop;

/**
 * 限流命中事件：平台层只发事件，认证等业务模块自行决定是否补审计/告警。
 *
 * @param key     注解上的限流 key（如 auth:register）
 * @param subject 主体（IP 或 userId）
 */
public record RateLimitExceededEvent(String key, String subject) {
}
