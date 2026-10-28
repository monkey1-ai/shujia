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
import org.springframework.ai.chat.metadata.Usage;
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
import java.util.concurrent.atomic.AtomicReference;
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
        String sessionId = request.getSessionId() != null && !request.getSessionId().isBlank() ? request.getSessionId() : UUID.randomUUID().toString();
        String messageId = request.getMessageId() != null && !request.getMessageId().isBlank() ? request.getMessageId() : UUID.randomUUID().toString();
        log.info("【rag查询】开始sessionId:{}，messageId:{}, question:{}", sessionId,messageId, question);
        long time1=System.nanoTime();
        // 1. 查询缓存
        String cacheKey = "rag:" + sessionId + ":" + question;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        long time2=System.nanoTime();
        if (cached != null) {
            log.info("【rag查询】命中缓存,sessionId:{}，question:{}", sessionId, question);
            memoryService.addMessage(sessionId, messageId, "user", question);
            memoryService.addMessage(sessionId, messageId + "-a", "assistant", cached);
            try {
                // 先发 sessionId，再发答案，最后发完成标记
                emitter.send(SseEmitter.event().name("session").data(sessionId));
                emitter.send(SseEmitter.event().name("content").data(cached));
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, e);
            }
            return;
        }
        // 2. 查询历史对话
        String historyText = memoryService.getFormattedHistory(sessionId);
        memoryService.addMessage(sessionId,messageId,"user",question);
        long time3=System.nanoTime();
        // 3. 向量检索

        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .similarityThreshold(0.0)
                .build();
        List<Document> retrievedDocs = vectorStore.similaritySearch(searchRequest);
        log.info("【rag查询】向量检索召回 {} 条（topK={}）,question:{}", retrievedDocs.size(), topK, question);
        List<String> docTexts = retrievedDocs.stream().map(Document::getText).distinct().toList();
        long time4=System.nanoTime();
        // 4. 调用reranker服务
        List<String> rerankedContents = rerankerClient.reranker(question, docTexts, topK);
        log.info("【rag查询】reranker 返回 {} 条,question:{}", rerankedContents.size(), question);
        long time5=System.nanoTime();
        // 5. 拼接上下文
        String context = String.join("\n\n", rerankedContents);
        log.info("【rag查询】最终拼接 context 长度={} 字符", context.length());
        long time6=System.nanoTime();
        log.info("【rag查询】耗时：缓存查询:{}ms，历史对话:{}ms，向量检索:{}ms，reranker:{}ms，拼接上下文:{}ms",
                TimeUnit.NANOSECONDS.toMillis(time2-time1),
                TimeUnit.NANOSECONDS.toMillis(time3-time2),
                TimeUnit.NANOSECONDS.toMillis(time4-time3),
                TimeUnit.NANOSECONDS.toMillis(time5-time4),
                TimeUnit.NANOSECONDS.toMillis(time6-time5));
        // 6. 构建 prompt
        String promptTemplate = promptBuilder.build(context, question, historyText);
        ChatClient model = chatModelRouter.route(sessionId);

        // 7. 先发 sessionId 给前端
        try {
            emitter.send(SseEmitter.event().name("session").data(sessionId));
        } catch (Exception e) {
            sendError(emitter, e);
            return;
        }

        // 8. 流式调用模型
        StringBuilder fullAnswer = new StringBuilder();
        final String finalCacheKey = cacheKey;
        AtomicReference<Usage> finalUsage = new AtomicReference<>();

        model.prompt(promptTemplate)
                .stream()
                .chatResponse()
                .doOnNext(chunk -> {
                    try {
                        if(!chunk.getMetadata().isEmpty()){
                            var usage = chunk.getMetadata().getUsage();
                            if (usage.getPromptTokens() > 0 || usage.getCompletionTokens() > 0) {
                                log.info("💰 [真实Token] prompt={}, completion={}, total={}",
                                        usage.getPromptTokens(),
                                        usage.getCompletionTokens(),
                                        usage.getTotalTokens());
                            }
                        }
                        if (chunk.getResult() != null) {
                            chunk.getResult();
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
                        log.info("【rag查询】流式完成，sessionId:{}，耗时:{}ms，答案长度:{}", sessionId, costMs, answer.length());

                        // 存记忆
                        memoryService.addMessage(sessionId, messageId+ "-a", "assistant", answer);

                        // 存缓存
                        redisTemplate.opsForValue().set(finalCacheKey, answer, 1, TimeUnit.DAYS);
                        log.info("结果已缓存");

                        // 发送完成事件（包含耗时）
                        emitter.send(SseEmitter.event().name("done").data("{\"costMs\":" + costMs + "}"));
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("【流式输出】完成处理失败", e);
                        sendError(emitter, e);
                    }
                })
                .doOnError(e -> {
                    log.error("【流式输出】模型调用失败", e);
                    sendError(emitter, e);
                })
                .subscribe();
    }

    /**
     * 统一把流内错误转成一个 error 事件推给前端，再正常关闭流。
     *
     * 【为什么不用 completeWithError】那会把异常回抛给 Servlet 容器，
     * 触发 GlobalExceptionHandler → 它想往一个已经是 text/event-stream 的响应里
     * 写 Result JSON，于是又报 “No converter for ... text/event-stream”。
     * 前端 index.js 只认 error 事件（case 'error'），所以这里推事件 + complete() 即可。
     */
    private void sendError(SseEmitter emitter, Throwable e) {
        try {
            emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    private String preview(String text) {
        if (text == null) return "null";
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 120 ? singleLine.substring(0, 120) + "..." : singleLine;
    }
}