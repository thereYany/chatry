package com.wherelee.base.modules.identity.invitation;

import com.wherelee.base.modules.platform.aop.AdminAudit;
import com.wherelee.base.modules.platform.aop.Idempotent;
import com.wherelee.base.modules.platform.aop.Measured;
import com.wherelee.base.modules.platform.aop.RateLimit;
import com.wherelee.base.modules.platform.aop.PermissionCodes;
import com.wherelee.base.modules.platform.aop.RequirePermission;
import com.wherelee.base.modules.platform.common.PageResult;
import com.wherelee.base.modules.identity.invitation.dto.UserInvitationDtos;
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
@RequestMapping("/api/admin/invitations")
public class UserInvitationAdminController {

    private final UserInvitationService service;

    public UserInvitationAdminController(UserInvitationService service) {
        this.service = service;
    }

    @PostMapping
    @RequirePermission(PermissionCodes.ADMIN_INVITATION_MANAGE)
    @RateLimit(key = "admin:invite", limit = 20, windowSeconds = 60)
    @Idempotent(key = "admin:invite", windowSeconds = 5)
    @Measured("admin.invitation.create")
    @AdminAudit(action = "INVITE_CREATE", targetType = "INVITATION",
            targetIdFromResult = true)
    public UserInvitationDtos.CreatedResponse create(
            @RequestAttribute("userId") Long adminId,
            @Valid @RequestBody UserInvitationDtos.CreateRequest request) {
        return service.create(request, adminId);
    }

    @GetMapping
    @RequirePermission(PermissionCodes.ADMIN_INVITATION_MANAGE)
    @Measured("admin.invitation.page")
    public PageResult<UserInvitationVO> page(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.page(status, page, size);
    }

    @PutMapping("/{id}/revoke")
    @RequirePermission(PermissionCodes.ADMIN_INVITATION_MANAGE)
    @Idempotent(key = "admin:inviteRevoke", windowSeconds = 3)
    @Measured("admin.invitation.revoke")
    @AdminAudit(action = "INVITE_REVOKE", targetType = "INVITATION",
            targetIdArg = 0)
    public void revoke(@PathVariable Long id) {
        service.revoke(id);
    }
}
