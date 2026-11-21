package com.wherelee.base.modules.identity.security;

import com.wherelee.base.modules.identity.user.User;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发/校验测试。
 *
 * <p>业务不变式：token 必须绑定用户、token_version、租户；iss/aud 必须校验（防跨系统复用）；
 * 篡改/过期 token 一律拒绝；密钥过短启动失败（防弱密钥）。</p>
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-0123456789-abcdef";

    private static JwtService service(int ttlMinutes, String issuer, String audience) {
        return new JwtService(new JwtProperties(SECRET, ttlMinutes, issuer, audience));
    }

    private static User user(long id, long tenantId, int tokenVersion) {
        return new User(id, tenantId, "alice", "hash", "爱丽丝", "USER", "ACTIVE", false, tokenVersion);
    }

    @Test
    void 签发后可解析出用户与租户及版本() {
        JwtService service = service(60, "atry", "atry-web");
        String token = service.createToken(user(42L, 7L, 3));

        JwtService.TokenPayload payload = service.parse(token);
        assertThat(payload.userId()).isEqualTo(42L);
        assertThat(payload.tenantId()).isEqualTo(7L);
        assertThat(payload.tokenVersion()).isEqualTo(3);
    }

    @Test
    void 签发会话id可取回_无sid时为null() {
        JwtService service = service(60, "atry", "atry-web");
        String withSid = service.createToken(user(42L, 7L, 3), "sess-42");
        assertThat(service.parse(withSid).sessionId()).isEqualTo("sess-42");

        String withoutSid = service.createToken(user(42L, 7L, 3));
        assertThat(service.parse(withoutSid).sessionId()).isNull();
    }

    @Test
    void 密钥不足32字节时启动失败() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties("too-short", 60, "atry", "web")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
        assertThatThrownBy(() -> new JwtService(new JwtProperties(null, 60, "atry", "web")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 篡改token被拒绝() {
        JwtService service = service(60, "atry", "atry-web");
        String token = service.createToken(user(1L, 0L, 0));
        String tampered = token.substring(0, token.length() - 3)
                + (token.endsWith("abc") ? "xyz" : "abc");
        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void 过期token被拒绝() {
        // ttl 为负 => exp 在过去，解析必须抛 ExpiredJwtException
        JwtService expired = service(-1, "atry", "atry-web");
        String token = expired.createToken(user(1L, 0L, 0));
        assertThatThrownBy(() -> expired.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void issuer不匹配被拒绝() {
        JwtService issuerA = service(60, "atry", "atry-web");
        JwtService issuerB = service(60, "evil-system", "atry-web");
        String tokenFromB = issuerB.createToken(user(1L, 0L, 0));
        assertThatThrownBy(() -> issuerA.parse(tokenFromB)).isInstanceOf(JwtException.class);
    }

    @Test
    void audience不匹配被拒绝_防止其他前端复用token() {
        JwtService web = service(60, "atry", "atry-web");
        JwtService otherClient = service(60, "atry", "other-client");
        String otherToken = otherClient.createToken(user(1L, 0L, 0));
        assertThatThrownBy(() -> web.parse(otherToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void 缺少可选claim时使用安全默认值() {
        String token = Jwts.builder()
                .issuer("atry")
                .audience().add("atry-web").and()
                .subject("9")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        JwtService.TokenPayload payload = service(60, "atry", "atry-web").parse(token);
        assertThat(payload.tokenVersion()).isEqualTo(-1);
        assertThat(payload.tenantId()).isZero();
    }
}
