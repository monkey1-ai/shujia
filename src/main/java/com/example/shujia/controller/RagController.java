package com.example.shujia.controller;

import com.example.shujia.dto.request.QueryRequest;
import com.example.shujia.dto.response.QueryResponse;
import com.example.shujia.dto.response.Result;
import com.example.shujia.service.RagService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
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
        SseEmitter emitter = new SseEmitter(300_000L);
        CompletableFuture.runAsync(() -> ragService.searchWithRerank(request, emitter));
        return emitter;
    }
    public Result<QueryResponse> rateLimiterFallback(QueryRequest request, Throwable throwable) {
        return Result.error(429,"请求过于频繁，请稍后重试");
    }
}