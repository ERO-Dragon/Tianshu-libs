# Java-Llama-Server 使用文档

> **纯 Java 实现的本地大模型推理服务** —— 完美替代 C++ 版 llama-server.exe，专为 Minecraft Java 模组环境深度定制，支持 **嵌入式直连** 和 **HTTP 服务** 双模式

---

## 目录

1. [项目简介](#1-项目简介)
2. [环境准备与构建规范](#2-环境准备与构建规范)
3. [核心架构：原生库自包含部署与自解压逻辑](#3-核心架构原生库自包含部署与自解压逻辑)
4. [启动指南与参数说明](#4-启动指南与参数说明)
5. [API 接口说明](#5-api-接口说明)
6. [核心实战：Minecraft 模组集成指南（嵌入式直连模式）](#6-核心实战minecraft-模组集成指南嵌入式直连模式)

---

## 1. 项目简介

### 1.1 核心价值

Java-Llama-Server 是一个用 **纯 Java** 编写的本地大语言模型推理服务，底层基于 **jjml**（Java 对 llama.cpp 的 JNI 绑定）。它的核心使命是完美替代官方 C++ 版本的 `llama-server.exe`，并专为 Minecraft Java 模组环境进行深度定制。

**玩家的终极体验：**

- **丢一个 JAR 进 mods 文件夹即可运行** —— 无需额外配置环境变量，无需安装 VC++ 运行库，无需编译 C++ 代码
- **对外 API 100% 兼容 OpenAI 标准格式** —— 任何支持 OpenAI API 的客户端/库均可无缝对接
- **行为与官方 llama-server.exe 完全对齐** —— 命令行参数、SSE 流式输出格式、模型管理端点均保持一致
- **支持嵌入式直连调用** —— 模组侧直接通过 Java API 调用，零网络延迟，无需 HTTP 中转

### 1.2 双模式架构

本项目支持两种集成模式，可根据场景灵活选择：

#### 模式 A：嵌入式直连（推荐）

DLL 打包在 JAR 内部，运行时自动解压加载。模组侧只需 `depend` 本 JAR，然后直接调 Java API，无需任何额外配置，**零网络延迟**。

```
┌─────────────────────────────────────────────────────┐
│              Minecraft 主进程 (JVM)                   │
│  ┌─────────────┐     直接 Java 方法调用              │
│  │  宿主模组     │ ──────────────────┐               │
│  │  (Fabric/    │                    │               │
│  │   Forge/NF)  │                    ▼               │
│  └─────────────┘     ┌──────────────────────────┐   │
│                      │  LlamaServerService       │   │
│                      │  ┌──────────┐             │   │
│                      │  │LlamaEngine│  ← JNI     │   │
│                      │  │  (jjml)   │    调用     │   │
│                      │  └──────────┘  llama.dll  │   │
│                      └──────────────────────────┘   │
│  ✅ 零网络延迟，直接方法调用                          │
│  ✅ DLL 自动解压加载，无需手动配置                    │
└─────────────────────────────────────────────────────┘
```

#### 模式 B：隔离进程（可选，追求极致安全）

通过 `ProcessBuilder` 拉起独立 JVM 进程，JNI 崩溃不影响游戏。但需要 HTTP 通信，有额外延迟。详见[附录 E](#e-隔离进程模式可选)。

```
┌─────────────────────────────────────────────────────┐
│              Minecraft 主进程 (JVM 1)                 │
│  ┌─────────────┐                                    │
│  │  宿主模组     │  ← 负责 MC 交互、生命周期管理       │
│  │  (Fabric/    │                                    │
│  │   Forge/NF)  │                                    │
│  └──────┬───────┘                                    │
│         │ ProcessBuilder.start()                     │
│         ▼                                            │
│  ┌──────────────────────────────────────────────┐   │
│  │  独立 AI 服务进程 (JVM 2)                      │   │
│  │  ┌──────────┐  ┌──────────────┐  ┌────────┐  │   │
│  │  │LlamaEngine│  │ChatController│  │Javalin │  │   │
│  │  │  (jjml)   │  │  (HTTP API)  │  │(Server)│  │   │
│  │  └──────────┘  └──────────────┘  └────────┘  │   │
│  │        ▲ JNI 调用 llama.dll                     │   │
│  │        │ 如果这里崩溃...                          │   │
│  └────────│───────────────────────────────────────┘   │
│          │ 仅杀死 AI 进程                              │
│  ✅ 游戏完全不受影响！                                │
└─────────────────────────────────────────────────────┘
```

#### 两种模式对比

| 特性 | 嵌入式直连（模式 A） | 隔离进程（模式 B） |
|------|---------------------|-------------------|
| 通信方式 | 直接 Java 方法调用 | HTTP（Javalin） |
| 延迟 | **零网络延迟** | 有 HTTP 序列化/反序列化开销 |
| JNI 崩溃影响 | ⚠️ 影响整个游戏进程 | ✅ 仅 AI 进程退出，游戏继续 |
| 内存隔离 | 共享 MC 堆内存 | 独立 `-Xmx`，互不干扰 |
| DLL 管理 | JAR 内自动解压 | JAR 内自动解压（同模式 A） |
| 集成复杂度 | **极简**：`depend` + 调 API | 较高：需 ProcessBuilder + HTTP 客户端 |
| 适用场景 | 追求低延迟、简化集成 | 追求极致稳定性、可接受 HTTP 延迟 |

### 1.3 技术栈概览

| 组件 | 技术选型 | 说明 |
|------|---------|------|
| HTTP 服务端 | Javalin 5.6.3 | 轻量级嵌入式 Web 框架 |
| JSON 处理 | Gson 2.10.1 | Google 出品，禁用 HTML 转义以保证中文兼容 |
| LLM 推理 | jjml (argeo-jjml) | llama.cpp 的 Java JNI 绑定 |
| 原生库 | llama.dll / libllama.so | llama.cpp 编译产物 |
| 日志 | SLF4J 2.0.9 + slf4j-simple | 轻量日志门面 |
| 构建工具 | Gradle (Kotlin DSL) | Shadow 插件打包 Fat JAR |
| 目标 JDK | JDK 17 | 编译和运行均需 JDK 17+ |

---

## 2. 环境准备与构建规范

### 2.1 JDK 要求

> **⚠️ 强制要求：必须使用 JDK 17 及以上版本编译和运行**

本项目代码使用了 JDK 17 的语言特性（如 `switch` 箭头表达式、`var` 局部变量类型推断、文本块等），低于 JDK 17 将无法编译。

```kotlin
// build.gradle.kts 中的 JDK 版本声明
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
```

### 2.2 构建命令

```bash
# Windows
gradlew.bat shadowJar

# Linux / macOS
./gradlew shadowJar
```

构建产物位于 `build/libs/JavaLlamaServer.jar`，这是一个包含所有依赖的 Fat JAR。

### 2.3 🔴 打包红线（极其重要）

#### 绝对不要使用 Shadow 插件重定位第三方包！

当前项目作为独立服务端使用 Shadow 打包是合理的。**但当本项目作为 Minecraft 模组的内部库集成时**，必须严格遵守以下规则：

| 规则 | 原因 |
|------|------|
| ❌ **禁止**修改任何第三方依赖的包名 | 同上，JNI 绑定对类路径极其敏感 |
| ✅ **必须**使用模组加载器原生的嵌套打包机制 | 见下文 |

#### 三大模组加载器的正确打包方式

| 加载器 | 打包机制 | 配置方式 |
|--------|---------|---------|
| **Fabric** | `include`（JIJ - Jar in Jar） | 在 `build.gradle` 中使用 `include("...")` 配置 |
| **Forge** | `jarJar` | 在 `build.gradle` 中使用 `jarJar(group: "...", name: "...")` 配置 |
| **NeoForge** | `jarJar` | 同 Forge，使用 `jarJar()` DSL |

这些机制会将 `jjml.jar` 等依赖 **原封不动** 地塞进最终模组的 `META-INF/jarjar/` 目录下，不修改任何字节码。

## 3. 核心架构：原生库自包含部署与自解压逻辑

### 3.1 自包含设计

本项目采用与 `onnxruntime.jar` 和 `sherpa-onnx.jar` 相同的自包含设计：

- **DLL 打包在 JAR 内部**（`natives/windows-x86_64/*.dll`）
- **首次调用时自动从 JAR 内解压 DLL 到临时目录**
- **通过 `System.load(绝对路径)` 加载，不依赖 `java.library.path`**
- **模组侧只需 `depend` 本 JAR，无需任何额外配置**

```
JavaLlamaServer-all.jar
├── com/javallamaserver/...          # Java 类
├── natives/windows-x86_64/
│   ├── native-libs.txt              # DLL 清单（构建时自动生成）
│   ├── ggml.dll
│   ├── ggml-base.dll
│   ├── ggml-cpu-x64.dll
│   ├── ggml-cpu-sse42.dll
│   ├── ggml-cpu-haswell.dll
│   ├── ggml-cpu-sandybridge.dll
│   ├── ggml-cpu-icelake.dll
│   ├── ggml-cpu-skylakex.dll
│   ├── ggml-cpu-cascadelake.dll
│   ├── ggml-cpu-alderlake.dll
│   ├── ggml-cpu-cannonlake.dll
│   ├── llama.dll
│   ├── Java_org_argeo_jjml_ggml.dll
│   └── Java_org_argeo_jjml_llm.dll
└── ...
```

> **注意**：`ggml-vulkan.dll`（54 MB）默认不打包，如需 Vulkan GPU 后端需修改 `build.gradle.kts`。

### 3.2 运行时自解压流程

当模组代码首次引用 `LlamaServerService`、`LlamaEngine` 或 `EmbeddingEngine` 时，`NativeLibraryLoader` 自动执行以下流程：

```
NativeLibraryLoader.ensureLoaded()
  ├─ 检查是否已加载（AtomicBoolean 标志）
  ├─ 读取 JAR 内的 natives/windows-x86_64/native-libs.txt 清单
  ├─ 计算 native 资源指纹（清单内容 + 每个 DLL 的 SHA-256）
  ├─ 指纹相同且目录存在？→ 跳过解压，直接使用缓存
  ├─ 指纹不同或目录不存在？→ 清理旧目录，按清单逐个解压 DLL
  ├─ 设置 jjml 的 4 个路径字段（3 个 setter + 1 个 System.setProperty）
  ├─ 调用 LlamaCppNative.ensureLibrariesLoaded()
  └─ 标记已加载
```

**解压目录策略**：

```
%TEMP%/javallamaserver-natives/<SHA-256-fingerprint>/
├── .fingerprint          # 指纹文件，用于缓存校验
├── ggml.dll
├── llama.dll
├── ggml-base.dll
├── ggml-cpu-*.dll
├── Java_org_argeo_jjml_ggml.dll
├── Java_org_argeo_jjml_llm.dll
└── ...
```

- 使用 DLL 内容的 SHA-256 摘要作为子目录名，**资源不变则不重复解压**
- 超过 7 天的旧目录自动清理
- 使用 `native-libs.txt` 清单而非"扫描 JAR 目录"，确保在 fat JAR / nested JAR 场景下可靠运行

### 3.3 为什么不用 `java.library.path`

传统方案需要手动设置 `-Djava.library.path` 指向 DLL 目录，而自包含方案通过 `System.load(绝对路径)` 直接加载：

| 方案 | DLL 位置 | 加载方式 | 需要手动配置 |
|------|---------|---------|-------------|
| 传统 | JAR 外部目录 | `System.loadLibrary()` + `java.library.path` | ✅ 需要 `-Djava.library.path` |
| **自包含** | **JAR 内部** | **`System.load(绝对路径)`** | **❌ 不需要** |

自包含方案的核心优势：模组侧无需关心 DLL 放在哪里、无需设置任何 JVM 参数，`depend` 即用。

---

## 4. 启动指南与参数说明

### 4.1 快速启动

#### 独立模式（非模组）(仅供参考)

```bash
# 基本启动（DLL 在 JAR 内部，自动解压，无需 -Djava.library.path）
java -jar JavaLlamaServer.jar -m /path/to/model.gguf

# 完整参数启动
java -Xmx4G ^
     -jar JavaLlamaServer.jar ^
     -m D:\models\Qwen3-4B-Q4_K_M.gguf ^
     -c 4096 ^
     -t 8 ^
     -ngl 999 ^
     --host 127.0.0.1 ^
     --port 8080 ^
     --alias qwen3-4b
```

> **注意：** 当前版本 DLL 打包在 JAR 内部，由 `NativeLibraryLoader` 自动解压加载，**不再需要 `-Djava.library.path`**。

### 4.2 命令行参数表

当前服务端采用显式配置方式启动，`--model` 为必填参数；如果启用静态 RAG 或长期记忆 RAG，必须同时提供 embedding 模型。

| 参数 | 短格式 | 类型 | 默认值 | 说明 |
|------|--------|------|--------|------|
| `--model` | `-m` | String | 无 | **GGUF 聊天模型路径，必填** |
| `--context` | `-c` | int | `4096` | 兼容旧参数，作为 `--chat-context` 的默认值；如果未显式设置 `--task-context`，也作为 task lane 默认上下文大小 |
| `--threads` | `-t` | int | CPU 核心数 | 兼容旧参数，作为 `--chat-threads` 的默认值 |
| `--chat-context` | 无 | int | `--context` | chat lane 上下文窗口大小，面向玩家实时对话 |
| `--chat-threads` | 无 | int | `--threads` | chat lane CPU 推理线程数 |
| `--chat-max-queue-size` | 无 | int | `--max-queue-size` | chat lane 队列容量。队列满时返回 `429` |
| `--task-context` | 无 | int | `--context` | task lane 上下文窗口大小，面向压缩、摘要、后台任务 |
| `--task-threads` | 无 | int | `min(2, CPU核心数)` | task lane CPU 推理线程数 |
| `--task-max-queue-size` | 无 | int | `1` | task lane 队列容量。建议保持较小，避免后台任务堆积 |
| `--task-suspend-on-chat` | 无 | boolean | `true` | 当 chat lane 有待处理请求时，task lane 在安全点保存状态并挂起 |
| `--cache-type-k` | 无 | String | llama.cpp 默认值 | KV cache K 类型，支持 `f16`、`q8_0` |
| `--cache-type-v` | 无 | String | llama.cpp 默认值 | KV cache V 类型，支持 `f16`、`q8_0` |
| `--n-gpu-layers` | `-ngl` | int | `999` | 聊天模型卸载到 GPU 的层数 |
| `--host` | 无 | String | `127.0.0.1` | 绑定地址。当前仅允许 `127.0.0.1` |
| `--port` | 无 | int | `8080` | 绑定端口 |
| `--alias` | 无 | String | 模型文件名 | 聊天模型别名，用于 API 返回 |
| `--model-profile` | 无 | String | 自动识别 | 模型适配 profile，例如 `qwen3` |
| `--embedding-model` | 无 | String | 无 | GGUF embedding 模型路径 |
| `--embedding-context` | 无 | int | `4096` | embedding 模型上下文窗口大小 |
| `--embedding-threads` | 无 | int | CPU 核心数 | embedding 模型 CPU 线程数 |
| `--embedding-gpu-layers` | 无 | int | `999` | embedding 模型 GPU 卸载层数 |
| `--embedding-alias` | 无 | String | `embedding` | embedding 模型别名 |
| `--static-rag-path` | 无 | String | 无 | 静态 RAG 文件或文件夹路径 |
| `--memory-rag-path` | 无 | String | 无 | 兼容旧版：单长期记忆 RAG 文件夹路径。新多世界架构推荐使用 `--rag-root-path` |
| `--rag-root-path` | 无 | String | 无 | 多世界 / 多模组 / 多 agent RAG 根目录。启用后请求可通过 `world` + `profile` 定位 RAG |
| `--rag-profile-refresh-interval-ms` | 无 | int | `1000` | 每个世界 `profiles.json` 的懒刷新检查间隔 |
| `--world-static-rag-scan-interval-ms` | 无 | int | `5000` | 扫描当前世界下所有模组 `static_rag/` 的最小间隔 |
| `--memory-rag-refresh-interval-ms` | 无 | int | `1000` | 长期记忆文件变更检查的最小间隔。服务端在 chat 请求前懒刷新，不依赖文件监听 |
| `--static-rag-top-k` | 无 | int | `4` | 每次请求检索的静态 RAG 条数 |
| `--dynamic-rag-top-k` | 无 | int | `4` | 每次请求检索的动态 RAG 条数 |
| `--rag-chunk-size` | 无 | int | `900` | 静态 RAG 文本切块大小，按字符近似 |
| `--rag-chunk-overlap` | 无 | int | `120` | 静态 RAG 切块重叠大小 |
| `--max-queue-size` | 无 | int | `4` | 兼容旧参数，作为 `--chat-max-queue-size` 的默认值 |
| `--request-timeout-seconds` | 无 | int | `300` | 非流式请求最大等待时间 |
| `--help` | `-h` | 无 | 无 | 打印帮助信息 |

#### 普通聊天模型启动

```bash
java -jar JavaLlamaServer.jar \
     --model /path/to/chat-model.gguf \
     --port 8080
```

#### 双 lane 推理启动示例

服务端支持 `chat` / `task` 双 lane 推理架构：

- `chat` lane 面向玩家实时对话，优先级最高，支持流式输出、静态 RAG、动态 RAG 和长期记忆 RAG。
- `task` lane 面向压缩、摘要、记忆整理等后台任务，优先级较低，支持流式和非流式输出，默认不启用 RAG；即使显式启用 RAG，也只使用静态 RAG 和动态 RAG，不使用长期记忆 RAG。
- 两条 lane 共享同一份 `LlamaCppModel` 模型权重，但按 lane 创建独立 `LlamaCppContext` 和 KV cache。
- 当 `--task-suspend-on-chat true` 时，task lane 在 decode 阶段每生成一个 token 后检查是否有 chat 请求；如果有，则保存 context state、关闭 task context，让 chat 先执行，chat 空闲后再恢复 task。
- 第一版只在 decode 安全点抢占，不在 prefill/native kernel 执行中硬中断。

```bash
java -jar JavaLlamaServer.jar \
     --model /path/to/chat-model.gguf \
     --chat-context 4096 \
     --chat-threads 6 \
     --chat-max-queue-size 2 \
     --task-context 8192 \
     --task-threads 2 \
     --task-max-queue-size 1 \
     --task-suspend-on-chat true \
     --cache-type-k q8_0 \
     --cache-type-v q8_0
```

#### 兼容模式：启用单静态 RAG、动态 RAG 与单长期记忆 RAG

```bash
java -jar JavaLlamaServer.jar \
     --model /path/to/chat-model.gguf \
     --embedding-model /path/to/bge-large-zh-v1.5.gguf \
     --static-rag-path /path/to/rag-folder \
     --memory-rag-path /path/to/current-world/memory_rag \
     --static-rag-top-k 4 \
     --dynamic-rag-top-k 4
```

`--static-rag-path` 可以是单个文件，也可以是文件夹。传入文件夹时，服务端会递归扫描其中的 `.txt`、`.md`、`.json`、`.jsonl` 文件，启动时统一切块、向量化并建立内存索引。后续每次问答只会向量化用户查询和动态 RAG 条目，不会反复向量化静态文件。

`--memory-rag-path` 指向单长期记忆文件夹，是兼容旧版的启动方式。服务端只读取其中的 `memories.jsonl`，不会修改该文件；服务端会在同一目录下维护 `.javallama-memory-index/` 作为长期记忆向量缓存。长期记忆 RAG 只对 `chat` lane 生效，`task` lane 即使设置 `use_rag=true` 也不会使用长期记忆系统。

#### 推荐模式：启用多世界 / 多模组 / 多 Agent RAG

```bash
java -jar JavaLlamaServer.jar \
     --model /path/to/chat-model.gguf \
     --embedding-model /path/to/bge-large-zh-v1.5.gguf \
     --rag-root-path /path/to/llm_rag \
     --static-rag-top-k 4 \
     --dynamic-rag-top-k 4 \
     --memory-rag-refresh-interval-ms 1000 \
     --rag-profile-refresh-interval-ms 1000 \
     --world-static-rag-scan-interval-ms 5000
```

---

## 5. API 接口说明

### 5.1 POST /v1/chat/completions

**请求地址：** `POST http://127.0.0.1:8080/v1/chat/completions`

**请求体（JSON）：**

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `messages` | Array | **必填** | 消息列表，格式同 OpenAI |
| `stream` | Boolean | `false` | 是否启用 SSE 流式输出 |
| `temperature` | Double | `0.0` | 采样温度 |
| `top_k` | Integer | `40` | Top-K 采样 |
| `top_p` | Double | `0.95` | Top-P 采样 |
| `min_p` | Double | `0.05` | Min-P 采样 |
| `max_tokens` | Integer | 无限制 | 最大生成 token 数 |
| `repeat_penalty` | Double | `1.0` | 重复惩罚 |
| `lane` | String | `"chat"` | 推理 lane：`"chat"` 或 `"task"` |
| `use_rag` | Boolean | chat 默认 `true`，task 默认 `false` | 是否启用 RAG |
| `dynamic_rag` | Array/String | 无 | 动态 RAG 条目，可以是字符串数组或对象数组 |
| `world` | String | 无 | RAG 世界标识（需启用 `--rag-root-path`） |
| `profile` | String | 无 | RAG profile 标识（需启用 `--rag-root-path`） |
| `static_scope` | String | `"mod"` | 静态 RAG 作用域：`"mod"` / `"world"` / `"list"` |
| `static_mods` | Array | 无 | `static_scope=list` 时指定的模组列表 |
| `use_memory_rag` | Boolean | `true` | 是否启用长期记忆 RAG（仅 chat lane） |
| `memory_rag_token_budget` | Integer | 无 | 长期记忆 token 预算 |
| `include_rag_hits` | Boolean | `true` | 是否在响应中返回 RAG 命中详情 |
| `task_priority` | Integer | `0` | task lane 任务优先级，数值越大越优先 |
| `task_preemptible` | Boolean | `false` | task 是否可被更高优先级 task 抢占 |

#### 基础聊天请求示例

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{
    "messages": [
      {"role": "system", "content": "你是 Minecraft 世界中的铁匠 NPC。"},
      {"role": "user", "content": "我该怎么做才能在夜晚生存？"}
    ],
    "temperature": 0.7,
    "max_tokens": 256
  }'
```

#### 带 RAG 的聊天请求示例

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{
    "messages": [
      {"role": "system", "content": "你是 Minecraft 世界中的铁匠 NPC。"},
      {"role": "user", "content": "我该怎么做才能在夜晚生存？"}
    ],
    "dynamic_rag": [
      "玩家背包里有铁剑、铁镐和煤炭。",
      "附近有僵尸，村庄有一名铁匠。"
    ]
  }'
```

如果模组侧愿意传对象列表，也可以：

```json
"dynamic_rag": [
  {"text": "玩家背包里有铁剑、铁镐和煤炭。"},
  {"text": "附近有僵尸，村庄有一名铁匠。"}
]
```

服务端会先对当前用户问题做向量检索，再把命中的静态 RAG、动态 RAG 和长期记忆 RAG 整理进 system 上下文，然后送进模型。`chat` lane 默认启用 RAG；`task` lane 默认不启用 RAG，只有在显式设置 `use_rag=true` 时才会启用静态 + 动态 RAG。长期记忆 RAG 是独立的 chat-only 机制：只有 `chat` lane 会使用长期记忆，`task` lane 永远不会读取、检索或注入长期记忆。

#### task lane 后台任务示例

`task` lane 适合模组发起压缩、摘要、记忆整理等后台任务。服务端默认不会为 task lane 自动执行 RAG；当 `use_rag=false` 或不传时，哪怕传入 `dynamic_rag` 也不会触发任何 RAG 处理。`task` lane 可以通过 `use_rag=true` 使用静态 RAG 和动态 RAG，但不能使用长期记忆 RAG；`use_memory_rag`、`memory_rag_token_budget` 和 `include_rag_hits` 对 `task` lane 无效。`task` lane 支持流式和非流式两种模式：如果调用方设置 `stream=true`，服务端会像 chat 一样按 chunk 返回；如果设置 `stream=false`，则一次性返回完整结果。模组应根据任务类型决定是否启用 `use_rag` 和是否需要流式输出。

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{
    "lane": "task",
    "stream": false,
    "use_rag": false,
    "task_priority": 0,
    "task_preemptible": false,
    "messages": [
      {"role": "system", "content": "你是记忆压缩器。请把输入压缩成稳定、可复用的摘要。"},
      {"role": "user", "content": "这里放入模组已经收集和裁剪过的对话、事件或世界状态。"}
    ],
    "max_tokens": 512
  }'
```

新增 task 时，如果队列中已经有多个后台任务，服务端会优先执行 `task_priority` 更大的任务，同优先级按提交顺序执行。例如紧急世界状态摘要可以使用 `task_priority=100`，普通长期记忆压缩可以使用 `task_priority=0`，可延后的批量整理可以使用负数优先级。已经开始执行的 task 只有在自身设置了 `task_preemptible=true` 时，才可能被队列中更高优先级的 task 在 decode 安全点抢占。chat 永远优先于 task。

如果一个 task 已经开始执行，默认不会被新的 task 抢占。只有该 task 自己设置了 `task_preemptible=true`，并且队列里出现更高 `task_priority` 的 task 时，服务端才会在下一个 token 安全点保存该 task 的 context state、挂起它并执行更高优先级 task。高优先级 task 完成后，调度器会继续从已挂起 task 和待执行 task 中选择当前优先级最高的 task。

如果 task 正在 decode 阶段运行，而此时有新的 chat 请求进入，在 `--task-suspend-on-chat true` 时，服务端会在下一个 token 安全点保存 task context state、释放 task context，然后优先执行 chat。chat 空闲后，服务端会重新创建 task context，加载已保存的 context state，并继续生成。对于 `task` lane 的流式请求，服务端会在生成过程中持续输出 chunk；对于非流式请求，则会在任务完成后一次性返回完整结果。

#### 多世界 / 多模组 / 多 Agent RAG 目录

启用 `--rag-root-path` 后，服务端按以下目录规范查找 RAG：

```text
llm_rag/
├── world_a/
│   ├── profiles.json
│   ├── mod_a/
│   │   ├── static_rag/
│   │   └── agents/
│   │       └── agent_001/
│   │           └── memory_rag/
│   │               └── memories.jsonl
│   └── mod_b/
│       ├── static_rag/
│       └── agents/
│           └── guard_bob/
│               └── memory_rag/
│                   └── memories.jsonl
└── world_b/
    ├── profiles.json
    └── mod_c/
        ├── static_rag/
        └── agents/
            └── npc_x/
                └── memory_rag/
                    └── memories.jsonl
```

每个世界维护自己的 `profiles.json`：

```json
{
  "version": 1,
  "default_profile": "mod_a/agent_001",
  "profiles": {
    "mod_a/agent_001": {
      "mod": "mod_a",
      "agent": "agent_001"
    },
    "mod_b/guard_bob": {
      "mod": "mod_b",
      "agent": "guard_bob",
      "static_scope": "mod",
      "memory_token_budget": 1000
    }
  }
}
```

请求示例：

```json
{
  "world": "world_a",
  "profile": "mod_b/guard_bob",
  "static_scope": "world",
  "messages": [
    {"role": "user", "content": "你知道附近发生了什么吗？"}
  ]
}
```

`static_scope=mod` 时只使用当前 profile 所属模组的 `static_rag/`；`static_scope=world` 时扫描并使用当前世界所有模组的 `static_rag/`；`static_scope=list` 时只使用 `static_mods` 指定的模组。静态 RAG 和长期记忆 RAG 均按完整路径缓存，同路径只加载一次。

#### 长期记忆 RAG 设计

长期记忆 RAG 用于承载稳定记忆，例如玩家曾经做过的事、和 NPC 的关系、偏好或世界中已经沉淀下来的事实。兼容模式下长期记忆目录由 `--memory-rag-path` 指定；多世界 profile RAG 模式下长期记忆目录固定为：

```text
llm_rag/<world>/<mod>/agents/<agent>/memory_rag/
├── memories.jsonl
└── .javallama-memory-index/
```

它和动态 RAG/current facts 分开处理，不会混在同一个上下文区块中。

职责边界：

- `memories.jsonl` 由模组侧维护，服务端只读不写。
- `.javallama-memory-index/` 由服务端维护，用于保存长期记忆向量缓存，模组侧不修改。
- 服务端启动和 chat 请求前会读取或刷新长期记忆索引；刷新失败时继续使用旧索引，不影响聊天服务。
- 长期记忆 RAG 只对 `chat` lane 生效，`task` lane 不会使用长期记忆。

`memories.jsonl` 使用 JSONL 格式，一行一条记忆。第一版只要求两个字段：

```jsonl
{"uid":"mem-0001","long_term_memory":"玩家第一次进入村庄时帮助铁匠找回了丢失的矿石。"}
{"uid":"mem-0002","long_term_memory":"玩家偏好使用弓箭进行远程战斗。"}
{"uid":"mem-0003","long_term_memory":"玩家和村民艾拉关系很好，艾拉信任玩家。"}
```

约定：

- `uid` 由模组侧生成，在当前世界长期记忆库内唯一。
- `uid` 一旦生成不复用。
- `uid` 对应的 `long_term_memory` 可以修正；服务端会通过文本 hash 识别变化，并只重建该条记忆的向量。
- 模组侧也可以选择删除旧 `uid` 并新增新 `uid`，用于表达语义上已经不是同一条记忆的情况。
- 模组侧可在其他地方维护 TTL、hit_count、last_hit_time、importance 等生命周期信息；如果未来这些字段出现在 JSONL 中，服务端会忽略未知字段。

写入安全约定：

```text
1. 模组侧写 memories.jsonl.tmp
2. flush 完成
3. 原子替换 memories.jsonl
```

服务端只读取 `memories.jsonl`，忽略 `*.tmp` 和 `*.lock`。

长期记忆刷新策略：

```text
启动时加载一次
每次 chat 请求前懒刷新
通过 --memory-rag-refresh-interval-ms 限制文件检查频率
检测 memories.jsonl 的修改时间和大小
发现变化后按 uid 增量更新索引
新增 uid 计算 embedding
删除 uid 从索引移除
同 uid 文本变化时只重建该条向量
```

长期记忆注入 prompt 时保持简洁和沉浸，不使用生硬的资料库提示。推荐格式：

```text
你隐约记得：
1. 玩家和村民艾拉关系很好，艾拉信任玩家。
2. 玩家偏好使用弓箭进行远程战斗。
```

该区块会作为独立 system 上下文拼入请求，和动态 RAG/current facts 分开。服务端会在 `memory_rag_token_budget` 的预算内自行决定实际注入哪些长期记忆；返回给模组侧的 `rag_hits.memory` 只包含本次实际注入 prompt 的长期记忆。

#### 非流式响应示例

```json
{
  "id": "chatcmpl-a1b2c3d4",
  "object": "chat.completion",
  "created": 1710000000,
  "model": "Qwen3-4B-Q4_K_M.gguf",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "你现在应该先进屋或靠近铁傀儡，夜晚附近有僵尸，不适合继续外出。你手里有铁剑，可以先清理最近的威胁，再回来找我修理装备。"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 0,
    "completion_tokens": 0,
    "total_tokens": 0
  },
  "rag_hits": {
    "memory": [
      {
        "uid": "mem-0003",
        "score": 0.8231,
        "long_term_memory": "玩家和村民艾拉关系很好，艾拉信任玩家。"
      }
    ]
  }
}
```

#### 流式请求示例（stream=true）

**请求：**

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{
    "messages": [
      {"role": "user", "content": "你好"}
    ],
    "stream": true
  }'
```

**SSE 流式响应（逐步返回）：**

如果本次 chat 请求启用了长期记忆 RAG 且 `include_rag_hits` 未显式设为 `false`，第一帧会先返回本次实际注入 prompt 的长期记忆命中信息：

```
data: {"id":"chatcmpl-a1b2c3d4","object":"chat.completion.chunk","created":1710000000,"model":"Qwen3-4B-Q4_K_M.gguf","choices":[],"rag_hits":{"memory":[{"uid":"mem-0003","score":0.8231,"long_term_memory":"玩家和村民艾拉关系很好，艾拉信任玩家。"}]}}
```

随后正常返回 token：

```
data: {"id":"chatcmpl-a1b2c3d4","object":"chat.completion.chunk","created":1710000000,"model":"Qwen3-4B-Q4_K_M.gguf","choices":[{"index":0,"delta":{"content":"你"},"finish_reason":null}]}

data: {"id":"chatcmpl-a1b2c3d4","object":"chat.completion.chunk","created":1710000000,"model":"Qwen3-4B-Q4_K_M.gguf","choices":[{"index":0,"delta":{"content":"好"},"finish_reason":null}]}

data: {"id":"chatcmpl-a1b2c3d4","object":"chat.completion.chunk","created":1710000000,"model":"Qwen3-4B-Q4_K_M.gguf","choices":[{"index":0,"delta":{"content":"！"},"finish_reason":null}]}

data: {"id":"chatcmpl-a1b2c3d4","object":"chat.completion.chunk","created":1710000000,"model":"Qwen3-4B-Q4_K_M.gguf","choices":[{"index":0,"delta":{"content":null},"finish_reason":"stop"}]}

data: {"id":"chatcmpl-a1b2c3d4","object":"chat.completion.chunk","created":1710000000,"model":"Qwen3-4B-Q4_K_M.gguf","choices":[],"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}

data: [DONE]
```

**SSE 数据格式要点：**

- 每条数据以 `data: ` 前缀开头，后跟 JSON 字符串，以 `\n\n` 结尾
- 如果启用了长期记忆 RAG，第一帧可以是 `choices: []` 的 metadata chunk，用于返回 `rag_hits.memory`
- 每个 token chunk 的 `delta.content` 包含本次生成的 token 文本
- 当生成结束时，发送 `finish_reason: "stop"` 的 chunk
- 最后发送 `usage` chunk（包含 token 统计信息）
- 最终以 `data: [DONE]\n\n` 标记流结束
- 如果队列在进入 SSE 前已经满，服务端会直接返回 HTTP `429` 和普通 JSON 错误，不会打开 SSE 流
- 如果连接已经建立后底层发生异常，客户端应按 SSE 数据帧中的错误 JSON 或连接结束进行容错处理

### 5.2 GET /v1/models

**请求：**

```bash
curl http://127.0.0.1:8080/v1/models
```

**响应：**

```json
{
  "object": "list",
  "data": [
    {
      "id": "Qwen3-4B-Q4_K_M.gguf",
      "object": "model",
      "owned_by": "local"
    }
  ]
}
```

> `id` 字段的值由启动参数 `--alias` 决定。如果不指定，则自动从模型文件路径中提取文件名。

### 5.3 GET /health

**请求：**

```bash
curl http://127.0.0.1:8080/health
```

**模型已加载时的响应：**

```json
{
  "status": "ready",
  "embedding": true,
  "static_rag_chunks": 128,
  "queue_size": 0,
  "max_queue_size": 2,
  "chat_queue_size": 0,
  "chat_max_queue_size": 2,
  "task_queue_size": 0,
  "task_max_queue_size": 1,
  "current_lane": null,
  "task_suspended": false
}
```

HTTP 状态码：`200`

**模型未加载时的响应：**

```json
{"status": "loading"}
```

HTTP 状态码：`503`

### 5.4 POST /v1/embeddings

该接口用于把文本转换为向量。只有启动时传入 `--embedding-model` 后才可用。

**请求：**

```bash
curl -X POST http://127.0.0.1:8080/v1/embeddings \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{
    "input": ["铁剑可以保护玩家", "夜晚的村庄可能刷怪"]
  }'
```

**响应：**

```json
{
  "object": "list",
  "model": "bge-large-zh-v1.5.gguf",
  "data": [
    [0.01, 0.02, -0.03],
    [0.04, -0.01, 0.08]
  ]
}
```

### 5.5 错误响应格式

所有错误均返回标准 JSON 格式：

```json
{
  "error": "错误描述信息"
}
```

| HTTP 状态码 | 触发条件 |
|-------------|---------|
| `400` | 请求 JSON 格式错误、`messages` 字段缺失 |
| `404` | 请求 embedding 接口但未配置 embedding 模型 |
| `429` | 推理队列已满 |
| `504` | 非流式请求等待超时 |
| `500` | 推理过程中发生内部错误 |

---

## 6. 核心实战：Minecraft 模组集成指南（嵌入式直连模式）

> **推荐集成方式** —— 零网络延迟，DLL 自动管理，API 调用极简。

### 6.1 依赖配置

#### Fabric（`build.gradle`）

```groovy
dependencies {
    include("com.rheinmetal:JavaLlamaServer:1.0.1")
}
```

#### Forge / NeoForge（`build.gradle`）

```groovy
dependencies {
    jarJar(group: "com.rheinmetal", name: "JavaLlamaServer", version: "1.0.1")
}
```

### 6.2 基础用法

```java
import com.javallamaserver.core.LlamaServerService;
import com.javallamaserver.web.ChatController.ChatMessage;
import com.javallamaserver.llm.SamplerConfig;
import java.util.concurrent.CompletableFuture;

public class YourMod implements ModInitializer {
    private LlamaServerService aiService;

    @Override
    public void onInitialize() {
        aiService = LlamaServerService.builder()
            .chatModel(resolveModelPath("qwen3-4b-q4_k_m.gguf"))
            .chatContext(4096)
            .chatThreads(4)
            .gpuLayers(999)
            .embeddingModel(resolveModelPath("bge-large-zh-v1.5.gguf"))
            .ragRootPath(resolveRagPath("llm_rag"))
            .build();

        CompletableFuture.runAsync(() -> {
            try {
                aiService.start();
                System.out.println("[YourMod] AI 服务就绪");
            } catch (Exception e) {
                System.err.println("[YourMod] AI 服务启动失败: " + e.getMessage());
            }
        });
    }
}
```

**关键说明：**

- `LlamaServerService` 类加载时，`NativeLibraryLoader` 自动解压并加载 DLL，**无需任何手动配置**
- `start()` 会加载模型、初始化 RAG 索引，耗时较长，**务必异步调用**，不要阻塞 MC 主线程
- `startWithHttp(port)` 在嵌入式基础上同时启动 HTTP 服务，方便调试

### 6.3 API 速查

`LlamaServerService` 提供以下方法，**全部是直接 Java 方法调用，无 HTTP 开销**：

#### 同步聊天

```java
// 简写：单条消息 + system prompt
String reply = aiService.chatSync("你好", "你是铁匠NPC");

// 完整：自定义消息列表 + 采样参数 + maxTokens
List<ChatMessage> messages = List.of(
    new ChatMessage("system", "你是铁匠NPC"),
    new ChatMessage("user", "我需要一把剑")
);
SamplerConfig sampler = new SamplerConfig();
sampler.setTemperature(0.7);
sampler.setTopP(0.9);
String reply = aiService.chatSync(messages, sampler, 256);
```

#### 流式聊天

```java
// 简写
aiService.chatStream("你好", "你是铁匠NPC", token -> {
    System.out.print(token);
});

// 完整：自定义消息列表 + 采样参数
aiService.chatStream(messages, sampler, token -> {
    // 每个 token 实时回调
    broadcastToPlayer(token);
});
```

#### 后台任务（Task Lane）

```java
CompletableFuture<String> future = aiService.submitTask(
    List.of(
        new ChatMessage("system", "你是记忆压缩器"),
        new ChatMessage("user", longConversationText)
    ),
    null,       // sampler（null 使用默认）
    512,        // maxTokens
    0,          // priority
    false       // preemptible
);

future.thenAccept(summary -> {
    System.out.println("压缩结果: " + summary);
});
```

#### 服务状态

```java
aiService.isReady();            // 模型是否加载完成
aiService.hasChatQueueCapacity(); // chat 队列是否还有空位
aiService.getChatQueueSize();   // 当前 chat 队列长度
```

#### 关闭服务

```java
aiService.shutdown();           // 释放模型、关闭 HTTP 服务（如有）
```

### 6.4 Builder 参数完整列表

| Builder 方法 | 对应命令行参数 | 默认值 | 说明 |
|-------------|--------------|--------|------|
| `chatModel(path)` | `--model` | 无 | **必填**，聊天模型路径 |
| `chatContext(n)` | `--chat-context` | `4096` | chat lane 上下文窗口 |
| `chatThreads(n)` | `--chat-threads` | CPU 核心数 | chat lane 推理线程数 |
| `chatMaxQueueSize(n)` | `--chat-max-queue-size` | `4` | chat lane 队列容量 |
| `gpuLayers(n)` | `--n-gpu-layers` | `999` | GPU 卸载层数 |
| `modelAlias(name)` | `--alias` | 文件名 | 模型别名 |
| `modelProfile(name)` | `--model-profile` | 自动识别 | 模型适配 profile |
| `cacheTypeK(type)` | `--cache-type-k` | 默认 | KV cache K 类型 |
| `cacheTypeV(type)` | `--cache-type-v` | 默认 | KV cache V 类型 |
| `embeddingModel(path)` | `--embedding-model` | 无 | embedding 模型路径 |
| `embeddingContext(n)` | `--embedding-context` | `4096` | embedding 上下文窗口 |
| `embeddingThreads(n)` | `--embedding-threads` | CPU 核心数 | embedding 线程数 |
| `embeddingGpuLayers(n)` | `--embedding-gpu-layers` | `999` | embedding GPU 层数 |
| `embeddingAlias(name)` | `--embedding-alias` | `embedding` | embedding 模型别名 |
| `staticRagPath(path)` | `--static-rag-path` | 无 | 静态 RAG 路径 |
| `memoryRagPath(path)` | `--memory-rag-path` | 无 | 长期记忆 RAG 路径 |
| `ragRootPath(path)` | `--rag-root-path` | 无 | 多世界 RAG 根目录 |
| `ragChunkSize(n)` | `--rag-chunk-size` | `900` | RAG 切块大小 |
| `ragChunkOverlap(n)` | `--rag-chunk-overlap` | `120` | RAG 切块重叠 |
| `staticRagTopK(n)` | `--static-rag-top-k` | `4` | 静态 RAG 检索条数 |
| `dynamicRagTopK(n)` | `--dynamic-rag-top-k` | `4` | 动态 RAG 检索条数 |
| `taskContext(n)` | `--task-context` | 同 chatContext | task lane 上下文窗口 |
| `taskThreads(n)` | `--task-threads` | `min(2, CPU)` | task lane 线程数 |
| `taskMaxQueueSize(n)` | `--task-max-queue-size` | `1` | task lane 队列容量 |
| `taskSuspendOnChat(b)` | `--task-suspend-on-chat` | `true` | chat 优先时挂起 task |
| `requestTimeoutSeconds(n)` | `--request-timeout-seconds` | `300` | 请求超时秒数 |

### 6.5 生命周期管理

在游戏关闭时调用 `shutdown()` 释放 GPU 资源：

**Fabric：**

```java
ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
    if (aiService != null) aiService.shutdown();
});
```

**Forge / NeoForge：**

```java
MinecraftForge.EVENT_BUS.addListener((FMLServerStoppingEvent e) -> {
    if (aiService != null) aiService.shutdown();
});
```

**双重保险：ShutdownHook**

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    if (aiService != null) aiService.shutdown();
}));
```

### 6.6 嵌入式模式下的 Embedding 访问

嵌入式模式下，`/v1/embeddings` 和 `/v1/models` HTTP 端点默认不启动。如需访问 embedding 功能或模型信息，有两种方式：

**方式一：同时启动 HTTP 服务（推荐调试用）**

```java
aiService.startWithHttp(8080);
// 此时 /v1/embeddings、/v1/models、/health 等 HTTP 端点均可访问
```

**方式二：纯嵌入式访问 EmbeddingEngine**

```java
// 通过 ModelRegistry 访问（需自行持有引用，当前 API 未直接暴露）
// 未来版本可能提供 aiService.embed() 等便捷方法
```

---

## 附录

### A. 推理引擎内部架构

服务端采用双 lane 推理架构，但模型权重只加载一份：

- `chat` lane：玩家实时对话，高优先级，支持流式输出、静态 RAG、`dynamic_rag` 和长期记忆 RAG。
- `task` lane：后台压缩、摘要、记忆整理等任务，低优先级，支持流式和非流式输出；默认不自动执行 RAG，设置 `use_rag=true` 时只启用静态 RAG 和 `dynamic_rag`，不启用长期记忆 RAG。
- 两条 lane 共享同一个 `LlamaCppModel`，但创建各自的 `LlamaCppContext`，因此 KV cache 按 lane/task 生命周期隔离。
- task lane 在 decode 阶段可挂起恢复；prefill 阶段和 native/GPU kernel 执行中不会被硬中断。

```
┌───────────────────────────────────────────────────────┐
│                   ServerApp (入口)                      │
│  参数解析 → LlamaEngine 初始化 → Javalin HTTP 服务启动  │
└─────────────────────┬─────────────────────────────────┘
                      │
          ┌───────────┼───────────┐
          ▼           ▼           ▼
   ┌────────────┐ ┌──────────┐ ┌────────┐
   │ChatController│ │/v1/models│ │/health │
   │  POST 流式   │ │  GET     │ │  GET   │
   └──────┬─────┘ └──────────┘ └────────┘
          │
          ▼
   ┌──────────────┐     提交任务      ┌──────────────┐
   │ InferenceTask │ ───────────────→ │  TaskExecutor │
   │ 含 lane 信息   │                  │ chat 优先调度  │
   └──────────────┘                   └───────┬──────┘
                                              │
                                     ┌────────▼────────┐
                                     │   LlamaEngine    │
                                     │ 单模型权重持有者 │
                                     │ chatQueue/taskQueue│
                                     │ lane 配置与状态  │
                                     └────────┬────────┘
                                              │ 创建
                                     ┌────────▼────────┐
                                     │  LlamaCppContext │
                                     │ 按 lane 独立创建 │
                                     │ chat/task KV 隔离│
                                     └────────┬────────┘
                                              │ 使用
                                     ┌────────▼────────┐
                                     │ SamplerConfig    │
                                     │ → LlamaCppSampler│
                                     │   Chain (采样链)  │
                                     └─────────────────┘
```

### B. SamplerConfig 参数说明

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `temperature` | `0.0` | 采样温度，0.0 = 贪心解码，>0 引入随机性 |
| `topK` | `40` | Top-K 采样，限制候选 token 数量 |
| `topP` | `0.95` | Top-P（核采样）累积概率阈值 |
| `minP` | `0.05` | Min-P 采样，最小概率阈值 |
| `penaltyRepeat` | `1.0` | 重复惩罚系数 |
| `penaltyFreq` | `0.0` | 频率惩罚 |
| `penaltyPresent` | `0.0` | 存在惩罚 |
| `penaltyLastN` | `64` | 惩罚计算的上下文窗口 |
| `grammarStr` | `null` | GBNF 语法约束字符串 |
| `grammarRoot` | `"root"` | 语法根节点名称 |

### C. 常见问题排查

| 问题 | 可能原因 | 解决方案 |
|------|---------|---------|
| `UnsatisfiedLinkError` | DLL 未自动解压或架构不匹配 | 检查 JAR 内 `natives/` 目录是否完整；确认操作系统架构 |
| `Model file not found` | 模型路径错误 | 使用绝对路径指定 `.gguf` 文件 |
| 启动后立即退出 | 绑定了 `0.0.0.0` | 改为 `127.0.0.1` |
| 流式输出中文乱码 | 编码未指定 UTF-8 | 嵌入式模式无此问题；HTTP 模式确保 `charset=utf-8` |
| GPU 未被使用 | `-ngl` 设置为 0 或 CUDA 不可用 | 确保安装了 CUDA 驱动，`gpuLayers(999)` |
| `Engine already initialized` | 重复初始化 | LlamaEngine 是单例，只能初始化一次 |
| 中文输入后模型输出异常 | 请求编码问题 | 嵌入式模式无此问题；HTTP 模式确保 `String.getBytes("UTF-8")` |

### D. 目录结构速查

```
Java-llama-server/
├── build.gradle.kts              # Gradle 构建配置（Kotlin DSL）
├── settings.gradle.kts           # Gradle 设置
├── gradlew / gradlew.bat         # Gradle Wrapper
├── libs/
│   ├── argeo-jjml/               # jjml Java 源码（参考用）
│   │   └── org/argeo/jjml/...
│   └── jjml-all/                 # 原生 DLL 文件
│       ├── llama.dll             # llama.cpp 核心库
│       ├── ggml.dll              # GGML 基础库
│       ├── ggml-cpu-*.dll        # CPU 优化库（多架构）
│       ├── Java_org_argeo_jjml_*.dll  # JNI 桥接库
│       └── ...
└── src/main/java/com/javallamaserver/
    ├── core/
    │   ├── ServerApp.java        # 服务端主入口
    │   └── LlamaServerService.java  # 嵌入式 API 门面（Builder 模式）
    ├── llm/
    │   ├── LlamaEngine.java      # LLM 推理引擎（单模型权重 + chat/task 双队列）
    │   ├── EmbeddingEngine.java  # Embedding 推理引擎
    │   ├── InferenceLane.java    # 推理 lane 定义（chat/task）
    │   ├── LaneConfig.java       # lane 上下文、线程和队列配置
    │   ├── LaneMetrics.java      # lane 健康检查状态
    │   ├── KvCacheType.java      # KV cache 类型映射
    │   ├── InferenceTask.java    # 推理任务模型
    │   ├── SamplerConfig.java    # 采样参数配置
    │   └── TaskExecutor.java     # 推理任务执行器（chat 优先 + task 可挂起）
    ├── nativelib/
    │   └── NativeLibraryLoader.java  # DLL 自解压与加载
    ├── rag/
    │   └── RagService.java       # RAG 服务（静态/动态/长期记忆）
    └── web/
        ├── ChatController.java   # 聊天 API 控制器（流式+同步）
        └── SseConnectionManager.java  # SSE 连接管理器
```

### E. 隔离进程模式（可选）

> **仅当你的场景对 JNI 崩溃零容忍时才需要此模式**。绝大多数模组推荐使用[嵌入式直连模式](#6-核心实战minecraft-模组集成指南嵌入式直连模式)。

隔离进程模式通过 `ProcessBuilder` 拉起独立 JVM 运行 JavaLlamaServer，AI 进程崩溃不会影响游戏。代价是需要 HTTP 通信，有额外延迟。

#### E.1 工作原理

```
模组侧                              AI 服务进程（独立 JVM）
  │                                      │
  │  1. extractServerJar()               │
  │     从 JAR-in-JAR 中提取              │
  │     JavaLlamaServer.jar              │
  │                                      │
  │  2. ProcessBuilder                   │
  │     java -cp JavaLlamaServer.jar ──→ │  NativeLibraryLoader 自动解压 DLL
  │     ServerApp -m model.gguf          │  加载模型、启动 Javalin HTTP
  │                                      │
  │  3. HTTP 请求 ──────────────────────→│  处理推理请求
  │     POST /v1/chat/completions        │
  │                                      │
  │  ←──────────────────── HTTP 响应     │
```

#### E.2 启动代码

```java
public class AiServerLauncher {
    private static final String INNER_JAR = "META-INF/jarjar/JavaServer-Fat-all.jar";
    private static Process aiServerProcess;

    private static Path extractServerJar() throws IOException {
        Path target = Path.of(".").toAbsolutePath().resolve("JavaServer-Fat-all.jar");
        if (Files.exists(target)) return target;
        try (InputStream is = AiServerLauncher.class.getClassLoader()
                .getResourceAsStream(INNER_JAR)) {
            if (is == null) throw new FileNotFoundException("未找到: " + INNER_JAR);
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    public static void startAiServer(String modelPath, int port) throws Exception {
        Path jar = extractServerJar();
        List<String> cmd = List.of(
            ProcessHandle.current().info().command().orElse("java"),
            "-Xmx2G",
            "-cp", jar.toAbsolutePath().toString(),
            "com.javallamaserver.core.ServerApp",
            "-m", modelPath,
            "--port", String.valueOf(port),
            "-ngl", "999"
        );
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        aiServerProcess = pb.start();
    }

    public static void stopAiServer() {
        if (aiServerProcess != null && aiServerProcess.isAlive()) {
            aiServerProcess.destroy();
            try {
                if (!aiServerProcess.waitFor(10, TimeUnit.SECONDS))
                    aiServerProcess.destroyForcibly();
            } catch (InterruptedException e) {
                aiServerProcess.destroyForcibly();
            }
        }
    }
}
```

> **注意：** 不再需要 `-Djava.library.path`，`NativeLibraryLoader` 会自动从 JAR 内解压 DLL。

#### E.3 HTTP 客户端编码要点

隔离进程模式下，模组侧通过 HTTP 调用 AI 服务。**务必注意编码**：

```java
// ✅ 正确：显式指定 UTF-8
conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
os.write(jsonBody.getBytes("UTF-8"));

// ❌ 错误：使用平台默认编码（Windows 上是 GBK）
os.write(jsonBody.getBytes());
```

| 环节 | 正确做法 |
|------|---------|
| JSON 序列化 | `GsonBuilder().disableHtmlEscaping()` |
| 请求头 | `Content-Type: application/json; charset=utf-8` |
| 请求体 | `jsonBody.getBytes("UTF-8")` |
| 响应读取 | `new InputStreamReader(is, "UTF-8")` |

#### E.4 生命周期管理

```java
// Fabric
ServerLifecycleEvents.SERVER_STOPPING.register(server -> AiServerLauncher.stopAiServer());

// Forge / NeoForge
MinecraftForge.EVENT_BUS.addListener((FMLServerStoppingEvent e) -> AiServerLauncher.stopAiServer());

// 兜底 ShutdownHook
Runtime.getRuntime().addShutdownHook(new Thread(() -> AiServerLauncher.stopAiServer()));
```
