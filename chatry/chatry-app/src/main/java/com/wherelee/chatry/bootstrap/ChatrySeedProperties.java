package com.wherelee.chatry.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 首次启动的演示账号种子配置。
 *
 * <p>只用于本地/演示：账号已存在时不会重置密码。真实部署前必须改掉默认口令。</p>
 *
 * @param adminPassword   管理员初始密码（留空则随机生成并打印一次）
 * @param demoUserEnabled 是否创建演示用户
 * @param demoUsername    演示用户名
 * @param demoPassword    演示用户密码
 */
@ConfigurationProperties(prefix = "chatry.seed")
public record ChatrySeedProperties(
        String adminPassword,
        Boolean demoUserEnabled,
        String demoUsername,
        String demoPassword
) {

    public ChatrySeedProperties {
        demoUserEnabled = demoUserEnabled == null || demoUserEnabled;
        demoUsername = demoUsername == null || demoUsername.isBlank() ? "demo" : demoUsername.trim();
        demoPassword = demoPassword == null || demoPassword.isBlank() ? "demo123456" : demoPassword;
    }
}
