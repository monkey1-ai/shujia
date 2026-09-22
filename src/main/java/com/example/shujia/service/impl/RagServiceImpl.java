package com.example.shujia.service.impl;

import com.example.shujia.client.RerankerClient;
import com.example.shujia.dto.request.QueryRequest;
import com.example.shujia.service.ConversationMemoryService;
import com.example.shujia.service.RagService;
import com.example.shujia.service.prompt.PromptBuilder;
import com.example.shujia.service.router.ChatModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class RagServiceImpl implements RagService {
    private static final Logger log = LoggerFactory.getLogger(RagServiceImpl.class);
    private final RerankerClient rerankerClient;
    private final VectorStore vectorStore;
    private final RedisTemplate<String, String> redisTemplate;
    private final ChatModelRouter chatModelRouter;
    private final PromptBuilder promptBuilder;
    private final ConversationMemoryService memoryService;

    public RagServiceImpl(RerankerClient rerankerClient,
                          VectorStore vectorStore,
                          RedisTemplate<String, String> redisTemplate,
                          ChatModelRouter chatModelRouter,
                          PromptBuilder promptBuilder,
                          ConversationMemoryService memoryService) {
        this.rerankerClient = rerankerClient;
        this.vectorStore = vectorStore;
        this.redisTemplate = redisTemplate;
        this.chatModelRouter = chatModelRouter;
        this.promptBuilder = promptBuilder;
        this.memoryService = memoryService;
    }

    @Value("${rag.retrieval.top-k}")
    private int topK;

    @Override
    public void searchWithRerank(QueryRequest request, SseEmitter emitter) {
        long start = System.nanoTime();
        String question = request.getQuestion();
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        log.info("【rag查询】开始sessionId:{}，question:{}", sessionId, question);

        // 1. 查询缓存
        String cacheKey = "rag:" + sessionId + ":" + question;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.info("【rag查询】命中缓存,sessionId:{}，question:{}", sessionId, question);
            try {
                // 先发 sessionId，再发答案，最后发完成标记
                emitter.send(SseEmitter.event().name("session").data(sessionId));
                emitter.send(SseEmitter.event().name("content").data(cached));
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return;
        }

        // 2. 查询历史对话
        List<String> history = memoryService.getHistory(sessionId);
        String historyText = formatHistory(history);
        memoryService.addMessage(sessionId, "user", question);

        // 3. 向量检索
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .similarityThreshold(0.0)
                .build();
        List<Document> retrievedDocs = vectorStore.similaritySearch(searchRequest);
        log.info("【rag查询】向量检索召回 {} 条（topK={}）,question:{}", retrievedDocs.size(), topK, question);
        List<String> docTexts = retrievedDocs.stream().map(Document::getText).distinct().toList();

        // 4. 调用reranker服务
        List<String> rerankedContents = rerankerClient.reranker(question, docTexts, topK);
        log.info("【rag查询】reranker 返回 {} 条,question:{}", rerankedContents.size(), question);

        // 5. 拼接上下文
        String context = String.join("\n\n", rerankedContents);
        log.info("【rag查询】最终拼接 context 长度={} 字符", context.length());

        // 6. 构建 prompt
        String promptTemplate = promptBuilder.build(context, question, historyText);
        ChatClient model = chatModelRouter.route(sessionId);

        // 7. 先发 sessionId 给前端
        try {
            emitter.send(SseEmitter.event().name("session").data(sessionId));
        } catch (Exception e) {
            emitter.completeWithError(e);
            return;
        }

        // 8. 流式调用模型
        StringBuilder fullAnswer = new StringBuilder();
        final String finalSessionId = sessionId;
        final String finalCacheKey = cacheKey;

        model.prompt(promptTemplate)
                .stream()
                .chatResponse()
                .doOnNext(chunk -> {
                    try {
                        if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                            String text = chunk.getResult().getOutput().getText();
                            if (text != null && !text.isEmpty()) {
                                fullAnswer.append(text);
                                emitter.send(SseEmitter.event().name("content").data(text, MediaType.TEXT_PLAIN));
                            }
                        }
                    } catch (Exception e) {
                        log.error("【流式输出】发送chunk失败", e);
                    }
                })
                .doOnComplete(() -> {
                    try {
                        String answer = fullAnswer.toString();
                        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                        log.info("【rag查询】流式完成，sessionId:{}，耗时:{}ms，答案长度:{}", finalSessionId, costMs, answer.length());

                        // 存记忆
                        memoryService.addMessage(finalSessionId, "assistant", answer);

                        // 存缓存
                        redisTemplate.opsForValue().set(finalCacheKey, answer, 1, TimeUnit.DAYS);
                        log.info("结果已缓存");

                        // 发送完成事件（包含耗时）
                        emitter.send(SseEmitter.event().name("done").data("{\"costMs\":" + costMs + "}"));
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("【流式输出】完成处理失败", e);
                        emitter.completeWithError(e);
                    }
                })
                .doOnError(e -> {
                    log.error("【流式输出】模型调用失败", e);
                    try {
                        emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                    } catch (Exception ignored) {}
                    emitter.completeWithError(e);
                })
                .subscribe();
    }

    private String preview(String text) {
        if (text == null) return "null";
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 120 ? singleLine.substring(0, 120) + "..." : singleLine;
    }

    private String formatHistory(List<String> history) {
        if (history.isEmpty()) return "";
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
}