package com.wherelee.base.modules.identity.invitation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserInvitationMapper {

    int insert(UserInvitation invitation);

    UserInvitation findById(@Param("id") Long id, @Param("tenantId") Long tenantId);

    UserInvitation findByTokenHash(@Param("tokenHash") String tokenHash,
                                      @Param("tenantId") Long tenantId);

    UserInvitation findByTokenHashForUpdate(@Param("tokenHash") String tokenHash,
                                               @Param("tenantId") Long tenantId);

    UserInvitation findPendingByUsername(@Param("username") String username,
                                            @Param("now") LocalDateTime now,
                                            @Param("tenantId") Long tenantId);

    List<UserInvitationVO> findPage(@Param("status") String status,
                                       @Param("pageNow") LocalDateTime pageNow,
                                       @Param("offset") int offset,
                                       @Param("size") int size,
                                       @Param("tenantId") Long tenantId);

    long count(@Param("status") String status, @Param("tenantId") Long tenantId);

    int markUsed(@Param("id") Long id,
                 @Param("userId") Long userId,
                 @Param("now") LocalDateTime now,
                 @Param("tenantId") Long tenantId);

    int revoke(@Param("id") Long id, @Param("now") LocalDateTime now,
               @Param("tenantId") Long tenantId);
}
