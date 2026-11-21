package com.wherelee.base.modules.identity.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置：密钥、有效期、签发方（iss）与受众（aud）。
 *
 * <p>iss/aud 用于防止 token 被其他系统/其他前端复用，属于标准 claim 校验。</p>
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, int ttlMinutes, String issuer, String audience) {

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "atry";
        }
        if (audience == null || audience.isBlank()) {
            audience = "atry-web";
        }
    }
}
