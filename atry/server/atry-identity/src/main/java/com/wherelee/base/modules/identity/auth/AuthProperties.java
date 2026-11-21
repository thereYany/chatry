package com.wherelee.base.modules.identity.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自助注册配置。
 *
 * @param registrationEnabled 是否开放自助注册（关闭后只有管理员/邀请能建号）
 * @param defaultRole         自助注册用户的默认角色码（内核只存字符串，角色权限由垂直应用配置）
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(Boolean registrationEnabled, String defaultRole) {

    public AuthProperties {
        registrationEnabled = registrationEnabled == null || registrationEnabled;
        defaultRole = defaultRole == null || defaultRole.isBlank() ? "USER" : defaultRole.trim();
    }
}
