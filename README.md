English | [中文](./README_zh.md)

# Tianshu Libraries

[CurseForge](#) | [Modrinth](#) | [GitHub](https://github.com/ERO-Dragon/Tianshu-libs) | [Gitee](https://gitee.com/tianshu-ai/tianshu-libs)

---

Tianshu Libraries provides local AI capabilities for Minecraft, including LLM inference, speech recognition, and embedding vectorization. It serves as a core dependency backend for AI-driven mods.

## ⚠️ Important Notes

- **No Models Included** - This mod only provides inference services. You must download the AI models yourself (GGUF format for LLM, ONNX format for ASR).
- **Native Libraries Bundled** - Contains native libraries (`.dll` / `.so`) required by JJML, Sherpa-ONNX, and ONNX Runtime, loaded at runtime via each dependency's standard loading mechanism.
- **High Resource Usage** - Local AI inference is computationally intensive. We recommend using an NVIDIA RTX 20 series or higher GPU (≥6GB VRAM) for a better performance experience.

## Supported Versions

- Minecraft 1.21.1
- NeoForge 21.1.x

## ⚙️ Installation

Simply drop the downloaded mod JAR (`tianshu_libs-x.x.x.jar`) into your `.minecraft/mods` folder.

## Features

- **LLM Inference** - Local large language model chat powered by JJML (llama.cpp) with Vulkan acceleration.
- **Speech Recognition** - Ultra-low latency voice-to-text powered by Sherpa-ONNX.
- **Embedding** - High-performance text vectorization for RAG (Retrieval-Augmented Generation) memory engines.
- **Dual-Lane Inference** - Concurrent execution threads for chat lanes and task background lanes to prevent game stuttering.

## Related Projects

- [JJML (llama.cpp Java bindings)](https://github.com/argeo/argeo-jjml) - *Vulkan backend build.*
- [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)

## ⚖️ License & Open-Source Compliance

- **This Mod**: MIT License
- **Sherpa-ONNX**: Apache-2.0 License
- **ONNX Runtime**: MIT License
- **JJML**: GNU Lesser General Public License v2.1 (LGPL-2.1)

> ### ℹ️ LGPL Compliance Notice
> This mod includes JJML (LGPL-2.1) with its Vulkan build.
>
> JJML is distributed as a standalone dependency JAR via NeoForge's Jar-in-Jar mechanism and loaded by the loader at runtime.