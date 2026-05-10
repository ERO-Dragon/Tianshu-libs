package com.javallamaserver.rag;

import com.google.gson.JsonElement;
import com.javallamaserver.web.ChatController.ChatMessage;

import java.util.ArrayList;
import java.util.List;

public class RagService {
    private final StaticRagIndex staticRagIndex;
    private final DynamicRagRetriever dynamicRagRetriever;
    private final RagConfig config;

    public RagService(StaticRagIndex staticRagIndex, DynamicRagRetriever dynamicRagRetriever, RagConfig config) {
        this.staticRagIndex = staticRagIndex;
        this.dynamicRagRetriever = dynamicRagRetriever;
        this.config = config;
    }

    public List<ChatMessage> augmentMessages(List<ChatMessage> originalMessages, List<JsonElement> dynamicRagEntries, float[] queryVector) throws Exception {
        if (originalMessages == null || originalMessages.isEmpty() || queryVector == null || queryVector.length == 0) {
            return originalMessages;
        }
        List<RagSearchResult> dynamicResults = dynamicRagRetriever == null
                ? List.of()
                : dynamicRagRetriever.search(dynamicRagEntries, queryVector);
        List<RagSearchResult> staticResults = staticRagIndex == null
                ? List.of()
                : staticRagIndex.search(queryVector);
        if (dynamicResults.isEmpty() && staticResults.isEmpty()) {
            return originalMessages;
        }
        List<ChatMessage> augmented = new ArrayList<>();
        ChatMessage ragMessage = new ChatMessage();
        ragMessage.role = "system";
        ragMessage.content = buildRagContext(dynamicResults, staticResults);
        boolean inserted = false;
        for (ChatMessage message : originalMessages) {
            if (!inserted && message != null && "user".equalsIgnoreCase(message.role)) {
                augmented.add(ragMessage);
                inserted = true;
            }
            augmented.add(message);
        }
        if (!inserted) augmented.add(0, ragMessage);
        return augmented;
    }

    public boolean isStaticReady() {
        return staticRagIndex != null && staticRagIndex.isLoaded();
    }

    public int getStaticChunkCount() {
        return staticRagIndex == null ? 0 : staticRagIndex.size();
    }

    public RagConfig getConfig() {
        return config;
    }

    private String buildRagContext(List<RagSearchResult> dynamicResults, List<RagSearchResult> staticResults) {
        StringBuilder builder = new StringBuilder();
        builder.append("以下是检索到的参考信息。只在和用户问题相关时使用，不要编造未提供的信息。\n");
        appendSection(builder, "动态上下文", dynamicResults);
        appendSection(builder, "静态知识", staticResults);
        return builder.toString().trim();
    }

    private void appendSection(StringBuilder builder, String title, List<RagSearchResult> results) {
        if (results == null || results.isEmpty()) return;
        builder.append('\n').append(title).append(':').append('\n');
        int index = 1;
        for (RagSearchResult result : results) {
            builder.append(index).append(". ").append(result.getChunk().getText()).append('\n');
            index++;
        }
    }
}
