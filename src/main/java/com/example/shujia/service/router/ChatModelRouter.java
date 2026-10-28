package com.example.shujia.service.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

@Component
public class ChatModelRouter {
    private static final Logger log = LoggerFactory.getLogger(ChatModelRouter.class);

    private final ChatClient deepseek;
    private final ChatClient kimi;

    public ChatModelRouter(@Qualifier("deepseek") ChatClient deepseek,
                           @Qualifier("Kimi") ChatClient kimi) {
        this.deepseek = deepseek;
        this.kimi = kimi;
    }

    /**
     * A/B 盲测路由：按 sessionId 自动分流到不同模型。
     * - 有 sessionId 时：按 hash 分流，保证同一对话始终用同一模型
     * - 无 sessionId 时：随机分流
     */
    @Fallback
    public ChatClient route(String sessionId) {
        boolean useKimi;
        if (sessionId != null && !sessionId.isEmpty()) {
            useKimi = Math.abs(sessionId.hashCode()) % 2 == 0;
        } else {
            useKimi = Math.random() >= 0.5;
        }

        ChatClient selected = useKimi ? kimi : deepseek;
        String modelName = useKimi ? "kimi" : "deepseek";
        log.info("【模型路由】sessionId={}, 分配到 {}", sessionId, modelName);
        return selected;
    }
}
