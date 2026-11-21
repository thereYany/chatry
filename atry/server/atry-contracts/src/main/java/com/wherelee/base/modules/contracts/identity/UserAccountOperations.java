package com.wherelee.base.modules.contracts.identity;

/**
 * 管理员账号写操作契约。
 */
public interface UserAccountOperations {

    /**
     * 由管理员直接创建账号并授予角色。
     *
     * <p>身份域不解释角色语义（角色码由垂直应用定义），只校验角色存在。</p>
     */
    Long createUser(String username, String rawPassword, String nickname, String roleCode);

    void changeStatus(Long operatorId, Long userId, String status);

    void resetPassword(Long operatorId, Long userId, String rawPassword);

    UserAccount require(Long userId);
}