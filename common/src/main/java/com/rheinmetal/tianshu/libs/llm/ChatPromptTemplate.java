package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppChatMessage;
import org.argeo.jjml.llm.LlamaCppModel;
import org.argeo.jjml.llm.util.ThinkingMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChatPromptTemplate {
    private ChatPromptTemplate() {
    }

    static PromptSnapshot snapshot(LlamaCppModel model, List<LlamaCppChatMessage> messages, SamplerConfig config) {
        String formattedPrompt = format(model, messages, config);
        return new PromptSnapshot(formattedPrompt, tokenize(model, formattedPrompt));
    }

    static int countTokens(LlamaCppModel model, List<LlamaCppChatMessage> messages, SamplerConfig config) {
        return snapshot(model, messages, config).tokenIds().length;
    }

    static int[] tokenize(LlamaCppModel model, String formattedPrompt) {
        return TokenIds.copyRemaining(model.getVocabulary().tokenize(formattedPrompt));
    }

    private static String format(LlamaCppModel model, List<LlamaCppChatMessage> messages, SamplerConfig config) {
        SamplerConfig effectiveConfig = config != null ? config : new SamplerConfig();
        ChatTemplateOptions options = chatTemplateOptions(effectiveConfig);
        return options.kwargs().isEmpty()
                ? model.formatChatMessages(messages, options.thinkingMode())
                : model.formatChatMessagesJinja(messages, true, options.thinkingMode(), options.kwargs());
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
}
