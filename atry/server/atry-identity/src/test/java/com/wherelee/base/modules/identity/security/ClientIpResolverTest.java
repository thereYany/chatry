package com.wherelee.base.modules.identity.security;

import com.wherelee.base.modules.platform.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 解析测试。
 *
 * <p>业务不变式：默认不信任转发头（防止伪造 X-Forwarded-For 绕过登录节流）；
 * 开启信任后取第一个合法值，跳过 unknown/空/超长值，最后回退 remoteAddr。</p>
 */
class ClientIpResolverTest {

    private static ClientIpResolver resolver(boolean trustForwarded) {
        return new ClientIpResolver(new SecurityProperties(
                List.of(), trustForwarded, true, 8, 40, 10));
    }

    private static MockHttpServletRequest request(String remoteAddr, String forwarded, String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(remoteAddr);
        if (forwarded != null) {
            request.addHeader("X-Forwarded-For", forwarded);
        }
        if (realIp != null) {
            request.addHeader("X-Real-IP", realIp);
        }
        return request;
    }

    @Test
    void 不信任转发头时只取remoteAddr() {
        assertThat(resolver(false).resolve(request("10.0.0.1", "1.2.3.4", "5.6.7.8")))
                .isEqualTo("10.0.0.1");
    }

    @Test
    void 信任转发头时取XFF第一个合法值() {
        assertThat(resolver(true).resolve(request("10.0.0.1", "1.2.3.4, 5.6.7.8", "9.9.9.9")))
                .isEqualTo("1.2.3.4");
    }

    @Test
    void 跳过空值与unknown后取第一个合法值() {
        assertThat(resolver(true).resolve(request("10.0.0.1", " , unknown, 1.2.3.4", null)))
                .isEqualTo("1.2.3.4");
    }

    @Test
    void XFF无合法值时回退XRealIP再回退remoteAddr() {
        assertThat(resolver(true).resolve(request("10.0.0.1", "unknown", "5.6.7.8")))
                .isEqualTo("5.6.7.8");
        assertThat(resolver(true).resolve(request("10.0.0.1", null, null)))
                .isEqualTo("10.0.0.1");
    }

    @Test
    void 超长或unknown的remoteAddr返回unknown() {
        assertThat(resolver(true).resolve(request("x".repeat(65), "1.2.3.4", null)))
                .isEqualTo("1.2.3.4");
        assertThat(resolver(false).resolve(request("unknown", null, null)))
                .isEqualTo("unknown");
        assertThat(resolver(false).resolve(request("", null, null)))
                .isEqualTo("unknown");
    }
}
