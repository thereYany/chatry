package com.wherelee.base.modules.identity.admin;

import com.wherelee.base.modules.identity.admin.dto.AdminUserVO;
import com.wherelee.base.modules.identity.admin.dto.ChangeStatusRequest;
import com.wherelee.base.modules.identity.admin.dto.CreateUserRequest;
import com.wherelee.base.modules.identity.admin.dto.ResetPasswordRequest;
import com.wherelee.base.modules.platform.aop.AdminAudit;
import com.wherelee.base.modules.platform.aop.Idempotent;
import com.wherelee.base.modules.platform.aop.Measured;
import com.wherelee.base.modules.platform.aop.RateLimit;
import com.wherelee.base.modules.platform.aop.PermissionCodes;
import com.wherelee.base.modules.platform.aop.RequirePermission;
import com.wherelee.base.modules.platform.common.PageResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping("/users")
    @RequirePermission(PermissionCodes.ADMIN_USER_MANAGE)
    @Measured("admin.user.page")
    public PageResult<AdminUserVO> users(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String role,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        return service.page(keyword, role, status, page, size);
    }

    @PostMapping("/users")
    @RequirePermission(PermissionCodes.ADMIN_USER_MANAGE)
    @RateLimit(key = "admin:createUser", limit = 30, windowSeconds = 60)
    @Idempotent(key = "admin:createUser", windowSeconds = 5)
    @Measured("admin.user.create")
    @AdminAudit(action = "USER_CREATE", targetType = "USER", targetIdFromResult = true)
    public Long create(@Valid @RequestBody CreateUserRequest request) {
        return service.create(request);
    }

    @PutMapping("/users/{id}/status")
    @RequirePermission(PermissionCodes.ADMIN_USER_MANAGE)
    @Idempotent(key = "admin:changeUserStatus", windowSeconds = 3)
    @Measured("admin.user.status")
    @AdminAudit(action = "USER_STATUS_CHANGE", targetType = "USER", targetIdArg = 1)
    public void changeStatus(@RequestAttribute("userId") Long operatorId,
                             @PathVariable Long id,
                             @Valid @RequestBody ChangeStatusRequest request) {
        service.changeStatus(operatorId, id, request.status());
    }

    @PutMapping("/users/{id}/password")
    @RequirePermission(PermissionCodes.ADMIN_USER_MANAGE)
    @Idempotent(key = "admin:resetUserPassword", windowSeconds = 5)
    @Measured("admin.user.resetPassword")
    @AdminAudit(action = "USER_PASSWORD_RESET", targetType = "USER", targetIdArg = 1)
    public void resetPassword(@RequestAttribute("userId") Long operatorId,
                              @PathVariable Long id,
                              @Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(operatorId, id, request.newPassword());
    }

}
