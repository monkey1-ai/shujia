# shujia RAG 项目流程整理

> 整理日期：2026-08-23　|　基于 `com.gym.shujia` 包下全部 13 个 Java 文件（12 个 main + 1 个 test）

---

## 一、项目分层总览

```
┌─────────────────────────────────────────────────────┐
│  启动层    ShujiaApplication（入口，@SpringBootApplication）│
├─────────────────────────────────────────────────────┤
│  配置层    VectorStoreConfig（PgVectorStore Bean）         │
│           ChatConfig（ChatClient Bean，默认人设）           │
├─────────────────────────────────────────────────────┤
│  入库层    DocumentLoader（CommandLineRunner）             │
│           Tika 解析 documents/ → TokenTextSplitter 切块     │
│           → 写入 PostgreSQL pgvector                     │
├─────────────────────────────────────────────────────┤
│  接口层    RagController（POST /rag/search）              │
├─────────────────────────────────────────────────────┤
│  业务层    RagService（接口）→ RagServiceImpl（实现）      │
│           检索 → 拼上下文 → 调大模型 → 组装响应            │
├─────────────────────────────────────────────────────┤
│  支撑层    Result（统一响应外壳）                          │
│           GlobalExceptionHandler（异常转 Result）         │
│           TraceIdUtil（链路追踪 ID）                      │
├─────────────────────────────────────────────────────┤
│  外部依赖  PostgreSQL + pgvector（存储）                   │
│           DeepSeek（对话模型，OpenAI 兼容协议）            │
│           SiliconFlow（bge-large-zh-v1.5 嵌入模型）        │
└─────────────────────────────────────────────────────┘
```

---

## 二、启动流程

```
运行 ShujiaApplication.main()
   │
   ├─ ① SpringApplication.run() 启动容器
   │     读取 @SpringBootApplication：自动配置 + 扫描 com.gym.shujia 及子包
   │
   ├─ ② 加载 application.yaml（active profile = dev）
   │     数据源 → localhost:5432/demo3
   │     spring.ai.openai.* → DeepSeek（chat）/ SiliconFlow（embedding），经 .env 注入密钥
   │
   ├─ ③ 装配 Bean
   │     starter 自动创建：OpenAiChatModel、EmbeddingModel、JdbcTemplate
   │     VectorStoreConfig 手动创建：PgVectorStore
   │       （表 vector_store / 1024 维 / 余弦距离 / HNSW 索引 / 自动建表）
   │     ChatConfig 手动创建：ChatClient
   │       （默认系统人设 "你是一个专业的问答机器人"）
   │
   ├─ ④ 执行 DocumentLoader.run()（CommandLineRunner）
   │     → 入库流程（见下节）
   │
   └─ ⑤ 启动内嵌 Tomcat，监听端口，等待请求
```

---

## 三、入库流程（文档 → 可检索向量）

执行者：`DocumentLoader`（启动时自动执行一次）

```
classpath:/documents/*（当前有 message1.txt、message2.txt）
   │
   ▼
PathMatchingResourcePatternResolver 通配符匹配所有文件
   │
   ▼
逐个文件 new TikaDocumentReader(file)
   │  └─ Tika 自动识别格式（txt/pdf/docx...）抽取纯文本
   ▼
reader.get() → List<Document>（文本 + 元数据）
   │
   ▼
TokenTextSplitter 切块（builder 方式，硬编码参数）
   │  chunkSize=1024 / minChunkSizeChars=50 / maxNumChunks=10000 / keepSeparator=true
   ▼
vectorStore.add(docs)
   │  └─ Spring AI 内部自动完成：
   │       ① EmbeddingModel 将文本转向量（SiliconFlow bge-large-zh-v1.5，1024 维）
   │       ② 写入 PostgreSQL 的 vector_store 表（HNSW 索引）
   ▼
控制台打印：✅ 已加载并索引 N 个文档到 PostgreSQL pgvector
```

> 注意：切块参数是 `DocumentLoader` 里硬编码的（1024），与 application.yaml 的 `rag.chunk.size=300` 不一致，后者未被引用。

---

## 四、查询流程（用户提问 → 生成答案）

### 完整链路

```
前端 POST /rag/search
  请求体 { "question": "什么是RAG", "topk": 3, "sessionId": "..." }
   │
   ▼
① RagController.search()
   │  @RequestBody 反序列化为 QueryRequest
   │  @Valid 触发校验：
   │    question 非空（@NotBlank）
   │    topk ∈ [1, 10]（@Min/@Max，默认值 3）
   │  校验失败 → 抛 MethodArgumentNotValidException → 走异常分支（见下节）
   │
   ▼
② RagServiceImpl.searchWithRerank(request)
   │
   ├─ 记开始时间（用于算耗时）
   │
   ├─ 第一步：向量检索
   │    SearchRequest.builder().query(question).topK(topK).build()
   │    vectorStore.similaritySearch()
   │    └─ 问题文本转向量 → pgvector 余弦距离比对 → 返回 topK 条最相关文档
   │
   ├─ 第二步：拼接上下文
   │    retrievedDocs → 取每条文本 → 用 "\n---\n" 连接成一个 context
   │
   ├─ 第三步：构造 Prompt 调用大模型
   │    模板：参考信息 + 用户问题 + "信息不足请说无法回答" 的约束
   │    chatClient.prompt().user(prompt).call().content()
   │    └─ HTTP 调用 DeepSeek 对话接口，拿到答案文本
   │
   └─ 第四步：组装 QueryResponse
        answer = [答案文本]（List 包装）
        costMs = 结束时间 - 开始时间
   │
   ▼
③ Result.success(response)
   │  套统一外壳：code=200, message="success"
   │  traceId = TraceIdUtil.get()（当前线程的追踪 ID）
   │
   ▼
前端收到：
{
  "code": 200,
  "message": "success",
  "traceId": "a1b2c3d4e5f6...",
  "data": { "answer": ["RAG 是检索增强生成..."], "costMs": 842 }
}
```

### 方法命名提醒

`searchWithRerank`（带重排序的搜索）这个名字暗示了重排逻辑，但当前实现里**只有向量检索，没有 rerank 步骤**（yaml 里配了 `rag.rerank.*` 但代码未使用）。要么补上重排实现，要么改名为 `search` 更准确。

---

## 五、异常处理流程

```
任何环节抛出异常
   │
   ▼
GlobalExceptionHandler（@RestControllerAdvice 全局拦截）
   │
   ├─ MethodArgumentNotValidException（参数校验失败）
   │    取字段错误文案（如"查询内容不能为空"）
   │    → log.warn 记录 → Result.error(400, 文案) 返回用户
   │
   └─ Exception（其他一切异常：数据库、大模型调用失败等）
        → log.warn 记录 → Result.error(500, ex.getMessage())
```

> ⚠️ 当前兜底处理器把 `ex.getMessage()` 直接返回给了前端，真实错误细节（可能含表名、堆栈片段）会泄露给用户。建议改为：日志记详情、响应返回固定文案"系统繁忙，请稍后重试"。

---

## 六、Result 统一响应机制（贯穿全流程）

```
        ┌────────── Result（快递盒）──────────┐
        │  code      状态判断（给前端程序）      │
        │  message   提示文案（给用户看）        │
        │  traceId   追踪单号（给开发者查日志）   │
        │  data      业务数据（渲染给用户）      │
        └────────────────────────────────────┘
```

- 成功：`Result.success(data)` —— code/message 写死，只接收 data
- 失败：`Result.error(code, message)` —— data 恒为 null
- 每次构造都自动附带 `TraceIdUtil.get()` 的追踪 ID

---

## 七、配置依赖关系（application.yaml + .env）

```
application.yaml
   ├─ spring.datasource.*      → PostgreSQL 连接（demo3 库，密码硬编码 123456）
   ├─ spring.profiles.active   → dev（application-dev.yml / application-prod.yml 目前为空文件）
   ├─ spring.config.import     → 引入 .env（optional，不存在不报错）
   ├─ spring.ai.openai.*
   │    ├─ chat 走 DeepSeek：   base_url + ${deepseek_api_key} + model=deepseek-v4-pro + temperature=0.7
   │    └─ embedding 走 SiliconFlow：base_url + ${embedding_api_key} + bge-large-zh-v1.5
   └─ rag.*                    → chunk/retrieval/rerank/vector-db 参数（⚠️ 当前无代码引用，见问题清单）

.env（不进 git 的密钥文件，已存在）
   ├─ deepseek_api_key=...
   └─ embedding_api_key=...

VectorStoreConfig 硬编码参数：
   表名 vector_store / 1024 维 / 余弦距离 / HNSW / initializeSchema=true
   （1024 维与 bge-large-zh-v1.5 输出维度一致 ✅）
```

### 关键依赖（pom.xml）

- Spring Boot `4.1.0`，Java `17`，Spring AI `2.0.0`
- `spring-ai-starter-model-openai`（对话 + 嵌入，OpenAI 兼容协议）
- `spring-ai-pgvector-store` + `spring-ai-starter-vector-store-pgvector`（PG 向量库）
- `spring-ai-tika-document-reader`（文档解析）
- `onnxruntime 1.18.0`（重排模型预留，当前未使用）
- `resilience4j-spring-boot4`（限流熔断预留）
- `micrometer-tracing-bridge-brave` + `actuator`（链路追踪预留）

---

## 八、文件清单与职责一览

| 文件 | 状态 | 职责 |
|---|---|---|
| `ShujiaApplication` | ✅ 可用 | 启动入口，@SpringBootApplication |
| `ChatConfig` | ✅ 可用 | ChatClient Bean，默认系统人设 |
| `VectorStoreConfig` | ✅ 可用 | 手动注册 PgVectorStore Bean |
| `DocumentLoader` | ✅ 可用 | 启动时 Tika 解析 + 切块 + 入库 |
| `RagController` | ✅ 可用 | POST /rag/search 接口 |
| `RagService` / `RagServiceImpl` | ✅ 可用 | 检索 + 生成核心逻辑 |
| `QueryRequest` | ✅ 可用 | 入参 DTO（question/topk/sessionId） |
| `QueryResponse` | ✅ 可用 | 出参 DTO（answer + costMs） |
| `Result` | ✅ 可用 | 统一响应外壳（泛型 + 静态工厂） |
| `GlobalExceptionHandler` | ⚠️ 有泄露隐患 | 全局异常 → Result |
| `TraceIdUtil` | ⚠️ 可用但未接线 | ThreadLocal 追踪 ID |
| `ShujiaApplicationTests` | ✅ 可用 | @SpringBootTest 空测试 |

图例：✅ 可用　⚠️ 可用但有问题

---

## 九、当前存在的问题（按优先级）

### P0：会导致功能不可用或配置无效

1. **rerank 完全未实现**：方法名 `searchWithRerank`、`rag.rerank.enabled=true`、`model-path=models/bge-reranker-base.onnx`、`onnxruntime` 依赖都已就位，但代码里没有加载模型、没有对检索结果重排序的步骤（项目目录里也没有 `models/bge-reranker-base.onnx` 文件）
2. **`rag.*` 配置段无代码引用**：`rag.chunk.size=300/overlap=50` 与 `DocumentLoader` 里硬编码的 `withChunkSize(1024)` 不一致；`rag.retrieval.top-k=10` 未使用（实际用请求参数 `topk`）；`rag.vector-db.path=./chroma_db` 完全误导（实际向量库是 PostgreSQL pgvector，不是 chroma）
3. **`DocumentLoader` 的 `@NonNull` 注解**：`org.jspecify.annotations.NonNull`，但 pom.xml 中未见 jspecify 依赖，需确认是否影响编译

### P1：安全与设计问题

4. **兜底异常处理器泄露内部信息**：`Result.error(500, ex.getMessage())` 应改为固定文案，详情只进日志
5. **TraceIdUtil 未接线**：没有拦截器/过滤器在请求入口调 `generate()`、请求结束调 `clear()`，当前靠 `get()` 的惰性生成兜底，traceId 无法贯穿日志
6. **数据源密码硬编码**：application.yaml 里 `password: 123456` 直接写死，应改为 `${password}` 走环境变量（demo3 项目就是这么做的）

### P2：清理项

7. `application-dev.yml`、`application-prod.yml` 均为空文件，且 `spring.profiles.active=dev` 指向空配置
8. 依赖疑似重复：`spring-ai-pgvector-store` 与 `spring-ai-starter-vector-store-pgvector` 同时引入，`spring-ai-vector-store` 也单独引入，建议确认去重
9. `base_url` 使用下划线风格，Spring 惯例为连字符 `base-url`（宽松绑定通常可识别下划线，但建议统一）
10. `onnxruntime`、`resilience4j`、`micrometer-tracing` 等依赖已引入但未接线使用

---

## 十、下次接续的切入点

按优先级建议顺序：

1. 先解决 **P0-1 重排实现**（这是项目名"支持重排"的核心，目前完全缺失；可对接已有的 Python `rerank_service`）
2. 清理 `rag.*` 配置与代码不一致（P0-2）
3. 确认 `@NonNull` 编译问题（P0-3）
4. 修兜底异常的信息泄露（P1-4）、密码硬编码（P1-6）
5. 给 TraceIdUtil 配拦截器接线（P1-5）
6. 清理空配置与重复依赖（P2）
