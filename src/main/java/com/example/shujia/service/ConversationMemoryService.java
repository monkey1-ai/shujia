package com.example.shujia.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ConversationMemoryService {
    private final static Logger log= LoggerFactory.getLogger(ConversationMemoryService.class);
    private static final String KEY_PREFIX = "chat:memory:";
    private static final int MAX_MESSAGES = 20;      // 最多保留 20 条（10 轮）
    private static final long TTL_HOURS = 2;         // 2 小时过期
    @Autowired
    private RedisTemplate redisTemplate;
    /** 追加一条消息 */
    public void addMessage(String sessionId, String role, String content) {
        if (sessionId == null || sessionId.isBlank()) {
            return;   // 无 sessionId 不记录
        }
        String key = KEY_PREFIX + sessionId;
        String message = role + ":" + content;
        redisTemplate.opsForList().rightPush(key, message);
        // 滑窗截断：只保留最近 MAX_MESSAGES 条
        redisTemplate.opsForList().trim(key, -MAX_MESSAGES, -1);
        // 设置过期时间
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS);
        log.debug("【记忆】写入 sessionId={}, role={}", sessionId, role);
    }
    /** 获取历史对话（按时间顺序） */
    public List<String> getHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<String> history = redisTemplate.opsForList().range(KEY_PREFIX + sessionId, 0, -1);
        log.debug("【记忆】读取 sessionId={}, history={}", sessionId, history);
        return history;
    }
    /** 清空会话（用户主动清空时调用） */
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        redisTemplate.delete(KEY_PREFIX + sessionId);
        log.info("【记忆】清空 sessionId={}", sessionId);
    }

}

