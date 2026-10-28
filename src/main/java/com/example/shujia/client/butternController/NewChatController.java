package com.example.shujia.client.butternController;

import com.example.shujia.dto.response.Result;
import com.example.shujia.service.ConversationMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat/session")
public class NewChatController {
    private static final Logger log = LoggerFactory.getLogger(NewChatController.class);
    private final ConversationMemoryService conversationMemoryService;
    private final JdbcTemplate jdbcTemplate;

    public NewChatController(ConversationMemoryService conversationMemoryService,
                             JdbcTemplate jdbcTemplate) {
        this.conversationMemoryService = conversationMemoryService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新建会话：后端颁发 id，并登记进 chat_session（这样列表里能立刻看到它） */
    @PostMapping
    public Result<String> newChat() {
        String sessionId = "web-" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO chat_session (session_id) VALUES (?)", sessionId);
        log.info("【会话】新建 sessionId={}", sessionId);
        return Result.success(sessionId);
    }

    /** 会话列表：最近活跃在前 */
    @GetMapping
    public Result<List<ConversationMemoryService.SessionSummary>> list(
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(conversationMemoryService.listSessions(limit));
    }

    /** 获取某个会话的历史消息（切换/刷新恢复用） */
    @GetMapping("/{sessionId}/messages")
    public Result<List<String>> history(@PathVariable String sessionId) {
        return Result.success(conversationMemoryService.getHistory(sessionId));
    }

    /** 删除某个会话：会话行、PG 消息、Redis 记忆一起删 */
    @DeleteMapping("/{sessionId}")
    public Result<String> delete(@PathVariable String sessionId) {
        log.info("【会话】删除 sessionId={}", sessionId);
        conversationMemoryService.clear(sessionId);
        jdbcTemplate.update("DELETE FROM chat_session WHERE session_id = ?", sessionId);
        return Result.success("success");
    }
}
