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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 消息仓储：只做持久化，不含 LLM 调用与计费逻辑。 */
@Repository
public class ChatMessageRepository {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public static final String STATUS_OK = "OK";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_FAILED = "FAILED";

    private static final RowMapper<ChatMessageEntity> MAPPER = ChatMessageRepository::map;

    private final JdbcTemplate jdbc;

    public ChatMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertUserMessage(long tenantId, long conversationId, long userId, String content) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_chat_message
                        (tenant_id, conversation_id, user_id, role, content, status)
                    VALUES (?, ?, ?, 'user', ?, 'OK')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, conversationId);
            ps.setLong(3, userId);
            ps.setString(4, content);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public long insertAssistantMessage(long tenantId, long conversationId, long userId, String content,
                                       int inputTokens, int outputTokens, int cachedReadTokens, long costMicros,
                                       String llmRequestId, String provider, String modelServed,
                                       String finishReason, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_chat_message
                        (tenant_id, conversation_id, user_id, role, content,
                         input_tokens, output_tokens, cached_read_tokens, cost_micros,
                         llm_request_id, provider, model_served, finish_reason, status)
                    VALUES (?, ?, ?, 'assistant', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, conversationId);
            ps.setLong(3, userId);
            ps.setString(4, content);
            ps.setInt(5, inputTokens);
            ps.setInt(6, outputTokens);
            ps.setInt(7, cachedReadTokens);
            ps.setLong(8, costMicros);
            ps.setString(9, llmRequestId);
            ps.setString(10, provider);
            ps.setString(11, modelServed);
            ps.setString(12, finishReason);
            ps.setString(13, status);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    /** 最近 N 条，按时间正序返回（供上下文拼装）。 */
    public List<ChatMessageEntity> recent(long tenantId, long conversationId, int limit) {
        List<ChatMessageEntity> rows = new ArrayList<>(jdbc.query("""
                SELECT * FROM t_chat_message
                WHERE tenant_id = ? AND conversation_id = ?
                ORDER BY id DESC
                LIMIT ?
                """, MAPPER, tenantId, conversationId, limit));
        Collections.reverse(rows);
        return rows;
    }

    /** 全量历史，按时间正序（供前端回放）。 */
    public List<ChatMessageEntity> listByConversation(long tenantId, long conversationId, int limit) {
        List<ChatMessageEntity> rows = new ArrayList<>(jdbc.query("""
                SELECT * FROM t_chat_message
                WHERE tenant_id = ? AND conversation_id = ?
                ORDER BY id DESC
                LIMIT ?
                """, MAPPER, tenantId, conversationId, limit));
        Collections.reverse(rows);
        return rows;
    }

    private long requireKey(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("消息写入失败：未返回主键");
        }
        return key.longValue();
    }

    private static ChatMessageEntity map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new ChatMessageEntity(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getLong("conversation_id"),
                rs.getLong("user_id"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getInt("input_tokens"),
                rs.getInt("output_tokens"),
                rs.getInt("cached_read_tokens"),
                rs.getLong("cost_micros"),
                rs.getString("llm_request_id"),
                rs.getString("provider"),
                rs.getString("model_served"),
                rs.getString("finish_reason"),
                rs.getString("status"),
                createdAt == null ? Instant.now() : createdAt.toInstant());
    }
}
