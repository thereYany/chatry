package com.wherelee.base.modules.identity.user;

import com.wherelee.base.modules.contracts.identity.AccountDirectory;
import com.wherelee.base.modules.contracts.identity.AccountQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号展示信息目录实现：只读、批量、不暴露用户表结构。
 */
@Service
public class AccountDirectoryService implements AccountDirectory {

    private final JdbcTemplate jdbc;

    public AccountDirectoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, String> nicknames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Long> unique = ids.stream().filter(id -> id != null).distinct().toList();
        if (unique.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(unique.size(), "?"));
        Map<Long, String> result = new HashMap<>();
        jdbc.query("SELECT id, nickname FROM t_user WHERE id IN (" + placeholders + ")",
                rs -> {
                    result.put(rs.getLong("id"), rs.getString("nickname"));
                }, unique.toArray());
        return result;
    }

    @Override
    public String nickname(Long id) {
        return nicknames(List.of(id)).get(id);
    }

    @Override
    public Map<Long, AccountQuery.AccountProfile> profiles(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Long> unique = ids.stream().filter(id -> id != null).distinct().toList();
        if (unique.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(unique.size(), "?"));
        Map<Long, AccountQuery.AccountProfile> result = new HashMap<>();
        jdbc.query("SELECT id, username, nickname, role, status, must_change_password, created_at "
                        + "FROM t_user WHERE id IN (" + placeholders + ")",
                rs -> {
                    long id = rs.getLong("id");
                    result.put(id, new AccountQuery.AccountProfile(
                            id,
                            rs.getString("username"),
                            rs.getString("nickname"),
                            rs.getString("role"),
                            rs.getString("status"),
                            rs.getBoolean("must_change_password"),
                            rs.getTimestamp("created_at").toLocalDateTime()));
                }, unique.toArray());
        return result;
    }

    @Override
    public List<Long> searchIds(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String like = "%" + keyword.trim() + "%";
        return jdbc.queryForList(
                "SELECT id FROM t_user WHERE username LIKE ? OR nickname LIKE ? ORDER BY id LIMIT ?",
                Long.class, like, like, Math.min(Math.max(limit, 1), 500));
    }
}
