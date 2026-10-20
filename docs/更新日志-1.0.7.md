# Tianshu Libs 1.0.7 更新日志

本文记录本次 libs 侧围绕“只加载 embedding、不加载聊天模型”启动模式所做的接口与实现更新，以及由此带来的配置校验、状态查询与失败语义调整。

本日志覆盖 `common` 模块的启动约束、公开 API 增补、无聊天引擎时的行为契约和新增测试；不涉及构建配置以外的其它模块调整。

## 版本信息

- libs 版本：`1.0.7`
- JJML 版本：`2.1.2.0025-02b5d02`（本次未升级）
- 主要目标：允许只配置 embedding 模型就启动服务，让“聊天走云端 / 本地只做 RAG 向量”的部署不必再挂一个聊天 GGUF；同时把“没有聊天引擎”时的行为固化成可预期、可探测的契约，而不是让它变成 NPE。

## 仅 embedding 的启动模式

新增一种服务形态：只加载 embedding 模型，不加载聊天模型。

使用场景：

- 上层模组的聊天由云端 API 提供，但仍需要本地向量能力做 RAG 检索。
- 上层只想用 libs 的 `embed` / `search`，不希望为聊天分摊一份 GGUF 的下载体积与显存。
- 老版本的约束是“没有 `modelPath` 就不能起服务”，这类部署只能被迫多下一个聊天模型。

```java
JavaLlamaServer server = JavaLlamaServer.builder()
    .embeddingModel("models/bge-large-zh-v1.5-q4_k_m.gguf")
    .embeddingContextSize(16000)
    .embeddingThreads(4)
    .embeddingGpuLayers(999)
    .build();

server.start();                                 // 成功：只加载 embedding 引擎
server.isStarted();                             // true
server.hasEmbeddingEngine();                    // true
server.hasChatEngine();                         // false
server.isReady();                               // false：isReady() 只表示聊天可用

float[] vector = server.embed("玩家正在搭建一座石塔");
List<RagSearchResult> hits = server.search("石塔", texts, 4, 0.7f);

server.shutdown();                              // 与旧版一致，重复调用安全
```

## 配置校验变更

`ServerConfig.validateOrThrow` 的必需项从“必须有聊天模型”改为“聊天模型与 embedding 模型至少有一个”：

- 旧规则：`modelPath` 必填。
- 新规则：`modelPath` 与 `embeddingModelPath` 至少一个非空，两者皆空才报错。
- 新的错误文案：`modelPath or embeddingModelPath is required: set at least one chat or embedding model`。
- 其余校验项未变：`contextSize` / `embeddingContextSize` / 线程数 / `gpuLayers` / `embeddingGpuLayers` / 队列长度 / `requestTimeoutSeconds` 的范围校验与旧版一致。

`JavaLlamaServer.start()` 的加载逻辑相应调整：`LlamaEngine.loadChatEngine(...)` 只在 `modelPath` 非空时执行；embedding 模型的加载逻辑不变。因此只配 embedding 模型时，`start()` 不会去加载任何聊天引擎。

`LibsApi` 与 `ModelRegistry` 的构造器约束同时放宽：

- 旧约束：必须传聊天引擎。
- 新约束：聊天引擎与 embedding 引擎至少其一，两者都为 `null` 时抛 `IllegalArgumentException`，消息为 `At least one engine is required: configure a chat model, an embedding model, or both`。

## 新增状态访问器

新增区分“服务是否活着”和“聊天是否可用”的查询入口：

| 方法 | 语义 |
|------|------|
| `JavaLlamaServer.isStarted()` | `start()` 成功且之后未 `shutdown()`；与配置了哪些模型无关 |
| `JavaLlamaServer.hasChatEngine()` | 聊天模型已配置且已加载 |
| `JavaLlamaServer.hasEmbeddingEngine()` | embedding 模型已配置且已加载 |
| `LibsApi.hasChatEngine()` | 同上语义：已配置且已加载 |
| `LibsApi.hasEmbeddingEngine()` | 同上语义：已配置且已加载 |
| `ModelRegistry.hasChatEngine()` | 只表示已绑定聊天引擎，不含加载状态 |

`ModelRegistry.hasChatEngine()` 与另外两个同名方法有细微语义差别：`ModelRegistry` 按“本次绑定了哪些引擎”建模，拿不到加载进度，所以它返回 `true` 并不代表聊天已经可用。上层判断“能否聊天”应使用 `LibsApi` / `JavaLlamaServer` 上的同名方法。

新增公共错误文案常量 `ModelRegistry.CHAT_MODEL_NOT_CONFIGURED`：

- 内容：`Chat model is not configured: this server was started without a chat model, so chat inference is unavailable`
- 用途：无聊天引擎时的异常消息，以及 `LlmContextBudgetPlan.limitation` 的说明文本。
- 使用建议：用它渲染给用户看的限制提示可以，但不要用它做状态判断；探测 `hasChatEngine()` 更稳。

## isReady() 语义

`isReady()` 的语义本次未变，仍然表示“聊天可用”：聊天模型已加载，且已配置的 embedding 模型也已加载。因此在仅 embedding 模式下它是 `false`。

这是一个容易踩的陷阱：仅 embedding 模式下服务完全正常（`embed` / `search` 都出结果），但 `isReady()` 为 `false`。不要把 `isReady()` 当健康检查用。

javadoc 已补充说明，并按用途给出替代入口：

| 判断目的 | 使用 |
|----------|------|
| 服务是否已启动且未关闭 | `isStarted()` |
| 能否聊天 / 任务推理 | `hasChatEngine()` |
| 能否向量化 / RAG 检索 | `hasEmbeddingEngine()` |
| 聊天是否就绪（含已配置的 embedding 模型也已加载） | `isReady()` |

## 无聊天引擎时的行为契约

仅 embedding 模式下，聊天相关入口按下面的契约失败或降级。能力与状态查询不会抛异常，只有真正的推理、token 计数和 MTP 校准入口会失败。

| 入口 | 行为 |
|------|------|
| `chat` / `chatWithUsage` / `chatStream` / `task` / `taskWithUsage` / `taskStream` / `taskStreamWithUsage`（`LibsApi` 共 20 个推理重载） | 返回的 future 以 `IllegalStateException(CHAT_MODEL_NOT_CONFIGURED)` 异常完成，沿用既有失败通道 |
| `countChatPromptTokens` | 同步抛同一异常 |
| `calibrateMtp` / `calibrateMtpAsync` | 同步抛同一异常 |
| `supportsEnableThinking()` | `false` |
| `supportsMtp()` | `false` |
| `getMtpCapability()` | 非支持能力：`isSupported()=false`、`isCalibrated()=false`、layerCount 为 0、bestTrial 为 `null` |
| `getRuntimeCapabilities()` | `LlmRuntimeCapabilities.unavailable()` |
| `getContextBudgetPlan()` / `getContextBudgetPlan(lane)` | `LlmContextBudgetPlan.unavailable(CHAT_MODEL_NOT_CONFIGURED)` |
| `hasChatQueueCapacity()` / `hasTaskQueueCapacity()` / `hasQueueCapacity()` | `false` |
| `getChatQueueSize()` | `0` |
| `shutdown()` | null 安全，重复调用安全 |
| `embed` / `search` | 行为不变：有 embedding 引擎就正常出结果，没有 embedding 引擎时仍报原有错误 |

补充说明：

- 推理失败沿用既有失败通道，不会把“没有聊天模型”降级成空字符串或假的成功结果。
- `calibrateMtpAsync` 虽然返回 future，但在无聊天引擎时会同步抛异常，因为进入 future 之前就需要拿到聊天引擎；上层应先用 `hasChatEngine()` 判断。
- `Builder.dryRunContextBudget()` 与静态 `JavaLlamaServer.dryRunContextBudget(ServerConfig)` 都是聊天上下文规划入口：没有聊天模型时抛 `IllegalStateException(CHAT_MODEL_NOT_CONFIGURED)`。这类调用原先会走到 `Path.of(null)` 并以 NPE 失败，现在改为明确的状态异常。

## 测试

新增与扩充的测试：

- `EmbeddingOnlyModeTest`：不需要任何模型文件，验证引擎组合约束、dryRun 的前置条件、无聊天引擎时的能力/状态查询、未启动时的错误语义、重复 `shutdown()` 的安全性。
- `EmbeddingOnlyStartupIntegrationTest`：需要 `-Dtianshu.embedding.model=<gguf 路径>`，真实启动一个仅 embedding 的服务，验证启动成功、`embed` / `search` 出结果、聊天入口按契约失败、双重 `shutdown()` 安全。
- `ServerConfigTest`：扩充“有 embedding 模型时聊天模型可省略”“有聊天模型时 embedding 模型可省略”“两者皆空被拒绝”“空白路径视为未配置”“无聊天模型时其余校验项仍生效”。
- `common/build.gradle`：把 `tianshu.embedding.model` 系统属性转发给测试任务，集成测试在该属性存在时才运行。

## 验证记录

已执行构建与单元测试：

```powershell
.\gradlew.bat :common:build --no-daemon
```

结果：通过。

已执行仅 embedding 的集成测试（属性传入真实 embedding 模型）：

```powershell
.\gradlew.bat :common:test --no-daemon `
  "-Dtianshu.embedding.model=D:\...\bge-large-zh-v1.5-q4_k_m.gguf"
```

结果：通过。

验证结论：

- 仅配置 embedding 模型时 `start()` 成功，服务可对外提供向量能力。
- `hasEmbeddingEngine()=true`、`hasChatEngine()=false`、`isReady()=false`。
- `embed(String)` / `embed(List<String>)` / `search(...)` 均返回结果。
- 聊天入口返回的 future 以 `ExecutionException` 包装的 `IllegalStateException(CHAT_MODEL_NOT_CONFIGURED)` 失败，消息可读。
- `countChatPromptTokens` 同步抛出同一异常。
- 未加载聊天引擎时 `getRuntimeCapabilities().ready()` 为 `false`、`getContextBudgetPlan().reliable()` 为 `false`、`getChatQueueSize()` 为 `0`。
- 连续两次 `shutdown()` 均安全，之后 `isStarted()` / `hasEmbeddingEngine()` 均为 `false`。

未验证：流式与 TASK 各重载（`chatStream` / `task*`）、`calibrateMtp` / `calibrateMtpAsync` 在仅 embedding 模式下的实时行为没有被集成测试逐条断言；这些入口与已断言路径共用同一个聊天引擎获取点，行为一致性来自代码核对而非实测。

## 迁移提示

- 升级后仍是“聊天 + embedding 都配置”的部署，行为与 1.0.6 一致，无需改动。
- 想省掉聊天模型时，只调用 `embeddingModel(...)` 即可启动；此时聊天必须走上层自己的通道（例如云端 API）。
- 如果你以前用 `isReady()` 判断“服务可以用了”，改成 `isStarted()`；再用 `hasChatEngine()` / `hasEmbeddingEngine()` 判断具体能力。
- 如果你以前捕获 `NullPointerException` 兜底“没配聊天模型”，改为判断 `hasChatEngine()`；需要统一提示文案时用 `ModelRegistry.CHAT_MODEL_NOT_CONFIGURED`。
- 如果你以前在启动前无条件调用 `dryRunContextBudget()`，现在它只对聊天上下文有意义；仅 embedding 部署不要调用，否则会拿到明确的状态异常。
- 如果你直接构造 `LibsApi` / `ModelRegistry`，注意新约束是“两个引擎至少其一”，只传 `null, null` 仍然会被拒绝。
