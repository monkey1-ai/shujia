package com.example.shujia.service;

import com.example.shujia.dto.request.QueryRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface RagService {
    void searchWithRerank(QueryRequest request, SseEmitter emitter);
}
