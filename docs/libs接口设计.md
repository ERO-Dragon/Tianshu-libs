# libs 层接口设计

## 1. 核心能力

libs 只提供底层基础能力，不包含业务逻辑。

| 能力 | 说明 |
|------|------|
| 推理 | chat / chatStream / task / taskStream |
| token / usage | countChatPromptTokens / LlmTokenUsage |
| 向量 | embed(text) / embed(texts) |
| 检索 | search(queryText, texts, topK, threshold) |
| 调度 | CHAT 优先，可暂停 TASK |
| 运行选项 | InferenceOptions 控制请求级 MTP 与 Vulkan 时间片优先级 |
| 能力探测 | supportsMtp / getMtpCapability / calibrateMtp |

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
    .flashAttention(FlashAttentionMode.ENABLED) // 可选：默认 ENABLED；也可 AUTO / DISABLED
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

模型级推理参数说明：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `contextSize` | `16000` | LLM 上下文窗口大小；模型加载服务后固定，CHAT / TASK 共用同一配置 |
| `gpuLayers` | `999` | 交给 JJML / llama.cpp 的 GPU offload 层数；通常用于尽量全量放到 GPU |
| `device` | `null` | 可选设备选择器；纯数字会规范化为 JJML 设备索引格式，例如 `"0"` -> `"#0"` |
| `flashAttention` | `FlashAttentionMode.ENABLED` | Flash Attention 模式；属于 context/runtime 结构配置，不是单次请求参数 |
| `cacheTypeK` | `null` | K cache 类型；`null` 表示使用 JJML 默认值，可显式设置 `F16` / `Q8_0` |
| `cacheTypeV` | `null` | V cache 类型；`null` 表示使用 JJML 默认值，可显式设置 `F16` / `Q8_0` |

`flashAttention`、`cacheTypeK`、`cacheTypeV` 都会在创建 `LlamaCppContext` 时写入 JJML context params，因此它们是服务级配置：同一个 `JavaLlamaServer` 实例内的 `chat` / `task` 会使用同一组设置。如果需要对比不同 FA 或 KV cache 配置，应创建不同服务实例或重新加载模型服务。

### 2.1 推理

```java
// 简单聊天
CompletableFuture<String> chat(String message, String systemPrompt);
CompletableFuture<String> chat(String message, String systemPrompt, ThinkingMode thinkingMode);

// 聊天；返回 future，可通过 cancel(false) 取消本次 chat
CompletableFuture<String> chat(List<ChatMessage> messages);
CompletableFuture<String> chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens);
CompletableFuture<String> chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, InferenceOptions options);
CompletableFuture<LlmGenerationResult> chatWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens);
CompletableFuture<LlmGenerationResult> chatWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, InferenceOptions options);

// 流式聊天
CompletableFuture<String> chatStream(String message, String systemPrompt, Consumer<String> onToken);
CompletableFuture<String> chatStream(String message, String systemPrompt, int maxTokens, Consumer<String> onToken);
CompletableFuture<String> chatStream(List<ChatMessage> messages, Consumer<String> onToken);
CompletableFuture<String> chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken);
CompletableFuture<String> chatStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, Consumer<String> onToken);
CompletableFuture<String> chatStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, InferenceOptions options, Consumer<String> onToken);
CompletableFuture<String> chatStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, InferenceOptions options, Consumer<String> onToken, Consumer<LlmStreamFinish> onFinish);

// 请求前 token 预算查询；使用当前 LLM 的 chat template + tokenizer
int countChatPromptTokens(List<ChatMessage> messages, SamplerConfig sampler);

// 后台任务（可被 chat 暂停）- 非流式返回
CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible);
CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, InferenceOptions options);
CompletableFuture<LlmGenerationResult> taskWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, InferenceOptions options);

// 后台任务 - 流式返回
CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, Consumer<String> tokenConsumer);
CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, InferenceOptions options, Consumer<String> tokenConsumer);
CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, InferenceOptions options, Consumer<String> tokenConsumer, Consumer<LlmStreamFinish> finishConsumer);
```

所有 `chat` / `chatStream` / `task` / `taskStream` 返回的 `CompletableFuture` 都是本次请求的控制句柄：

- 成功时 future 返回完整文本，`WithUsage` 变体返回 `LlmGenerationResult`。
- 调用 `future.cancel(false)` 会取消对应请求；流式请求会通过 `LlmStreamFinish` 返回 `CANCELLED` 和已知 usage。
- `chat` 不会抢占另一个正在执行的 `chat`；新的 CHAT 请求仍按 CHAT 队列排序执行。

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

所有归一化都发生在内部 token 输出边界，对 `chat` / `chatStream` / `task` / `taskStream` 统一生效。

- 不同开源模型可能使用不同的思考包裹格式，例如 `<think>`、`<reasoning>`、`<thought>`、`<analysis>`、`<|begin_of_thought|>` 等。
- libs 会把已知思考包裹统一归一化为 `<think>...</think>` 后返回，便于上层只处理一种格式。
- 空思考块会被清洗掉，例如 `<think></think>`、`<think>\n\n</think>` 或 no-think 标记块，不再返回给上层。
- 流式输出不会缓冲完整思考段。内部只暂存可能组成标签的短尾部，以及开标签后的空白前缀；一旦出现非空思考内容，会立即流式输出规范化后的 `<think>` 和后续内容。
- `enableThinking` / `ThinkingMode` 仍然只是生成前的模板控制；输出归一化是生成后的统一兼容层，二者互不改变对外 API。
- `LlmTokenUsage.completionTokens` 只统计归一化后可见回答 token；被识别为思考包裹内的 token 不计入 completion。

### 2.1.3 请求级运行选项

`InferenceOptions` 用于描述一次推理请求的执行策略。它不替代 `SamplerConfig`：采样、思考模板、grammar 仍放在 `SamplerConfig`；MTP、Vulkan 时间片等运行时策略放在 `InferenceOptions`。

```java
InferenceOptions options = InferenceOptions.builder()
    .mtpEnabled(true)          // 本轮请求尝试使用 MTP；模型不支持时自动走普通推理
    .mtpDraftMax(null)         // null 表示使用当前模型已校准的推荐值；未校准时使用默认值
    .vulkanPriority(0.35f)     // 0.0~1.0；值越低越倾向于给游戏渲染让路
    .build();

String reply = service.chat(messages, sampler, 256, options).get();
```

字段语义：

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `mtpEnabled` | false | 是否在本轮请求尝试使用 MTP speculative decoding |
| `mtpDraftMax` | null | 本轮请求指定 draft token 窗口；null 表示使用自动校准出的推荐值 |
| `vulkanPriority` | null | Vulkan 时间片推理优先级；null 表示不改动底层调度状态 |

说明：

- MTP 是请求级开关，不是模型加载时的永久开关；同一个模型可以某些请求启用 MTP，某些请求走普通推理。
- 当模型不支持 MTP，或 MTP 初始化失败时，libs 会安全降级到普通推理，不要求上层额外兜底。
- `vulkanPriority=1.0f` 近似普通 Vulkan 执行；`vulkanPriority=0.0f` 最大程度让位给游戏侧负载。
- Vulkan 时间片能力依赖当前运行时的 Vulkan backend、驱动和扩展支持；不支持时该设置会被忽略。

### 2.1.4 MTP 能力探测与自动校准

MTP 能力和推荐配置属于“当前已加载模型 + 当前运行环境”的状态。上层推荐在模型加载完成后先查询能力，再按需触发一次校准。

```java
boolean supported = service.supportsMtp();
MtpCapability capability = service.getMtpCapability();

if (supported && !capability.isCalibrated()) {
    MtpCalibrationResult result = service.calibrateMtp(
        MtpCalibrationRequest.defaults()
    );
    int bestDraftMax = result.getBestDraftMax();
}
```

对外接口：

```java
boolean supportsMtp();
MtpCapability getMtpCapability();
CompletableFuture<MtpCalibrationResult> calibrateMtpAsync();
CompletableFuture<MtpCalibrationResult> calibrateMtpAsync(MtpCalibrationRequest request);
MtpCalibrationResult calibrateMtp() throws Exception;
MtpCalibrationResult calibrateMtp(MtpCalibrationRequest request) throws Exception;
```

主要数据：

| 类型 | 说明 |
|------|------|
| `MtpCapability` | 当前模型是否支持 MTP、MTP 层数、是否已有校准结果、推荐 `draftMax` |
| `MtpCalibrationRequest` | 校准范围：draftMax 自动扫描或手动上限、每轮生成 token 数、目标长 prompt token 数 |
| `MtpCalibrationResult` | 校准是否支持、测试列表、最佳 trial、最佳 `draftMax` |
| `MtpTrialResult` | 单次测试的速度、接受率、draft/accepted token 数等统计 |

校准说明：

- 校准任务走 TASK 通道，避免阻塞 CHAT 队列；上层可以用 async 方法在 UI 中显示“能力测试中”。
- 校准使用内部生成的重型长上下文 workload，默认目标约 8k prompt tokens，并按当前 TASK context 自动保留生成 token、draft 窗口和安全余量。
- 如果当前 context 太小，无法容纳至少 4096 prompt tokens 的重型 workload，校准会返回失败结果，不会退化为无意义的短 prompt 测试。
- 默认校准使用 `maxDraftMax=0` 自动扫描：先测试保守初始范围；如果最佳 `draftMax` 贴近当前扫描边界，再逐步扩大范围，直到最佳值不再贴边、MTP 初始化失败或达到硬安全上限。
- `maxDraftMax > 0` 表示高级调用手动指定扫描上限；这个上限是校准预算，不是模型能力上限。
- 校准按 tokens/s 选择当前最优值，并缓存到当前 `LlamaEngine`。
- `InferenceOptions.builder().mtpEnabled(true).build()` 未指定 `mtpDraftMax` 时，会使用缓存的推荐值；未校准时使用默认 `draftMax=3`。
- 校准结果只保存在当前进程/当前引擎内；如果上层希望跨启动复用，需要自行持久化策略和环境信息。

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
- TASK 挂起只使用冷挂起（COLD）：关闭当前 `LlamaCppContext` / KV，只保留格式化 prompt、prompt token ids、已生成 token ids、模型原始已生成文本和归一化输出状态，并回到 TASK 队列。
- COLD 恢复优先使用 token ids 重建上下文，不再把已生成 raw text 重新 tokenize。标准推理在安全采样配置下可使用最近 context checkpoint + tail token replay；其他配置回退为 prompt token ids + generated token ids 全量 replay。这会增加恢复时延，但不会为挂起任务额外保留 GPU KV 显存。
- MTP 冷恢复优先使用 target context checkpoint + `LlamaCppSpeculativeProcessor.beginFromRestoredTarget(...)`，并保留至少 1 个已输出 token 做 tail replay 来重建 draft context 和 pending 状态；如果 checkpoint 不可用或 restored-target 恢复失败，则回退为 token-id 全量 replay。
- 当前 checkpoint 只在无 grammar、无随机采样、无重复/频率/存在惩罚的安全配置下用于快速恢复；复杂采样配置会回退为 token-id 全量 replay，因为 JJML 当前 context state 不包含 sampler chain / grammar 状态。
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
    public String grammarStr;      // 可选：GBNF grammar
    public String grammarRoot;     // 默认 root

    public static SamplerConfig defaults();
    public void setEnableThinking(Boolean enableThinking);
    public ThinkingMode getEffectiveThinkingMode();
    public SamplerConfig chatTemplateKwarg(String key, String value);
    public SamplerConfig withKwargs(String key, String value);
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

### 3.4 LLM usage 相关结构

```java
public record LlmTokenUsage(int promptTokens, int completionTokens) {
    public int totalTokens(); // promptTokens + completionTokens
}

public record LlmGenerationResult(String text, LlmTokenUsage usage) {
}

public record LlmStreamFinish(StreamFinishType type, LlmTokenUsage usage, Throwable error) {
}

public enum StreamFinishType {
    COMPLETED,
    CANCELLED,
    FAILED
}
```

说明：

- `promptTokens`：实际 chat template 渲染后的 prompt token 数。
- `completionTokens`：模型实际生成且归一化后对上层可见的回答 token 数；识别出的 COT token 不计入。
- `totalTokens()`：`promptTokens + completionTokens`。
- 非流式 `chatWithUsage` / `taskWithUsage` 成功完成时通过 `LlmGenerationResult` 返回 usage。
- 流式请求通过 `LlmStreamFinish` 返回终态和 usage；取消时 future 进入取消态，finish type 为 `CANCELLED`。

### 3.5 FlashAttentionMode 与 KvCacheType

```java
public enum FlashAttentionMode {
    AUTO,      // 交给 JJML / llama.cpp 自动判断
    DISABLED,  // 显式关闭 Flash Attention
    ENABLED    // 显式开启 Flash Attention；libs 默认值
}

public enum KvCacheType {
    F16,
    Q8_0
}
```

说明：

- `FlashAttentionMode` 是模型/context 级配置，通过 `JavaLlamaServer.Builder.flashAttention(...)` 设置，默认 `ENABLED`。
- `KvCacheType` 是 KV cache 量化配置，通过 `cacheTypeK(...)` 和 `cacheTypeV(...)` 设置；不设置时保留 JJML 默认值。
- 这两类参数都会影响 context 创建方式和显存/性能特征，因此不放入 `InferenceOptions`，也不建议在单轮对话级别频繁切换。

### 3.6 InferenceOptions

```java
public final class InferenceOptions {
    public static InferenceOptions defaults();
    public static InferenceOptions mtpEnabled();
    public static Builder builder();

    public boolean isMtpEnabled();
    public Integer getMtpDraftMax();
    public Float getVulkanPriority();
}
```

`InferenceOptions` 是不可变对象，提交任务时会复制快照。上层可以安全复用模板对象，也可以为每轮对话临时构建。

### 3.7 MTP 相关结构

```java
public final class MtpCapability {
    public boolean isSupported();
    public int getMtpLayerCount();
    public boolean isCalibrated();
    public int getRecommendedDraftMax();
    public MtpTrialResult getBestTrial();
}

public final class MtpCalibrationRequest {
    // defaults(): maxDraftMax=0 表示自动扫描，不是固定测到某个模型上限
    public MtpCalibrationRequest(int maxDraftMax, int maxTokens);
    public MtpCalibrationRequest(int maxDraftMax, int maxTokens, int targetPromptTokens);
    public static MtpCalibrationRequest defaults();
}

public final class MtpCalibrationResult {
    public boolean isSupported();
    public int getMtpLayerCount();
    public int getMaxDraftMaxTested();
    public boolean hasBestTrial();
    public int getBestDraftMax();
    public MtpTrialResult getBestTrial();
    public List<MtpTrialResult> getTrials();
    public String getMessage();
}
```

---

## 4. 服务状态

```java
// 检查是否就绪
boolean isReady();
boolean supportsEnableThinking();
boolean supportsMtp();
MtpCapability getMtpCapability();

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
| 模型级运行参数 | contextSize / flashAttention / cacheTypeK / cacheTypeV 放在 Builder；创建 context 时统一生效 |
| 请求级运行策略 | 采样参数放在 SamplerConfig；MTP / Vulkan 时间片放在 InferenceOptions |
| MTP 策略 | 模型级能力探测，请求级启用，校准结果缓存在当前 LlamaEngine |
| 视觉能力 | 当前文本推理路径不加载 mtmd/mmproj；视觉仍保持显式 opt-in，不在本接口中默认启用 |
