简体中文 | [English](./README.md)

# 天枢 AI 能力库 (Tianshu Libraries)

[CurseForge](https://www.curseforge.com/minecraft/mc-mods/tianshu-library/preview) | [Modrinth](https://modrinth.com/mod/tianshu-library) | [GitHub](https://github.com/ERO-Dragon/Tianshu-libs) | [Gitee](https://gitee.com/tianshu-ai/tianshu-libs)

---

天枢 AI 能力库为 Minecraft 提供本地 AI 能力，包括大模型推理、语音识别和文本向量化。它作为 AI 驱动模组的核心依赖后端。

## ⚠️ 注意事项

- **不包含模型** - 本模组仅提供推理服务。您必须自行下载 AI 模型（LLM 使用 GGUF 格式，语音识别使用 ONNX 格式）。
- **包含原生库** - 包含 JJML、Sherpa-ONNX 与 ONNX Runtime 所需的原生库（`.dll` / `.so`），由各依赖通过标准加载机制在运行时调用。
- **高资源占用** - 本地 AI 推理计算密集。建议使用 NVIDIA RTX 20 系及以上显卡（≥6GB 显存）以获得较好的性能体验。


## ⚙️ 安装

只需将下载的模组 JAR 文件（`tianshu_libs-x.x.x.jar`）放入您的 `.minecraft/mods` 文件夹中。

## 功能特性

- **LLM 推理** - 本地大语言模型对话，基于 JJML (llama.cpp) 并支持 Vulkan 加速。
- **语音识别** - 超低延迟语音转文字，基于 Sherpa-ONNX。
- **文本向量化** - 高性能文本向量提取，用于 RAG（检索增强生成）记忆引擎。
- **双通道推理** - Chat 通道和 Task 后台通道并发执行线程，防止游戏卡顿。

## 相关项目

- [JJML (llama.cpp Java 绑定)](https://github.com/ERO-Dragon/argeo-jjml/tree/EroDragon/jjml) - *使用 Vulkan 后端构建版本。*
- [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)

## ⚖️ 许可证与开源合规性

- **本模组**: MIT 许可证
- **Sherpa-ONNX**: Apache-2.0 许可证
- **ONNX Runtime**: MIT 许可证
- **JJML**: GNU Lesser General Public License v2.1 (LGPL-2.1)

> ### ℹ️ LGPL 合规声明
> 本模组包含 JJML（LGPL-2.1）及其 Vulkan 构建版本。
>
> JJML 作为独立依赖 JAR 通过 NeoForge Jar-in-Jar 机制随模组分发，并在运行时由加载器加载。
