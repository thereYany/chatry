package com.wherelee.base.modules.platform.audit;

import com.wherelee.base.modules.platform.aop.PermissionCodes;
import com.wherelee.base.modules.platform.aop.RequirePermission;
import com.wherelee.base.modules.platform.common.PageResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 管理端：管理员操作审计检索。 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuditController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminAuditRepository repository;

    public AdminAuditController(AdminAuditRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/audit-logs")
    @RequirePermission(PermissionCodes.ADMIN_AUDIT_VIEW)
    public PageResult<AdminAuditView> logs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, Math.min(page, 100_000));
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        LocalDateTime fromTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toTime = to == null ? null : to.plusDays(1).atStartOfDay();
        return repository.search(blankToNull(action), operatorId, success, fromTime, toTime,
                safePage, safeSize);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
