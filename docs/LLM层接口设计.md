# LLM 层接口设计

## 1. 概述

LLM 层是主体的适配层，负责：
- 解析 LLMRequest（chunks）
- RAG 编排和缓存管理
- 调用 libs 底层能力

---

## 2. LLMRequest 请求结构

```java
public class LLMRequest {
    // 生成参数
    public Integer max_tokens;
    public Float temperature;
    public Boolean stream;
    public Boolean thinking; // 映射到 libs SamplerConfig.enableThinking

    // 任务调度
    public String lane;               // "CHAT" / "TASK"
    public Integer task_priority;     // -1000 ~ 1000
    public Boolean task_preemptible;

    // 核心数据
    public List<Chunk> chunks;
}
```

### 2.1 Chunk 分块

```java
public class Chunk {
    public String type;  // "message" / "rag"

    // type="message" 时
    public List<MessageItem> content;

    // type="rag" 时
    public List<String> content;       // RAG 文本数组
    public String uid;                  // 唯一标识
    public Boolean use_cache;           // 是否使用缓存（默认 true）
    public Boolean include_rag_hits;    // 是否返回检索结果
    public Integer memory_rag_token_budget; // 记忆 RAG 预算
}
```

### 2.2 MessageItem

```java
public class MessageItem {
    public String role;    // "system" / "user" / "assistant"
    public String content;
}
```

---

## 3. API 使用方式

### 3.1 同步聊天

```java
LLMService service = LLMService.builder()
    .model("models/qwen3.bin")
    .embeddingModel("models/bge.bin")
    .build();

String reply = service.chatSync("你好", "你是铁匠NPC");

// 或使用 LLMRequest
LLMRequest request = LLMRequest.of(
    LLMRequest.Chunk.message(
        LLMRequest.MessageItem.of("system", "你是铁匠NPC"),
        LLMRequest.MessageItem.of("user", "我需要一把剑")
    ),
    LLMRequest.Chunk.rag("rag_dynamic", List.of("玩家手持铁锭"), true, true, 1000)
);
request.setStream(true);
String reply = service.chatSync(request);
```

### 3.2 流式聊天

```java
service.chatStream(request, token -> {
    broadcast(token); // 实时推送
});
```

### 3.3 后台任务

```java
LLMRequest request = LLMRequest.of(
    LLMRequest.Chunk.message(
        LLMRequest.MessageItem.of("system", "你是记忆压缩器"),
        LLMRequest.MessageItem.of("user", longText)
    )
);
request.setLane("TASK");
request.setTaskPriority(10);

CompletableFuture<String> future = service.submitTask(request);
```

---

## 4. RAG 缓存管理

### 4.1 核心功能

```java
RagCacheManager cache = service.getRagCache();

// 增量索引
cache.index("rag_memory_001", List.of("新记忆1", "新记忆2"));

// 检索
List<RagSearchResult> results = cache.search("rag_memory_001", "我的钻石镐在哪", 4);

// 删除
cache.evict("rag_memory_001");                    // 删除某 uid
cache.evict("rag_memory_001", "具体记忆内容");    // 删除单条

// 查询
boolean hasCache = cache.hasCache("rag_memory_001");
CacheStats stats = cache.getStats();

// 清空
cache.clear();
```

### 4.2 RagCacheManager 接口

LLM 层基于 libs 的 `embed()` 实现缓存（存储向量），检索时用缓存的向量自己计算相似度。

```java
public interface RagCacheManager {
    // 增量索引（自动向量化并缓存）
    void index(String uid, List<String> texts);

    // 基于 uid 检索（使用缓存的向量）
    List<RagSearchResult> search(String uid, String queryText, int topK);

    // 删除
    void evict(String uid);
    void evict(String uid, String content);

    // 查询
    boolean hasCache(String uid);
    CacheStats getStats();

    // 清空
    void clear();
}

public class CacheStats {
    public int uidCount;
    public int totalChunks;
    public long cacheSizeBytes;
}
```

---

## 5. 字段默认值

| 字段 | 默认值 |
|------|--------|
| `max_tokens` | 0 (不限制) |
| `temperature` | 0.7 |
| `stream` | false |
| `thinking` | null (AUTO) |
| `lane` | "CHAT" |
| `task_priority` | 0 |
| `task_preemptible` | false |
| `use_cache` | true |
| `include_rag_hits` | true |
| `memory_rag_token_budget` | 1000 |

---

## 6. RAG 处理流程

```
LLMRequest
    ↓
解析 chunks
    ↓
┌─ message chunks ──→ 组装 messages
│
└─ rag chunks ──→ 判断 use_cache
                      ↓
           ┌──────────┴──────────┐
           ↓                      ↓
       use_cache=true         use_cache=false
           ↓                      ↓
    ragCache.search()      libs.search(queryText, texts, topK)
    (用缓存向量)            (无缓存，直接检索)
           ↓                      ↓
           └──────────┬──────────┘
                      ↓
                 组装 prompt（注入 RAG 结果）
                      ↓
                 libs.chat(messages) → 推理结果
```

**说明**：
- `use_cache=true`：LLM 层用缓存的向量自己计算相似度
- `use_cache=false`：直接调用 libs.search() 检索
- `thinking`：LLM 层应优先映射到 `SamplerConfig.enableThinking`。`true` 开启，`false` 关闭，缺省则保持 `null` 走自动模式。
- Qwen3 / Qwen3.5 的思考切换依赖模型 chat template 支持；libs 通过 JJML 的 Jinja chat template 参数传递 `enable_thinking`，不再建议由主包手动拼 `/think` 或 `/no_think`。
- 如需传 Qwen3.5 风格的 `chat_template_kwargs`，使用 `SamplerConfig.chatTemplateKwarg(key, value)`。值类型是字符串，布尔值写 `"true"` / `"false"`；数字等非布尔参数需要实测 JJML / llama.cpp 的解析行为。
- libs 会在内部统一归一化模型输出中的思考包裹。LLM 层收到的思考内容只需要按 `<think>...</think>` 处理，不需要识别 `<reasoning>`、`<thought>`、`<analysis>` 等模型私有格式。
- 空思考块会由 libs 清洗掉，例如关闭思考时某些模型可能输出的 `<think></think>` / `<think>\n\n</think>`，LLM 层通常不会再收到。
- 流式场景下 libs 不会等待完整思考段结束才返回；非空思考内容会以规范 `<think>` 包裹尽快流式传出。

---

## 7. 示例

### 7.1 请求示例

```json
{
  "max_tokens": 1024,
  "temperature": 0.7,
  "stream": true,
  "thinking": false,
  "lane": "CHAT",
  "chunks": [
    {
      "type": "message",
      "content": [
        {"role": "system", "content": "你是一个 Minecraft 助手"},
        {"role": "user", "content": "我的钻石镐在哪里？"}
      ]
    },
    {
      "type": "rag",
      "content": ["玩家手持钻石镐", "坐标 (100,64,200)"],
      "uid": "rag_dynamic_001",
      "use_cache": true,
      "include_rag_hits": true,
      "memory_rag_token_budget": 2000
    }
  ]
}
```

### 7.2 Java 使用示例

```java
LLMRequest request = LLMRequest.of(
    LLMRequest.Chunk.message(
        LLMRequest.MessageItem.of("system", "你是一个 Minecraft 助手"),
        LLMRequest.MessageItem.of("user", "我的钻石镐在哪里？")
    ),
    LLMRequest.Chunk.rag(
        "rag_dynamic_001",
        List.of("玩家手持钻石镐", "坐标 (100,64,200)"),
        true, true, 2000
    )
);
request.setStream(true);

String reply = service.chatSync(request);
```

---

## 8. 设计决策

| 决策项 | 结论 |
|--------|------|
| LLM 层职责 | 业务编排：chunk 解析、RAG 编排、缓存管理 |
| 缓存归属 | LLM 层负责，libs 只提供 embed |
| 有缓存场景 | 使用 embed() 获取向量，LLM 层自己计算相似度 |
| 无缓存场景 | 直接调用 libs.search() 检索 |
