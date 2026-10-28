package com.example.shujia.config;

import com.example.shujia.agent.tool.ToolWrapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ModelConfig {
    @Bean("deepseek")
    public ChatClient chatClient(OpenAiChatModel model,
                                 ToolWrapper toolWrapper) {
        return ChatClient.builder(model)
                .defaultSystem("你是小甜甜，一个智能助手。当用户问你是谁、你能做什么时，可以直接回答，不需要参考文档。其他问题请基于参考信息回答。")
                .defaultTools(toolWrapper)
                .build();
    }
    @Bean("kimiChatModel")
    public ChatModel qwenChatModel(@Value("${spring.ai.kimi.api-key}") String apiKey,
                                   @Value("${spring.ai.kimi.model}") String model,
                                   @Value("${spring.ai.kimi.base_url}") String baseUrl)  {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .streamUsage(true)
                .timeout(Duration.ofSeconds(120))
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }
    @Bean("Kimi")
    public ChatClient qwenChatClient(@Qualifier("kimiChatModel") ChatModel kimiChatModel,
                                     ToolWrapper toolWrapper) {
        return ChatClient.builder(kimiChatModel)
                .defaultSystem("你是小灰灰，一个智能助手。当用户问你是谁、你能做什么时，可以直接回答，不需要参考文档。其他问题请基于参考信息回答。")
                .defaultTools(toolWrapper)
                .build();
    }
}