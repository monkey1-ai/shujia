package com.example.shujia.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ConversationMemoryService {
    private final static Logger log= LoggerFactory.getLogger(ConversationMemoryService.class);
    private static final String KEY_PREFIX = "chat:memory:";
    private static final int MAX_MESSAGES = 20;      // 最多保留 20 条（10 轮）
    private static final long TTL_HOURS = 2;         // 2 小时过期
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RedisTemplate redisTemplate;
    /** 追加一条消息 */
    public void addMessage(String sessionId, String messageId, String role, String content) {
        if (sessionId == null || sessionId.isBlank()) {
            return;   // 无 sessionId 不记录
        }
        try {
            //这个是把数据添加的时候添加的行数赋值给affected，然后返回0的话说明没添加。如果messageid本身存在，那么就不修改，也就是跳过
            int affected = jdbcTemplate.update(
                    "INSERT INTO chat_message (message_id, session_id, role, content) " +
                            "VALUES (?, ?, ?, ?) ON CONFLICT (message_id) DO NOTHING",
                    messageId, sessionId, role, content);
            if (affected == 0) {
                log.debug("【记忆】判重命中，跳过 messageId={}", messageId);
                return;
                }
            touchSession(sessionId);
        }
        catch (Exception e){
            log.error("【PG】插入消息失败，sessionId={}, messageId={}, role={}, content={}",
                    sessionId, messageId, role, content, e);
        }
        try {
            String key= KEY_PREFIX + sessionId;
            redisTemplate.opsForList().rightPush(key, role + ":" + content);
            redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS);
            redisTemplate.opsForList().trim(key, 0, MAX_MESSAGES - 1);
        }
        catch (Exception e) {
            log.error("【Redis】追加消息失败，sessionId={}, messageId={}, role={}, content={}",
                    sessionId, messageId, role, content, e);
        }
    }
    /** 获取历史对话（按时间顺序） */
    public List<String> getHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            List<String> history = redisTemplate.opsForList().range(KEY_PREFIX + sessionId, 0, -1);
            if( history != null && ! history.isEmpty()) {
                log.debug("【记忆】读取 sessionId={}, history={}", sessionId, history);
                return history;
            }
        }catch (Exception e) {
            log.error("【Redis】获取历史对话失败，sessionId={}", sessionId, e);
        }
        List<String> fromDB ;
        try {
            fromDB = jdbcTemplate.query(
                    "SELECT role, content FROM chat_message WHERE session_id = ? ORDER BY id ASC",
                    (rs, rowNum) -> rs.getString("role") + ":" + rs.getString("content"),
                    sessionId);
        } catch (Exception e) {
            log.error("【记忆】读取 PG 失败 sessionId={}", sessionId, e);
            return List.of();
        }
        if( fromDB != null && ! fromDB.isEmpty()) {
            try {
                String key = KEY_PREFIX + sessionId;
                redisTemplate.opsForList().rightPushAll(key, fromDB.toArray(new String[0]));
                redisTemplate.opsForList().trim(key, -MAX_MESSAGES, -1);
                redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS);
                log.info("【记忆】从 PG 回填 sessionId={}, 条数={}", sessionId, fromDB.size());
            } catch (Exception e) {
                log.error("【记忆】回填 Redis 失败 sessionId={}", sessionId, e);
            }
        }
        return fromDB;
    }
    /** 清空会话（用户主动清空时调用） */
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            jdbcTemplate.update("DELETE FROM chat_message WHERE session_id = ?", sessionId);
        }
        catch (Exception e) {
            log.error("【PG】清空会话失败，sessionId={}", sessionId, e);
        }
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        }
        catch (Exception e) {
            log.error("【Redis】清空会话失败，sessionId={}", sessionId, e);
        }
    }
    /** 获取格式化后的历史对话文本（用于拼 prompt） */
    public String getFormattedHistory(String sessionId) {
        List<String> history = getHistory(sessionId);
        return history.stream()
                .map(msg -> {
                    int idx = msg.indexOf(':');
                    if (idx < 0) return msg;
                    String role = msg.substring(0, idx);
                    String content = msg.substring(idx + 1);
                    return ("user".equals(role) ? "用户：" : "助手：") + content;
                })
                .collect(Collectors.joining("\n"));
    }
    public record SessionSummary(String sessionId, String title, int messageCount, String updatedAt) {
    }

    /**
     * 会话列表：直接读 chat_session（按最近活跃倒序）。
     * 标题优先用库里存的 title；没有就用该会话第一条 user 消息的前 20 字。
     * 消息数实时从 chat_message 统计。
     */
    public List<SessionSummary> listSessions(int limit) {
        return jdbcTemplate.query(
                "SELECT s.session_id, s.title, s.updated_at, " +
                        "       (SELECT count(*) FROM chat_message m WHERE m.session_id = s.session_id) AS msg_count, " +
                        "       (SELECT m.content FROM chat_message m " +
                        "         WHERE m.session_id = s.session_id AND m.role = 'user' " +
                        "         ORDER BY m.id ASC LIMIT 1) AS first_user " +
                        "FROM chat_session s " +
                        "ORDER BY s.updated_at DESC LIMIT ?",
                (rs, n) -> {
                    String title = rs.getString("title");
                    if (title == null || title.isBlank()) {
                        title = rs.getString("first_user");
                    }
                    if (title == null || title.isBlank()) {
                        title = "新会话";
                    }
                    title = title.replaceAll("\\s+", " ").trim();
                    if (title.length() > 20) {
                        title = title.substring(0, 20) + "…";
                    }
                    return new SessionSummary(
                            rs.getString("session_id"),
                            title,
                            rs.getInt("msg_count"),
                            rs.getString("updated_at"));
                },
                limit);
    }

    /**
     * 登记/更新一个会话。
     * 追加消息后调用：会话存在就刷新 updated_at（好让它冒到列表顶部），
     * 不存在就补一行（兜底场景：sessionId 是 RagServiceImpl 临时生成的，没走过新建接口）。
     */
    public void touchSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO chat_session (session_id) VALUES (?) " +
                            "ON CONFLICT (session_id) DO UPDATE SET updated_at = now()",
                    sessionId);
        } catch (Exception e) {
            log.error("【会话】登记失败 sessionId={}", sessionId, e);
        }
    }

}

