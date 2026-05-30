简体中文 | [English](./README.md)

# Tianshu Libraries

[CurseForge](#) | [Modrinth](#) | [GitHub](https://github.com/tianshu-ai/tianshu-libs) | [Gitee](https://gitee.com/tianshu-ai/tianshu-libs)

---

天枢 AI 能力库为 Minecraft 提供本地 AI 能力，包括大模型对话、语音识别和文本向量化。

## ⚠️ 注意事项

- **不包含模型** - 本模组仅提供推理服务，不提供模型的下载服务，需要您自行下载 AI 模型（LLM 使用 GGUF 格式，语音识别使用 ONNX 格式）。
- **包含原生库** - 本模组打包了 JJML、Sherpa-ONNX、ONNX Runtime 等原生库（DLL/SO），这些是 AI 推理所必需的。
- **高资源占用** - AI 推理计算密集，对于 CPU 和 GPU 的性能有一定要求。如需使用 LLM 服务，建议使用 RTX 20 系列及以上显卡，显存最低 6GB。

## 支持的版本

- Minecraft 1.21.1
- NeoForge 21.1.x

## 安装

将 JAR 文件放入 `mods` 文件夹即可。

## 功能特性

- **LLM 推理** - 本地大语言模型对话，基于 JJML (llama.cpp)
- **语音识别** - 语音转文字，基于 Sherpa-ONNX
- **文本向量化** - 用于 RAG 检索
- **双通道推理** - Chat 和 Task 通道并发运行

## 相关项目

- [JJML (llama.cpp Java 绑定)](https://github.com/argeo/argeo-jjml)
- [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)

## 许可证

- 本模组：LGPL-3.0
- JJML：LGPL-2.1
- Sherpa-ONNX：Apache-2.0
- ONNX Runtime：MIT