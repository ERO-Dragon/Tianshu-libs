package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppChatMessage;
import org.argeo.jjml.llm.LlamaCppChatTool;
import org.argeo.jjml.llm.LlamaCppChatToolChoice;
import org.argeo.jjml.llm.LlamaCppModel;
import org.argeo.jjml.llm.util.ThinkingMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChatPromptTemplate {
    private ChatPromptTemplate() {
    }

    static PromptSnapshot snapshot(LlamaCppModel model, List<LlamaCppChatMessage> messages, SamplerConfig config) {
        return snapshot(model, messages, config, null);
    }

    static PromptSnapshot snapshot(LlamaCppModel model, List<LlamaCppChatMessage> messages, SamplerConfig config, InferenceOptions options) {
        String formattedPrompt = format(model, messages, config, options);
        return new PromptSnapshot(
                formattedPrompt,
                tokenize(model, formattedPrompt),
                ReasoningTagNormalizer.promptEndsInReasoningOpen(formattedPrompt)
        );
    }

    static int countTokens(LlamaCppModel model, List<LlamaCppChatMessage> messages, SamplerConfig config) {
        return snapshot(model, messages, config).tokenIds().length;
    }

    static int countTokens(LlamaCppModel model, List<LlamaCppChatMessage> messages, SamplerConfig config, InferenceOptions options) {
        return snapshot(model, messages, config, options).tokenIds().length;
    }

    static int[] tokenize(LlamaCppModel model, String formattedPrompt) {
        return TokenIds.copyRemaining(model.getVocabulary().tokenize(formattedPrompt));
    }

    private static String format(LlamaCppModel model, List<LlamaCppChatMessage> messages, SamplerConfig config, InferenceOptions inferenceOptions) {
        SamplerConfig effectiveConfig = config != null ? config : new SamplerConfig();
        ChatTemplateOptions templateOptions = chatTemplateOptions(effectiveConfig);
        String requestToolsJson = toolsJson(inferenceOptions);
        if (requestToolsJson != null && templateOptions.kwargs().containsKey("tools")) {
            throw new IllegalArgumentException("chatTemplateKwargs.tools conflicts with InferenceOptions.toolsJson");
        }
        ToolsInput toolsInput = resolveToolsInput(model, requestToolsJson);
        if (!toolsInput.nativeTools().isEmpty()) {
            return model.formatChatMessagesJinjaFull(
                    messages,
                    true,
                    templateOptions.thinkingMode(),
                    templateOptions.kwargs(),
                    toolsInput.nativeTools(),
                    LlamaCppChatToolChoice.AUTO,
                    false,
                    null
            ).prompt();
        }
        if (toolsInput.appendFallback()) {
            messages = appendToolsFallback(messages, requestToolsJson);
        }
        return templateOptions.kwargs().isEmpty()
                ? model.formatChatMessages(messages, templateOptions.thinkingMode())
                : model.formatChatMessagesJinja(messages, true, templateOptions.thinkingMode(), templateOptions.kwargs());
    }

    private static String toolsJson(InferenceOptions options) {
        return options == null ? null : options.getToolsJson();
    }

    private static boolean supportsTools(LlamaCppModel model) {
        try {
            return model.getChatTemplateCapabilities().supportsTools();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ToolsInput resolveToolsInput(LlamaCppModel model, String toolsJson) {
        if (toolsJson == null) return ToolsInput.none();
        if (!supportsTools(model)) return ToolsInput.fallback();
        try {
            return ToolsInput.nativeTools(LlmChatTools.parseToolsJson(toolsJson));
        } catch (IllegalArgumentException e) {
            return ToolsInput.fallback();
        }
    }

    private static List<LlamaCppChatMessage> appendToolsFallback(List<LlamaCppChatMessage> messages, String toolsJson) {
        List<LlamaCppChatMessage> result = new ArrayList<>(messages);
        result.add(new LlamaCppChatMessage("user", "<tools>\n" + toolsJson + "\n</tools>"));
        return List.copyOf(result);
    }

    private static ChatTemplateOptions chatTemplateOptions(SamplerConfig config) {
        ThinkingMode thinkingMode = config.effectiveThinkingMode();
        Map<String, String> kwargs = config.chatTemplateKwargs();
        if (kwargs.isEmpty()) return new ChatTemplateOptions(thinkingMode, kwargs);

        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : kwargs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("chatTemplateKwargs contains a blank key");
            }
            if (value == null) {
                throw new IllegalArgumentException("chatTemplateKwargs value for '" + key + "' cannot be null");
            }
            String normalizedKey = key.trim();
            if ("enable_thinking".equals(normalizedKey)) {
                boolean kwargThinking = parseBooleanKwarg(normalizedKey, value);
                ThinkingMode kwargMode = kwargThinking ? ThinkingMode.ENABLED : ThinkingMode.DISABLED;
                if (thinkingMode == ThinkingMode.AUTO) {
                    thinkingMode = kwargMode;
                } else if (thinkingMode != kwargMode) {
                    throw new IllegalArgumentException("Conflicting thinking settings: thinkingMode="
                            + thinkingMode + " but chatTemplateKwargs.enable_thinking=" + value);
                }
                continue;
            }
            normalized.put(normalizedKey, value);
        }
        return new ChatTemplateOptions(thinkingMode, normalized);
    }

    private static boolean parseBooleanKwarg(String key, String value) {
        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized)) return true;
        if ("false".equals(normalized)) return false;
        throw new IllegalArgumentException("chatTemplateKwargs." + key + " must be 'true' or 'false', got '" + value + "'");
    }

    private record ChatTemplateOptions(ThinkingMode thinkingMode, Map<String, String> kwargs) {
    }

    private record ToolsInput(List<LlamaCppChatTool> nativeTools, boolean appendFallback) {
        private static ToolsInput none() {
            return new ToolsInput(List.of(), false);
        }

        private static ToolsInput nativeTools(List<LlamaCppChatTool> tools) {
            return new ToolsInput(tools, false);
        }

        private static ToolsInput fallback() {
            return new ToolsInput(List.of(), true);
        }
    }
}
