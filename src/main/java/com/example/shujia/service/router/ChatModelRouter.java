package com.example.shujia.service.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ChatModelRouter {
    private static final Logger log = LoggerFactory.getLogger(ChatModelRouter.class);

    private static final String  deepseek="deepseek";
    private static final String  kimi="Kimi";
    private final Map<String, ChatClient> chatClientMap;

    public ChatModelRouter(Map<String, ChatClient> chatClientMap) {
        this.chatClientMap = chatClientMap;
    }
    public String route(String sessionId){
        boolean usedDeepSeek =sessionId!=null && !sessionId.isEmpty()
                ?Math.abs(sessionId.hashCode())%2==0:Math.random()>=0.5;
        String model=usedDeepSeek?deepseek:kimi;
        log.info("【模型路由】:{},sessionId:{}",model,sessionId);
        return model;
    }
    public static String backupOf(String model){
        return deepseek.equals(model)?kimi:deepseek;
    }
    public ChatClient getChatClient(String model){
        return chatClientMap.get(model);
    }
}
