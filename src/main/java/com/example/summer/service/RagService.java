package com.example.summer.service;

import com.example.summer.dto.request.QueryRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface RagService {
    void searchWithRerank(QueryRequest request, SseEmitter emitter);
}
