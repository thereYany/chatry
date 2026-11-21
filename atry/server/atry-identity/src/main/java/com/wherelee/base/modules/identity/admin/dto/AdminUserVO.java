package com.wherelee.base.modules.identity.admin.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserVO {
    private Long id;
    private String username;
    private String nickname;
    private String role;
    private String status;
    private Boolean mustChangePassword;
    private LocalDateTime createdAt;
}