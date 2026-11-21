/**
 * 跨模块契约（Contracts）：各业务模块对外暴露的接口与 DTO。
 *
 * <p>契约模块只依赖 platform（如 PageResult），不依赖任何业务模块；
 * 业务模块之间只允许通过本模块交互，从而保证模块图无环。</p>
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Contracts",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"platform"})
package com.wherelee.base.modules.contracts;
