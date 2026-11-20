package com.wherelee.chatry.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话仓储。
 *
 * <p>所有 SQL 都显式带 tenant_id：租户拦截器看到 tenant_id 就不再改写，
 * 这样 JOIN/子查询/排序都不会被误注入，条件也一眼可审。</p>
 */
@Repository
public class ChatConversationRepository {

    private static final RowMapper<ChatConversation> MAPPER = ChatConversationRepository::map;

    private final JdbcTemplate jdbc;

    public ChatConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(long tenantId, long userId, String title, String model) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_chat_conversation (tenant_id, user_id, title, model, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, userId);
            ps.setString(3, title);
            ps.setString(4, model);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("会话创建失败：未返回主键");
        }
        return key.longValue();
    }

    public List<ChatConversation> listByUser(long tenantId, long userId, int limit) {
        return jdbc.query("""
                SELECT * FROM t_chat_conversation
                WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """, MAPPER, tenantId, userId, limit);
    }

    public Optional<ChatConversation> findOwned(long tenantId, long userId, long id) {
        List<ChatConversation> rows = jdbc.query("""
                SELECT * FROM t_chat_conversation
                WHERE tenant_id = ? AND id = ? AND user_id = ?
                """, MAPPER, tenantId, id, userId);
        return rows.stream().findFirst();
    }

    public boolean softDelete(long tenantId, long userId, long id) {
        return jdbc.update("""
                UPDATE t_chat_conversation SET status = 'DELETED', updated_at = CURRENT_TIMESTAMP(3)
                WHERE tenant_id = ? AND id = ? AND user_id = ? AND status = 'ACTIVE'
                """, tenantId, id, userId) > 0;
    }

    /** 用真实消息数刷新计数与活跃时间（成功、部分、失败落库后都可调用）。 */
    public void refresh(long tenantId, long id, Instant at) {
        jdbc.update("""
                UPDATE t_chat_conversation c
                SET c.message_count = (SELECT COUNT(*) FROM t_chat_message m
                                       WHERE m.tenant_id = c.tenant_id AND m.conversation_id = c.id),
                    c.last_message_at = ?,
                    c.updated_at = ?
                WHERE c.tenant_id = ? AND c.id = ?
                """, Timestamp.from(at), Timestamp.from(at), tenantId, id);
    }

    /** 首条消息到达时把占位标题替换成内容摘要（已有自定义标题则不改）。 */
    public void renameIfPlaceholder(long tenantId, long id, String placeholder, String title) {
        jdbc.update("""
                UPDATE t_chat_conversation SET title = ?
                WHERE tenant_id = ? AND id = ? AND title = ?
                """, title, tenantId, id, placeholder);
    }

    private static ChatConversation map(ResultSet rs, int rowNum) throws SQLException {
        return new ChatConversation(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getLong("user_id"),
                rs.getString("title"),
                rs.getString("model"),
                rs.getString("status"),
                rs.getInt("message_count"),
                toInstant(rs, "last_message_at"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
