package com.wherelee.base.modules.identity.rbac;


import com.wherelee.base.modules.platform.aop.AspectOrders;
import com.wherelee.base.modules.platform.aop.RequirePermission;
import com.wherelee.base.modules.platform.common.BizException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
@Order(AspectOrders.PERMISSION)
public class PermissionAspect {

    private final PermissionService permissionService;

    public PermissionAspect(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Around("@annotation(requirePermission)")
    public Object around(ProceedingJoinPoint joinPoint, RequirePermission requirePermission) throws Throwable {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        Object userId = attributes == null
                ? null : attributes.getRequest().getAttribute("userId");
        if (!(userId instanceof Long id) || !permissionService.hasAny(id, requirePermission.value())) {
            throw new BizException(HttpStatus.FORBIDDEN, "无权限执行此操作");
        }
        return joinPoint.proceed();
    }
}
