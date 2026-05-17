# Java Llama Server

Java Llama Server 是一个基于 Java 17 的本地大语言模型 HTTP 服务端，底层通过 jjml 调用 llama.cpp / ggml native runtime，HTTP 层使用 Javalin，JSON 处理使用 Gson。

项目目标是把本地 LLM 推理服务独立成一个 Java 进程，便于 Minecraft Java 模组或其他 Java 应用通过 HTTP/SSE 调用，避免把 native 推理库直接加载进主应用进程。

## 功能特性

- 提供兼容 OpenAI Chat Completions 风格的 HTTP 接口
- 支持流式输出
- 支持 chat / task 双 lane 推理架构
- 支持长期记忆 RAG 和静态知识 RAG
- 支持按 world/profile 组织多世界、多角色 RAG 数据
- 使用 Shadow 打包为可运行 fat jar
- native 推理库与主应用进程隔离，降低 JNI 崩溃对宿主应用的影响

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 语言 | Java 17 |
| HTTP 服务 | Javalin 5.6.3 |
| JSON | Gson 2.10.1 |
| 日志 | SLF4J 2.x + slf4j-simple |
| 构建 | Gradle Kotlin DSL |
| 打包 | Shadow Jar |
| LLM JNI 绑定 | jjml / argeo-jjml |
| Native runtime | llama.cpp / ggml / whisper 相关动态库 |

## 第三方依赖说明

本仓库不包含以下内容：

- argeo-jjml 源码副本
- 本地编译的 jjml jar
- llama.cpp / ggml / whisper native 动态库
- `.gguf` 等模型文件
- RAG 运行数据和向量索引缓存

这些内容需要使用者自行准备。

这样处理的原因是：

- argeo-jjml 是独立开源项目，本项目只作为使用方依赖它
- native 动态库是平台相关二进制产物，不适合直接放入源码仓库
- 模型文件通常体积很大，并且有各自的 license
- RAG 数据和记忆文件通常属于本地运行数据，不应进入公共仓库

## 本地依赖目录

当前 Gradle 配置期望本地存在 jjml jar：

```text
libs/
└── org.argeo.jjml-2.1.2.0006-7f18908.jar
```

运行时还需要准备 native 动态库目录，例如 Windows 下：

```text
libs/
└── jjml-all/
    ├── llama.dll
    ├── ggml.dll
    ├── ggml-base.dll
    ├── ggml-cpu-x64.dll
    ├── Java_org_argeo_jjml_llm.dll
    ├── Java_org_argeo_jjml_ggml.dll
    ├── Java_org_argeo_jjml_mtmd.dll
    ├── Java_org_argeo_jjml_whisper.dll
    └── whisper.dll
```

Linux/macOS 下请放置对应平台的 `.so` 或 `.dylib` 文件，并在启动时通过 `java.library.path` 指向 native 库目录。

## 构建

确保本地已经安装 JDK 17，并且已经把自行构建或获取的 jjml jar 放到 `libs/` 目录。

Windows：

```powershell
.\gradlew.bat build
```

Linux/macOS：

```bash
./gradlew build
```

构建完成后，可运行 jar 位于：

```text
build/libs/JavaLlamaServer-v1.0.1-all.jar
```

## 运行

基础启动示例：

```powershell
java -Djava.library.path=./libs/jjml-all -jar build/libs/JavaLlamaServer-v1.0.1-all.jar --model D:/models/model.gguf
```

Linux/macOS 示例：

```bash
java -Djava.library.path=./libs/jjml-all -jar build/libs/JavaLlamaServer-v1.0.1-all.jar --model /path/to/model.gguf
```

`--model` 指向本地模型文件路径。模型文件不由本项目下载或管理。

更多启动参数、RAG 目录结构和接口示例请参考 [USAGE.md](USAGE.md)。

## RAG 运行数据

项目支持按世界和 profile 组织 RAG 数据。典型目录结构如下：

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
```

这些文件属于运行数据，默认不建议提交到 Git 仓库。

运行过程中生成的索引缓存包括：

```text
.javallama-rag-index/
.javallama-memory-index/
```

这些目录也属于本地缓存，不应提交。

## 开源仓库边界

本仓库只维护 Java Llama Server 自身源码和构建配置。

不随仓库分发的内容包括：

```text
argeo-jjml/
libs/*.jar
libs/jjml-all/
*.gguf
llm_rag/
.javallama-rag-index/
.javallama-memory-index/
```

如果你需要发布开箱即用版本，建议将完整运行包、jjml jar、native 动态库放到 GitHub Release，而不是直接提交到源码仓库。

## License

本项目采用 [MIT License](LICENSE)。

本项目依赖的第三方项目有各自的 license。分发源码、fat jar 或其他二进制包时，请同时遵守 argeo-jjml、llama.cpp、ggml、whisper、Javalin、Gson、SLF4J 等项目的许可要求。
