package com.wherelee.base.modules.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储与签名 URL 缓存配置。
 *
 * <p>默认关闭：通用基座不假设部署方一定接对象存储。需要媒体能力的垂直应用
 * 设置 {@code app.storage.enabled=true} 并提供 bucket/region 与凭据即可启用。</p>
 *
 * @param enabled         是否启用对象存储适配（关闭时不注册 COS/SignedUrlCache Bean）
 * @param bucket          COS bucket
 * @param region          COS region
 * @param urlTtlMinutes   预签名 URL 有效期（分钟）
 * @param connectTimeoutMs 连接超时
 * @param readTimeoutMs   读超时
 * @param maxRetries      最大重试次数
 * @param urlCacheMarginMinutes 签名 URL 缓存提前失效余量（分钟）
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        boolean enabled,
        String bucket,
        String region,
        int urlTtlMinutes,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxRetries,
        int urlCacheMarginMinutes
) {
}
