package com.wherelee.base.modules.contracts.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 账号展示信息目录（读侧契约）。
 *
 * <p>存在的意义：其他模块要展示昵称时，不能直接 join `t_user`，
 * 而是通过这个批量接口拿——一次调用、无 N+1、身份域的表结构不外泄。</p>
 */
public interface AccountDirectory {

    /** 批量取昵称；不存在的 id 不出现在结果里。 */
    Map<Long, String> nicknames(Collection<Long> ids);

    String nickname(Long id);

    /**
     * 按用户名/昵称模糊搜索账号 id（管理端按昵称搜索账号时用）。
     * 只返回 id，昵称等展示信息仍然由调用方批量取。
     */
    List<Long> searchIds(String keyword, int limit);

    /** 批量取账号展示资料（用户名/昵称/角色/状态）。 */
    Map<Long, AccountQuery.AccountProfile> profiles(Collection<Long> ids);
}
