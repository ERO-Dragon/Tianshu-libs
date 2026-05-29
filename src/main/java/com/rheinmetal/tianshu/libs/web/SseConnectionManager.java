package com.rheinmetal.tianshu.libs.web;

import io.javalin.http.sse.SseClient;

public class SseConnectionManager {

    private final SseClient sseClient;
    private Runnable onCloseCallback;
    private volatile boolean closed = false;

    public SseConnectionManager(SseClient sseClient) {
        this.sseClient = sseClient;
    }

    public void sendData(String json) {
        if (closed) return;
        try {
            sseClient.sendEvent(json);
        } catch (Exception e) {
            if (!closed) {
                closed = true;
                System.out.println("[SSE] Client disconnected: " + e.getMessage());
                if (onCloseCallback != null) {
                    onCloseCallback.run();
                }
            }
        }
    }

    public void sendDone() {
        if (closed) return;
        try {
            // 必须绕过 Javalin 的 JSON 序列化，强制输出纯文本格式：data: [DONE]\n\n
            var writer = sseClient.ctx().res().getWriter();
            writer.write("data: [DONE]\n\n");
            writer.flush();
        } catch (Exception e) {
            if (!closed) {
                closed = true;
                System.out.println("[SSE] Client disconnected: " + e.getMessage());
                if (onCloseCallback != null) {
                    onCloseCallback.run();
                }
            }
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            sseClient.close();
        } catch (Exception ignored) {}
    }

    public void onClose(Runnable callback) {
        this.onCloseCallback = callback;
    }

    public boolean isClosed() { return closed; }
}
