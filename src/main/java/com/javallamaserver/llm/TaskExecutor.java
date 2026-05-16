package com.javallamaserver.llm;

import org.argeo.jjml.llm.*;
import org.argeo.jjml.llm.params.DefaultSamplerChainParams;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class TaskExecutor implements Runnable {

    private final LlamaEngine engine;
    private final BlockingQueue<InferenceTask> chatQueue;
    private final List<SuspendedTask> suspendedTasks = new ArrayList<>();

    public TaskExecutor(LlamaEngine engine, BlockingQueue<InferenceTask> chatQueue) {
        this.engine = engine;
        this.chatQueue = chatQueue;
    }

    @Override
    public void run() {
        System.out.println("[TaskExecutor] Worker loop started.");
        while (engine.isRunning()) {
            try {
                InferenceTask task = selectNextTask();
                if (task == null) continue;
                if (task.isCancelled()) {
                    task.getSyncFuture().cancel(false);
                    continue;
                }
                System.out.println("[TaskExecutor] Processing task " + task.getTaskId()
                        + " type=" + task.getTaskType()
                        + " lane=" + task.getLane().wireName());
                ExecutionResult result = executeTask(task);
                if (result == ExecutionResult.COMPLETED) {
                    System.out.println("[TaskExecutor] Task " + task.getTaskId() + " completed.");
                } else if (result == ExecutionResult.SUSPENDED) {
                    System.out.println("[TaskExecutor] Task " + task.getTaskId() + " suspended.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[TaskExecutor] Fatal error in worker loop: " + e.getMessage());
                e.printStackTrace();
            } finally {
                engine.setCurrentLane(null);
            }
        }
        System.out.println("[TaskExecutor] Worker loop exited.");
    }

    private InferenceTask selectNextTask() throws InterruptedException {
        InferenceTask chatTask = engine.pollChatTask(1, TimeUnit.SECONDS);
        if (chatTask != null) return chatTask;
        SuspendedTask suspended = selectBestSuspendedTask();
        int queuedPriority = engine.peekTaskPriority();
        if (suspended != null && suspended.task.getTaskPriority() >= queuedPriority) return suspended.task;
        InferenceTask queuedTask = engine.pollTaskTask();
        if (queuedTask != null) return queuedTask;
        return suspended == null ? null : suspended.task;
    }

    private SuspendedTask selectBestSuspendedTask() {
        SuspendedTask best = null;
        for (SuspendedTask suspended : suspendedTasks) {
            if (best == null || suspended.task.getTaskPriority() > best.task.getTaskPriority()) {
                best = suspended;
            }
        }
        return best;
    }

    private SuspendedTask findSuspendedTask(InferenceTask task) {
        for (SuspendedTask suspended : suspendedTasks) {
            if (suspended.task == task) return suspended;
        }
        return null;
    }

    private void parkSuspendedTask(SuspendedTask state) {
        if (!suspendedTasks.contains(state)) suspendedTasks.add(state);
        engine.setTaskSuspended(!suspendedTasks.isEmpty());
    }

    private SuspendedTask removeSuspendedTask(InferenceTask task) {
        for (int i = 0; i < suspendedTasks.size(); i++) {
            SuspendedTask suspended = suspendedTasks.get(i);
            if (suspended.task == task) return suspendedTasks.remove(i);
        }
        return null;
    }

    private ExecutionResult executeTask(InferenceTask task) {
        if (findSuspendedTask(task) != null && task.getLane() != InferenceLane.TASK) {
            return ExecutionResult.SUSPENDED;
        }
        LlamaCppContext context = null;
        try {
            engine.setCurrentLane(task.getLane());
            context = engine.createContext(task.getLane());
            LlamaCppSamplerChain chain = buildSamplerChain(task.getSamplerConfig());

            ExecutionResult result;
            if (task.getLane() == InferenceLane.TASK) {
                result = doTaskInference(context, chain, task);
            } else {
                result = doChatInference(context, chain, task);
            }
            return result;
        } catch (Exception e) {
            System.err.println("[TaskExecutor] Task " + task.getTaskId() + " failed: " + e.getMessage());
            e.printStackTrace();
            task.getSyncFuture().completeExceptionally(e);
            if (!suspendedTasks.isEmpty() && findSuspendedTask(task) != null) {
                removeSuspendedTask(task);
                engine.setTaskSuspended(!suspendedTasks.isEmpty());
            }
            return ExecutionResult.COMPLETED;
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

    private ExecutionResult doChatInference(LlamaCppContext context, LlamaCppSamplerChain chain, InferenceTask task) throws IOException {
        LlamaCppInstructProcessor processor = new LlamaCppInstructProcessor(context, chain);
        writeMessages(processor, task);

        StringBuilder fullResponse = new StringBuilder();
        Consumer<String> callback = task.getStreamCallback();
        String token;
        int generatedTokens = 0;
        while ((token = processor.nextToken()) != null) {
            if (task.isCancelled()) break;
            if (task.getMaxTokens() > 0 && generatedTokens >= task.getMaxTokens()) break;
            generatedTokens++;
            fullResponse.append(token);
            if (callback != null) callback.accept(token);
        }

        task.getSyncFuture().complete(fullResponse.toString());
        return ExecutionResult.COMPLETED;
    }

    private ExecutionResult doTaskInference(LlamaCppContext context, LlamaCppSamplerChain chain, InferenceTask task) throws IOException {
        SuspendedTask state = findSuspendedTask(task);
        if (state == null) state = new SuspendedTask(task);
        LlamaCppInstructProcessor processor = new LlamaCppInstructProcessor(context, chain);

        if (state.savedState == null) {
            writeMessages(processor, task);
        } else {
            processor.loadContextState(state.savedState);
        }

        String token;
        Consumer<String> callback = task.getStreamCallback();
        while ((token = processor.nextToken()) != null) {
            if (task.isCancelled()) {
                task.getSyncFuture().cancel(false);
                clearSuspendedTask(task);
                return ExecutionResult.COMPLETED;
            }
            if (task.getMaxTokens() > 0 && state.generatedTokens >= task.getMaxTokens()) break;
            state.generatedTokens++;
            state.generatedText.append(token);
            if (callback != null) callback.accept(token);
            if (engine.shouldSuspendTaskLane(task)) {
                LlamaCppContextState.ByteBufferSavedState savedState = new LlamaCppContextState.ByteBufferSavedState();
                processor.saveContextState(savedState);
                state.savedState = savedState;
                parkSuspendedTask(state);
                return ExecutionResult.SUSPENDED;
            }
        }

        task.getSyncFuture().complete(state.generatedText.toString());
        clearSuspendedTask(task);
        return ExecutionResult.COMPLETED;
    }

    private void writeMessages(LlamaCppInstructProcessor processor, InferenceTask task) {
        for (LlamaCppChatMessage msg : task.getMessages()) {
            processor.write(msg.getRole(), msg.getContent());
        }
    }

    private void clearSuspendedTask(InferenceTask task) {
        removeSuspendedTask(task);
        engine.setTaskSuspended(!suspendedTasks.isEmpty());
    }

    private enum ExecutionResult {
        COMPLETED,
        SUSPENDED
    }

    private static class SuspendedTask {
        private final InferenceTask task;
        private final StringBuilder generatedText = new StringBuilder();
        private int generatedTokens;
        private LlamaCppContextState.ByteBufferSavedState savedState;

        private SuspendedTask(InferenceTask task) {
            this.task = task;
        }
    }
}
