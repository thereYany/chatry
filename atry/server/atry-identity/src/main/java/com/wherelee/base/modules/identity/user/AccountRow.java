package com.wherelee.base.modules.identity.user;

import lombok.Data;

import java.time.LocalDateTime;

/** MyBatis 行映射对象（账号分页查询）；保持与 AccountProfile 字段一一对应。 */
@Data
public class AccountRow {
    private Long id;
    private String username;
    private String nickname;
    private String role;
    private String status;
    private Boolean mustChangePassword;
    private LocalDateTime createdAt;
}
