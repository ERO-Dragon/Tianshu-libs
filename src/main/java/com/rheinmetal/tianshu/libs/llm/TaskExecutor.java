package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.*;
import org.argeo.jjml.llm.params.DefaultSamplerChainParams;
import org.argeo.jjml.llm.util.ThinkingMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class TaskExecutor implements Runnable {

    private final LlamaEngine engine;
    private final List<SuspendedTask> suspendedTasks = new ArrayList<>();

    public TaskExecutor(LlamaEngine engine) {
        this.engine = engine;
    }

    @Override
    public void run() {
        System.out.println("[TaskExecutor] Worker loop started.");
        while (engine.isRunning()) {
            try {
                InferenceTask task = selectNextTask();
                if (task == null) continue;
                engine.setCurrentTask(task);
                if (!engine.isRunning()) {
                    task.cancel();
                    task.getSyncFuture().cancel(false);
                    clearSuspendedTask(task);
                    engine.finishTask(task);
                    break;
                }
                if (task.isCancelled()) {
                    task.getSyncFuture().cancel(false);
                    clearSuspendedTask(task);
                    engine.finishTask(task);
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
                engine.setCurrentTask(null);
            }
        }
        cancelSuspendedTasks();
        System.out.println("[TaskExecutor] Worker loop exited.");
    }

    private InferenceTask selectNextTask() throws InterruptedException {
        InferenceTask chatTask = engine.pollChatTaskNonBlocking();
        if (chatTask != null) return chatTask;
        
        SuspendedTask suspended = selectBestSuspendedTask();
        int queuedPriority = engine.peekTaskPriority();
        
        InferenceTask taskTask = engine.pollTaskTask();
        if (taskTask != null) {
            if (suspended != null && suspended.task.getTaskPriority() >= queuedPriority && 
                suspended.task.getTaskPriority() >= taskTask.getTaskPriority()) {
                engine.requeueTask(taskTask);
                return suspended.task;
            }
            return taskTask;
        }
        
        if (suspended != null) {
            return suspended.task;
        }
        
        engine.awaitTaskArrival();
        return null;
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
            if (task.isCancelled()) {
                task.getSyncFuture().cancel(false);
                engine.finishTask(task);
                return ExecutionResult.COMPLETED;
            }
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
            engine.finishTask(task);
            return ExecutionResult.COMPLETED;
        } finally {
            if (context != null) {
                try { context.close(); } catch (Exception ignored) {}
            }
        }
    }

    private LlamaCppSamplerChain buildSamplerChain(SamplerConfig config) {
        DefaultSamplerChainParams params = new DefaultSamplerChainParams(
                config.temperature(),
                0,
                0L,
                config.topK(),
                config.topP(),
                config.minP(),
                1.0F,
                1.0F,
                0.0F,
                1.0F,
                config.penaltyLastN(),
                config.penaltyRepeat(),
                config.penaltyFreq(),
                config.penaltyPresent(),
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
        FormattedPromptProcessor processor = new FormattedPromptProcessor(context, chain);
        writeMessages(processor, task);

        StringBuilder fullResponse = new StringBuilder();
        Consumer<String> callback = task.getStreamCallback();
        int generatedTokens = 0;
        while (task.getMaxTokens() <= 0 || generatedTokens < task.getMaxTokens()) {
            if (task.isCancelled()) break;
            String token = processor.nextToken();
            if (token == null) break;
            generatedTokens++;
            fullResponse.append(token);
            if (callback != null) callback.accept(token);
        }

        if (task.isCancelled()) {
            task.getSyncFuture().cancel(false);
            engine.finishTask(task);
            return ExecutionResult.COMPLETED;
        }
        task.getSyncFuture().complete(fullResponse.toString());
        engine.finishTask(task);
        return ExecutionResult.COMPLETED;
    }

    private ExecutionResult doTaskInference(LlamaCppContext context, LlamaCppSamplerChain chain, InferenceTask task) throws IOException {
        SuspendedTask state = removeSuspendedTask(task);
        if (state == null) state = new SuspendedTask(task);
        engine.setTaskSuspended(!suspendedTasks.isEmpty());
        FormattedPromptProcessor processor = new FormattedPromptProcessor(context, chain);

        if (state.savedState == null) {
            writeMessages(processor, task);
        } else {
            processor.loadContextState(state.savedState);
        }

        Consumer<String> callback = task.getStreamCallback();
        while (task.getMaxTokens() <= 0 || state.generatedTokens < task.getMaxTokens()) {
            if (task.isCancelled()) {
                task.getSyncFuture().cancel(false);
                clearSuspendedTask(task);
                engine.finishTask(task);
                return ExecutionResult.COMPLETED;
            }
            String token = processor.nextToken();
            if (token == null) break;
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
        engine.finishTask(task);
        return ExecutionResult.COMPLETED;
    }

    private void writeMessages(FormattedPromptProcessor processor, InferenceTask task) {
        SamplerConfig config = task.getSamplerConfig();
        ChatTemplateOptions options = chatTemplateOptions(config);

        String prompt = options.kwargs().isEmpty()
                ? engine.getModel().formatChatMessages(task.getMessages(), options.thinkingMode())
                : engine.getModel().formatChatMessagesJinja(task.getMessages(), true, options.thinkingMode(), options.kwargs());
        processor.writePreFormatted(prompt);
    }

    private ChatTemplateOptions chatTemplateOptions(SamplerConfig config) {
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

    private boolean parseBooleanKwarg(String key, String value) {
        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized)) return true;
        if ("false".equals(normalized)) return false;
        throw new IllegalArgumentException("chatTemplateKwargs." + key + " must be 'true' or 'false', got '" + value + "'");
    }

    private record ChatTemplateOptions(ThinkingMode thinkingMode, Map<String, String> kwargs) {
    }

    private void clearSuspendedTask(InferenceTask task) {
        removeSuspendedTask(task);
        engine.setTaskSuspended(!suspendedTasks.isEmpty());
    }

    private void cancelSuspendedTasks() {
        for (SuspendedTask suspended : suspendedTasks) {
            suspended.task.cancel();
            suspended.task.getSyncFuture().cancel(false);
            engine.finishTask(suspended.task);
        }
        suspendedTasks.clear();
        engine.setTaskSuspended(false);
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
