package com.wherelee.base.modules.identity.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 账号管理读侧 Mapper（MyBatis 试点）：动态过滤 + 分页。
 *
 * <p>所有 SQL 显式带 tenant_id（MyBatis 不走 JdbcTemplate 的租户注入，
 * 由 MybatisTenantGuard 在运行期强制校验）。</p>
 */
@Mapper
public interface AccountQueryMapper {

    List<AccountRow> page(@Param("tenantId") long tenantId,
                          @Param("keyword") String keyword,
                          @Param("role") String role,
                          @Param("status") String status,
                          @Param("limit") int limit,
                          @Param("offset") int offset);

    long count(@Param("tenantId") long tenantId,
               @Param("keyword") String keyword,
               @Param("role") String role,
               @Param("status") String status);
}
