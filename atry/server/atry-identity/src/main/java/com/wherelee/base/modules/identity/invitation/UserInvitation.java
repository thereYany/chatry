package com.wherelee.base.modules.identity.invitation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInvitation {

    private Long tenantId;
    private Long id;
    private String tokenHash;
    private String username;
    private String nickname;
    private String roleCode;
    private String status;
    private LocalDateTime expiresAt;
    private Long createdBy;
    private Long usedBy;
    private LocalDateTime createdAt;
    private LocalDateTime usedAt;
}
