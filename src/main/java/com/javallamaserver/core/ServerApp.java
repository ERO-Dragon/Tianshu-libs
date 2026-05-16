package com.javallamaserver.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.javallamaserver.llm.EmbeddingEngine;
import com.javallamaserver.llm.InferenceLane;
import com.javallamaserver.llm.InferenceTask;
import com.javallamaserver.llm.LaneConfig;
import com.javallamaserver.llm.LaneMetrics;
import com.javallamaserver.llm.LlamaEngine;
import com.javallamaserver.llm.ModelRegistry;
import com.javallamaserver.rag.DynamicRagRetriever;
import com.javallamaserver.rag.MemoryRagIndex;
import com.javallamaserver.rag.RagConfig;
import com.javallamaserver.rag.RagProfileRegistry;
import com.javallamaserver.rag.RagService;
import com.javallamaserver.rag.RagSourceCache;
import com.javallamaserver.rag.StaticRagIndex;
import com.javallamaserver.rag.WorldStaticRagRegistry;
import com.javallamaserver.web.ChatController;
import com.javallamaserver.web.ChatController.ChatRequest;
import io.javalin.Javalin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerApp {

    public static void main(String[] args) throws Exception {
        try {
            ServerConfig config = ServerConfig.parse(args);
            if (config.help) {
                printUsage();
                return;
            }
            run(config);
        } catch (ServerConfig.ConfigException e) {
            System.err.println("[ServerApp] Configuration error:");
            System.err.println(e.getMessage());
            printUsage();
            System.exit(2);
        }
    }

    private static void run(ServerConfig config) throws Exception {
        printStartupConfig(config);

        String alias = config.alias;
        if (alias == null || alias.isBlank() || alias.equals("unknown")) {
            alias = extractFileName(config.modelPath);
        }

        LaneConfig chatLaneConfig = new LaneConfig(InferenceLane.CHAT, config.chatContext, config.chatThreads, config.chatMaxQueueSize);
        LaneConfig taskLaneConfig = new LaneConfig(InferenceLane.TASK, config.taskContext, config.taskThreads, config.taskMaxQueueSize);
        LlamaEngine engine = LlamaEngine.loadChatEngine(
                config.modelPath,
                chatLaneConfig,
                taskLaneConfig,
                config.gpuLayers,
                alias,
                config.modelProfile,
                config.cacheTypeK,
                config.cacheTypeV,
                config.taskSuspendOnChat
        );

        EmbeddingEngine embeddingEngine = null;
        if (config.embeddingModelPath != null && !config.embeddingModelPath.isBlank()) {
            String embeddingAlias = config.embeddingAlias;
            if (embeddingAlias == null || embeddingAlias.isBlank() || embeddingAlias.equals("embedding")) {
                embeddingAlias = extractFileName(config.embeddingModelPath);
            }
            embeddingEngine = EmbeddingEngine.load(
                    config.embeddingModelPath,
                    config.embeddingContextSize,
                    config.embeddingThreads,
                    config.embeddingGpuLayers,
                    embeddingAlias
            );
        }

        ModelRegistry models = new ModelRegistry(engine, embeddingEngine);
        RagService ragService = buildRagService(config, models);
        ChatController chatController = new ChatController(models.getChatEngine(), embeddingEngine, ragService, config.requestTimeoutSeconds);
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();

        Javalin app = Javalin.create(javalinConfig -> javalinConfig.showJavalinBanner = false).start(config.host, config.port);
        AtomicBoolean stopped = new AtomicBoolean(false);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(app, models, stopped), "server-shutdown-hook"));

        app.post("/v1/chat/completions", ctx -> {
            ChatRequest request = chatController.parseRequestFromContext(ctx);
            if (request == null) return;
            if (Boolean.TRUE.equals(request.stream)) {
                InferenceLane lane = request.resolveLane();
                if (!chatController.hasQueueCapacity(lane)) {
                    ctx.status(429).contentType("application/json").result(gson.toJson(new ErrorResponse(lane.wireName() + " inference queue is full")));
                    return;
                }
                ctx.res().setContentType("text/event-stream");
                ctx.res().setHeader("Cache-Control", "no-cache");
                ctx.res().setHeader("Connection", "keep-alive");
                ctx.res().setStatus(200);
                ctx.future(() -> {
                    java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
                    final InferenceTask[] currentTask = new InferenceTask[1];
                    try {
                        java.io.OutputStream out = ctx.res().getOutputStream();
                        java.util.function.Consumer<String> sender = data -> {
                            try {
                                out.write(("data: " + data + "\n\n").getBytes("UTF-8"));
                                out.flush();
                            } catch (Exception e) {
                                System.err.println("[ServerApp] SSE connection broken, cancelling task...");
                                if (currentTask[0] != null) currentTask[0].cancel();
                                future.completeExceptionally(e);
                            }
                        };
                        Runnable doneRunner = () -> {
                            try {
                                out.write("data: [DONE]\n\n".getBytes("UTF-8"));
                                out.flush();
                            } catch (Exception ignored) {
                            }
                            future.complete(null);
                        };
                        currentTask[0] = chatController.handleStreamChatRaw(request, sender, doneRunner);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                    return future;
                });
            } else {
                chatController.handleSyncChat(ctx, request);
            }
        });

        app.get("/health", ctx -> {
            LaneMetrics laneMetrics = models.getChatEngine().getLaneMetrics();
            HealthResponse response = new HealthResponse(
                    models.isReady() ? "ready" : "loading",
                    models.hasEmbeddingEngine(),
                    ragService == null ? 0 : ragService.getStaticChunkCount(),
                    ragService == null ? 0 : ragService.getMemoryCount(),
                    laneMetrics
            );
            ctx.status(models.isReady() ? 200 : 503).contentType("application/json").result(gson.toJson(response));
        });

        app.post("/v1/embeddings", ctx -> {
            if (!models.hasEmbeddingEngine()) {
                ctx.status(404).result(gson.toJson(new ErrorResponse("embedding model is not configured")));
                return;
            }
            EmbeddingRequest request = gson.fromJson(ctx.body(), EmbeddingRequest.class);
            if (request == null || request.input == null || request.input.isEmpty()) {
                ctx.status(400).result(gson.toJson(new ErrorResponse("input is required")));
                return;
            }
            float[][] vectors = models.getEmbeddingEngine().embed(request.input);
            EmbeddingResponse response = new EmbeddingResponse(models.getEmbeddingEngine().getModelAlias(), vectors);
            ctx.contentType("application/json").result(gson.toJson(response));
        });

        app.get("/v1/models", ctx -> {
            ModelsResponse response = new ModelsResponse();
            response.data.add(new ModelInfo(models.getChatEngine().getModelAlias(), "model", "local", null));
            if (models.hasEmbeddingEngine()) {
                response.data.add(new ModelInfo(models.getEmbeddingEngine().getModelAlias(), "model", "local", "embedding"));
            }
            ctx.contentType("application/json").result(gson.toJson(response));
        });

        System.out.println("[ServerApp] Server started at http://" + config.host + ":" + config.port);
        System.out.println("[ServerApp] Endpoints:");
        System.out.println("[ServerApp]   POST /v1/chat/completions  (stream=true/false)");
        System.out.println("[ServerApp]   GET  /health");
        System.out.println("[ServerApp]   GET  /v1/models");
        System.out.println("[ServerApp]   POST /v1/embeddings");

        startParentWatchdog();
    }

    private static RagService buildRagService(ServerConfig config, ModelRegistry models) throws Exception {
        if (!models.hasEmbeddingEngine()) return null;
        RagConfig ragConfig = new RagConfig(config.staticRagTopK, config.dynamicRagTopK, config.ragChunkSize, config.ragChunkOverlap);
        DynamicRagRetriever dynamicRagRetriever = new DynamicRagRetriever(models.getEmbeddingEngine(), ragConfig);
        if (config.ragRootPath != null && !config.ragRootPath.isBlank()) {
            RagProfileRegistry profileRegistry = new RagProfileRegistry(config.ragRootPath, config.ragProfileRefreshIntervalMillis);
            RagSourceCache sourceCache = new RagSourceCache(models.getEmbeddingEngine(), ragConfig, config.memoryRagRefreshIntervalMillis);
            WorldStaticRagRegistry worldStaticRegistry = new WorldStaticRagRegistry(config.worldStaticRagScanIntervalMillis);
            return new RagService(dynamicRagRetriever, profileRegistry, sourceCache, worldStaticRegistry, ragConfig);
        }
        StaticRagIndex staticRagIndex = new StaticRagIndex(models.getEmbeddingEngine(), ragConfig);
        if (config.staticRagPath != null && !config.staticRagPath.isBlank()) {
            staticRagIndex.load(config.staticRagPath);
        }
        MemoryRagIndex memoryRagIndex = null;
        if (config.memoryRagPath != null && !config.memoryRagPath.isBlank()) {
            memoryRagIndex = new MemoryRagIndex(models.getEmbeddingEngine(), config.memoryRagPath, config.memoryRagRefreshIntervalMillis);
            memoryRagIndex.load();
        }
        return new RagService(staticRagIndex, dynamicRagRetriever, memoryRagIndex, ragConfig);
    }

    private static void shutdown(Javalin app, ModelRegistry models, AtomicBoolean stopped) {
        if (!stopped.compareAndSet(false, true)) return;
        System.err.println("[ServerApp] ShutdownHook: releasing resources...");
        long start = System.currentTimeMillis();
        try {
            app.stop();
        } catch (Exception e) {
            System.err.println("[ServerApp] App shutdown error: " + e.getMessage());
        }
        try {
            models.shutdown();
        } catch (Exception e) {
            System.err.println("[ServerApp] Model shutdown error: " + e.getMessage());
        }
        System.err.println("[ServerApp] Shutdown completed in " + (System.currentTimeMillis() - start) + "ms");
    }

    private static void printStartupConfig(ServerConfig config) {
        System.out.println("[ServerApp] Initializing models...");
        System.out.println("[ServerApp]   Chat Model: " + config.modelPath);
        System.out.println("[ServerApp]   Chat Context: " + config.chatContext);
        System.out.println("[ServerApp]   Chat Threads: " + config.chatThreads);
        System.out.println("[ServerApp]   Chat Max Queue Size: " + config.chatMaxQueueSize);
        System.out.println("[ServerApp]   Task Context: " + config.taskContext);
        System.out.println("[ServerApp]   Task Threads: " + config.taskThreads);
        System.out.println("[ServerApp]   Task Max Queue Size: " + config.taskMaxQueueSize);
        System.out.println("[ServerApp]   Task Suspend On Chat: " + config.taskSuspendOnChat);
        System.out.println("[ServerApp]   KV Cache Type K: " + (config.cacheTypeK == null ? "default" : config.cacheTypeK.wireName()));
        System.out.println("[ServerApp]   KV Cache Type V: " + (config.cacheTypeV == null ? "default" : config.cacheTypeV.wireName()));
        System.out.println("[ServerApp]   Chat GPU Layers: " + config.gpuLayers);
        System.out.println("[ServerApp]   Chat Alias: " + config.alias);
        System.out.println("[ServerApp]   Chat Model Profile: " + (config.modelProfile == null ? "auto" : config.modelProfile));
        System.out.println("[ServerApp]   Max Queue Size: " + config.maxQueueSize);
        System.out.println("[ServerApp]   Request Timeout Seconds: " + config.requestTimeoutSeconds);
        if (config.embeddingModelPath != null && !config.embeddingModelPath.isBlank()) {
            System.out.println("[ServerApp]   Embedding Model: " + config.embeddingModelPath);
            System.out.println("[ServerApp]   Embedding Context: " + config.embeddingContextSize);
            System.out.println("[ServerApp]   Embedding Threads: " + config.embeddingThreads);
            System.out.println("[ServerApp]   Embedding GPU Layers: " + config.embeddingGpuLayers);
            System.out.println("[ServerApp]   Embedding Alias: " + config.embeddingAlias);
        }
        if (config.staticRagPath != null && !config.staticRagPath.isBlank()) {
            System.out.println("[ServerApp]   Static RAG Path: " + config.staticRagPath);
            System.out.println("[ServerApp]   Static RAG TopK: " + config.staticRagTopK);
            System.out.println("[ServerApp]   Dynamic RAG TopK: " + config.dynamicRagTopK);
            System.out.println("[ServerApp]   RAG Chunk Size: " + config.ragChunkSize);
            System.out.println("[ServerApp]   RAG Chunk Overlap: " + config.ragChunkOverlap);
        }
        if (config.memoryRagPath != null && !config.memoryRagPath.isBlank()) {
            System.out.println("[ServerApp]   Memory RAG Path: " + config.memoryRagPath);
            System.out.println("[ServerApp]   Memory RAG Refresh Interval Millis: " + config.memoryRagRefreshIntervalMillis);
        }
        if (config.ragRootPath != null && !config.ragRootPath.isBlank()) {
            System.out.println("[ServerApp]   RAG Root Path: " + config.ragRootPath);
            System.out.println("[ServerApp]   RAG Profile Refresh Interval Millis: " + config.ragProfileRefreshIntervalMillis);
            System.out.println("[ServerApp]   World Static RAG Scan Interval Millis: " + config.worldStaticRagScanIntervalMillis);
        }
    }

    private static void startParentWatchdog() {
        String parentPidStr = System.getenv("PARENT_PID");
        if (parentPidStr == null) {
            System.out.println("[ServerApp] PARENT_PID not set. Running in standalone mode.");
            return;
        }
        try {
            long parentPid = Long.parseLong(parentPidStr);
            Thread watchdog = new Thread(() -> {
                System.out.println("[ServerApp] Watchdog started. Monitoring parent PID: " + parentPid);
                while (true) {
                    try {
                        Thread.sleep(5000);
                        if (!ProcessHandle.of(parentPid).isPresent()) {
                            System.err.println("[ServerApp] Parent process is dead. Exiting...");
                            System.exit(0);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "parent-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        } catch (NumberFormatException ignored) {
        }
    }

    public static class EmbeddingRequest {
        public List<String> input;
    }

    public static class EmbeddingResponse {
        public String object = "list";
        public String model;
        public float[][] data;

        public EmbeddingResponse(String model, float[][] data) {
            this.model = model;
            this.data = data;
        }
    }

    public static class HealthResponse {
        public String status;
        public boolean embedding;
        public int static_rag_chunks;
        public int memory_rag_memories;
        public int queue_size;
        public int max_queue_size;
        public int chat_queue_size;
        public int chat_max_queue_size;
        public int task_queue_size;
        public int task_max_queue_size;
        public String current_lane;
        public boolean task_suspended;

        public HealthResponse(String status, boolean embedding, int staticRagChunks, int memoryRagMemories, LaneMetrics laneMetrics) {
            this.status = status;
            this.embedding = embedding;
            this.static_rag_chunks = staticRagChunks;
            this.memory_rag_memories = memoryRagMemories;
            this.queue_size = laneMetrics.getChatQueueSize();
            this.max_queue_size = laneMetrics.getChatMaxQueueSize();
            this.chat_queue_size = laneMetrics.getChatQueueSize();
            this.chat_max_queue_size = laneMetrics.getChatMaxQueueSize();
            this.task_queue_size = laneMetrics.getTaskQueueSize();
            this.task_max_queue_size = laneMetrics.getTaskMaxQueueSize();
            this.current_lane = laneMetrics.getCurrentLane();
            this.task_suspended = laneMetrics.isTaskSuspended();
        }
    }

    public static class ModelsResponse {
        public String object = "list";
        public List<ModelInfo> data = new ArrayList<>();
    }

    public static class ModelInfo {
        public String id;
        public String object;
        public String owned_by;
        public String type;

        public ModelInfo(String id, String object, String ownedBy, String type) {
            this.id = id;
            this.object = object;
            this.owned_by = ownedBy;
            this.type = type;
        }
    }

    public static class ErrorResponse {
        public String error;

        public ErrorResponse(String error) {
            this.error = error;
        }
    }

    private static String extractFileName(String path) {
        if (path == null || path.isBlank()) return "unknown";
        int lastSlash = path.lastIndexOf('\\');
        if (lastSlash == -1) lastSlash = path.lastIndexOf('/');
        return lastSlash != -1 ? path.substring(lastSlash + 1) : path;
    }

    private static void startConsoleChat(String host, int port) {
        Thread consoleThread = new Thread(() -> {
            Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

            System.out.println("\n[Console] Type 'exit' to quit. Enter message to chat:");
            System.out.println("========================================================");

            while (true) {
                try {
                    System.out.print("\n[User] > ");
                    System.out.flush();
                    String input = reader.readLine();
                    if (input == null) break;
                    input = input.trim();
                    if (input.isEmpty()) continue;
                    if (input.equalsIgnoreCase("exit")) {
                        System.out.println("[Console] Exiting...");
                        System.exit(0);
                    }

                    String jsonBody = "{\"messages\":[{\"role\":\"user\",\"content\":"
                            + gson.toJson(input) + "}],\"stream\":true}";

                    java.net.URL url = new java.net.URL("http://" + host + ":" + port + "/v1/chat/completions");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "text/event-stream");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(300000);

                    try (var os = conn.getOutputStream()) {
                        os.write(jsonBody.getBytes("UTF-8"));
                        os.flush();
                    }

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        System.err.println("[Console] HTTP " + responseCode);
                        try (var errStream = conn.getErrorStream()) {
                            if (errStream != null) {
                                byte[] errBytes = errStream.readAllBytes();
                                System.err.println("[Console] " + new String(errBytes, "UTF-8"));
                            }
                        }
                        continue;
                    }

                    System.out.print("[Assistant] > ");
                    System.out.flush();

                    StringBuilder lineBuf = new StringBuilder();
                    try (var is = conn.getInputStream();
                         var isr = new java.io.InputStreamReader(is, "UTF-8")) {
                        int ch;
                        while ((ch = isr.read()) != -1) {
                            if (ch == '\n') {
                                String line = lineBuf.toString().trim();
                                lineBuf.setLength(0);
                                if (line.isEmpty()) continue;
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6).trim();
                                    if (data.equals("[DONE]")) {
                                        System.out.println();
                                        break;
                                    }
                                    try {
                                        JsonObject chunk = gson.fromJson(data, JsonObject.class);
                                        if (chunk != null && chunk.has("choices")) {
                                            var choicesArr = chunk.getAsJsonArray("choices");
                                            if (choicesArr != null && choicesArr.size() > 0) {
                                                JsonObject choice = choicesArr.get(0).getAsJsonObject();
                                                if (choice.has("delta")) {
                                                    JsonObject delta = choice.getAsJsonObject("delta");
                                                    if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                                                        System.out.print(delta.get("content").getAsString());
                                                        System.out.flush();
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Exception ignore) {
                                    }
                                }
                            } else {
                                lineBuf.append((char) ch);
                            }
                        }
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    System.err.println("[Console] Error: " + e.getMessage());
                }
            }
        }, "console-chat");
        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar LlamaServer-Fat-all.jar [options]");
        System.out.println("  -m,  --model <path>                 GGUF chat model path (required)");
        System.out.println("  -c,  --context <n>                  Context size (default: 4096)");
        System.out.println("  -t,  --threads <n>                  Thread count (default: CPU cores)");
        System.out.println("       --host <addr>                  Bind host (default: 127.0.0.1 only)");
        System.out.println("       --port <n>                     Bind port (default: 8080)");
        System.out.println("       --alias <name>                 Model alias (default: model file name)");
        System.out.println("       --model-profile <name>         Override model adapter profile (default: auto)");
        System.out.println("       --embedding-model <path>       GGUF embedding model path");
        System.out.println("       --embedding-context <n>        Embedding context size (default: 4096)");
        System.out.println("       --embedding-threads <n>        Embedding thread count (default: CPU cores)");
        System.out.println("       --embedding-gpu-layers <n>     Embedding GPU layers (default: 999)");
        System.out.println("       --embedding-alias <name>       Embedding model alias");
        System.out.println("       --static-rag-path <path>       Static RAG file or directory path");
        System.out.println("       --memory-rag-path <path>       Memory RAG directory path");
        System.out.println("       --rag-root-path <path>         Multi-world profile RAG root path");
        System.out.println("       --rag-profile-refresh-interval-ms <n> Profile config refresh interval in milliseconds (default: 1000)");
        System.out.println("       --world-static-rag-scan-interval-ms <n> World static RAG discovery interval in milliseconds (default: 5000)");
        System.out.println("       --memory-rag-refresh-interval-ms <n> Memory RAG refresh interval in milliseconds (default: 1000)");
        System.out.println("       --static-rag-top-k <n>         Static RAG top-k (default: 4)");
        System.out.println("       --dynamic-rag-top-k <n>        Dynamic RAG top-k (default: 4)");
        System.out.println("       --rag-chunk-size <n>           Static RAG chunk size (default: 900)");
        System.out.println("       --rag-chunk-overlap <n>        Static RAG chunk overlap (default: 120)");
        System.out.println("       --chat-context <n>             Chat lane context size (default: --context)");
        System.out.println("       --chat-threads <n>             Chat lane thread count (default: --threads)");
        System.out.println("       --chat-max-queue-size <n>      Chat lane queue capacity (default: --max-queue-size)");
        System.out.println("       --task-context <n>             Task lane context size (default: --context)");
        System.out.println("       --task-threads <n>             Task lane thread count (default: min(2, CPU cores))");
        System.out.println("       --task-max-queue-size <n>      Task lane queue capacity (default: 1)");
        System.out.println("       --task-suspend-on-chat <bool>  Suspend task lane when chat is pending (default: true)");
        System.out.println("       --cache-type-k <type>          KV cache K type, supported: f16, q8_0");
        System.out.println("       --cache-type-v <type>          KV cache V type, supported: f16, q8_0");
        System.out.println("       --max-queue-size <n>           Legacy chat lane queue capacity (default: 4)");
        System.out.println("       --request-timeout-seconds <n>  Non-stream request timeout (default: 300)");
        System.out.println("  -ngl, --n-gpu-layers <n>            Chat model GPU layers (default: 999)");
    }
}
