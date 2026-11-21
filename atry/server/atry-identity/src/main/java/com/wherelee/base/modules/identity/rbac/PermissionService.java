package com.wherelee.base.modules.identity.rbac;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * 用户权限解析（Caffeine 本地缓存 + Redis 失效广播）。
 *
 * <p>缓存 key 含租户，避免多租户串号；角色变更/用户禁用时本地立即失效并广播到其它实例。
 * TTL 2 分钟只是兜底（广播失败/Redis 不可用时最迟等 TTL）。</p>
 */
@Service
public class PermissionService {

    private static final Duration TTL = Duration.ofMinutes(2);

    private final RbacRepository rbacRepository;
    private final RbacCacheInvalidator invalidator;
    private final Cache<String, Set<String>> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(TTL)
            .build();

    @Autowired
    public PermissionService(RbacRepository rbacRepository, RbacCacheInvalidator invalidator) {
        this.rbacRepository = rbacRepository;
        this.invalidator = invalidator;
    }

    /** 兼容旧测试/手工构造：只清本地缓存，不广播。 */
    PermissionService(RbacRepository rbacRepository) {
        this(rbacRepository, null);
    }

    public Set<String> permissionsOf(Long userId) {
        Long tenantId = TenantContext.current();
        return cache.get(cacheKey(tenantId, userId),
                key -> Set.copyOf(rbacRepository.findPermissionCodesByUser(userId)));
    }

    public boolean has(Long userId, String permissionCode) {
        return permissionsOf(userId).contains(permissionCode);
    }

    public boolean hasAny(Long userId, String... permissionCodes) {
        Set<String> owned = permissionsOf(userId);
        for (String code : permissionCodes) {
            if (owned.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /** 本机失效 + 广播到其它实例。 */
    public void invalidate(Long userId) {
        long tenantId = TenantContext.current();
        invalidateLocal(tenantId, userId);
        if (invalidator != null) {
            invalidator.publishUser(tenantId, userId);
        }
    }

    public void invalidateAll() {
        invalidateAllLocal();
        if (invalidator != null) {
            invalidator.publishAll();
        }
    }

    /** 只清本地缓存（供广播监听器调用，避免回声）。 */
    void invalidateLocal(long tenantId, long userId) {
        cache.invalidate(cacheKey(tenantId, userId));
    }

    void invalidateAllLocal() {
        cache.invalidateAll();
    }

    private static String cacheKey(long tenantId, long userId) {
        return tenantId + ":" + userId;
    }
}
