/**
 * 通用身份与访问模块：登录、账号、JWT、角色/权限和用户邀请。
 *
 * <p>依赖方向：identity -> platform、contracts。不包含任何业务角色语义：
 * 角色码是数据（t_role），由垂直应用自行配置。</p>
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity", allowedDependencies = {"platform", "contracts"})

package com.wherelee.base.modules.identity;
