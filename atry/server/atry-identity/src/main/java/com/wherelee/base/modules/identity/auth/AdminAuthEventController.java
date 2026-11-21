package com.wherelee.base.modules.identity.auth;

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

/** 管理端：认证事件检索（注册/登录/登出/改密）。 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthEventController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuthEventAdminRepository repository;

    public AdminAuthEventController(AuthEventAdminRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/auth-events")
    @RequirePermission(PermissionCodes.ADMIN_AUDIT_VIEW)
    public PageResult<AuthEventView> events(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, Math.min(page, 100_000));
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        LocalDateTime fromTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toTime = to == null ? null : to.plusDays(1).atStartOfDay();
        return repository.search(blankToNull(eventType), blankToNull(username), success,
                fromTime, toTime, safePage, safeSize);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
