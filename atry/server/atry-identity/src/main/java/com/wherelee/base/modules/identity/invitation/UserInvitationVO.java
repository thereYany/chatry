package com.wherelee.base.modules.identity.invitation;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserInvitationVO {
    private Long id;
    private String username;
    private String nickname;
    private String roleCode;
    private String status;
    private LocalDateTime expiresAt;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime usedAt;
    private Boolean expired;
}
