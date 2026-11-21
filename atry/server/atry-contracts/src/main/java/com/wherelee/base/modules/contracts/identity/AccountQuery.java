package com.wherelee.base.modules.contracts.identity;

import com.wherelee.base.modules.platform.common.PageResult;

import java.time.LocalDateTime;

/**
 * 账号管理读侧契约（管理端用户列表）。
 *
 * <p>分页、关键字、角色/状态过滤都在身份域完成；其他模块不再直接查 `t_user`。</p>
 */
public interface AccountQuery {

    PageResult<AccountProfile> page(String keyword, String role, String status, int offset, int limit);

    long count(String keyword, String role, String status);

    record AccountProfile(Long id, String username, String nickname, String role, String status,
                          boolean mustChangePassword, LocalDateTime createdAt) {
    }
}
