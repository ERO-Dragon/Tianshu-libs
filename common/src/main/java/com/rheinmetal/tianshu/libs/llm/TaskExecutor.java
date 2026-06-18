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
                    engine.publishInferenceEvent(task, InferenceEventType.CANCELLED, "Inference was cancelled because engine stopped.");
                    engine.finishTask(task);
                    break;
                }
                if (task.isCancelled()) {
                    task.getSyncFuture().cancel(false);
                    closeSuspendedTask(task);
                    engine.publishInferenceEvent(task, InferenceEventType.CANCELLED, "Inference was cancelled before execution.");
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
        
        InferenceTask taskTask = engine.pollTaskTask();
        if (taskTask != null) return taskTask;
        
        engine.awaitTaskArrival();
        return null;
    }

    private SuspendedTask findSuspendedTask(InferenceTask task) {
        SuspendedTask cold = coldTasks.get(task);
        if (cold != null) return cold;
        return null;
    }

    private void parkSuspendedTask(SuspendedTask state) {
        coldParkSuspendedTask(state);
    }

    private void coldParkSuspendedTask(SuspendedTask state) {
        state.coolDown();
        coldTasks.put(state.task, state);
        engine.publishInferenceEvent(
                state.task,
                InferenceEventType.SUSPENDED,
                "Task was suspended and will be cold-resumed later.",
                state.replayCharacters(),
                state.generatedTokens,
                null
        );
        engine.requeueTask(state.task);
        engine.setTaskSuspended(hasSuspendedTasks());
    }

    private void purgeCancelledSuspendedTasks() {
        purgeCancelledColdTasks();
        engine.setTaskSuspended(hasSuspendedTasks());
    }

    private void purgeCancelledColdTasks() {
        List<InferenceTask> cancelled = new ArrayList<>();
        for (Map.Entry<InferenceTask, SuspendedTask> entry : coldTasks.entrySet()) {
            if (!entry.getKey().isCancelled()) continue;
            cancelled.add(entry.getKey());
            entry.getValue().close();
            entry.getKey().getSyncFuture().cancel(false);
            engine.publishInferenceEvent(entry.getKey(), InferenceEventType.CANCELLED, "Task was cancelled while suspended.");
            engine.finishTask(entry.getKey());
        }
        for (InferenceTask task : cancelled) {
            coldTasks.remove(task);
        }
    }

    private SuspendedTask removeSuspendedTask(InferenceTask task) {
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
                engine.publishInferenceEvent(task, InferenceEventType.CANCELLED, "Inference was cancelled.");
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
            engine.publishInferenceEvent(task, InferenceEventType.FAILED, "Inference failed: " + e.getMessage(), e);
            if (findSuspendedTask(task) != null) {
                closeSuspendedTask(task);
                engine.setTaskSuspended(hasSuspendedTasks());
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
            engine.publishInferenceEvent(task, InferenceEventType.STARTED, "Chat inference started.");
            engine.publishInferenceEvent(task, InferenceEventType.PREFILL_STARTED, "Chat prefill started.");
            writeMessages(processor, task);
            engine.publishInferenceEvent(task, InferenceEventType.PREFILL_COMPLETED, "Chat prefill completed.");
            engine.publishInferenceEvent(task, InferenceEventType.GENERATION_STARTED, "Chat generation started.");

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
                engine.publishInferenceEvent(task, InferenceEventType.CANCELLED, "Chat inference was cancelled.");
                engine.finishTask(task);
                return ExecutionResult.COMPLETED;
            }
            task.getSyncFuture().complete(fullResponse.toString());
            engine.publishInferenceEvent(task, InferenceEventType.COMPLETED, "Chat inference completed.", 0, generatedTokens, null);
            engine.finishTask(task);
            return ExecutionResult.COMPLETED;
        } finally {
            try { context.close(); } catch (Exception ignored) {}
        }
    }

    private ExecutionResult doTaskInference(InferenceTask task) throws IOException {
        SuspendedTask state = removeSuspendedTask(task);
        boolean resumed = state != null;
        if (state == null) state = new SuspendedTask(task);
        engine.setTaskSuspended(hasSuspendedTasks());
        if (resumed) {
            engine.publishInferenceEvent(
                    task,
                    InferenceEventType.COLD_RESUME_STARTED,
                    "Task cold resume started; previous output will be replayed before generation continues.",
                    state.replayCharacters(),
                    state.generatedTokens,
                    null
            );
        } else {
            engine.publishInferenceEvent(task, InferenceEventType.STARTED, "Task inference started.");
        }
        try {
            if (state.processor == null) {
                state.context = engine.createContext(task.getLane());
                state.chain = buildSamplerChain(task.getSamplerConfig());
                state.processor = new FormattedPromptProcessor(state.context, state.chain);
                if (state.formattedPrompt == null) {
                    state.formattedPrompt = formatMessages(task);
                }
                engine.publishInferenceEvent(
                        task,
                        InferenceEventType.PREFILL_STARTED,
                        resumed ? "Task cold replay/prefill started." : "Task prefill started.",
                        state.replayCharacters(),
                        state.generatedTokens,
                        null
                );
                state.processor.writePreFormatted(state.formattedPrompt + state.rawGeneratedText);
                engine.publishInferenceEvent(
                        task,
                        InferenceEventType.PREFILL_COMPLETED,
                        resumed ? "Task cold replay/prefill completed." : "Task prefill completed.",
                        state.replayCharacters(),
                        state.generatedTokens,
                        null
                );
                if (resumed) {
                    engine.publishInferenceEvent(
                            task,
                            InferenceEventType.COLD_RESUME_COMPLETED,
                            "Task cold resume completed; generation can continue.",
                            state.replayCharacters(),
                            state.generatedTokens,
                            null
                    );
                }
            }
            engine.publishInferenceEvent(task, InferenceEventType.GENERATION_STARTED, "Task generation started.", state.replayCharacters(), state.generatedTokens, null);

            FormattedPromptProcessor processor = state.processor;
            Consumer<String> callback = task.getStreamCallback();
            ReasoningTagNormalizer reasoningNormalizer = state.reasoningNormalizer;
            while (task.getMaxTokens() <= 0 || state.generatedTokens < task.getMaxTokens()) {
                if (task.isCancelled()) {
                    task.getSyncFuture().cancel(false);
                    clearSuspendedTask(task);
                    state.close();
                    engine.publishInferenceEvent(task, InferenceEventType.CANCELLED, "Task inference was cancelled.", state.replayCharacters(), state.generatedTokens, null);
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
            engine.publishInferenceEvent(task, InferenceEventType.COMPLETED, "Task inference completed.", state.replayCharacters(), state.generatedTokens, null);
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
        engine.setTaskSuspended(hasSuspendedTasks());
    }

    private void closeSuspendedTask(InferenceTask task) {
        SuspendedTask suspended = removeSuspendedTask(task);
        if (suspended != null) suspended.close();
        engine.setTaskSuspended(hasSuspendedTasks());
    }

    private void cancelSuspendedTasks() {
        for (SuspendedTask suspended : coldTasks.values()) {
            suspended.task.cancel();
            suspended.task.getSyncFuture().cancel(false);
            suspended.close();
            engine.publishInferenceEvent(suspended.task, InferenceEventType.CANCELLED, "Task was cancelled during shutdown.");
            engine.finishTask(suspended.task);
        }
        coldTasks.clear();
        engine.setTaskSuspended(false);
    }

    private boolean hasSuspendedTasks() {
        return !coldTasks.isEmpty();
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
        }

        private void coolDown() {
            close();
        }

        private int replayCharacters() {
            int promptLength = formattedPrompt == null ? 0 : formattedPrompt.length();
            return promptLength + rawGeneratedText.length();
        }
    }
}
