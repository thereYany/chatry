package com.wherelee.base.modules.platform.common;

import java.security.SecureRandom;

/**
 * 生成满足强密码规则的随机初始密码。
 *
 * <p>通用基座里所有「首次启动创建管理员」的装配代码共用：16 位，至少一个字母与一个数字，
 * 排除容易混淆的 I/O/l/o/0/1，避免把固定口令写进仓库。</p>
 */
public final class PasswordGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String ALPHABET = LETTERS + DIGITS;

    private PasswordGenerator() {
    }

    public static String generate() {
        char[] chars = new char[16];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
        }
        chars[0] = LETTERS.charAt(RANDOM.nextInt(LETTERS.length()));
        chars[1] = DIGITS.charAt(RANDOM.nextInt(DIGITS.length()));
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }
}
