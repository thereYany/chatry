package com.wherelee.base.modules.identity.rbac;

import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import com.wherelee.base.modules.contracts.identity.RoleAdminOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色与权限的运营管理：租户可以自定义角色、调整角色权限、给用户授角色。
 *
 * <p>这是"新项目只改配置就能定义角色"的落点：不改代码即可新增角色并生效。</p>
 */
@Service
public class RoleAdminService implements RoleAdminOperations {

    private final RbacRepository rbacRepository;
    private final PermissionService permissionService;
    private final com.wherelee.base.modules.identity.user.UserRepository userRepository;

    public RoleAdminService(RbacRepository rbacRepository, PermissionService permissionService,
                            com.wherelee.base.modules.identity.user.UserRepository userRepository) {
        this.rbacRepository = rbacRepository;
        this.permissionService = permissionService;
        this.userRepository = userRepository;
    }

    @Override
    public List<RoleAdminOperations.RoleView> listRoles() {
        return rbacRepository.listRoles().stream().map(this::toView).toList();
    }

    @Override
    public List<String> permissionsOfRole(String roleCode) {
        RbacRepository.RoleRow role = requireRole(roleCode);
        return rbacRepository.findPermissionCodesByRole(role.id());
    }

    @Override
    public List<RoleAdminOperations.PermissionView> listPermissions() {
        return rbacRepository.listPermissions().stream()
                .map(permission -> new RoleAdminOperations.PermissionView(
                        permission.code(), permission.name(), permission.description()))
                .toList();
    }

    @Override
    @Transactional
    public RoleAdminOperations.RoleView createRole(String code, String name, String description,
                                                   List<String> permissionCodes) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,39}")) {
            throw BizException.badRequest("角色码需为大写字母开头、由大写字母/数字/下划线组成，2-40 位");
        }
        if (rbacRepository.findRoleId(code).isPresent()) {
            throw new BizException(HttpStatus.CONFLICT, "角色已存在");
        }
        Long roleId = rbacRepository.insertRole(code, name, description);
        rbacRepository.replaceRolePermissions(roleId, permissionCodes == null ? List.of() : permissionCodes);
        return toView(requireRole(code));
    }

    @Override
    @Transactional
    public RoleAdminOperations.RoleView updateRolePermissions(String roleCode, List<String> permissionCodes) {
        RbacRepository.RoleRow role = requireRole(roleCode);
        rbacRepository.replaceRolePermissions(role.id(), permissionCodes == null ? List.of() : permissionCodes);
        permissionService.invalidateAll();
        return toView(requireRole(roleCode));
    }

    @Override
    @Transactional
    public void assignRoles(Long userId, List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw BizException.badRequest("至少保留一个角色");
        }
        userRepository.findById(userId)
                .orElseThrow(() -> BizException.notFound("用户不存在"));
        List<Long> roleIds = rbacRepository.findRoleIdsByCodes(roleCodes);
        if (roleIds.size() != roleCodes.size()) {
            throw BizException.badRequest("存在未知角色");
        }
        if (roleCodes.contains("ADMIN") && roleIds.isEmpty()) {
            throw BizException.badRequest("角色不存在");
        }
        rbacRepository.replaceUserRoles(userId, roleIds);
        permissionService.invalidate(userId);
    }

    /** 创建用户/受邀注册后同步 user_role（以 t_user.role 为主角色）。 */
    public void bindPrimaryRole(Long userId, String roleCode) {
        rbacRepository.bindRole(userId, roleCode);
        permissionService.invalidate(userId);
    }

    private RoleAdminOperations.RoleView toView(RbacRepository.RoleRow role) {
        return new RoleAdminOperations.RoleView(role.code(), role.name(), role.description(),
                role.builtIn(), rbacRepository.findPermissionCodesByRole(role.id()));
    }

    private RbacRepository.RoleRow requireRole(String roleCode) {
        return rbacRepository.listRoles().stream()
                .filter(role -> role.code().equals(roleCode))
                .findFirst()
                .orElseThrow(() -> BizException.badRequest("角色不存在：" + roleCode));
    }
}
