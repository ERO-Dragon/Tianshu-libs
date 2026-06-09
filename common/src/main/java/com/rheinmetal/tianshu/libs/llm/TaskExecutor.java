package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.*;
import org.argeo.jjml.llm.params.DefaultSamplerChainParams;
import org.argeo.jjml.llm.util.ThinkingMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class TaskExecutor implements Runnable {

    private final LlamaEngine engine;
    private final List<SuspendedTask> suspendedTasks = new ArrayList<>();
    private final Map<InferenceTask, SuspendedTask> coldTasks = new IdentityHashMap<>();

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
                    closeSuspendedTask(task);
                    engine.finishTask(task);
                    break;
                }
                if (task.isCancelled()) {
                    task.getSyncFuture().cancel(false);
                    closeSuspendedTask(task);
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
        purgeCancelledSuspendedTasks();
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
        purgeCancelledSuspendedTasks();
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
        SuspendedTask cold = coldTasks.get(task);
        if (cold != null) return cold;
        return null;
    }

    private void parkSuspendedTask(SuspendedTask state) {
        if (!state.hot) {
            engine.requeueTask(state.task);
            return;
        }
        if (!suspendedTasks.contains(state)) suspendedTasks.add(state);
        enforceHotSuspendLimit();
        engine.setTaskSuspended(!suspendedTasks.isEmpty());
    }

    private void coldParkSuspendedTask(SuspendedTask state) {
        state.coolDown();
        coldTasks.put(state.task, state);
        engine.requeueTask(state.task);
        engine.setTaskSuspended(!suspendedTasks.isEmpty());
    }

    private void enforceHotSuspendLimit() {
        int limit = Math.max(0, engine.getHotSuspendLimit());
        while (suspendedTasks.size() > limit) {
            int coldIndex = indexOfLowestPrioritySuspendedTask();
            if (coldIndex < 0) return;
            SuspendedTask cold = suspendedTasks.remove(coldIndex);
            coldParkSuspendedTask(cold);
        }
    }

    private int indexOfLowestPrioritySuspendedTask() {
        int bestIndex = -1;
        for (int i = 0; i < suspendedTasks.size(); i++) {
            SuspendedTask candidate = suspendedTasks.get(i);
            if (bestIndex < 0
                    || candidate.task.getTaskPriority() < suspendedTasks.get(bestIndex).task.getTaskPriority()) {
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private void purgeCancelledSuspendedTasks() {
        for (int i = suspendedTasks.size() - 1; i >= 0; i--) {
            SuspendedTask suspended = suspendedTasks.get(i);
            if (!suspended.task.isCancelled()) continue;
            suspendedTasks.remove(i);
            coldTasks.remove(suspended.task);
            suspended.task.getSyncFuture().cancel(false);
            suspended.close();
            engine.finishTask(suspended.task);
        }
        purgeCancelledColdTasks();
        engine.setTaskSuspended(!suspendedTasks.isEmpty());
    }

    private void purgeCancelledColdTasks() {
        List<InferenceTask> cancelled = new ArrayList<>();
        for (Map.Entry<InferenceTask, SuspendedTask> entry : coldTasks.entrySet()) {
            if (!entry.getKey().isCancelled()) continue;
            cancelled.add(entry.getKey());
            entry.getValue().close();
            entry.getKey().getSyncFuture().cancel(false);
            engine.finishTask(entry.getKey());
        }
        for (InferenceTask task : cancelled) {
            coldTasks.remove(task);
        }
    }

    private SuspendedTask removeSuspendedTask(InferenceTask task) {
        for (int i = 0; i < suspendedTasks.size(); i++) {
            SuspendedTask suspended = suspendedTasks.get(i);
            if (suspended.task == task) return suspendedTasks.remove(i);
        }
        SuspendedTask cold = coldTasks.remove(task);
        if (cold != null) return cold;
        return null;
    }

    private ExecutionResult executeTask(InferenceTask task) {
        if (findSuspendedTask(task) != null && task.getLane() != InferenceLane.TASK) {
            return ExecutionResult.SUSPENDED;
        }
        try {
            if (task.isCancelled()) {
                task.getSyncFuture().cancel(false);
                engine.finishTask(task);
                return ExecutionResult.COMPLETED;
            }
            engine.setCurrentLane(task.getLane());

            ExecutionResult result;
            if (task.getLane() == InferenceLane.TASK) {
                result = doTaskInference(task);
            } else {
                LlamaCppContext context = engine.createContext(task.getLane());
                LlamaCppSamplerChain chain = buildSamplerChain(task.getSamplerConfig());
                result = doChatInference(context, chain, task);
            }
            return result;
        } catch (Exception e) {
            System.err.println("[TaskExecutor] Task " + task.getTaskId() + " failed: " + e.getMessage());
            e.printStackTrace();
            task.getSyncFuture().completeExceptionally(e);
            if (!suspendedTasks.isEmpty() && findSuspendedTask(task) != null) {
                closeSuspendedTask(task);
                engine.setTaskSuspended(!suspendedTasks.isEmpty());
            }
            engine.finishTask(task);
            return ExecutionResult.COMPLETED;
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
        try {
            FormattedPromptProcessor processor = new FormattedPromptProcessor(context, chain);
            writeMessages(processor, task);

            StringBuilder fullResponse = new StringBuilder();
            ReasoningTagNormalizer reasoningNormalizer = new ReasoningTagNormalizer();
            Consumer<String> callback = task.getStreamCallback();
            int generatedTokens = 0;
            while (task.getMaxTokens() <= 0 || generatedTokens < task.getMaxTokens()) {
                if (task.isCancelled()) break;
                String token = processor.nextToken();
                if (token == null) break;
                generatedTokens++;
                String normalizedToken = reasoningNormalizer.accept(token);
                fullResponse.append(normalizedToken);
                if (callback != null && !normalizedToken.isEmpty()) callback.accept(normalizedToken);
            }
            String normalizedRemainder = reasoningNormalizer.finish();
            fullResponse.append(normalizedRemainder);
            if (callback != null && !normalizedRemainder.isEmpty()) callback.accept(normalizedRemainder);

            if (task.isCancelled()) {
                task.getSyncFuture().cancel(false);
                engine.finishTask(task);
                return ExecutionResult.COMPLETED;
            }
            task.getSyncFuture().complete(fullResponse.toString());
            engine.finishTask(task);
            return ExecutionResult.COMPLETED;
        } finally {
            try { context.close(); } catch (Exception ignored) {}
        }
    }

    private ExecutionResult doTaskInference(InferenceTask task) throws IOException {
        SuspendedTask state = removeSuspendedTask(task);
        if (state == null) state = new SuspendedTask(task);
        engine.setTaskSuspended(!suspendedTasks.isEmpty());
        try {
            if (state.processor == null) {
                state.context = engine.createContext(task.getLane());
                state.chain = buildSamplerChain(task.getSamplerConfig());
                state.processor = new FormattedPromptProcessor(state.context, state.chain);
                if (state.formattedPrompt == null) {
                    state.formattedPrompt = formatMessages(task);
                }
                state.processor.writePreFormatted(state.formattedPrompt + state.rawGeneratedText);
                state.hot = true;
            }

            FormattedPromptProcessor processor = state.processor;
            Consumer<String> callback = task.getStreamCallback();
            ReasoningTagNormalizer reasoningNormalizer = state.reasoningNormalizer;
            while (task.getMaxTokens() <= 0 || state.generatedTokens < task.getMaxTokens()) {
                if (task.isCancelled()) {
                    task.getSyncFuture().cancel(false);
                    clearSuspendedTask(task);
                    state.close();
                    engine.finishTask(task);
                    return ExecutionResult.COMPLETED;
                }
                String token = processor.nextToken();
                if (token == null) break;
                state.generatedTokens++;
                state.rawGeneratedText.append(token);
                String normalizedToken = reasoningNormalizer.accept(token);
                state.generatedText.append(normalizedToken);
                if (callback != null && !normalizedToken.isEmpty()) callback.accept(normalizedToken);
                if (engine.shouldSuspendTaskLane(task)) {
                    parkSuspendedTask(state);
                    return ExecutionResult.SUSPENDED;
                }
            }
            String normalizedRemainder = reasoningNormalizer.finish();
            state.generatedText.append(normalizedRemainder);
            if (callback != null && !normalizedRemainder.isEmpty()) callback.accept(normalizedRemainder);

            task.getSyncFuture().complete(state.generatedText.toString());
            clearSuspendedTask(task);
            state.close();
            engine.finishTask(task);
            return ExecutionResult.COMPLETED;
        } catch (RuntimeException e) {
            state.close();
            throw e;
        }
    }

    private void writeMessages(FormattedPromptProcessor processor, InferenceTask task) {
        processor.writePreFormatted(formatMessages(task));
    }

    private String formatMessages(InferenceTask task) {
        SamplerConfig config = task.getSamplerConfig();
        ChatTemplateOptions options = chatTemplateOptions(config);

        return options.kwargs().isEmpty()
                ? engine.getModel().formatChatMessages(task.getMessages(), options.thinkingMode())
                : engine.getModel().formatChatMessagesJinja(task.getMessages(), true, options.thinkingMode(), options.kwargs());
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

    private void closeSuspendedTask(InferenceTask task) {
        SuspendedTask suspended = removeSuspendedTask(task);
        if (suspended != null) suspended.close();
        engine.setTaskSuspended(!suspendedTasks.isEmpty());
    }

    private void cancelSuspendedTasks() {
        for (SuspendedTask suspended : suspendedTasks) {
            suspended.task.cancel();
            suspended.task.getSyncFuture().cancel(false);
            suspended.close();
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
        private final StringBuilder rawGeneratedText = new StringBuilder();
        private final ReasoningTagNormalizer reasoningNormalizer = new ReasoningTagNormalizer();
        private String formattedPrompt;
        private LlamaCppContext context;
        private LlamaCppSamplerChain chain;
        private FormattedPromptProcessor processor;
        private int generatedTokens;
        private boolean hot;

        private SuspendedTask(InferenceTask task) {
            this.task = task;
        }

        private void close() {
            processor = null;
            chain = null;
            if (context != null) {
                try { context.close(); } catch (Exception ignored) {}
                context = null;
            }
            hot = false;
        }

        private void coolDown() {
            close();
        }
    }
}
