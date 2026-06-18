# libs 层接口设计

## 1. 核心能力

libs 只提供底层基础能力，不包含业务逻辑。

| 能力 | 说明 |
|------|------|
| 推理 | chat / chatStream / task / taskStream |
| 向量 | embed(text) / embed(texts) |
| 检索 | search(queryText, texts, topK, threshold) |
| 调度 | CHAT 优先，可暂停 TASK |

---

## 2. API 接口

### 2.0 构建与生命周期

主包通过 `JavaLlamaServer` 作为 libs 入口。`build()` 只校验并保存配置，不加载模型；`JavaLlamaServer` 类初始化时会触发 native 加载，`start()` 负责加载 LLM / embedding 模型并启动推理 worker。

```java
JavaLlamaServer service = JavaLlamaServer.builder()
    .model("models/qwen3.5-4b.gguf")      // 必填：LLM 模型
    .modelAlias("qwen3.5-4b")             // 可选：模型别名
    .modelProfile("auto")                 // 可选：auto / qwen3 / qwen3.5 / deepseek-r1 / generic
    .contextSize(16000)                   // LLM 上下文窗口；CHAT / TASK 共用同一个配置
    .chatThreads(4)
    .chatMaxQueueSize(4)
    .gpuLayers(999)
    .device("0")                         // 可选：传给 JJML ModelParam.device
    .cacheTypeK(KvCacheType.F16)          // 可选：F16 / Q8_0
    .cacheTypeV(KvCacheType.F16)          // 可选：F16 / Q8_0
    .taskThreads(2)
    .taskSuspendOnChat(true)
    .inferenceEventListener(event -> {    // 可选：CHAT / TASK 统一推理状态事件
        // event.getType(), event.getLane(), event.getReplayCharacters()
    })
    .embeddingModel("models/bge.gguf")    // 可选：不配置则 embed/search 不可用
    .embeddingContextSize(16000)
    .embeddingThreads(4)
    .embeddingGpuLayers(999)
    .embeddingDevice("0")                 // 可选：embedding 模型加载设备
    .embeddingAlias("bge")
    .requestTimeoutSeconds(300)
    .build();

service.start();      // 加载模型并启动服务
service.shutdown();   // 游戏关闭或模块卸载时释放资源
```

`start()` 可能耗时较长，主包不应在 Minecraft 主线程中同步阻塞等待模型加载。

### 2.1 推理

```java
// 简单聊天
String chat(String message, String systemPrompt);
String chat(String message, String systemPrompt, ThinkingMode thinkingMode);

// 同步聊天
String chat(List<ChatMessage> messages);
String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens);

// 流式聊天
void chatStream(String message, String systemPrompt, Consumer<String> onToken);
void chatStream(List<ChatMessage> messages, Consumer<String> onToken);
void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken);

// 后台任务（可被 chat 暂停）- 同步返回
CompletableFuture<String> task(List<ChatMessage> messages);
CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible);

// 后台任务 - 流式返回
CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, Consumer<String> tokenConsumer);
```

### 2.1.1 推理状态事件

`inferenceEventListener` 可用于把推理状态反馈给上层 UI。事件不会混入模型 token 输出，不设置 listener 时没有业务影响。

```java
public class InferenceEvent {
    String getTaskId();
    InferenceTask.TaskType getTaskType();
    InferenceLane getLane();              // CHAT / TASK
    int getPriority();
    InferenceEventType getType();
    String getMessage();
    int getReplayCharacters();            // 冷恢复 replay 的字符量估计
    int getGeneratedTokens();
    Throwable getError();
    boolean isChat();
    boolean isTask();
}

public enum InferenceEventType {
    QUEUED,
    STARTED,
    COLD_RESUME_STARTED,
    COLD_RESUME_COMPLETED,
    PREFILL_STARTED,
    PREFILL_COMPLETED,
    GENERATION_STARTED,
    SUSPENDED,
    COMPLETED,
    CANCELLED,
    FAILED
}
```

常见事件序列：

- CHAT：`QUEUED -> STARTED -> PREFILL_STARTED -> PREFILL_COMPLETED -> GENERATION_STARTED -> COMPLETED`
- 新 TASK：`QUEUED -> STARTED -> PREFILL_STARTED -> PREFILL_COMPLETED -> GENERATION_STARTED -> COMPLETED`
- 被打断 TASK：`... -> SUSPENDED -> QUEUED -> COLD_RESUME_STARTED -> PREFILL_STARTED -> PREFILL_COMPLETED -> COLD_RESUME_COMPLETED -> GENERATION_STARTED -> COMPLETED`

### 2.1.2 推理输出归一化（内部行为）

libs 不新增或改变上述调用方法；所有归一化都发生在内部 token 输出边界，对 `chat` / `chatStream` / `task` / `taskStream` 统一生效。

- 不同开源模型可能使用不同的思考包裹格式，例如 `<think>`、`<reasoning>`、`<thought>`、`<analysis>`、`<|begin_of_thought|>` 等。
- libs 会把已知思考包裹统一归一化为 `<think>...</think>` 后返回，便于上层只处理一种格式。
- 空思考块会被清洗掉，例如 `<think></think>`、`<think>\n\n</think>` 或 no-think 标记块，不再返回给上层。
- 流式输出不会缓冲完整思考段。内部只暂存可能组成标签的短尾部，以及开标签后的空白前缀；一旦出现非空思考内容，会立即流式输出规范化后的 `<think>` 和后续内容。
- `enableThinking` / `ThinkingMode` 仍然只是生成前的模板控制；输出归一化是生成后的统一兼容层，二者互不改变对外 API。

### 2.2 向量

```java
// 文本向量化（最大8192字符）
float[] embed(String text);

// 批量向量化（最大100条，每条最大8192字符）
float[][] embed(List<String> texts);
```

### 2.3 检索（适用于无缓存场景）

```java
// 带阈值参数
List<RagSearchResult> search(String queryText, List<String> texts, int topK, float threshold);

// 使用默认值 (topK=4, threshold=0.7f)
List<RagSearchResult> search(String queryText, List<String> texts);
```

**参数说明**：
| 参数 | 默认值 | 说明 |
|------|--------|------|
| `topK` | 4 | 返回最相似的 Top-K 条 |
| `threshold` | 0.7f | 相似度阈值，低于此分数的结果不返回 |

**说明**：
- 入参：原始 query 文本 + 原始文本列表
- 内部：调用 `embed(queryText)` 向量化，然后计算相似度，排序取 Top-K
- 返回：`RagSearchResult` 包含 `content`（原文）+ `score`（相似度分数）
- 维度校验：查询向量和文档向量维度必须与 embedding 模型维度一致，否则抛出 `IllegalArgumentException`

### 2.4 调度规则

- CHAT 通道优先级高于 TASK
- CHAT 请求会挂起正在执行的 TASK 任务（`taskSuspendOnChat=true`）
- TASK 任务在 `preemptible=true` 时可被更高优先级 TASK 抢占
- TASK 逻辑队列按 priority + FIFO 排序；TASK 接收不受热挂起槽数量限制。
- TASK 挂起只使用冷挂起（COLD）：关闭当前 `LlamaCppContext` / KV，只保留 prompt、模型原始已生成文本和归一化输出状态，并回到 TASK 队列。
- COLD 恢复时通过 replay `prompt + rawGeneratedText` 重建上下文，再继续生成；这会增加恢复时延，但不会为挂起任务额外保留 KV 显存。
- 如果 chat 在 COLD replay 期间到达，JJML 当前高层 API 无法可靠分片中断 replay，chat 需要等本次 replay 返回到可检查点后再执行；上层可通过 `inferenceEventListener` 的 `COLD_RESUME_STARTED/PREFILL_COMPLETED/COLD_RESUME_COMPLETED` 在 UI 中提示“后台任务恢复中可能稍慢”。

---

## 3. 数据结构

### 3.1 ChatMessage

```java
public class ChatMessage {
    public String role;    // "system" / "user" / "assistant"
    public String content; // 消息内容
}
```

### 3.2 SamplerConfig

```java
public class SamplerConfig {
    public Float temperature;   // 采样温度
    public Integer topK;        // Top-K
    public Float topP;          // Top-P
    public Float minP;          // Min-P
    public Float penaltyRepeat; // 重复惩罚
    public Float penaltyFreq;   // 频率惩罚
    public Float penaltyPresent;// 存在惩罚
    public Integer penaltyLastN;// 惩罚窗口
    public Boolean enableThinking; // 最省事入口：true 开启，false 关闭，null 自动
    public ThinkingMode thinkingMode; // AUTO / ENABLED / DISABLED
    public Map<String, String> chatTemplateKwargs; // 额外 Jinja 模板参数

    public void setEnableThinking(Boolean enableThinking);
    public ThinkingMode getEffectiveThinkingMode();
    public SamplerConfig chatTemplateKwarg(String key, String value);
    public SamplerConfig copy();
}
```

**enableThinking 说明**：
- `true`：向 chat template 传 `enable_thinking=true`。
- `false`：向 chat template 传 `enable_thinking=false`。
- `null`：走 `ThinkingMode.AUTO`。
- 这是主包推荐使用的最简单入口。

**ThinkingMode 说明**：
- `AUTO`：默认值。模型 chat template 支持 `enable_thinking` 时传 `true`，不支持时忽略。
- `ENABLED`：向 chat template 传 `enable_thinking=true`。
- `DISABLED`：向 chat template 传 `enable_thinking=false`。
- 该开关依赖模型的 chat template / Jinja 支持；没有相关模板能力的模型会忽略该设置。

**chatTemplateKwargs 说明**：
- 额外参数按 `Map<String, String>` 传给 JJML / llama.cpp 的 Jinja chat template。
- `enable_thinking` 可通过 `chatTemplateKwargs.put("enable_thinking", "false")` 传入，libs 会解析成 `ThinkingMode.DISABLED`。
- 布尔值使用字符串 `"true"` / `"false"`；数字等非布尔参数需要按 llama.cpp 实际解析行为验证。
- 不要让 `enableThinking` / `thinkingMode` 与 `chatTemplateKwargs.enable_thinking` 冲突；冲突时 libs 抛出 `IllegalArgumentException`。

**配置快照说明**：
- 推理任务创建时会复制一份 `SamplerConfig`，主包提交任务后继续修改同一个 sampler，不会影响已经排队或正在执行的任务。

### 3.3 RagSearchResult

```java
public class RagSearchResult {
    public String content; // 原文内容
    public double score;   // 相似度分数
}
```

---

## 4. 服务状态

```java
// 检查是否就绪
boolean isReady();
boolean supportsEnableThinking();

// 检查队列容量
boolean hasChatQueueCapacity();
boolean hasTaskQueueCapacity();
boolean hasQueueCapacity(); // 等价于 hasChatQueueCapacity()
int getChatQueueSize();

// hasTaskQueueCapacity 对 TASK 逻辑队列通常返回 true；
// TASK 挂起不保留热 KV/context，恢复时冷 replay。

// 关闭服务
void shutdown();
```

---

## 5. 设计决策

| 决策项 | 结论 |
|--------|------|
| libs 职责 | 仅提供基础能力：推理、向量、检索、调度 |
| libs 检索 | 仅适用于无缓存场景，接收原始文本，内部自动向量化并计算相似度 |
| Lane 调度 | libs 内置：CHAT 优先，可暂停 TASK |
| RagSearchResult | 只包含 content + score，不包含 id（libs 不知道业务 ID） |
| 向量维度校验 | 维度不匹配时抛出异常，确保查询和文档使用同一 embedding 模型 |
