package com.javallamaserver.rag;

import com.google.gson.JsonElement;
import com.javallamaserver.web.ChatController.ChatMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RagService {
    private static final int DEFAULT_MEMORY_TOKEN_BUDGET = 1000;

    private final StaticRagIndex staticRagIndex;
    private final DynamicRagRetriever dynamicRagRetriever;
    private final MemoryRagIndex memoryRagIndex;
    private final RagProfileRegistry profileRegistry;
    private final RagSourceCache sourceCache;
    private final WorldStaticRagRegistry worldStaticRegistry;
    private final RagConfig config;

    public RagService(StaticRagIndex staticRagIndex, DynamicRagRetriever dynamicRagRetriever, RagConfig config) {
        this(staticRagIndex, dynamicRagRetriever, null, config);
    }

    public RagService(StaticRagIndex staticRagIndex, DynamicRagRetriever dynamicRagRetriever, MemoryRagIndex memoryRagIndex, RagConfig config) {
        this(staticRagIndex, dynamicRagRetriever, memoryRagIndex, null, null, null, config);
    }

    public RagService(DynamicRagRetriever dynamicRagRetriever, RagProfileRegistry profileRegistry, RagSourceCache sourceCache, WorldStaticRagRegistry worldStaticRegistry, RagConfig config) {
        this(null, dynamicRagRetriever, null, profileRegistry, sourceCache, worldStaticRegistry, config);
    }

    private RagService(StaticRagIndex staticRagIndex, DynamicRagRetriever dynamicRagRetriever, MemoryRagIndex memoryRagIndex, RagProfileRegistry profileRegistry, RagSourceCache sourceCache, WorldStaticRagRegistry worldStaticRegistry, RagConfig config) {
        this.staticRagIndex = staticRagIndex;
        this.dynamicRagRetriever = dynamicRagRetriever;
        this.memoryRagIndex = memoryRagIndex;
        this.profileRegistry = profileRegistry;
        this.sourceCache = sourceCache;
        this.worldStaticRegistry = worldStaticRegistry;
        this.config = config;
    }

    public AugmentationResult augmentMessages(List<ChatMessage> originalMessages, List<JsonElement> dynamicRagEntries, float[] queryVector, boolean useStaticAndDynamic, boolean useMemory, int memoryTokenBudget) throws Exception {
        return augmentMessages(originalMessages, dynamicRagEntries, queryVector, useStaticAndDynamic, useMemory, memoryTokenBudget, null);
    }

    public AugmentationResult augmentMessages(List<ChatMessage> originalMessages, List<JsonElement> dynamicRagEntries, float[] queryVector, boolean useStaticAndDynamic, boolean useMemory, int memoryTokenBudget, RagQueryOptions options) throws Exception {
        if (originalMessages == null || originalMessages.isEmpty() || queryVector == null || queryVector.length == 0) {
            return new AugmentationResult(originalMessages == null ? List.of() : originalMessages, RagHits.empty());
        }
        List<RagSearchResult> dynamicResults = useStaticAndDynamic && dynamicRagRetriever != null
                ? dynamicRagRetriever.search(dynamicRagEntries, queryVector)
                : List.of();
        List<RagSearchResult> staticResults;
        List<RagSearchResult> memoryResults;
        if (options != null && options.hasProfile() && profileRegistry != null && sourceCache != null) {
            RagProfile profile = profileRegistry.resolve(options.getWorld(), options.getProfile());
            staticResults = useStaticAndDynamic ? searchProfileStatic(profile, options, queryVector) : List.of();
            int budget = memoryTokenBudget > 0 ? memoryTokenBudget : profile.getMemoryTokenBudget();
            memoryResults = useMemory ? sourceCache.searchMemory(profile.getMemoryPath(), queryVector, normalizeMemoryTokenBudget(budget)) : List.of();
        } else {
            staticResults = useStaticAndDynamic && staticRagIndex != null
                    ? staticRagIndex.search(queryVector)
                    : List.of();
            memoryResults = useMemory && memoryRagIndex != null
                    ? memoryRagIndex.search(queryVector, normalizeMemoryTokenBudget(memoryTokenBudget))
                    : List.of();
        }
        if (dynamicResults.isEmpty() && staticResults.isEmpty() && memoryResults.isEmpty()) {
            return new AugmentationResult(originalMessages, new RagHits(memoryResults, staticResults));
        }
        List<ChatMessage> augmented = new ArrayList<>();
        ChatMessage ragMessage = buildContextMessage(dynamicResults, staticResults, memoryResults);
        boolean inserted = false;
        for (ChatMessage message : originalMessages) {
            if (!inserted && message != null && "user".equalsIgnoreCase(message.role)) {
                augmented.add(ragMessage);
                inserted = true;
            }
            augmented.add(message);
        }
        if (!inserted) augmented.add(0, ragMessage);
        return new AugmentationResult(augmented, new RagHits(memoryResults, staticResults));
    }

    public List<ChatMessage> augmentMessages(List<ChatMessage> originalMessages, List<JsonElement> dynamicRagEntries, float[] queryVector) throws Exception {
        return augmentMessages(originalMessages, dynamicRagEntries, queryVector, true, false, DEFAULT_MEMORY_TOKEN_BUDGET).messages();
    }

    public boolean isStaticReady() {
        return staticRagIndex != null && staticRagIndex.isLoaded();
    }

    public int getStaticChunkCount() {
        int count = staticRagIndex == null ? 0 : staticRagIndex.size();
        return count + (sourceCache == null ? 0 : sourceCache.staticChunkCount());
    }

    public boolean isMemoryReady() {
        return memoryRagIndex != null && memoryRagIndex.isLoaded();
    }

    public int getMemoryCount() {
        int count = memoryRagIndex == null ? 0 : memoryRagIndex.size();
        return count + (sourceCache == null ? 0 : sourceCache.memoryCount());
    }

    public RagConfig getConfig() {
        return config;
    }

    private List<RagSearchResult> searchProfileStatic(RagProfile profile, RagQueryOptions options, float[] queryVector) throws Exception {
        String scope = resolveStaticScope(profile, options);
        if (scope.equals("none")) return List.of();
        List<Path> paths = new ArrayList<>();
        if (scope.equals("world")) {
            paths.addAll(worldStaticRegistry.discover(profileRegistry.getWorldPath(profile.getWorld())));
        } else if (scope.equals("list")) {
            Path worldPath = profileRegistry.getWorldPath(profile.getWorld());
            for (String mod : options.getStaticMods()) {
                paths.add(worldPath.resolve(mod).resolve("static_rag").normalize());
            }
        } else {
            paths.add(profile.getStaticPath());
        }
        List<RagSearchResult> results = new ArrayList<>();
        for (Path path : paths) {
            results.addAll(sourceCache.searchStatic(path, queryVector));
        }
        return results.stream()
                .sorted(Comparator.comparingDouble(RagSearchResult::getScore).reversed())
                .limit(config.getStaticTopK())
                .toList();
    }

    private String resolveStaticScope(RagProfile profile, RagQueryOptions options) {
        String scope = options.getStaticScope() != null ? options.getStaticScope() : profile.getStaticScope();
        if (scope == null || scope.isBlank()) return "mod";
        String normalized = scope.trim().toLowerCase();
        if (normalized.equals("none") || normalized.equals("mod") || normalized.equals("world") || normalized.equals("list")) return normalized;
        return "mod";
    }

    private ChatMessage buildContextMessage(List<RagSearchResult> dynamicResults, List<RagSearchResult> staticResults, List<RagSearchResult> memoryResults) {
        ChatMessage ragMessage = new ChatMessage();
        ragMessage.role = "system";
        ragMessage.content = buildRagContext(dynamicResults, staticResults, memoryResults);
        return ragMessage;
    }

    private String buildRagContext(List<RagSearchResult> dynamicResults, List<RagSearchResult> staticResults, List<RagSearchResult> memoryResults) {
        StringBuilder builder = new StringBuilder();
        appendMemorySection(builder, memoryResults);
        if ((dynamicResults != null && !dynamicResults.isEmpty()) || (staticResults != null && !staticResults.isEmpty())) {
            if (!builder.isEmpty()) builder.append("\n\n");
            builder.append("以下是检索到的参考信息。只在和用户问题相关时使用，不要编造未提供的信息。");
            appendSection(builder, "动态上下文", dynamicResults);
            appendSection(builder, "静态知识", staticResults);
        }
        return builder.toString().trim();
    }

    private void appendMemorySection(StringBuilder builder, List<RagSearchResult> results) {
        if (results == null || results.isEmpty()) return;
        builder.append("你隐约记得：\n");
        int index = 1;
        for (RagSearchResult result : results) {
            builder.append(index).append(". ").append(result.getChunk().getText()).append('\n');
            index++;
        }
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

    private int normalizeMemoryTokenBudget(int memoryTokenBudget) {
        return memoryTokenBudget <= 0 ? DEFAULT_MEMORY_TOKEN_BUDGET : Math.min(memoryTokenBudget, 4000);
    }

    public record AugmentationResult(List<ChatMessage> messages, RagHits ragHits) {
    }

    public record RagHits(List<RagSearchResult> memory, List<RagSearchResult> staticHits) {
        public static RagHits empty() {
            return new RagHits(List.of(), List.of());
        }
    }
}
