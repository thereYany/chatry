package com.wherelee.base.modules.contracts.identity;

import java.util.List;

/**
 * 角色与权限管理契约：运营端只依赖本接口，不直接触碰 identity 内部实现。
 */
public interface RoleAdminOperations {

    List<RoleView> listRoles();

    List<String> permissionsOfRole(String roleCode);

    List<PermissionView> listPermissions();

    RoleView createRole(String code, String name, String description, List<String> permissions);

    RoleView updateRolePermissions(String roleCode, List<String> permissions);

    void assignRoles(Long userId, List<String> roles);

    record RoleView(String code, String name, String description, boolean builtIn,
                    List<String> permissions) {
    }

    record PermissionView(String code, String name, String description) {
    }
}
