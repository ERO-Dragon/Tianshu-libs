package com.rheinmetal.tianshu.libs.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rheinmetal.tianshu.libs.llm.EmbeddingEngine;
import com.rheinmetal.tianshu.libs.llm.InferenceLane;
import com.rheinmetal.tianshu.libs.llm.InferenceTask;
import com.rheinmetal.tianshu.libs.llm.LaneConfig;
import com.rheinmetal.tianshu.libs.llm.LaneMetrics;
import com.rheinmetal.tianshu.libs.llm.LlamaEngine;
import com.rheinmetal.tianshu.libs.llm.ModelRegistry;
import com.rheinmetal.tianshu.libs.web.ChatController;
import com.rheinmetal.tianshu.libs.web.ChatController.ChatRequest;

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
        ChatController chatController = new ChatController(models.getChatEngine(), embeddingEngine, config.requestTimeoutSeconds);
        Javalin app = startHttpServer(config, models, chatController);
        AtomicBoolean stopped = new AtomicBoolean(false);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(app, models, stopped), "server-shutdown-hook"));

        startParentWatchdog();
    }

    static Javalin startHttpServer(ServerConfig config, ModelRegistry models, ChatController chatController) {
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        Javalin app = Javalin.create(javalinConfig -> javalinConfig.showJavalinBanner = false).start(config.host, config.port);

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
        return app;
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
        public int queue_size;
        public int max_queue_size;
        public int chat_queue_size;
        public int chat_max_queue_size;
        public int task_queue_size;
        public int task_max_queue_size;
        public String current_lane;
        public boolean task_suspended;

        public HealthResponse(String status, boolean embedding, LaneMetrics laneMetrics) {
            this.status = status;
            this.embedding = embedding;
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

    static String extractFileName(String path) {
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
                    conn.getOutputStream().write(jsonBody.getBytes("UTF-8"));

                    java.io.BufferedReader reader2 = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                    String line;
                    while ((line = reader2.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if ("[DONE]".equals(data)) break;
                            try {
                                StreamChunkResponse chunk = gson.fromJson(data, StreamChunkResponse.class);
                                if (chunk != null && chunk.choices != null && !chunk.choices.isEmpty() && chunk.choices.get(0).delta != null) {
                                    System.out.print(chunk.choices.get(0).delta.content);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    System.out.println("\n");
                } catch (Exception e) {
                    System.err.println("[Console] Error: " + e.getMessage());
                }
            }
        }, "console-chat");
        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    private static class StreamChunkResponse {
        public List<Choice> choices;

        private static class Choice {
            public Delta delta;
        }

        private static class Delta {
            public String content;
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar ... [options]");
        System.out.println("Options:");
        System.out.println("  -m, --model <path>              Path to the model file (required)");
        System.out.println("  -c, --context <n>               Context size (default: " + ServerConfig.DEFAULT_CONTEXT_SIZE + ")");
        System.out.println("  -t, --threads <n>               Number of threads (default: " + ServerConfig.DEFAULT_THREAD_COUNT + ")");
        System.out.println("  --host <ip>                     Host to bind to (default: 127.0.0.1)");
        System.out.println("  --port <n>                      Port to bind to (default: " + ServerConfig.DEFAULT_PORT + ")");
        System.out.println("  --alias <name>                  Model alias");
        System.out.println("  --model-profile <profile>       Model profile (auto/qwen3/qwen3.5/deepseek-r1)");
        System.out.println("  --embedding-model <path>        Path to embedding model");
        System.out.println("  --embedding-context <n>         Embedding context size");
        System.out.println("  --embedding-threads <n>         Embedding threads");
        System.out.println("  --embedding-gpu-layers <n>      Embedding GPU layers");
        System.out.println("  --embedding-alias <name>        Embedding model alias");
        System.out.println("  --max-queue-size <n>            Max queue size (default: " + ServerConfig.DEFAULT_MAX_QUEUE_SIZE + ")");
        System.out.println("  --chat-context <n>              Chat context size");
        System.out.println("  --chat-threads <n>              Chat threads");
        System.out.println("  --chat-max-queue-size <n>       Chat max queue size");
        System.out.println("  --task-context <n>              Task context size");
        System.out.println("  --task-threads <n>              Task threads");
        System.out.println("  --task-max-queue-size <n>       Task max queue size");
        System.out.println("  --task-suspend-on-chat <bool>   Suspend task on chat (default: true)");
        System.out.println("  --cache-type-k <type>           KV cache type K");
        System.out.println("  --cache-type-v <type>           KV cache type V");
        System.out.println("  --request-timeout-seconds <n>  Request timeout (default: " + ServerConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS + ")");
        System.out.println("  -ngl, --n-gpu-layers <n>        Number of GPU layers");
        System.out.println("  -h, --help                      Show this help message");
    }
}