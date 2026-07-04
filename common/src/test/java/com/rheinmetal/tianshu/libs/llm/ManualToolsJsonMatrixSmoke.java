package com.rheinmetal.tianshu.libs.llm;

import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class ManualToolsJsonMatrixSmoke {
    private static final String DEFAULT_MODELS = String.join(";",
            "D:\\Minecraft\\Models\\qwen3.5-2B-MTP-unsloth\\Qwen3.5-2B-Q4_K_M.gguf",
            "D:\\Minecraft\\Models\\gemmaE4B-MTP-unsloth\\gemma-4-E4B-it-qat-UD-Q4_K_XL.gguf"
    );

    private static final String CHINESE_CONTEXT_BLOCK = """
            玩家当前在一个长期生存服务器中推进自动化基地建设。主基地包含村民交易所、刷怪塔、矿洞入口、
            红石仓储、农作物平台、下界交通和一个正在扩建的地下熔炉阵列。玩家要求所有建议都必须优先考虑
            安全、可逆性、低卡顿和多人协作，不允许破坏已有分类机，不允许让村民路径被水流或活塞阻断。
            如果需要读取技能，应该优先选择和任务规划、工程检查、Minecraft 模组开发或运行状态查询相关的技能。
            如果需要工具，应该只给出下一步工具调用意图，不要编造工具结果。
            """;

    private record Scenario(String name,
                            int targetPromptTokens,
                            boolean tools,
                            boolean thinking,
                            boolean stream,
                            boolean taskLane,
                            boolean conversationHistory) {
    }

    private record SamplerSettings(float temperature,
                                   int topK,
                                   float topP,
                                   float minP,
                                   float penaltyPresent) {
        SamplerConfig toConfig() {
            SamplerConfig sampler = new SamplerConfig();
            sampler.setTemperature(temperature);
            sampler.setTopK(topK);
            sampler.setTopP(topP);
            sampler.setMinP(minP);
            sampler.setPenaltyPresent(penaltyPresent);
            return sampler;
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> models = splitList(System.getProperty("tianshu.tools.models", DEFAULT_MODELS));
        List<Integer> targets = splitInts(System.getProperty("tianshu.tools.targets", "500,1500,3000"));
        int context = Integer.getInteger("tianshu.llm.context", 8192);
        int gpuLayers = Integer.getInteger("tianshu.llm.gpuLayers", 999);
        int chatThreads = Integer.getInteger("tianshu.llm.chatThreads", 4);
        int taskThreads = Integer.getInteger("tianshu.llm.taskThreads", 2);
        int maxTokens = Integer.getInteger("tianshu.tools.maxTokens", 512);
        int outputChars = Integer.getInteger("tianshu.tools.outputChars", 240);
        String device = System.getProperty("tianshu.llm.device", "");
        SamplerSettings nonThinkingSampler = readSamplerSettings("tianshu.tools.sampler.nonThinking");
        SamplerSettings thinkingSampler = readSamplerSettings("tianshu.tools.sampler.thinking");
        Path report = Path.of(System.getProperty(
                "tianshu.tools.output",
                "build/reports/manual-tools-json-matrix-smoke.txt"
        )).toAbsolutePath().normalize();
        if (report.getParent() != null) Files.createDirectories(report.getParent());

        writeReport(report,
                String.format("Manual toolsJson matrix smoke%ncontext=%d gpuLayers=%d maxTokens=%d device=%s%nnonThinkingSampler=%s%nthinkingSampler=%s%n",
                        context,
                        gpuLayers,
                        maxTokens,
                        device.isBlank() ? "<default>" : device,
                        nonThinkingSampler,
                        thinkingSampler));
        for (String model : models) {
            Path modelPath = Path.of(model);
            if (!Files.isRegularFile(modelPath)) {
                throw new IllegalArgumentException("Model file not found: " + model);
            }
            runModelMatrix(report, modelPath, targets, context, gpuLayers, chatThreads, taskThreads, maxTokens, outputChars, device, nonThinkingSampler, thinkingSampler);
        }
        System.out.println("Manual toolsJson matrix report: " + report);
    }

    private static void runModelMatrix(Path report,
                                       Path modelPath,
                                       List<Integer> targets,
                                       int context,
                                       int gpuLayers,
                                       int chatThreads,
                                       int taskThreads,
                                       int maxTokens,
                                       int outputChars,
                                       String device,
                                       SamplerSettings nonThinkingSampler,
                                       SamplerSettings thinkingSampler) throws Exception {
        appendReport(report, String.format("%n=== MODEL %s ===%n", modelPath));
        System.out.printf("%n[tools-matrix] loading model=%s%n", modelPath);
        JavaLlamaServer.Builder builder = JavaLlamaServer.builder()
                .model(modelPath.toString())
                .modelAlias(modelPath.getFileName().toString())
                .contextSize(context)
                .chatThreads(chatThreads)
                .taskThreads(taskThreads)
                .chatMaxQueueSize(4)
                .gpuLayers(gpuLayers)
                .requestTimeoutSeconds(600);
        if (!device.isBlank()) builder.device(device);

        JavaLlamaServer service = builder.build();
        try {
            service.start();
            appendReport(report, "capabilities=" + service.getRuntimeCapabilities() + System.lineSeparator());
            for (int target : targets) {
                runScenario(report, service, new Scenario("chat-no-tools", target, false, false, false, false, false), maxTokens, outputChars, nonThinkingSampler, thinkingSampler);
                runScenario(report, service, new Scenario("chat-cot-no-tools", target, false, true, false, false, false), maxTokens, outputChars, nonThinkingSampler, thinkingSampler);
                runScenario(report, service, new Scenario("chat-tools", target, true, false, false, false, false), maxTokens, outputChars, nonThinkingSampler, thinkingSampler);
                runScenario(report, service, new Scenario("chat-tools-cot", target, true, true, false, false, false), maxTokens, outputChars, nonThinkingSampler, thinkingSampler);
            }
            runScenario(report, service, new Scenario("conversation-sudden-tools", Math.min(1500, targets.get(targets.size() - 1)), true, false, false, false, true), maxTokens, outputChars, nonThinkingSampler, thinkingSampler);
            runScenario(report, service, new Scenario("stream-tools-cot", Math.min(1500, targets.get(targets.size() - 1)), true, true, true, false, false), maxTokens, outputChars, nonThinkingSampler, thinkingSampler);
            runScenario(report, service, new Scenario("task-long-tools", targets.get(targets.size() - 1), true, false, false, true, false), maxTokens, outputChars, nonThinkingSampler, thinkingSampler);
        } finally {
            service.shutdown();
        }
    }

    private static void runScenario(Path report,
                                    JavaLlamaServer service,
                                    Scenario scenario,
                                    int maxTokens,
                                    int outputChars,
                                    SamplerSettings nonThinkingSampler,
                                    SamplerSettings thinkingSampler) throws Exception {
        SamplerConfig sampler = (scenario.thinking() ? thinkingSampler : nonThinkingSampler).toConfig();
        sampler.setEnableThinking(scenario.thinking());

        InferenceOptions options = scenario.tools() || scenario.thinking()
                ? InferenceOptions.builder()
                .toolsJson(scenario.tools() ? toolsJson() : null)
                .captureThinkingContent(scenario.thinking())
                .build()
                : null;

        List<ChatMessage> messages = buildMessages(service, sampler, options, scenario);
        int basePrompt = service.countChatPromptTokens(messages, sampler);
        int effectivePrompt = service.countChatPromptTokens(messages, sampler, options);
        if (scenario.tools() && effectivePrompt <= basePrompt) {
            throw new IllegalStateException(scenario.name() + " toolsJson did not increase prompt tokens");
        }

        long started = System.nanoTime();
        if (scenario.stream()) {
            AtomicReference<LlmStreamFinish> finish = new AtomicReference<>();
            StringBuilder text = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            service.chatStream(messages, sampler, maxTokens, options, text::append, thinking::append, finish::set)
                    .get(600, TimeUnit.SECONDS);
            LlmStreamFinish done = finish.get();
            if (done == null) throw new IllegalStateException(scenario.name() + " stream did not finish");
            writeLine(report, scenario, basePrompt, effectivePrompt, done.usage(), thinking.toString(), text.toString(), started, outputChars);
            return;
        }

        LlmGenerationResult result = scenario.taskLane()
                ? service.taskWithUsage(messages, sampler, maxTokens, 0, false, options).get(600, TimeUnit.SECONDS)
                : service.chatWithUsage(messages, sampler, maxTokens, options).get(600, TimeUnit.SECONDS);
        writeLine(report, scenario, basePrompt, effectivePrompt, result.usage(), result.thinkingContent(), result.text(), started, outputChars);
    }

    private static List<ChatMessage> buildMessages(JavaLlamaServer service,
                                                   SamplerConfig sampler,
                                                   InferenceOptions options,
                                                   Scenario scenario) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("你是天枢的本地中文助手。请用中文回答，保持简洁，必要时输出下一步 JSON。"));
        if (scenario.conversationHistory()) {
            messages.add(ChatMessage.user("先记录：我正在整理一个 Minecraft 自动化基地，需要你后续帮助我判断是否要读取 skill。"));
            messages.add(ChatMessage.assistant("已记录。后续我会根据任务复杂度判断是否需要读取相关 skill。"));
        }
        String userPrefix = scenario.tools()
                ? "请根据下面材料判断下一步。若需要工具，请按模型工具格式选择 load_skill 或 query_world_state；若不需要工具，请直接给出中文建议。\n\n"
                : "请根据下面材料给出中文建议，输出 3 条要点。\n\n";
        messages.add(ChatMessage.user(userPrefix + buildContextToTarget(service, sampler, options, messages, scenario.targetPromptTokens())));
        return messages;
    }

    private static String buildContextToTarget(JavaLlamaServer service,
                                               SamplerConfig sampler,
                                               InferenceOptions options,
                                               List<ChatMessage> existing,
                                               int targetPromptTokens) {
        StringBuilder context = new StringBuilder();
        int repeats = 0;
        while (repeats < 200) {
            repeats++;
            context.append("资料段 ").append(repeats).append("：\n").append(CHINESE_CONTEXT_BLOCK).append('\n');
            List<ChatMessage> candidate = new ArrayList<>(existing);
            candidate.add(ChatMessage.user(context.toString()));
            int tokens = service.countChatPromptTokens(candidate, sampler, options);
            if (tokens >= targetPromptTokens) break;
        }
        return context.toString();
    }

    private static String toolsJson() {
        return """
                [
                  {
                    "type": "function",
                    "function": {
                      "name": "load_skill",
                      "description": "读取一个 skill 的完整内容。",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "skill_id": {
                            "type": "string",
                            "enum": ["task_planning", "doc_reasoning", "minecraft_modding"]
                          },
                          "reason": { "type": "string" }
                        },
                        "required": ["skill_id"]
                      }
                    }
                  },
                  {
                    "type": "function",
                    "function": {
                      "name": "query_world_state",
                      "description": "查询当前玩家、任务和世界运行状态。",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "scope": {
                            "type": "string",
                            "enum": ["player", "task", "world"]
                          }
                        },
                        "required": ["scope"]
                      }
                    }
                  }
                ]
                """;
    }

    private static void writeLine(Path report,
                                  Scenario scenario,
                                  int basePrompt,
                                  int effectivePrompt,
                                  LlmTokenUsage usage,
                                  String thinking,
                                  String text,
                                  long startedNanos,
                                  int outputChars) throws Exception {
        double seconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        String compact = formatOutput(text, outputChars);
        String compactThinking = formatOutput(thinking, outputChars);
        appendReport(report, String.format("[%s] target=%d tools=%s thinking=%s stream=%s task=%s basePrompt=%d effectivePrompt=%d usage=%s thinkingChars=%d seconds=%.2f text=%s thinkingText=%s%n",
                scenario.name(),
                scenario.targetPromptTokens(),
                scenario.tools(),
                scenario.thinking(),
                scenario.stream(),
                scenario.taskLane(),
                basePrompt,
                effectivePrompt,
                usage,
                thinking == null ? 0 : thinking.length(),
                seconds,
                compact,
                compactThinking));
        System.out.printf("[tools-matrix] %s target=%d usage=%s seconds=%.2f%n",
                scenario.name(), scenario.targetPromptTokens(), usage, seconds);
    }

    private static void appendReport(Path report, String text) throws Exception {
        Files.writeString(report, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    private static void writeReport(Path report, String text) throws Exception {
        Files.writeString(report, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static SamplerSettings readSamplerSettings(String prefix) {
        return new SamplerSettings(
                readFloat(prefix + ".temperature", 0.0f),
                readInt(prefix + ".topK", 40),
                readFloat(prefix + ".topP", 0.95f),
                readFloat(prefix + ".minP", 0.05f),
                readFloat(prefix + ".penaltyPresent", 0.0f)
        );
    }

    private static float readFloat(String key, float fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : Float.parseFloat(value);
    }

    private static int readInt(String key, int fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static String formatOutput(String text, int maxChars) {
        String normalized = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        if (maxChars <= 0 || normalized.length() <= maxChars) return normalized;
        return normalized.substring(0, maxChars) + "...";
    }

    private static List<String> splitList(String raw) {
        return List.of(raw.split(";")).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toList());
    }

    private static List<Integer> splitInts(String raw) {
        return List.of(raw.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::parseInt)
                .collect(Collectors.toList());
    }
}
