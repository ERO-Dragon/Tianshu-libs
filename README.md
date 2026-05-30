English | [中文](./README_zh.md)

# Tianshu Libraries

[CurseForge](#) | [Modrinth](#) | [GitHub](https://github.com/tianshu-ai/tianshu-libs) | [Gitee](https://gitee.com/tianshu-ai/tianshu-libs)

---

Tianshu Libraries provides AI capabilities for Minecraft, including LLM inference, speech recognition, and embedding.

## ⚠️ Important Notes

- **No Models Included** - This mod only provides inference services and does not provide model download services. You need to download AI models (GGUF for LLM, ONNX for ASR) yourself.
- **Native Libraries Bundled** - This mod includes JJML, Sherpa-ONNX, and ONNX Runtime native libraries (DLL/SO), which are required for AI inference.
- **High Resource Usage** - AI inference is computationally intensive and requires a decent GPU. For LLM services, a minimum of RTX 20-series GPU with 6GB VRAM is recommended.

## Supported Versions

- Minecraft 1.21.1
- NeoForge 21.1.x

## Installation

Drop the JAR into your `mods` folder.

## Features

- **LLM Inference** - Local large language model chat powered by JJML (llama.cpp)
- **Speech Recognition** - Voice-to-text using Sherpa-ONNX
- **Embedding** - Text vectorization for RAG
- **Dual Lane Inference** - Chat and task lanes run concurrently

## Related Projects

- [JJML (llama.cpp Java bindings)](https://github.com/argeo/argeo-jjml)
- [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)

## License

- This mod: LGPL-3.0
- JJML: LGPL-2.1
- Sherpa-ONNX: Apache-2.0
- ONNX Runtime: MIT