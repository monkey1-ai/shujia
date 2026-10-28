package com.example.shujia.controller;

import com.example.shujia.dto.request.QueryRequest;
import com.example.shujia.service.RagService;
import com.example.shujia.util.TraceIdUtil;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

@RestController
public class RagController {
    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/rag/search")
    @RateLimiter(name = "ragSearchRateLimiter", fallbackMethod = "rateLimiterFallback")
    public SseEmitter search(@Valid @RequestBody QueryRequest request) {
        // 30_000L = 30 秒 SSE 超时：连接最多活 30 秒，超时前端会收到断流
        SseEmitter emitter = new SseEmitter(120000L);

        // --- 第 1 步：在【请求线程】里取到入口的 traceId ---
        // 此刻还在接待 HTTP 请求的 Tomcat 线程上，TraceIdFilter 已经把 id 存进了
        // 这条线程的 ThreadLocal，所以 get() 拿到的是本次请求真正的入口 id。
        String traceId = TraceIdUtil.get();

        // --- 第 2 步：runAsync 把活儿丢给【异步线程】 ---
        // 这就是"切线程"的地方：runAsync 内部用 ForkJoinPool 的线程来跑这个 lambda，
        // 请求线程不等待，马上往下走 return emitter。
        // 也就是说，下面 searchWithRerank 的执行线程 ≠ 上面的请求线程。
        CompletableFuture.runAsync(() -> {
            // 因为换了线程、ThreadLocal 储物柜是空的，必须手动把入口 id 搬进来，
            // 否则后续 RerankerClient 等地方调 get() 会惰性生成一个新 id，对上不。
            TraceIdUtil.set(traceId);
            try {
                ragService.searchWithRerank(request, emitter);
            } finally {
                // 异步线程跑完自己清理，避免线程池复用这条线程时残留旧 id（脏数据）
                TraceIdUtil.clear();
            }
        });

        // 请求线程拿到 emitter 空壳就立刻返回，浏览器先收到 SSE 连接，之后异步线程再往里推数据
        return emitter;
    }

    /**
     * 限流兜底方法（@RateLimiter 触发限流时调用）。
     *
     * 【改这里的原因】原来的返回类型是 Result<QueryResponse>，结果报
     * NoSuchMethodException：resilience4j 是**按返回类型**去匹配 fallback 的，
     * 原方法 search() 返回 SseEmitter，fallback 也必须返回 SseEmitter 才能被找到。
     *
     * 【为什么不能返回 JSON】这是 SSE 接口，前端用 fetch 读的是事件流，不是 JSON。
     * 所以限流时不能返回 Result 对象，而是往 emitter 里推一个 error 事件再 complete()，
     * 前端 index.js 里 switch 到 case 'error' 就能正常展示这段提示。
     *
     * 参数必须与原方法一致并多加一个 Throwable，这也是 resilience4j 的约定。
     */
    public SseEmitter rateLimiterFallback(QueryRequest request, Throwable throwable) {
        SseEmitter emitter = new SseEmitter();
        try {
            emitter.send(SseEmitter.event().name("error").data("请求过于频繁，请稍后重试", MediaType.TEXT_PLAIN));
        } catch (Exception ignored) {
            // 推事件失败也无所谓：马上 complete() 结束这个流即可
        }
        emitter.complete();
        return emitter;
    }
}