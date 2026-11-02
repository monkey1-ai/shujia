package com.example.summer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class EvalService {
    private final Logger logger = LoggerFactory.getLogger(EvalService.class);

    @Autowired
    private PgVectorStore pgVectorStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    public static class EvalResult {
        private double hitRate; // 命中率
        private double mrr;     // 平均倒数排名 (MRR)
        private int totalQueries;
        private int hits;
        private List<String> details;
    }

    public EvalResult runEvaluation(int topk) {
        logger.info("开始评估，TopK = {}", topk);
        List<Map<String, String>> testSet = loadTestset();
        int total = testSet.size();
        int hits = 0;
        double mrrSum = 0.0;
        List<String> details = new ArrayList<>();

        for (Map<String, String> queryMap : testSet) {
            String question = queryMap.get("question");
            String source = queryMap.get("source");
            String groundTruth = queryMap.get("ground_truth");

            // 1. 发起向量检索
            List<Document> retrieved = pgVectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(topk)
                            .build()
            );

            boolean hit = false;
            int rank = -1;

            // 2. 遍历检索结果，判断是否命中
            for (int i = 0; i < retrieved.size(); i++) {
                Document doc = retrieved.get(i);
                if (matches(doc, source, groundTruth)) {
                    hit = true;
                    rank = i + 1; // 排名从1开始
                    break;
                }
            }

            // 3. 计算指标
            if (hit) {
                hits++;
                mrrSum += 1.0 / rank;
            }
            details.add("问题: " + question + " | 答案: " + groundTruth + " | 是否命中: " + hit + " | 排名: " + rank);
        }

        // 4. 汇总最终结果
        EvalResult result = new EvalResult();
        result.setHitRate(total > 0 ? (hits * 1.0 / total) : 0.0);
        result.setMrr(total > 0 ? (mrrSum / total) : 0.0);
        result.setTotalQueries(total);
        result.setHits(hits);
        result.setDetails(details);

        logger.info("评估完成，命中率: {}, MRR: {}", result.getHitRate(), result.getMrr());
        return result;
    }

    /**
     * 判断一个 chunk 是否算命中该测试题。
     *
     * 优先按 source（文件名）匹配：只要召回结果里出现了正确文件，就算检索捞到了出处，
     * 不用猜"答案原文长什么样"。测试集没给 source 时退回关键词匹配。
     */
    private boolean matches(Document doc, String source, String groundTruth) {
        if (source != null && !source.isBlank()) {
            Object actual = doc.getMetadata().get("source");
            return actual != null && source.equals(actual.toString());
        }
        if (groundTruth == null || groundTruth.isBlank()) {
            return false;
        }
        return doc.getText() != null && doc.getText().contains(groundTruth);
    }

    private List<Map<String, String>> loadTestset() {
        try {
            ClassPathResource resource = new ClassPathResource("eval/qa_testset.json");
            try (InputStream is = resource.getInputStream()) {
                return objectMapper.readValue(is, new TypeReference<List<Map<String, String>>>() {});
            }
        } catch (Exception e) {
            logger.error("加载评测数据集失败", e);
            return new ArrayList<>();
        }
    }
}