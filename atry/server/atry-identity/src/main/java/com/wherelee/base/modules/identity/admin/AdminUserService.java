package com.wherelee.base.modules.identity.admin;

import com.wherelee.base.modules.identity.admin.dto.AdminUserVO;
import com.wherelee.base.modules.identity.admin.dto.CreateUserRequest;
import com.wherelee.base.modules.contracts.identity.AccountQuery;
import com.wherelee.base.modules.contracts.identity.AccountQuery.AccountProfile;
import com.wherelee.base.modules.contracts.identity.UserAccountOperations;
import com.wherelee.base.modules.platform.common.PageResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 通用运营端用户管理：账号数据全部走身份域契约，不持有任何用户表 SQL。
 *
 * <p>不含任何垂直业务字段——垂直应用若需要扩展展示信息，
 * 在自己的运营端组合身份域契约与业务契约。</p>
 */
@Service
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE = 10_000;

    private final AccountQuery accountQuery;
    private final UserAccountOperations userAccounts;

    public AdminUserService(AccountQuery accountQuery, UserAccountOperations userAccounts) {
        this.accountQuery = accountQuery;
        this.userAccounts = userAccounts;
    }

    public PageResult<AdminUserVO> page(String keyword, String role, String status, int page, int size) {
        int safePage = Math.min(Math.max(page, 1), MAX_PAGE);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long total = accountQuery.count(blankToNull(keyword), blankToNull(role), blankToNull(status));
        if (total == 0) {
            return new PageResult<>(List.of(), 0, safePage, safeSize);
        }
        PageResult<AccountProfile> accounts = accountQuery.page(
                blankToNull(keyword), blankToNull(role), blankToNull(status),
                (safePage - 1) * safeSize, safeSize);
        List<AdminUserVO> records = accounts.records().stream()
                .map(this::toVo)
                .toList();
        return new PageResult<>(records, total, safePage, safeSize);
    }

    public Long create(CreateUserRequest request) {
        return userAccounts.createUser(
                request.username(),
                request.initialPassword(),
                request.nickname(),
                request.role()
        );
    }

    public void changeStatus(Long operatorId, Long userId, String status) {
        userAccounts.changeStatus(operatorId, userId, status);
    }

    public void resetPassword(Long operatorId, Long userId, String newPassword) {
        userAccounts.resetPassword(operatorId, userId, newPassword);
    }

    private AdminUserVO toVo(AccountProfile profile) {
        AdminUserVO vo = new AdminUserVO();
        vo.setId(profile.id());
        vo.setUsername(profile.username());
        vo.setNickname(profile.nickname());
        vo.setRole(profile.role());
        vo.setStatus(profile.status());
        vo.setMustChangePassword(profile.mustChangePassword());
        vo.setCreatedAt(profile.createdAt());
        return vo;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
