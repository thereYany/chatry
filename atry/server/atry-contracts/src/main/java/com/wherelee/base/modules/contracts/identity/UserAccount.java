package com.wherelee.base.modules.contracts.identity;

/**
 * 运营端需要的账号只读快照。
 */
public record UserAccount(Long id, String username, String nickname, String role, String status) {
}