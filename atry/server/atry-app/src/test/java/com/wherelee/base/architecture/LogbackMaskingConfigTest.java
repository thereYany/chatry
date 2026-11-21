package com.wherelee.base.architecture;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON 日志脱敏配置回归：直接校验 logback-spring.xml 里的 MaskingJsonGeneratorDecorator 规则，
 * 避免「配置写错一个字符，生产 JSON 日志泄露凭据」。
 */
class LogbackMaskingConfigTest {

    private static final Pattern VALUE_TAG = Pattern.compile("<value>(.*?)</value>", Pattern.DOTALL);

    @Test
    void configuredMaskingPatternsRemoveSecretsFromLogMessage() throws Exception {
        String config;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("logback-spring.xml")) {
            assertThat(in).as("logback-spring.xml 必须存在").isNotNull();
            config = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Pattern> patterns = new ArrayList<>();
        Matcher matcher = VALUE_TAG.matcher(config);
        while (matcher.find()) {
            String regex = unescapeXml(matcher.group(1));
            patterns.add(Pattern.compile(regex));
        }
        assertThat(patterns).as("至少配置 5 条脱敏规则").hasSizeGreaterThanOrEqualTo(5);

        String message = "login username=alice password=SuperSecret123 token=tok-abc-123 "
                + "Authorization: Bearer eyJhbGciOi.abc.def phone=13812345678 "
                + "id=110101199001011234 email=alice@example.com";
        String masked = message;
        for (Pattern pattern : patterns) {
            masked = pattern.matcher(masked).replaceAll("******");
        }
        assertThat(masked)
                .doesNotContain("SuperSecret123", "tok-abc-123", "eyJhbGciOi", "13812345678",
                        "110101199001011234", "alice@example.com");
    }

    private String unescapeXml(String value) {
        return value.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }
}
