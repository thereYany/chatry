package com.wherelee.base.modules.platform.common;

import com.wherelee.base.modules.platform.common.validation.StrongPasswordValidator;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 初始密码生成器与强密码校验器测试。
 *
 * <p>不变式：生成口令必须满足强密码规则、排除易混淆字符；校验器对 null/空串放行（交给 @NotBlank），
 * 对长度、字母+数字、弱口令字典做拦截。</p>
 */
class PasswordAndValidationTest {

    private final StrongPasswordValidator validator = new StrongPasswordValidator();

    @Test
    void 强密码校验器对空值放行() {
        // @StrongPassword 只负责格式，必填由 @NotBlank 负责
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("", null)).isTrue();
        assertThat(validator.isValid("   ", null)).isTrue();
    }

    @Test
    void 长度边界() {
        assertThat(validator.isValid("Abc1234", null)).isFalse();   // 7 位
        assertThat(validator.isValid("Abc12999", null)).isTrue();   // 8 位（注意 abc12345 在弱口令字典内）
        assertThat(validator.isValid("Abcdefghij1234567890", null)).isTrue();
        assertThat(validator.isValid("A1" + "x".repeat(31), null)).isFalse(); // 33 位
        assertThat(validator.isValid("A1" + "x".repeat(30), null)).isTrue();  // 32 位
    }

    @Test
    void 必须同时包含字母与数字() {
        assertThat(validator.isValid("abcdefgh", null)).isFalse();
        assertThat(validator.isValid("12345678", null)).isFalse();
    }

    @Test
    void 弱口令字典大小写不敏感拦截() {
        assertThat(validator.isValid("Password", null)).isFalse();
        assertThat(validator.isValid("PASSWORD1", null)).isFalse();
        assertThat(validator.isValid("Admin1234", null)).isFalse();
    }

    @Test
    void 生成的初始密码符合强密码规则且无易混淆字符() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String password = PasswordGenerator.generate();
            assertThat(password).hasSize(16);
            assertThat(password).matches("[A-Za-z0-9]+");
            assertThat(password).doesNotContain("I", "O", "l", "o", "0", "1");
            assertThat(validator.isValid(password, null)).isTrue();
            seen.add(password);
        }
        // 16 位随机口令 200 次出现重复的概率可忽略
        assertThat(seen).hasSize(200);
    }
}
