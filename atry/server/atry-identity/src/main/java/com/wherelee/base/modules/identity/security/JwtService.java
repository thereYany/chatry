package com.wherelee.base.modules.identity.security;

import com.wherelee.base.modules.identity.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;
    private final String issuer;
    private final String audience;

    public JwtService(JwtProperties properties) {
        String secret = properties.secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT 密钥长度不足 32 字节，请配置 JWT_SECRET");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(properties.ttlMinutes());
        this.issuer = properties.issuer();
        this.audience = properties.audience();
    }

    public String createToken(User user) {
        return createToken(user, null);
    }

    /** 带会话 id（sid）的 token：sid 用于服务端单会话吊销。 */
    public String createToken(User user, String sessionId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(String.valueOf(user.id()))
                .claim("username", user.username())
                .claim("ver", user.tokenVersion())
                .claim("tid", user.tenantId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        if (sessionId != null && !sessionId.isBlank()) {
            builder.claim("sid", sessionId);
        }
        return builder.signWith(key).compact();
    }

    public TokenPayload parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Integer version = claims.get("ver", Integer.class);
        Long tenantId = claims.get("tid", Long.class);
        String sessionId = claims.get("sid", String.class);
        return new TokenPayload(Long.valueOf(claims.getSubject()),
                version == null ? -1 : version,
                tenantId == null ? 0L : tenantId,
                sessionId);
    }

    public record TokenPayload(Long userId, int tokenVersion, Long tenantId, String sessionId) {

        /** 兼容旧构造（无 sid 的 token）。 */
        public TokenPayload(Long userId, int tokenVersion, Long tenantId) {
            this(userId, tokenVersion, tenantId, null);
        }
    }
}