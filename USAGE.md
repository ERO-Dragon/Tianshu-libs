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

所有参数与官方 `llama-server.exe` 保持对齐：

| 参数 | 短格式 | 类型 | 默认值 | 说明 |
|------|--------|------|--------|------|
| `--model` | `-m` | String | *(内置测试路径)* | **GGUF 模型文件路径（必填）** |
| `--context` | `-c` | int | `4096` | 上下文窗口大小（tokens 数） |
| `--threads` | `-t` | int | CPU 核心数 | 推理线程数 |
| `--n-gpu-layers` | `-ngl` | int | `999` | 卸载到 GPU 的层数（999 = 全部卸载） |
| `--host` | 无 | String | `127.0.0.1` | 绑定地址 |
| `--port` | 无 | int | `8080` | 绑定端口 |
| `--alias` | 无 | String | 模型文件名 | 模型别名（用于 API 返回的 model 字段） |
| `--help` | `-h` | 无 | 无 | 打印帮助信息 |

**参数解析源码参考**（[ServerApp.java](src/main/java/com/javallamaserver/core/ServerApp.java)）：

```java
for (int i = 0; i < args.length; i++) {
    switch (args[i]) {
        case "-m", "--model"      -> modelPath = args[++i];
        case "-c", "--context"    -> ctxSize = Integer.parseInt(args[++i]);
        case "-t", "--threads"    -> threads = Integer.parseInt(args[++i]);
        case "--host"             -> host = args[++i];
        case "--port"             -> port = Integer.parseInt(args[++i]);
        case "--alias"            -> alias = args[++i];
        case "-ngl", "--n-gpu-layers" -> gpuLayers = Integer.parseInt(args[++i]);
        case "-h", "--help"       -> { printUsage(); return; }
    }
}
```

### 4.3 安全限制

出于安全考虑，**禁止绑定到 `0.0.0.0`**。如果尝试绑定到 `0.0.0.0`，服务端会拒绝启动：

```java
if (host.equals("0.0.0.0")) {
    System.err.println("[ServerApp] SECURITY: Binding to 0.0.0.0 is forbidden. Use 127.0.0.1.");
    System.exit(1);
}
```

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

所有 API 端点 **100% 兼容 OpenAI 标准格式**，可以直接使用任何支持 OpenAI API 的客户端库（如 `openai-java`、Python `openai` 库等）进行调用。

### 5.1 端点总览

| 方法 | 路径 | 说明 | 对应官方 llama-server |
|------|------|------|----------------------|
| `POST` | `/v1/chat/completions` | 聊天补全（支持流式/非流式） | ✅ 完全兼容 |
| `GET` | `/v1/models` | 获取可用模型列表 | ✅ 完全兼容 |
| `GET` | `/health` | 健康检查 | ✅ 完全兼容 |

### 5.2 POST /v1/chat/completions

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messages` | Array | ✅ | 消息列表，每条包含 `role` 和 `content` |
| `stream` | Boolean | ❌ | 是否流式输出，默认 `false` |
| `temperature` | Float | ❌ | 采样温度，默认 `0.0`（贪心解码） |
| `max_tokens` | Integer | ❌ | 最大生成 token 数 |

**支持的 `role` 值：** `system`、`user`、`assistant`

#### 非流式请求示例（stream=false）

**请求：**

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{
    "messages": [
      {"role": "system", "content": "你是一个有帮助的助手。"},
      {"role": "user", "content": "用三句话介绍量子计算。"}
    ],
    "stream": false,
    "temperature": 0.7
  }'
```

**响应：**

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
        "content": "量子计算是利用量子力学原理进行信息处理的计算方式。与经典计算机使用比特（0或1）不同，量子计算机使用量子比特（qubit），可以同时处于0和1的叠加态。这种特性使得量子计算机在某些特定问题上具有指数级的计算优势。"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 0,
    "completion_tokens": 0,
    "total_tokens": 0
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
- 每个 chunk 的 `delta.content` 包含本次生成的 token 文本
- 当生成结束时，发送 `finish_reason: "stop"` 的 chunk
- 最后发送 `usage` chunk（包含 token 统计信息）
- 最终以 `data: [DONE]\n\n` 标记流结束

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
{"status": "ready"}
```

HTTP 状态码：`200`

**模型未加载时的响应：**

```json
{"status": "loading"}
```

HTTP 状态码：`503`

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
   │ (任务模型)     │                  │  (工作线程)    │
   └──────────────┘                   └───────┬──────┘
                                              │
                                     ┌────────▼────────┐
                                     │   LlamaEngine    │
                                     │ (单例，模型持有者) │
                                     └────────┬────────┘
                                              │ 创建
                                     ┌────────▼────────┐
                                     │  LlamaCppContext │
                                     │  (jjml 推理上下文) │
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
    │   ├── LlamaEngine.java      # LLM 推理引擎（单例）
    │   ├── InferenceTask.java    # 推理任务模型
    │   ├── SamplerConfig.java    # 采样参数配置
    │   └── TaskExecutor.java     # 推理任务执行器（单线程工作循环）
    └── web/
        ├── ChatController.java   # 聊天 API 控制器（流式+同步）
        └── SseConnectionManager.java  # SSE 连接管理器
```
