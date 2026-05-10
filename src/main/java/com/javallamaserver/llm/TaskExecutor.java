package com.javallamaserver.llm;

import org.argeo.jjml.llm.*;
import org.argeo.jjml.llm.params.DefaultSamplerChainParams;

import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;

public class TaskExecutor implements Runnable {

    private final LlamaEngine engine;
    private final BlockingQueue<InferenceTask> queue;

    public TaskExecutor(LlamaEngine engine, BlockingQueue<InferenceTask> queue) {
        this.engine = engine;
        this.queue = queue;
    }

    @Override
    public void run() {
        System.out.println("[TaskExecutor] Worker loop started.");
        while (engine.isRunning()) {
            try {
                InferenceTask task = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (task == null) continue;
                if (task.isCancelled()) {
                    task.getSyncFuture().cancel(false);
                    continue;
                }
                System.out.println("[TaskExecutor] Processing task " + task.getTaskId()
                        + " type=" + task.getTaskType());
                executeTask(task);
                System.out.println("[TaskExecutor] Task " + task.getTaskId() + " completed.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[TaskExecutor] Fatal error in worker loop: " + e.getMessage());
                e.printStackTrace();
            }
        }
        System.out.println("[TaskExecutor] Worker loop exited.");
    }

    private void executeTask(InferenceTask task) {
        LlamaCppContext context = null;
        try {
            context = engine.createContext();
            LlamaCppSamplerChain chain = buildSamplerChain(task.getSamplerConfig());

            switch (task.getTaskType()) {
                case STREAM_CHAT:
                    doStreamChat(context, chain, task);
                    break;
                case SYNC_COMPRESS:
                    doSyncInference(context, chain, task);
                    break;
                case SYNC_TOOL_CALL:
                    doSyncInference(context, chain, task);
                    break;
            }
        } catch (Exception e) {
            System.err.println("[TaskExecutor] Task " + task.getTaskId() + " failed: " + e.getMessage());
            e.printStackTrace();
            task.getSyncFuture().completeExceptionally(e);
        } finally {
            if (context != null) {
                try { context.close(); } catch (Exception ignored) {}
            }
        }
    }

    private LlamaCppSamplerChain buildSamplerChain(SamplerConfig config) {
        DefaultSamplerChainParams params = new DefaultSamplerChainParams(
                config.getTemperature(),
                0,
                0L,
                config.getTopK(),
                config.getTopP(),
                config.getMinP(),
                1.0F,
                1.0F,
                0.0F,
                1.0F,
                config.getPenaltyLastN(),
                config.getPenaltyRepeat(),
                config.getPenaltyFreq(),
                config.getPenaltyPresent(),
                false,
                false
        );
        LlamaCppSamplerChain chain = LlamaCppSamplers.newDefaultSampler(params);
        if (config.hasGrammar()) {
            LlamaCppNativeSampler grammarSampler = LlamaCppSamplers.newSamplerGrammar(
                    engine.getModel(),
                    config.getGrammarStr(),
                    config.getGrammarRoot()
            );
            chain.addSampler(grammarSampler);
        }
        return chain;
    }

    private LlamaCppSamplerChain buildSamplerChainWithGrammar(SamplerConfig config) {
        return buildSamplerChain(config);
    }

    private void doStreamChat(LlamaCppContext context, LlamaCppSamplerChain chain, InferenceTask task) throws IOException {
        LlamaCppInstructProcessor processor = new LlamaCppInstructProcessor(context, chain);

        for (LlamaCppChatMessage msg : task.getMessages()) {
            processor.write(msg.getRole(), msg.getContent());
        }

        StringBuilder fullResponse = new StringBuilder();
        Consumer<String> callback = task.getStreamCallback();

        String token;
        int generatedTokens = 0;
        while ((token = processor.nextToken()) != null) {
            if (task.isCancelled()) break;
            if (task.getMaxTokens() > 0 && generatedTokens >= task.getMaxTokens()) break;
            generatedTokens++;
            fullResponse.append(token);
            if (callback != null) {
                callback.accept(token);
            }
        }

        task.getSyncFuture().complete(fullResponse.toString());
    }

    private void doSyncInference(LlamaCppContext context, LlamaCppSamplerChain chain, InferenceTask task) throws IOException {
        if (task.getSamplerConfig().hasGrammar()) {
            LlamaCppSamplerChain grammarChain = buildSamplerChainWithGrammar(task.getSamplerConfig());
            doSyncInferenceInternal(context, grammarChain, task);
        } else {
            doSyncInferenceInternal(context, chain, task);
        }
    }

    private void doSyncInferenceInternal(LlamaCppContext context, LlamaCppSamplerChain chain, InferenceTask task) throws IOException {
        LlamaCppInstructProcessor processor = new LlamaCppInstructProcessor(context, chain);

        for (LlamaCppChatMessage msg : task.getMessages()) {
            processor.write(msg.getRole(), msg.getContent());
        }

        StringWriter writer = new StringWriter();
        int maxTokens = task.getMaxTokens();
        if (maxTokens > 0) {
            int generatedTokens = 0;
            String token;
            while ((token = processor.nextToken()) != null) {
                if (task.isCancelled()) break;
                if (generatedTokens >= maxTokens) break;
                generatedTokens++;
                writer.write(token);
            }
        } else {
            processor.readMessage(writer);
        }
        String result = writer.toString();

        task.getSyncFuture().complete(result);
    }
}
