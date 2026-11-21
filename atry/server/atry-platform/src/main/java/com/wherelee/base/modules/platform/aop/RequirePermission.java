package com.wherelee.base.modules.platform.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限声明：值为 {@link com.wherelee.base.modules.identity.rbac.PermissionCodes} 中的权限码。
 *
 * <p>与 @RequireRole 的区别：角色是租户可配置的，权限码是代码契约；
 * 新项目通过"建角色 + 授权限码"即可接入，无需改鉴权代码。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    String[] value();
}
