package com.wherelee.base.modules.identity.rbac;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RbacCacheInvalidationListenerTest {

    private final PermissionService permissionService = mock(PermissionService.class);
    private final RbacCacheInvalidationListener listener = new RbacCacheInvalidationListener(permissionService);

    @Test
    void clearsSingleUserCache() {
        listener.onMessage(message("0:42"), null);
        verify(permissionService).invalidateLocal(0L, 42L);
        verify(permissionService, never()).invalidateAllLocal();
    }

    @Test
    void clearsAllCaches() {
        listener.onMessage(message("ALL"), null);
        verify(permissionService).invalidateAllLocal();
    }

    @Test
    void ignoresMalformedPayload() {
        listener.onMessage(message("not-a-number"), null);
        verify(permissionService, never()).invalidateLocal(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(permissionService, never()).invalidateAllLocal();
    }

    private DefaultMessage message(String body) {
        return new DefaultMessage("atry:rbac:invalidate".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
