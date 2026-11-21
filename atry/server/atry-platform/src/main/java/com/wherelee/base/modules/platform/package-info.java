/**
 * 平台模块：AOP、审计存储、通用异常与 Web 基础设施、配置、缓存/COS 适配和健康检查。
 *
 * <p>依赖方向：platform 不反向依赖业务模块。业务模块可以依赖本模块。</p>
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Platform",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {})

package com.wherelee.base.modules.platform;