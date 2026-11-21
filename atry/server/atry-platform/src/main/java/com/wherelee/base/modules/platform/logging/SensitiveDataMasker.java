package com.wherelee.base.modules.platform.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志脱敏器：把凭证与常见 PII 从日志文本中抹掉。
 *
 * <p>策略是「宁可多遮、不可漏遮」：密码/令牌/密钥整段替换为 {@code ******}；
 * 手机号、身份证号、邮箱按最小可见原则打码。正则只做保守匹配，
 * 不追求覆盖所有格式——真正的边界是「不要把敏感值写进日志」。</p>
 */
public final class SensitiveDataMasker {

    public static final String MASK = "******";

    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|token|access_token|refresh_token|authorization|"
                    + "secret|api_key|credential)\"\\s*:\\s*\")([^\"]*)(\")");
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)(\\b(?:password|passwd|pwd|token|access[_-]?token|refresh[_-]?token|"
                    + "authorization|secret|api[_-]?key|credential|signature)\\b\\s*[=:]\\s*)"
                    + "(?!\\s)[^&,\\s\"'}]+");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private SensitiveDataMasker() {
    }

    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String masked = JSON_SECRET.matcher(message).replaceAll("$1" + MASK + "$3");
        masked = BEARER.matcher(masked).replaceAll("Bearer " + MASK);
        masked = SECRET_PAIR.matcher(masked).replaceAll("$1" + MASK);
        masked = PHONE.matcher(masked).replaceAll("1**********");
        masked = ID_CARD.matcher(masked).replaceAll("******************");
        masked = EMAIL.matcher(masked).replaceAll("$1***$2");
        return masked;
    }

    /** 仅用于测试/诊断：是否包含可识别的敏感片段。 */
    public static boolean containsSecret(String text) {
        if (text == null) {
            return false;
        }
        Matcher matcher = SECRET_PAIR.matcher(text);
        return matcher.find();
    }
}
