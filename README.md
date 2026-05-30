# 天枢 AI 能力库 (Tianshu Libraries)

- 为 Minecraft 天枢模组提供本地 AI 推理能力及ASR，TTS库支持

天枢 libs 库是一个 Minecraft NeoForge 模组，为天枢 AI 系统提供底层 AI 能力支持。底层通过 JJML 调用 llama.cpp / ggml 实现 LLM 推理，通过 Sherpa-ONNX 实现语音识别，通过 ONNX Runtime 实现通用神经网络加速。

## 核心能力

| 能力 | 技术栈 | 用途 |
|------|--------|------|
| **LLM 推理** | JJML (llama.cpp) | 本地大语言模型对话、任务处理 |
| **语音识别 (ASR)** | Sherpa-ONNX | 语音转文字 |
| **向量化** | JJML Embedding | 文本向量提取，用于 RAG |
| **通用 ML** | ONNX Runtime | 通用神经网络推理加速 |

## 技术特性

- **零外部依赖**：所有 native 库（DLL/SO/DYLIB）打包在 JAR 内部，运行时自动解压加载
- **嵌入式调用**：模组侧可直接通过 Java API 调用，零网络延迟
- **多 lane 推理**：支持 chat / task 双通道并发推理
- **RAG 支持**：长期记忆 RAG + 静态知识 RAG，按 world/profile 组织数据

## 架构

```
+-------------------------------------------------------------+
|                   Minecraft 主进程 (JVM)                    |
|  +-------------+                                            |
|  |  天枢核心模组  |  <- depends tianshu_libs                |
|  +-------------+                |                           |
|                              |                              |
|                              v                              |
|                  +---------------------+                   |
|                  |  TianshuLibsMod     |                   |
|                  |  (入口类)            |                   |
|                  +---------------------+                   |
|                              |                              |
|                              v                              |
|                  +---------------------+                   |
|                  |NativeLibraryLoader  |                   |
|                  | (自动解压加载 DLL)   |                   |
|                  +---------------------+                   |
|           +-------------+-------------+-------------+      |
|           v             v             v            v       |
|    +-----------+  +-----------+  +-------------+          |
|    |  JJML     |  | Sherpa-   |  | ONNX Runtime|          |
|    | (LLM/Emb) |  |   ONNX    |  |             |          |
|    +-----------+  +-----------+  +-------------+          |
+-------------------------------------------------------------+
```

## 构建

```powershell
.\gradlew.bat build
```

构建产物位于：`build/libs/tianshu-libs-1.0.1.jar`

## 冒烟测试

项目内置了 `NativeLibsSmokeTest` 用于验证所有 native 库正常工作：

```bash
java -cp "build/libs/tianshu-libs-1.0.1-all.jar" \
    com.rheinmetal.tianshu.libs.nativelib.NativeLibsSmokeTest
```

预期输出：
```
==========================================
   Native Libraries Comprehensive Smoke Test
==========================================

[STEP 0] Native libraries loaded: OK

=== Test Group 1: JJML (LlamaCpp) ===
[JJML-1] LlamaCppModel.defaultModelParams(): OK
[JJML-2] LlamaCppContext.defaultContextParams(): OK
[GROUP 1] JJML: PASSED

=== Test Group 2: Sherpa-ONNX ===
[Sherpa-1] Class.forName(OfflineRecognizer): OK
[Sherpa-2] Class.forName(OfflineStream): OK
[GROUP 2] Sherpa-ONNX: PASSED

=== Test Group 3: ONNX Runtime ===
[ONNX-1] OrtEnvironment.getEnvironment(): OK
[ONNX-2] OnnxTensor.createTensor(): OK
[GROUP 3] ONNX Runtime: PASSED

*** ALL TESTS PASSED ***
```

## 依赖声明

在其他模组中依赖天枢 libs：

```toml
[[dependencies.tianshu_libs]]
    modId="tianshu_libs"
    versionRange="[1.0.1,)"
    ordering="AFTER"
    side="CLIENT"
```

## 打包的 Native 库

| 库 | 路径 | 说明 |
|----|------|------|
| GGML | `natives/windows-x86_64/ggml*.dll` | ggml 核心 + CPU 后端 |
| Llama.cpp | `natives/windows-x86_64/llama.dll` | LLM 推理引擎 |
| Whisper | `natives/windows-x86_64/whisper.dll` | 音频编码 |
| Sherpa-ONNX | `natives/windows-x86_64/sherpa-onnx*.dll` | 语音识别 |
| ONNX Runtime | 通过 Sherpa 间接加载 | 神经网络加速 |

## 许可证

- 本项目：[MIT](LICENSE)
- JJML / llama.cpp / ggml：各有其独立许可证
- Sherpa-ONNX：Apache 2.0
- ONNX Runtime：MIT

分发时请同时遵守各第三方项目的许可要求。