package com.example.shujia.initializer;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentLoader implements CommandLineRunner {
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Value("${rag.chunk.size}")
    private int chunkSize;
    @Override
    public void run(String @NonNull ... args) throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        // 1. 读取所有文档
        Resource[] files = resolver.getResources("classpath:/documents/*");
        List<Document> docs = new ArrayList<>();
        for (Resource file : files) {
            TikaDocumentReader reader = new TikaDocumentReader(file);
            docs.addAll(reader.get());
        }

        System.out.println("读取到的原始文档数量: " + docs.size());
        for (int i = 0; i < docs.size(); i++) {
            System.out.println("文档 " + i + " 文本长度: " + docs.get(i).getText().length() + " 字符");
        }

        if (docs.isEmpty()) {
            System.out.println("⚠️ 没有找到任何文档，跳过入库");
            return;
        }

        // 2.清洗
        List<Document> cleanedDocuments = new ArrayList<>();
        for (Document doc : docs) {
            String cleaned = doc.getText()
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                    .replaceAll("\n{3,}", "\n\n")
                    .trim();
            if (!cleaned.isEmpty()) {
                cleanedDocuments.add(new Document(cleaned, doc.getMetadata()));
            }
        }

        // 3. 切块
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(50)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
        List<Document> cleanedChunks = splitter.split(cleanedDocuments);


        System.out.println("切分并清洗后，剩余有效文档片段数量: " + cleanedChunks.size());
        for (int i = 0; i < cleanedChunks.size(); i++) {
            System.out.println("文档片段 " + i + " 文本长度: " + cleanedChunks.get(i).getText().length() + " 字符");
        }
        // 4. 清空旧片段后全量重建：每次启动都按磁盘上的文档重建，避免重复累加和残留
        jdbcTemplate.update("DELETE FROM vector_store");
        // 5. 批量入库（一次调用，避免每个片段单独调 embedding）
        vectorStore.add(cleanedChunks);

        // 【原代码保留对照】
        // // 4. 入库（遍历 cleanedChunks）
        // for (int i = 0; i < cleanedChunks.size(); i++) {
        //     try {
        //         vectorStore.add(List.of(cleanedChunks.get(i)));
        //         System.out.println("✅ 片段 " + i + " 入库成功");
        //     } catch (Exception e) {
        //         System.out.println("❌ 片段 " + i + " 入库失败: " + e.getMessage());
        //         System.out.println("   内容: " + cleanedChunks.get(i).getText().substring(0, Math.min(100, cleanedChunks.get(i).getText().length())));
        //     }
        // }

        System.out.println("✅ 已加载并索引 " + cleanedChunks.size() + " 个文档到 PostgreSQL pgvector");
    }
}