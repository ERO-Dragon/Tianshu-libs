# Java-Llama-Server 使用文档

> **纯 Java 实现的本地大模型推理 HTTP 服务端** —— 完美替代 C++ 版 llama-server.exe，专为 Minecraft Java 模组环境深度定制

---

## 目录

1. [项目简介](#1-项目简介)
2. [环境准备与构建规范](#2-环境准备与构建规范)
3. [核心架构：原生库部署与自解压逻辑](#3-核心架构原生库dllso部署与自解压逻辑)
4. [启动指南与参数说明](#4-启动指南与参数说明)
5. [API 接口说明](#5-api-接口说明)
6. [核心实战：Minecraft 模组集成指南](#6-核心实战minecraft-模组集成指南自解压隔离启动)

---

## 1. 项目简介

### 1.1 核心价值

Java-Llama-Server 是一个用 **纯 Java** 编写的本地大语言模型推理 HTTP 服务端，底层基于 **jjml**（Java 对 llama.cpp 的 JNI 绑定）。它的核心使命是完美替代官方 C++ 版本的 `llama-server.exe`，并专为 Minecraft Java 模组环境进行深度定制。

**玩家的终极体验：**

- **丢一个 JAR 进 mods 文件夹即可运行** —— 无需额外配置环境变量，无需安装 VC++ 运行库，无需编译 C++ 代码
- **对外 API 100% 兼容 OpenAI 标准格式** —— 任何支持 OpenAI API 的客户端/库均可无缝对接
- **行为与官方 llama-server.exe 完全对齐** —— 命令行参数、SSE 流式输出格式、模型管理端点均保持一致

### 1.2 核心卖点：JNI 物理隔离

本项目采用 **"自解压隔离启动架构"**，这是解决 JNI 崩溃连坐问题的工业级方案：

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

**关键优势：**

| 问题 | 传统方案（JNI 直连） | 本项目（隔离架构） |
|------|---------------------|-------------------|
| C++ 底层崩溃 | 整个游戏闪退 | 仅 AI 服务进程退出，游戏继续运行 |
| JNI 内存泄漏 | 吃掉 MC 的堆内存 | 独立进程有独立 `-Xmx`，互不干扰 |
| 依赖冲突 | jjml 与其他模组的 JNI 库冲突 | 完全隔离的 classpath 和 library path |
| 多加载器适配 | 需要分别为 Fabric/Forge/NF 写启动逻辑 | 全部使用标准 Java API，一份代码通吃 |

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

## 3. 核心架构：原生库（DLL/SO）部署与自解压逻辑

### 3.1 打包时的存放位置
编译出的底层动态链接库必须放在该模组项目的标准资源目录下，模组加载器会在启动时自动将其提取到安全的临时沙箱目录中：

```
src/main/resources/
└── META-INF/
    └── nativess/
        ├── windows-x86_64/
        │   ├── llama.dll
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
        │   ├── Java_org_argeo_jjml_ggml.dll
        │   ├── Java_org_argeo_jjml_llm.dll
        │   ├── Java_org_argeo_jjml_mtmd.dll
        │   ├── Java_org_argeo_jjml_whisper.dll
        │   └── whisper.dll
```
> **原理解释**：加载器不会将其解压到固定的 `game/native` 文件夹，而是解压到系统临时目录（如 `AppData/Local/Temp`）下的沙箱中，并通过修改 `java.library.path` 系统属性来暴露路径。因此，在代码中必须通过解析 `java.library.path` 来动态获取真实位置（见 6.2 节代码）。

### 3.2 运行时的"自解压"设计思想

**为什么需要自解压？** 这是一个关键问题。

当我们通过 `ProcessBuilder` 拉起一个独立的 JVM 进程时，新进程面对的文件系统环境与宿主模组完全不同：

```
❌ 新进程不认识 Fabric 的内存虚拟文件系统（如 Fabric 的嵌套 JAR 协议）
❌ 新进程不认识 META-INF/jarjar/ 这种模组加载器特有的嵌套结构
❌ 新进程无法通过 ClassLoader.getResource() 访问到模组内部的 JAR
```

因此，模组代码在启动 HTTP Server 之前，**必须执行"自解压"**。

**自解压的两个核心目标：**

```
目标 1：将打包好的 JavaLlamaServer.jar（内含平铺的 Server 与 jjml 代码）从模组嵌套包中提取到物理硬盘
        → 提取到 ./Tianshu_AI/cache目录下

目标 2：获取当前 Minecraft JVM 已经加载的 llama.dll 的绝对临时路径
        → 通过解析 java.library.path 系统属性获得

目标 3：将上述路径传递给新 JVM 进程，使其能正确加载原生库
        → 通过 -Djava.library.path=<路径> JVM 参数传递
```

### 3.3 代码中的原生库加载机制

在 [LlamaEngine.java](src/main/java/com/javallamaserver/llm/LlamaEngine.java) 中，通过 jjml 的静态初始化块加载原生库：

```java
static {
    LlamaCppNative.ensureLibrariesLoaded();
}
```

`LlamaCppNative.ensureLibrariesLoaded()` 会调用 `System.loadLibrary()` 加载 JNI 原生库。JVM 会按照以下顺序搜索：

1. `java.library.path` 系统属性指定的路径
2. `user.dir` 当前工作目录
3. 操作系统默认的库搜索路径

这就是为什么在拉起独立进程时，**必须正确设置 `-Djava.library.path`** 的根本原因。

---

## 4. 启动指南与参数说明

### 4.1 快速启动

#### 独立模式（非模组）(仅供参考)

```bash
# 基本启动（使用默认参数）
java -Djava.library.path=./libs/jjml-all -jar JavaLlamaServer.jar -m /path/to/model.gguf

# 完整参数启动
java -Djava.library.path=./libs/jjml-all ^
     -Xmx4G ^
     -jar JavaLlamaServer.jar ^
     -m D:\models\Qwen3-4B-Q4_K_M.gguf ^
     -c 4096 ^
     -t 8 ^
     -ngl 999 ^
     --host 127.0.0.1 ^
     --port 8080 ^
     --alias qwen3-4b
```

> **注意：** `-Djava.library.path` 必须指向包含 `llama.dll`（Windows）或 `libllama.so`（Linux）的目录。当前项目中这些文件位于 `libs/jjml-all/` 目录下。

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
java -Djava.library.path=./libs/jjml-all \
     -jar JavaLlamaServer.jar \
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
java -Djava.library.path=./libs/jjml-all \
     -jar JavaLlamaServer.jar \
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
java -Djava.library.path=./libs/jjml-all \
     -jar JavaLlamaServer.jar \
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
java -Djava.library.path=./libs/jjml-all \
     -jar JavaLlamaServer.jar \
     --model /path/to/chat-model.gguf \
     --embedding-model /path/to/bge-large-zh-v1.5.gguf \
     --rag-root-path /path/to/llm_rag \
     --static-rag-top-k 4 \
     --dynamic-rag-top-k 4 \
     --memory-rag-refresh-interval-ms 1000 \
     --rag-profile-refresh-interval-ms 1000 \
     --world-static-rag-scan-interval-ms 5000
```

启用 `--rag-root-path` 后，服务端优先使用多世界 profile RAG 架构。请求通过 `world` 和 `profile` 定位具体世界、模组和 agent；静态 RAG 按 `static_scope` 决定检索当前模组、当前世界所有模组或指定模组列表。旧的 `--static-rag-path` / `--memory-rag-path` 不再作为主路径使用。

#### Minecraft 模组侧推荐启动方式

模组侧建议使用 `ProcessBuilder` 启动独立 JVM，并显式传入：

```text
-Djava.library.path=<宿主侧解压出来的 native 库目录>
-jar <解压出来的 JavaLlamaServer.jar>
--model <聊天模型绝对路径>
--embedding-model <embedding 模型绝对路径>
--rag-root-path <llm_rag 总根目录>
--port <本地端口>
--chat-max-queue-size 2
--task-max-queue-size 1
--task-suspend-on-chat true
--request-timeout-seconds 120
```

对于 Minecraft 场景，`--chat-max-queue-size` 和 `--task-max-queue-size` 都不建议过大。chat lane 通常 `1~4` 更适合，task lane 通常建议为 `1`，避免后台压缩、摘要或脚本任务堆积并长期占用资源。

### 4.3 模型 Profile 自动检测

服务端启动时会自动检测模型类型，当前支持：

| Profile | 检测条件 | thinking 控制方式 |
|---|---|---|
| `qwen3.5` | 模型路径或元数据包含 `qwen3.5`、`qwen3-5`、`qwen35` | `thinking: true` 时自动设置采样参数 `temperature=1.0, top_p=0.95, top_k=20`；默认不思考 |
| `qwen3` | 模型路径或元数据包含 `qwen3` | `thinking: true` 注入 `/think`，`false` 注入 `/no_think` |
| `deepseek-r1` | 模型路径或元数据包含 `deepseek` 和 `r1` | 不需要特殊控制，模型自带思考 |
| `generic` | 其他所有模型 | `thinking` 字段不生效 |

也可以通过 `--model-profile qwen3.5` 强制指定，跳过自动检测。

### 4.4 安全限制

出于安全考虑，当前服务端只允许绑定到 `127.0.0.1`。如果传入其他 host，启动配置校验会拒绝启动。

这是为了防止 AI 推理服务意外暴露到局域网/公网。

### 4.4 Java 版本解耦说明

> **即使游戏本体运行在 Java 21（如 MC 1.20.5+），AI 服务进程依然可以运行在 Java 17及以上版本 上，互不干扰。**

AI 服务进程最低要求 JDK 17，完全兼容 JDK 21 及以上版本。Java 具备严格的向后兼容性，且 JNI 底层库只要由低版本编译，即可在高版本 JVM 运行。因此，强烈推荐直接复用当前游戏本体的 Java 环境（见 6.2 节代码），无需在电脑上额外配置特定版本的 JDK，实现 MC 1.18~最新版本的通杀。

```java
// 示例：MC 运行在 Java 21，但 AI 服务使用 Java 17
String javaPath = minecraft自己的Java路径;
ProcessBuilder pb = new ProcessBuilder(
    javaPath,                          // 指定 JDK 17
    "-Xmx2G",                            // 独立内存，不抢 MC
    "-Djava.library.path=" + dllPath,    // DLL 路径
    "-cp", classpath,                    // 独立 classpath
    "com.javallamaserver.core.ServerApp", // 入口类
    "-m", modelPath,                     // 模型路径
    "--port", "8080"                     // 端口
);
```


## 5. API 接口说明

本服务端兼容 OpenAI Chat Completions 的核心请求/响应格式，适合直接被 Minecraft 模组侧通过 HTTP 调用。当前重点支持聊天补全、流式输出、模型列表、健康检查、embedding 和 RAG 场景。

### 5.1 端点总览

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/v1/chat/completions` | 聊天补全与后台推理任务，支持 `lane=chat/task`、流式/非流式、thinking 控制、动态 RAG；长期记忆 RAG 仅支持 `chat` lane |
| `POST` | `/v1/embeddings` | 文本向量化，需要启动时配置 embedding 模型 |
| `GET` | `/v1/models` | 获取当前加载的聊天模型和 embedding 模型 |
| `GET` | `/health` | 健康检查，包含 chat/task 队列、当前 lane、task 挂起状态和静态 RAG 状态 |

### 5.2 POST /v1/chat/completions

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messages` | Array | ✅ | 消息列表，每条包含 `role` 和 `content` |
| `lane` | String | ❌ | 推理 lane。可选 `chat` 或 `task`；不传默认 `chat`。`chat` 用于玩家实时对话，`task` 用于压缩、摘要、记忆整理等后台任务 |
| `stream` | Boolean | ❌ | 是否流式输出，默认 `false`。`chat` 和 `task` lane 都支持流式；是否启用 RAG 由 `use_rag` 控制 |
| `temperature` | Float | ❌ | 采样温度 |
| `max_tokens` | Integer | ❌ | 最大生成 token 数 |
| `thinking` | Boolean | ❌ | 是否开启模型思考。对 Qwen3 会自动注入 `/think` 或 `/no_think`；对 Qwen3.5 会自动设置推荐的采样参数（`temperature=1.0`, `top_p=0.95`, `top_k=20`）；其他模型不生效 |
| `use_rag` | Boolean | ❌ | 是否启用 RAG。`chat` lane 默认等价于 `true`；`task` lane 默认等价于 `false`。`task` 设置为 `false` 时即使传入 `dynamic_rag` 也不会执行任何 RAG；设置为 `true` 时会执行静态 RAG + dynamic RAG |
| `dynamic_rag` | Array | ❌ | 动态 RAG 条目列表，元素可以是字符串或包含 `text` 字段的对象。`chat` lane 默认参与 RAG；`task` lane 只有在 `use_rag=true` 时才会参与 RAG 处理 |
| `use_memory_rag` | Boolean | ❌ | 是否启用长期记忆 RAG。仅 `chat` lane 生效；`task` lane 会忽略该字段。未传时，如果启动时配置了 `--memory-rag-path` 或 `--rag-root-path` 且请求能定位到 memory_rag，chat 请求默认启用 |
| `memory_rag_token_budget` | Integer | ❌ | 长期记忆注入 prompt 的近似 token 预算，默认 `1000`。服务端在预算内自行决定命中并注入哪些长期记忆 |
| `include_rag_hits` | Boolean | ❌ | 是否在响应中返回本次实际注入的 RAG 命中信息，默认 `true`。模组侧可根据返回的长期记忆 `uid` 更新 hit_count、last_hit_time、TTL 等状态 |
| `world` | String | ❌ | 多世界 RAG 模式下的世界目录名。启用 `--rag-root-path` 后，chat 请求可通过该字段定位 `llm_rag/<world>/` |
| `profile` | String | ❌ | 当前世界内的 RAG profile key，例如 `mod_b/guard_bob`。服务端通过 `<world>/profiles.json` 映射到具体 mod 和 agent |
| `static_scope` | String | ❌ | 静态 RAG 范围：`none` 不使用、`mod` 当前 profile 所属模组、`world` 当前世界所有模组、`list` 指定 `static_mods` |
| `static_mods` | Array | ❌ | 当 `static_scope=list` 时指定要使用的模组静态 RAG 列表 |

**支持的 `role` 值：** `system`、`user`、`assistant`

#### 请求示例：带多 system、thinking 和动态 RAG

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{
    "messages": [
      {"role": "system", "content": "你是 Minecraft 世界中的村民铁匠。"},
      {"role": "system", "content": "当前时间是夜晚，玩家在村庄附近。"},
      {"role": "user", "content": "我现在该做什么？"}
    ],
    "temperature": 0.6,
    "stream": true,
    "thinking": true,
    "use_memory_rag": true,
    "memory_rag_token_budget": 1000,
    "include_rag_hits": true,
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

### 5.3 GET /v1/models

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

### 5.4 GET /health

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

### 5.5 POST /v1/embeddings

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

### 5.6 错误响应格式

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

## 6. 核心实战：Minecraft 模组集成指南（自解压隔离启动）

> **本章是重中之重**，详细阐述这套工业级架构的落地代码流程。

### 6.1 工程代码结构设计

在同一个 IDEA 工程中，需要维护 **两个逻辑包**，职责完全分离：

```
src/main/java/com/yourname/
├── mod/                            ← 宿主模组代码（运行在 MC 主进程）
│   ├── YourMod.java                # 模组主类，注册生命周期事件
│   ├── AiServerLauncher.java       # AI 服务进程启动器（自解压 + ProcessBuilder）
│   └── AiChatClient.java           # HTTP 客户端，调用 AI 服务的 API
│
└── server/                         ← 独立服务端代码（运行在独立 JVM 进程）
    ├── ServerApp.java              # HTTP 服务入口（Javalin 启动、参数解析）
    ├── llm/
    │   ├── LlamaEngine.java        # LLM 推理引擎（jjml 封装）
    │   ├── InferenceTask.java      # 推理任务模型
    │   ├── SamplerConfig.java      # 采样参数配置
    │   └── TaskExecutor.java       # 推理任务执行器
    └── web/
        ├── ChatController.java     # 聊天 API 控制器
        └── SseConnectionManager.java  # SSE 连接管理
```

**两个包的本质区别：**

| 特征 | `com.yourname.mod`（宿主） | `com.yourname.server`（独立服务） |
|------|---------------------------|----------------------------------|
| 运行进程 | Minecraft 主 JVM | 独立子 JVM |
| 可访问 MC API | ✅ 可以 | ❌ 不可以 |
| 可访问 JNI | ❌ 不直接访问 | ✅ 通过 jjml 访问 |
| 生命周期 | 随游戏启停 | 由宿主通过 ProcessBuilder 控制 |
| 崩溃影响 | 影响整个游戏 | 仅影响 AI 功能 |

### 6.2 运行时启动三步曲

以下给出完整的 Java 示例代码，展示如何从模组端启动独立的 AI 服务进程。

#### 第 1 步：自解压依赖

将嵌套在模组内部的 JavaLlamaServer.jar 完整提取到物理硬盘，并解析出 DLL 路径：
```java
    package com.yourname.mod;

    import java.io.*;
    import java.nio.file.*;

    public class AiServerLauncher {
    // 假设模组打包时，Server JAR 被放在了 resources 的根目录下或 jarjar 下
    // 请根据实际 Fabric Loom / Forge 打包出的路径进行调整，常见如 “META-INF/jarjar/JavaServer-Fat-all.jar”
    private static final String INNER_SERVER_JAR_PATH = “META-INF/jarjar/JavaServer-Fat-all.jar”;
    private static final String EXTRACTED_SERVER_JAR_NAME = “JavaServer-Fat-all.jar”;

    /**
     * 将内嵌的 Server JAR 提取到运行目录（物理硬盘）
     */
    private static Path extractServerJar() throws IOException {
        Path runDir = Path.of(".").toAbsolutePath();
        Path targetFile = runDir.resolve(EXTRACTED_SERVER_JAR_NAME);
        
        if (Files.exists(targetFile)) {
            return targetFile; // 已经提取过，直接返回
        }

        // 通过 ClassLoader 读取模组内部嵌套的 JAR
        try (InputStream is = AiServerLauncher.class.getClassLoader().getResourceAsStream(INNER_SERVER_JAR_PATH)) {
            if (is == null) {
                throw new FileNotFoundException("无法在模组包内找到: " + INNER_SERVER_JAR_PATH);
            }
            Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[AiServer] 已将内嵌 Server 解压至物理路径: " + targetFile);
        }
        return targetFile;
    }

    /**
     * 解析当前 JVM 已加载的 llama.dll 的物理路径
     */
    private static String resolveNativeLibPath() {
        String libPath = System.getProperty("java.library.path");
        if (libPath == null || libPath.isEmpty()) throw new RuntimeException("java.library.path 未设置!");

        String[] paths = libPath.split(File.pathSeparator);
        String dllName = System.getProperty("os.name").toLowerCase().contains("win") ? "llama.dll" : "libllama.so";
        
        for (String path : paths) {
            if (new File(path, dllName).exists()) {
                return new File(path).getAbsolutePath();
            }
        }
        throw new RuntimeException("在 java.library.path 中找不到原生库: " + libPath);
    }
```
#### 第 2 步：拉起独立 JVM 进程
使用 `ProcessBuilder` 直接将解压出的物理 JAR 路径丢给 `-cp`：
```java
    private static Process aiServerProcess;

    public static void startAiServer(String modelPath, int port) throws Exception {
        // 1. 提取内嵌的 Server JAR 到硬盘，并拿到绝对路径
        Path serverJarPath = extractServerJar();
        
        // 2. 获取 DLL 路径
        String nativeLibPath = resolveNativeLibPath();

        // 3. 构建启动命令
        List<String> command = new ArrayList<>();
        // 推荐直接复用游戏当前的 Java 路径，完美兼容 JDK 17 / 21
        command.add(ProcessHandle.current().info().command().orElse("java")); 
        command.add("-Xmx2G"); // 独立内存
        // ⚠️ 极其重要：必须加上双引号，防止 Windows 临时路径中的空格截断参数
        command.add("-Djava.library.path=\"" + nativeLibPath + "\""); 
        command.add("-cp");
        // 🌟 绝杀点：直接指向解压出来的 Fat JAR，不需要拼任何其他依赖！
        command.add(serverJarPath.toAbsolutePath().toString()); 
        command.add("com.javallamaserver.core.ServerApp"); // Server 入口类
        command.add("-m"); command.add(modelPath);
        command.add("--port"); command.add(String.valueOf(port));
        command.add("-ngl"); command.add("999");

        System.out.println("[AiServer] 启动命令: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.directory(new File(".")); // 建议工作目录与游戏运行目录一致
        aiServerProcess = pb.start();

        // ... (后续的日志消费线程 waitForServerReady 等代码保持原样不变) ...
    }

    /**
     * 停止 AI 服务进程
     */
    public static void stopAiServer() {
        if (aiServerProcess != null && aiServerProcess.isAlive()) {
            System.out.println("[AiServer] Stopping AI server process...");
            aiServerProcess.destroy();
            try {
                if (!aiServerProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    aiServerProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                aiServerProcess.destroyForcibly();
            }
        }
    }

```
### 6.3 三端通用与生命周期管理

#### 为什么一份代码通吃三大加载器？

上述"自解压 + ProcessBuilder"逻辑 **全部使用 `java.io` 和 `java.lang` 的标准 API**：

| 使用的 API | 所属包 | 是否模组加载器相关 |
|-----------|--------|------------------|
| `ClassLoader.getResourceAsStream()` | `java.lang` | ❌ 标准Java |
| `Files.copy()` | `java.nio.file` | ❌ 标准Java |
| `System.getProperty()` | `java.lang` | ❌ 标准Java |
| `ProcessBuilder` | `java.lang` | ❌ 标准Java |
| `getClass().getProtectionDomain()` | `java.lang` | ❌ 标准Java |

**不依赖任何模组加载器特有的 API**，因此 Fabric / Forge / NeoForge 通用一份代码。

#### 生命周期管理：防止僵尸进程

在游戏关闭时，必须调用 `process.destroy()` 终止 AI 服务进程，否则会留下僵尸进程占用 GPU 和内存。

**Fabric 生命周期监听：**

```java
public class YourMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // 模组加载时启动 AI 服务
        try {
            AiServerLauncher.startAiServer("models/qwen3-4b.gguf", 8080);
        } catch (Exception e) {
            System.err.println("Failed to start AI server: " + e.getMessage());
        }

        // 注册关闭事件
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            AiServerLauncher.stopAiServer();
        });
    }
}
```

**Forge / NeoForge 生命周期监听：**

```java
@Mod("yourmod")
public class YourMod {
    public YourMod(IEventBus modEventBus) {
        // 模组加载时启动 AI 服务
        modEventBus.addListener(this::onCommonSetup);
        // 注册关闭事件
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                AiServerLauncher.startAiServer("models/qwen3-4b.gguf", 8080);
            } catch (Exception e) {
                System.err.println("Failed to start AI server: " + e.getMessage());
            }
        });
    }

    private void onServerStopping(FMLServerStoppingEvent event) {
        AiServerLauncher.stopAiServer();
    }
}
```

**双重保险：ShutdownHook**

除了模组生命周期事件外，建议在 `startAiServer()` 中添加一个 JVM ShutdownHook 作为兜底：

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    if (aiServerProcess != null && aiServerProcess.isAlive()) {
        System.err.println("[AiServer] ShutdownHook: Force killing AI server...");
        aiServerProcess.destroyForcibly();
    }
}, "ai-server-shutdown-hook"));
```

### 6.4 模组 HTTP 请求编码陷阱

> **这是一个极易踩坑的问题，尤其是在处理中文输入时。**

#### 问题根源

当模组端构建 HTTP 请求 JSON 时，如果不显式指定 UTF-8 编码，中文字符可能会被 JVM 默认编码（如 Windows 上的 GBK）处理，导致服务端收到乱码。

#### 错误示例 ❌

```java
// ❌ 错误：没有指定编码，Windows 环境下中文会变成 GBK
OutputStream os = conn.getOutputStream();
os.write(jsonString.getBytes()); // 使用平台默认编码！
```

#### 正确示例 ✅

```java
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AiChatClient {

    private static final String SERVER_URL = "http://127.0.0.1:8080/v1/chat/completions";

    public static String chat(String userMessage) throws Exception {
        // 1. 构建 JSON（使用 Gson 的 disableHtmlEscaping 确保中文不被转义）
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        String jsonBody = gson.toJson(Map.of(
            "messages", List.of(
                Map.of("role", "user", "content", userMessage)
            ),
            "stream", false,
            "temperature", 0.7
        ));

        // 2. 发送请求
        URL url = new URL(SERVER_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);

        // ✅ 关键：Content-Type 中显式指定 charset=utf-8
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(60000);

        // ✅ 关键：getBytes() 必须传入 "UTF-8"
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes("UTF-8"));
            os.flush();
        }

        // 3. 读取响应（同样使用 UTF-8）
        int responseCode = conn.getResponseCode();
        try (InputStream is = conn.getInputStream();
             InputStreamReader isr = new InputStreamReader(is, "UTF-8")) {
            JsonObject response = gson.fromJson(isr, JsonObject.class);
            return response.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        }
    }
}
```

**编码要点总结：**

| 环节 | 正确做法 | 常见错误 |
|------|---------|---------|
| JSON 序列化 | `GsonBuilder().disableHtmlEscaping()` | 默认 Gson 会将中文转义为 `\uXXXX` |
| 请求头 | `Content-Type: application/json; charset=utf-8` | 仅写 `application/json`，不指定编码 |
| 请求体 | `jsonBody.getBytes("UTF-8")` | `jsonBody.getBytes()` 使用平台默认编码 |
| 响应读取 | `new InputStreamReader(is, "UTF-8")` | `new InputStreamReader(is)` 使用平台默认编码 |

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
| `UnsatisfiedLinkError` | DLL/SO 未找到或架构不匹配 | 检查 `-Djava.library.path` 是否指向正确的目录 |
| `Model file not found` | 模型路径错误 | 使用绝对路径指定 `.gguf` 文件 |
| 启动后立即退出 | 绑定了 `0.0.0.0` | 改为 `127.0.0.1` |
| 流式输出中文乱码 | 编码未指定 UTF-8 | 参见 6.4 节编码陷阱 |
| GPU 未被使用 | `-ngl` 设置为 0 或 CUDA 不可用 | 确保安装了 CUDA 驱动，`-ngl` 设为 999 |
| `Engine already initialized` | 重复初始化 | LlamaEngine 是单例，只能初始化一次 |
| 中文输入后模型输出异常 | 请求编码问题 | 确保 `String.getBytes("UTF-8")` 和 `charset=utf-8` |

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
    │   └── ServerApp.java        # 服务端主入口
    ├── llm/
    │   ├── LlamaEngine.java      # LLM 推理引擎（单模型权重 + chat/task 双队列）
    │   ├── InferenceLane.java    # 推理 lane 定义（chat/task）
    │   ├── LaneConfig.java       # lane 上下文、线程和队列配置
    │   ├── LaneMetrics.java      # lane 健康检查状态
    │   ├── KvCacheType.java      # KV cache 类型映射
    │   ├── InferenceTask.java    # 推理任务模型
    │   ├── SamplerConfig.java    # 采样参数配置
    │   └── TaskExecutor.java     # 推理任务执行器（chat 优先 + task 可挂起）
    └── web/
        ├── ChatController.java   # 聊天 API 控制器（流式+同步）
        └── SseConnectionManager.java  # SSE 连接管理器
```
