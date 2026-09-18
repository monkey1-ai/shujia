package com.example.shujia.config;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration
public class VectorStoreConfig {
    @Bean
    public PgVectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("vector_store")   // 向量存在 PG 的 vector_store 表
                .dimensions(1024)                  // 向量 1024 维，对齐 bge-large-zh-v1.5
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE) // 余弦距离衡量相似度
                .indexType(PgVectorStore.PgIndexType.HNSW) // HNSW 索引，检索更快
                .initializeSchema(true)            // 启动时自动建表
                .build();
    }
}