package com.wherelee.base.modules.identity.user;

import com.wherelee.base.modules.contracts.identity.AccountQuery;
import com.wherelee.base.modules.platform.common.PageResult;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 账号分页查询实现。
 *
 * <p>读侧已迁移到 MyBatis（动态过滤、分页），作为 JdbcTemplate → MyBatis 的试点；
 * 写侧与事务仍走 JdbcTemplate。租户条件在 XML 里显式声明，并有 MybatisTenantGuard 兜底。</p>
 */
@Service
public class AccountQueryService implements AccountQuery {

    private final AccountQueryMapper mapper;

    public AccountQueryService(AccountQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<AccountProfile> page(String keyword, String role, String status, int offset, int limit) {
        long tenantId = TenantContext.current();
        List<AccountProfile> records = mapper.page(tenantId, blankToNull(keyword), blankToNull(role),
                        blankToNull(status), limit, offset).stream()
                .map(this::toProfile)
                .toList();
        long total = count(keyword, role, status);
        return new PageResult<>(records, total, offset / Math.max(1, limit) + 1, limit);
    }

    @Override
    public long count(String keyword, String role, String status) {
        return mapper.count(TenantContext.current(), blankToNull(keyword), blankToNull(role),
                blankToNull(status));
    }

    private AccountProfile toProfile(AccountRow row) {
        return new AccountProfile(row.getId(), row.getUsername(), row.getNickname(), row.getRole(),
                row.getStatus(), Boolean.TRUE.equals(row.getMustChangePassword()), row.getCreatedAt());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
