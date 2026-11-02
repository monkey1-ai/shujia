package com.example.summer.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class QueryResponse {
    private List<String> answer;
    private long costMs;
    private String sessionId;
}
