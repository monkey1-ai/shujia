
package com.example.shujia.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.shujia.util.TraceIdUtil;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class RerankerClient {
    private static final Logger log=LoggerFactory.getLogger(RerankerClient.class);
    private final RestTemplate restTemplate;
    @Value("${rag.rerank.service-url}")
    private String rerankerUrl;
    private final ObjectMapper objectMapper= new ObjectMapper();
    public RerankerClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Retry(name = "rerankerRetry", fallbackMethod = "fallbackReranker")
    @CircuitBreaker(name = "rerankerCircuitBreaker", fallbackMethod = "fallbackReranker")
    public List<String> reranker(String query, List<String> documents,int topN) {
        String traceID= TraceIdUtil.get();
        log.info("调用Reranker服务,query={},docs数量={},traceId={}",query,documents.size(),traceID);
        Map<String ,Object> requestBody = new HashMap<>();
        requestBody.put("query",query);
        requestBody.put("documents",documents);
        requestBody.put("top_n",topN);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        //这里是向py服务发送请求，可以改为webclient
        ResponseEntity<String> requestEntity = restTemplate.postForEntity(rerankerUrl,entity, String.class);
        //这里的getstatuscode是获取返回的响应，如果等于200说明成功，400说明参数有误，其他的我也不会。还有这个org.springframework.http.HttpStatus.OK
        //这是标准格式，写200也可以
        if (requestEntity.getStatusCode()!= org.springframework.http.HttpStatus.OK) {
            throw new RuntimeException("Reranker服务调用失败"+requestEntity.getStatusCode());
            }
        try {
            JsonNode root= objectMapper.readTree(requestEntity.getBody());
            JsonNode results= root.get("results");
            List<String> ranked=new ArrayList<>();
            for (JsonNode result : results) {
                ranked.add(result.get("document").asText());
            }
            log.info("调用Reranker服务成功,query={},docs数量={},返回结果数量={},traceId={}",query,documents.size(),ranked.size(),traceID);
            return ranked;
            } catch (Exception e) {
            log.error("调用Reranker服务失败,query={},docs数量={},异常信息={}，traceId={}",query,documents.size(),e,traceID);
            throw new RuntimeException("Reranker服务调用失败"+e.getMessage());
            }

        }
        public List<String> fallbackReranker(String query, List<String> documents,int topN, Throwable throwable) {
            log.error("调用Reranker服务失败,query={},docs数量={},异常信息={}",
                    query,documents.size(),throwable.getMessage());
            return documents.stream().limit(topN).toList();
        }
    }
