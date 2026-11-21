package com.wherelee.base.architecture;

import com.wherelee.base.modules.identity.rbac.PermissionAspect;
import com.wherelee.base.modules.platform.aop.AdminAuditAspect;
import com.wherelee.base.modules.platform.aop.AspectOrders;
import com.wherelee.base.modules.platform.aop.DistributedLockAspect;
import com.wherelee.base.modules.platform.aop.IdempotentAspect;
import com.wherelee.base.modules.platform.aop.MeasuredAspect;
import com.wherelee.base.modules.platform.aop.RateLimitAspect;
import com.wherelee.base.modules.metering.aop.UsageMeteredAspect;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;

/** 切面顺序契约：权限必须早于限流/幂等/锁，度量最外层。 */
class AspectOrderTest {

    @Test
    void aspectsFollowDocumentedOrder() {
        assertThat(orderOf(MeasuredAspect.class)).isEqualTo(AspectOrders.MEASURED);
        assertThat(orderOf(PermissionAspect.class)).isEqualTo(AspectOrders.PERMISSION);
        assertThat(orderOf(RateLimitAspect.class)).isEqualTo(AspectOrders.RATE_LIMIT);
        assertThat(orderOf(IdempotentAspect.class)).isEqualTo(AspectOrders.IDEMPOTENT);
        assertThat(orderOf(DistributedLockAspect.class)).isEqualTo(AspectOrders.DISTRIBUTED_LOCK);
        assertThat(orderOf(AdminAuditAspect.class)).isEqualTo(AspectOrders.AUDIT);

        assertThat(AspectOrders.MEASURED)
                .isLessThan(AspectOrders.PERMISSION)
                .isLessThan(AspectOrders.RATE_LIMIT)
                .isLessThan(AspectOrders.IDEMPOTENT)
                .isLessThan(AspectOrders.DISTRIBUTED_LOCK)
                .isLessThan(AspectOrders.AUDIT)
                .isLessThan(AspectOrders.METERING);
    }

    private int orderOf(Class<?> aspectClass) {
        Order order = aspectClass.getAnnotation(Order.class);
        assertThat(order).as(aspectClass.getSimpleName() + " 必须显式声明 @Order").isNotNull();
        return order.value();
    }
}
