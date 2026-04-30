package com.javallamaserver.core;

import com.javallamaserver.llm.InferenceTask;
import com.javallamaserver.llm.LlamaEngine;
import com.javallamaserver.web.ChatController;
import com.javallamaserver.web.ChatController.ChatRequest;

import io.javalin.Javalin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

public class ServerApp {

    private static final String MODEL_PATH = "D:\\AIPROJECT\\LLMUnity\\models\\Qwen3-4B-Q4_K_M.gguf";
    private static final int CONTEXT_SIZE = 4096;
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int GPU_LAYERS = 999;
    private static String MODEL_ALIAS = "unknown";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8080;

    public static void main(String[] args) throws Exception {
        String modelPath = MODEL_PATH;
        int port = PORT;
        String host = HOST;
        int ctxSize = CONTEXT_SIZE;
        int threads = THREAD_COUNT;
        int gpuLayers = GPU_LAYERS;
        String alias = MODEL_ALIAS;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-m", "--model" -> modelPath = args[++i];
                case "-c", "--context" -> ctxSize = Integer.parseInt(args[++i]);
                case "-t", "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--alias" -> alias = args[++i];
                case "-ngl", "--n-gpu-layers" -> gpuLayers = Integer.parseInt(args[++i]);
                case "-h", "--help" -> {
                    printUsage();
                    return;
                }
            }
        }

        if (host.equals("0.0.0.0")) {
            System.err.println("[ServerApp] SECURITY: Binding to 0.0.0.0 is forbidden. Use 127.0.0.1.");
            System.exit(1);
        }

        System.out.println("[ServerApp] Initializing LlamaEngine...");
        System.out.println("[ServerApp]   Model: " + modelPath);
        System.out.println("[ServerApp]   Context: " + ctxSize);
        System.out.println("[ServerApp]   Threads: " + threads);
        System.out.println("[ServerApp]   GPU Layers: " + gpuLayers);
        System.out.println("[ServerApp]   Alias: " + alias);
        // 自动从模型路径提取文件名作为别名，完美兼容 llama.cpp 行为
        if (modelPath != null) {
            int lastSlash = modelPath.lastIndexOf('\\');
            if (lastSlash == -1) lastSlash = modelPath.lastIndexOf('/');
            MODEL_ALIAS = lastSlash != -1 ? modelPath.substring(lastSlash + 1) : modelPath;
        }
        
        LlamaEngine engine = LlamaEngine.initialize(modelPath, ctxSize, threads, gpuLayers, alias);
        final LlamaEngine engineRef = engine;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.err.println("[ServerApp] ShutdownHook: Releasing GPU resources...");
                long start = System.currentTimeMillis();
                try {
                    engineRef.shutdown(); // 释放 GPU 显存、关闭推理线程池
                } catch (Exception e) {
                    System.err.println("[ServerApp] ShutdownHook error: " + e.getMessage());
                }
                long elapsed = System.currentTimeMillis() - start;
                System.err.println("[ServerApp] ShutdownHook completed in " + elapsed + "ms");
            }, "gpu-cleanup-hook"));
        ChatController chatController = new ChatController();

        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        }).start(host, port);

        app.post("/v1/chat/completions", ctx -> {
            String body = ctx.body();
            boolean stream = body.contains("\"stream\"") && body.contains("true");

            if (stream) {
                ChatRequest request = chatController.parseRequestFromString(body);
                if (request == null) {
                    ctx.status(400).result("{\"error\": \"invalid request\"}");
                    return;
                }

                // 1. 必须在挂起前设置好 Header
                ctx.res().setContentType("text/event-stream");
                ctx.res().setHeader("Cache-Control", "no-cache");
                ctx.res().setHeader("Connection", "keep-alive");
                ctx.res().setStatus(200);

                // 2. 🌟 终极解法：ctx.future() 彻底挂起 Javalin 生命周期
                ctx.future(() -> {
                    java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
                    // 用于在连接断开时取消任务
                    final InferenceTask[] currentTask = new InferenceTask[1];
                    try {
                        java.io.OutputStream out = ctx.res().getOutputStream();

                        java.util.function.Consumer<String> sender = data -> {
                            try {
                                out.write(("data: " + data + "\n\n").getBytes("UTF-8"));
                                out.flush();
                            } catch (Exception e) {
                                // 连接断开，取消推理任务
                                System.err.println("[ServerApp] SSE connection broken, cancelling task...");
                                if (currentTask[0] != null) {
                                    currentTask[0].cancel();
                                }
                                future.completeExceptionally(e);
                            }
                        };

                        Runnable doneRunner = () -> {
                            try {
                                out.write("data: [DONE]\n\n".getBytes("UTF-8"));
                                out.flush();
                            } catch (Exception ignored) {}
                            future.complete(null);
                        };

                        currentTask[0] = chatController.handleStreamChatRaw(request, sender, doneRunner);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                    return future;
                });
            } else {
                chatController.syncHandler().handle(ctx);
            }
        });

        app.get("/health", ctx -> {
            if (engine.isModelLoaded()) {
                ctx.status(200).result("{\"status\": \"ready\"}");
            } else {
                ctx.status(503).result("{\"status\": \"loading\"}");
            }
        });

        app.get("/v1/models", ctx -> {
            ctx.contentType("application/json");
            ctx.result("{\"object\":\"list\",\"data\":[{\"id\":\"" + engine.getModelAlias()
                    + "\",\"object\":\"model\",\"owned_by\":\"local\"}]}");
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[ServerApp] Shutdown hook triggered.");
            app.stop();
            engine.shutdown();
        }));

        System.out.println("[ServerApp] Server started at http://" + host + ":" + port);
        System.out.println("[ServerApp] Endpoints:");
        System.out.println("[ServerApp]   POST /v1/chat/completions  (stream=true/false)");
        System.out.println("[ServerApp]   GET  /health");
        System.out.println("[ServerApp]   GET  /v1/models");

        // startConsoleChat(host, port);
        
        // ==========================================
        // 【防孤儿守护】：检测父进程是否存活
        // ==========================================
        String parentPidStr = System.getenv("PARENT_PID");
        if (parentPidStr != null) {
            try {
                long parentPid = Long.parseLong(parentPidStr);
                Thread watchdog = new Thread(() -> {
                    System.out.println("[ServerApp] Watchdog started. Monitoring parent PID: " + parentPid);
                    while (true) {
                        try {
                            Thread.sleep(5000); // 每 5 秒检查一次
                            // 询问操作系统：这个 PID 还在吗？
                            if (!ProcessHandle.of(parentPid).isPresent()) {
                                System.err.println("[ServerApp] Parent process (MC) is dead! Initiating suicide to release VRAM...");
                                System.exit(0); // 这会 100% 触发下面的 gpu-cleanup-hook
                            }
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }, "parent-watchdog");
                watchdog.setDaemon(true); // 设为守护线程，不阻止 JVM 正常退出
                watchdog.start();
            } catch (NumberFormatException ignored) {}
        } else {
            System.out.println("[ServerApp] PARENT_PID not set. Running in standalone mode.");
        }
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
        System.out.println("  -m,  --model <path>         GGUF model path (required)");
        System.out.println("  -c,  --context <n>          Context size (default: 4096)");
        System.out.println("  -t,  --threads <n>          Thread count (default: CPU cores)");
        System.out.println("       --host <addr>          Bind host (default: 127.0.0.1)");
        System.out.println("       --port <n>             Bind port (default: 8080)");
        System.out.println("       --alias <name>         Model alias (default: qwen3-4b)");
        System.out.println("  -ngl, --n-gpu-layers <n>    GPU layers (default: 999)");
    }
}
