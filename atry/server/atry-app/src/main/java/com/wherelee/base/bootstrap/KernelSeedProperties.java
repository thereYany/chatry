package com.wherelee.base.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内核首次启动创建管理员账号时使用的初始密码。
 *
 * <p>留空则随机生成并在启动日志打印一次，避免把固定口令写进代码仓库；
 * 账号已存在时不会重置密码。</p>
 */
@ConfigurationProperties(prefix = "app.seed")
public record KernelSeedProperties(String adminPassword) {
}
