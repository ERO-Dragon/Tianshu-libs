package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.*;
import org.argeo.jjml.llm.params.DefaultSamplerChainParams;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class TaskExecutor implements Runnable {
    private static final int STANDARD_CONTEXT_CHECKPOINT_INTERVAL_TOKENS = 64;
    private static final int MTP_CALIBRATION_CONTEXT_RESERVE = 64;
    private static final String MTP_CALIBRATION_SYSTEM_PROMPT =
            "You are a deterministic benchmark assistant. Use the provided long context only as reference data. "
                    + "Answer the final instruction directly and do not invent external facts.";
    private static final String MTP_CALIBRATION_USER_PREFIX =
            "The following is a long Minecraft operations log used to benchmark long-context local inference. "
                    + "Read it as context, keep track of constraints, and answer the final instruction after the log.\n\n";
    private static final String MTP_CALIBRATION_BLOCK = """
            Region note: The overworld base contains a storage hall, a villager trading floor, a redstone clock tower,
            a crop terrace, a copper roof under oxidation control, and a minecart line that passes through three chunk
            borders. The player wants safe automation without blocking villagers, breaking item filters, or creating
            lag spikes during combat. A skeleton farm sends bones into the sorter, a kelp farm sends fuel into the
            smelter, and a bamboo module is reserved for future scaffolding production.
            Task constraint: Keep entrances lit, preserve two-wide paths for villagers, never place lava near wooden
            stairs, keep water streams covered where mobs can pathfind, label overflow chests, and prefer reversible
            changes. If a route crosses a ravine, use slabs on the lower half and place torches every seventh block.
            Inventory note: Useful materials include stone bricks, spruce trapdoors, smooth basalt, powered rails,
            repeaters, comparators, hoppers, barrels, glass panes, moss carpets, candles, ladders, buckets, and spare
            shulker boxes. Avoid spending diamonds unless the plan directly improves survival or navigation.
            World note: Nearby structures include a taiga village to the north, a ruined portal beside a river bend,
            a dripstone cave under the sheep pen, and a stronghold route marked by blue wool. The Nether hub is compact
            and uses ice boat lanes, so any recommendation should avoid sending casual players through unmarked portals.
            Safety note: Before cave work, players should bring food, shields, torches, blocks, water, spare tools,
            and a clear exit marker. They should avoid digging straight down, avoid fighting near lava ledges, and
            retreat when inventory or durability becomes risky.
            """;
    private static final String MTP_CALIBRATION_FINAL_INSTRUCTION =
            "\nFinal instruction: In exactly four concise bullet points, recommend how the player should prepare a "
                    + "safe cave expedition while respecting the storage, villager, automation, and route constraints above.";

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
                    task.getGenerationFuture().cancel(false);
                    task.getMtpCalibrationFuture().cancel(false);
                    closeSuspendedTask(task);
                    publishStreamFinish(task, StreamFinishType.CANCELLED, new LlmTokenUsage(0, 0), null);
                    engine.publishInferenceEvent(task, InferenceEventType.CANCELLED, "Inference was cancelled because engine stopped.");
                    engine.finishTask(task);
                    break;
                }
                if (task.isCancelled()) {
                    task.getSyncFuture().cancel(false);
                    task.getGenerationFuture().cancel(false);
                    task.getMtpCalibrationFuture().cancel(false);
                    closeSuspendedTask(task);
                    publishStreamFinish(task, StreamFinishType.CANCELLED, new LlmTokenUsage(0, 0), null);
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
            entry.getKey().getGenerationFuture().cancel(false);
            entry.getKey().getMtpCalibrationFuture().cancel(false);
            publishStreamFinish(entry.getKey(), StreamFinishType.CANCELLED, entry.getValue().usage(), null);
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
                task.getGenerationFuture().cancel(false);
                task.getMtpCalibrationFuture().cancel(false);
                publishStreamFinish(task, StreamFinishType.CANCELLED, new LlmTokenUsage(0, 0), null);
                engine.publishInferenceEvent(task, InferenceEventType.CANCELLED, "Inference was cancelled.");
                engine.finishTask(task);
                return ExecutionResult.COMPLETED;
            }
            engine.setCurrentLane(task.getLane());

            ExecutionResult result;
            try (VulkanInferencePriorityScope ignored = VulkanInferencePriorityScope.apply(task.getInferenceOptions().getVulkanPriority())) {
                if (task.getTaskType() == InferenceTask.TaskType.MTP_CALIBRATION) {
                    result = doMtpCalibration(task);
                } else if (task.getLane() == InferenceLane.TASK) {
                    result = doTaskInference(task);
                } else {
                    result = doChatInference(task);
                }
            }
            return result;
        } catch (Exception e) {
            System.err.println("[TaskExecutor] Task " + task.getTaskId() + " failed: " + e.getMessage());
            e.printStackTrace();
            task.getSyncFuture().completeExceptionally(e);
            task.getGenerationFuture().completeExceptionally(e);
            task.getMtpCalibrationFuture().completeExceptionally(e);
            publishStreamFinish(task, StreamFinishType.FAILED, new LlmTokenUsage(0, 0), e);
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

    private ExecutionResult doChatInference(InferenceTask task) {
        InferenceTokenGenerator generator = null;
        try {
            engine.publishInferenceEvent(task, InferenceEventType.STARTED, "Chat inference started.");
            engine.publishInferenceEvent(task, InferenceEventType.PREFILL_STARTED, "Chat prefill started.");
            PromptSnapshot prompt = promptSnapshot(task);
            int completionLimit = resolveCompletionLimit(task, prompt, engine.getChatLaneConfig());
            if (completionLimit <= 0) {
                LlmTokenUsage usage = new LlmTokenUsage(prompt.tokenIds().length, 0);
                engine.publishInferenceEvent(task, InferenceEventType.PREFILL_COMPLETED, "Chat prefill completed.");
                completeGeneration(task, "", usage);
                engine.publishInferenceEvent(task, InferenceEventType.COMPLETED, "Chat inference completed.", 0, 0, null);
                engine.finishTask(task);
                return ExecutionResult.COMPLETED;
            }
            generator = createTokenGenerator(task, prompt);
            engine.publishInferenceEvent(task, InferenceEventType.PREFILL_COMPLETED, "Chat prefill completed.");
            engine.publishInferenceEvent(task, InferenceEventType.GENERATION_STARTED, "Chat generation started.");

            StringBuilder fullResponse = new StringBuilder();
            StringBuilder thinkingContent = new StringBuilder();
            ReasoningTagNormalizer reasoningNormalizer = new ReasoningTagNormalizer(
                    task.getInferenceOptions().isCaptureThinkingContent()
            );
            int generatedTokens = 0;
            int completionTokens = 0;
            while (generatedTokens < completionLimit) {
                if (task.isCancelled()) break;
                int remaining = completionLimit - generatedTokens;
                GeneratedToken generated = generator.next(remaining);
                if (generated == null) break;
                generatedTokens++;
                ReasoningTagNormalizer.AcceptResult accepted = reasoningNormalizer.acceptWithUsage(generated.text());
                if (accepted.visibleCompletion()) completionTokens++;
                appendAccepted(task, fullResponse, thinkingContent, accepted);
            }
            ReasoningTagNormalizer.AcceptResult remainder = reasoningNormalizer.finishWithUsage();
            appendAccepted(task, fullResponse, thinkingContent, remainder);

            LlmTokenUsage usage = new LlmTokenUsage(prompt.tokenIds().length, completionTokens);
            if (task.isCancelled()) {
                task.getSyncFuture().cancel(false);
                task.getGenerationFuture().cancel(false);
                publishStreamFinish(task, StreamFinishType.CANCELLED, usage, null, thinkingContent.toString());
                engine.publishInferenceEvent(task, InferenceEventType.CANCELLED, "Chat inference was cancelled.");
                engine.finishTask(task);
                return ExecutionResult.COMPLETED;
            }
            recordMtpStats(generator);
            completeGeneration(task, fullResponse.toString(), usage, thinkingContent.toString());
            engine.publishInferenceEvent(task, InferenceEventType.COMPLETED, "Chat inference completed.", 0, generatedTokens, null);
            engine.finishTask(task);
            return ExecutionResult.COMPLETED;
        } finally {
            if (generator != null) generator.close();
        }
    }

    private ExecutionResult doTaskInference(InferenceTask task) {
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
            if (state.generator == null) {
                if (state.prompt == null) {
                    state.prompt = promptSnapshot(task);
                }
                int completionLimit = resolveCompletionLimit(task, state.prompt, engine.getTaskLaneConfig());
                if (state.generatedTokens >= completionLimit) {
                    ReasoningTagNormalizer.AcceptResult remainder = state.reasoningNormalizer.finishWithUsage();
                    state.appendAccepted(remainder);
                    completeGeneration(task, state.generatedText.toString(), state.usage(), state.thinkingContent.toString());
                    clearSuspendedTask(task);
                    state.close();
                    engine.publishInferenceEvent(task, InferenceEventType.COMPLETED, "Task inference completed.", state.replayCharacters(), state.generatedTokens, null);
                    engine.finishTask(task);
                    return ExecutionResult.COMPLETED;
                }
                engine.publishInferenceEvent(
                        task,
                        InferenceEventType.PREFILL_STARTED,
                        resumed ? "Task cold replay/prefill started." : "Task prefill started.",
                        state.replayCharacters(),
                        state.generatedTokens,
                        null
                );
                state.generator = createResumableTokenGenerator(task, state);
                state.captureCheckpointIfDue(STANDARD_CONTEXT_CHECKPOINT_INTERVAL_TOKENS);
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

            InferenceTokenGenerator generator = state.generator;
            ReasoningTagNormalizer reasoningNormalizer = state.reasoningNormalizer;
            int completionLimit = resolveCompletionLimit(task, state.prompt, engine.getTaskLaneConfig());
            while (state.generatedTokens < completionLimit) {
                if (task.isCancelled()) {
                    task.getSyncFuture().cancel(false);
                    task.getGenerationFuture().cancel(false);
                    task.getMtpCalibrationFuture().cancel(false);
                    clearSuspendedTask(task);
                    publishStreamFinish(task, StreamFinishType.CANCELLED, state.usage(), null, state.thinkingContent.toString());
                    state.close();
                    engine.publishInferenceEvent(task, InferenceEventType.CANCELLED, "Task inference was cancelled.", state.replayCharacters(), state.generatedTokens, null);
                    engine.finishTask(task);
                    return ExecutionResult.COMPLETED;
                }
                int remaining = completionLimit - state.generatedTokens;
                GeneratedToken token = generator.next(remaining);
                if (token == null) break;
                state.generatedTokens++;
                state.generatedTokenIds.add(token.id());
                state.rawGeneratedText.append(token.text());
                ReasoningTagNormalizer.AcceptResult accepted = reasoningNormalizer.acceptWithUsage(token.text());
                if (accepted.visibleCompletion()) state.completionTokens++;
                state.appendAccepted(accepted);
                if (engine.shouldSuspendTaskLane(task)) {
                    parkSuspendedTask(state);
                    return ExecutionResult.SUSPENDED;
                }
                state.captureCheckpointIfDue(STANDARD_CONTEXT_CHECKPOINT_INTERVAL_TOKENS);
            }
            ReasoningTagNormalizer.AcceptResult remainder = reasoningNormalizer.finishWithUsage();
            state.appendAccepted(remainder);

            completeGeneration(task, state.generatedText.toString(), state.usage(), state.thinkingContent.toString());
            recordMtpStats(state.generator);
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

    private InferenceTokenGenerator createTokenGenerator(InferenceTask task, PromptSnapshot prompt) {
        int draftMax = engine.resolveMtpDraftMax(task.getInferenceOptions());
        if (draftMax > 0) {
            try {
                return createMtpTokenGenerator(task, prompt.tokenIds(), draftMax);
            } catch (RuntimeException e) {
                System.err.println("[TaskExecutor] MTP unavailable for task " + task.getTaskId()
                        + ", falling back to standard decoding: " + e.getMessage());
            }
        }
        return createStandardTokenGenerator(task, prompt.tokenIds());
    }

    private int resolveCompletionLimit(InferenceTask task, PromptSnapshot prompt, LaneConfig laneConfig) {
        return GenerationBudget.resolveCompletionLimit(
                task.getMaxTokens(),
                engine.getPlannedContextSize(laneConfig.getLane()),
                prompt.tokenIds().length,
                engine.getContextBudgetPolicy()
        );
    }

    private InferenceTokenGenerator createResumableTokenGenerator(InferenceTask task, SuspendedTask state) {
        int draftMax = engine.resolveMtpDraftMax(task.getInferenceOptions());
        int[] fullTokenIds = TokenIds.concat(state.prompt.tokenIds(), state.generatedTokenIds);
        int promptTokenCount = state.prompt.tokenIds().length;
        int generatedTokenCount = state.generatedTokenIds.size();
        GenerationCheckpoint checkpoint = state.checkpoint;
        if (draftMax > 0) {
            if (checkpoint != null && checkpoint.isMtpTarget() && checkpoint.generatedTokenCount() <= generatedTokenCount) {
                try {
                    return createMtpTokenGeneratorFromCheckpoint(
                            task,
                            fullTokenIds,
                            promptTokenCount,
                            generatedTokenCount,
                            checkpoint,
                            draftMax
                    );
                } catch (RuntimeException e) {
                    System.err.println("[TaskExecutor] MTP restored-target resume unavailable for task "
                            + task.getTaskId() + ", falling back to full MTP replay: " + e.getMessage());
                }
            }
            try {
                return createMtpTokenGenerator(task, fullTokenIds, promptTokenCount, generatedTokenCount, draftMax);
            } catch (RuntimeException e) {
                System.err.println("[TaskExecutor] MTP unavailable during resume for task " + task.getTaskId()
                        + ", falling back to standard decoding: " + e.getMessage());
            }
        }

        if (canResumeFromContextCheckpoint(task.getSamplerConfig())
                && checkpoint != null
                && checkpoint.isStandard()
                && checkpoint.generatedTokenCount() < state.generatedTokenIds.size()) {
            return createStandardTokenGeneratorFromCheckpoint(
                    task,
                    checkpoint,
                    TokenIds.tail(state.generatedTokenIds, checkpoint.generatedTokenCount())
            );
        }
        return createStandardTokenGenerator(task, fullTokenIds, promptTokenCount, generatedTokenCount);
    }

    private InferenceTokenGenerator createStandardTokenGenerator(InferenceTask task, int[] promptTokens) {
        return createStandardTokenGenerator(task, promptTokens, promptTokens.length, 0);
    }

    private InferenceTokenGenerator createStandardTokenGenerator(InferenceTask task,
                                                                int[] tokenIds,
                                                                int promptTokenCount,
                                                                int generatedTokenCount) {
        LlamaCppContext context = null;
        LlamaCppSamplerChain chain = null;
        try {
            context = engine.createContext(task.getLane());
            chain = buildSamplerChain(task.getSamplerConfig());
            return new StandardTokenGenerator(context, chain, tokenIds, promptTokenCount, generatedTokenCount);
        } catch (RuntimeException e) {
            if (context != null) try { context.close(); } catch (Exception ignored) {}
            if (chain != null) try { chain.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private InferenceTokenGenerator createStandardTokenGeneratorFromCheckpoint(InferenceTask task,
                                                                              GenerationCheckpoint checkpoint,
                                                                              int[] replayTokens) {
        LlamaCppContext context = null;
        LlamaCppSamplerChain chain = null;
        try {
            context = engine.createContext(task.getLane());
            chain = buildSamplerChain(task.getSamplerConfig());
            return new StandardTokenGenerator(context, chain, checkpoint, replayTokens);
        } catch (RuntimeException e) {
            if (context != null) try { context.close(); } catch (Exception ignored) {}
            if (chain != null) try { chain.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private InferenceTokenGenerator createMtpTokenGenerator(InferenceTask task, int[] promptTokens, int draftMax) {
        return createMtpTokenGenerator(task, promptTokens, promptTokens.length, 0, draftMax);
    }

    private InferenceTokenGenerator createMtpTokenGenerator(InferenceTask task,
                                                           int[] tokenIds,
                                                           int promptTokenCount,
                                                           int generatedTokenCount,
                                                           int draftMax) {
        return createMtpTokenGenerator(task, tokenIds, promptTokenCount, generatedTokenCount, null, draftMax);
    }

    private InferenceTokenGenerator createMtpTokenGeneratorFromCheckpoint(InferenceTask task,
                                                                         int[] tokenIds,
                                                                         int promptTokenCount,
                                                                         int generatedTokenCount,
                                                                         GenerationCheckpoint checkpoint,
                                                                         int draftMax) {
        if (checkpoint.restoredTokenCount() >= tokenIds.length) {
            throw new IllegalArgumentException("MTP restored-target resume requires at least one tail token");
        }
        return createMtpTokenGenerator(task, tokenIds, promptTokenCount, generatedTokenCount, checkpoint, draftMax);
    }

    private InferenceTokenGenerator createMtpTokenGenerator(InferenceTask task,
                                                           int[] tokenIds,
                                                           int promptTokenCount,
                                                           int generatedTokenCount,
                                                           GenerationCheckpoint checkpoint,
                                                           int draftMax) {
        InferenceOptions options = InferenceOptions.builder()
                .mtpEnabled(true)
                .mtpDraftMax(draftMax)
                .build();
        LlamaCppContext context = null;
        LlamaCppSamplerChain chain = null;
        try {
            context = engine.createContext(task.getLane(), options);
            chain = buildSamplerChain(task.getSamplerConfig());
            return new MtpTokenGenerator(context, engine.getMtpDraftModel(), chain, tokenIds, promptTokenCount, generatedTokenCount, checkpoint, draftMax);
        } catch (RuntimeException e) {
            if (context != null) try { context.close(); } catch (Exception ignored) {}
            if (chain != null) try { chain.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private void recordMtpStats(InferenceTokenGenerator generator) {
        if (generator == null || !generator.isMtp()) return;
        try {
            SpeculativeStats stats = generator.getSpeculativeStats();
            if (stats != null && stats.generatedTokens() > 0) {
                engine.recordMtpTrial(MtpTrialResult.success(generator.getMtpDraftMax(), stats));
            }
        } catch (RuntimeException ignored) {
        }
    }

    private ExecutionResult doMtpCalibration(InferenceTask task) {
        MtpCalibrationRequest request = task.getMtpCalibrationRequest();
        if (request == null) request = MtpCalibrationRequest.defaults();
        engine.publishInferenceEvent(task, InferenceEventType.STARTED, "MTP calibration started.");

        if (!engine.supportsMtpInternal()) {
            MtpCalibrationResult result = MtpCalibrationResult.unsupported(engine.getMtpLayerCount());
            completeMtpCalibration(task, result);
            engine.publishInferenceEvent(task, InferenceEventType.COMPLETED, result.getMessage());
            engine.finishTask(task);
            return ExecutionResult.COMPLETED;
        }

        List<MtpTrialResult> trials = new ArrayList<>();
        int draftSearchHardLimit = resolveMtpCalibrationDraftSearchHardLimit(request);
        String formattedPrompt;
        try {
            formattedPrompt = buildMtpCalibrationPrompt(task, request, draftSearchHardLimit);
        } catch (RuntimeException e) {
            MtpCalibrationResult result = MtpCalibrationResult.failed(
                    engine.getMtpLayerCount(),
                    0,
                    List.of(MtpTrialResult.failed(0, e.getMessage())),
                    "MTP calibration workload is not runnable: " + e.getMessage()
            );
            completeMtpCalibration(task, result);
            engine.publishInferenceEvent(task, InferenceEventType.FAILED, result.getMessage(), e);
            engine.finishTask(task);
            return ExecutionResult.COMPLETED;
        }
        int maxDraftMaxTested = 0;
        int nextDraftMax = 1;
        int currentDraftLimit = initialMtpCalibrationDraftLimit(request, draftSearchHardLimit);
        boolean stopCalibration = false;
        while (!stopCalibration && nextDraftMax <= currentDraftLimit) {
            boolean stoppedOnFailure = false;
            for (int draftMax = nextDraftMax; draftMax <= currentDraftLimit; draftMax++) {
                if (task.isCancelled()) {
                    cancelMtpCalibration(task);
                    return ExecutionResult.COMPLETED;
                }
                maxDraftMaxTested = draftMax;
                try (InferenceTokenGenerator generator = createMtpTokenGenerator(
                        task,
                        tokenizePrompt(formattedPrompt),
                        draftMax)) {
                    int generated = 0;
                    while (generated < request.getMaxTokens()) {
                        if (task.isCancelled()) {
                            cancelMtpCalibration(task);
                            return ExecutionResult.COMPLETED;
                        }
                        GeneratedToken token = generator.next(request.getMaxTokens() - generated);
                        if (token == null) break;
                        generated++;
                    }
                    MtpTrialResult trial = MtpTrialResult.success(draftMax, generator.getSpeculativeStats());
                    trials.add(trial);
                    engine.recordMtpTrial(trial);
                } catch (RuntimeException e) {
                    trials.add(MtpTrialResult.failed(draftMax, e.getMessage()));
                    stoppedOnFailure = request.isAutoDraftMax();
                    if (stoppedOnFailure) break;
                }
            }
            if (!request.isAutoDraftMax() || stoppedOnFailure) break;
            if (!shouldExpandMtpCalibrationDraftLimit(trials, currentDraftLimit, draftSearchHardLimit)) break;
            nextDraftMax = currentDraftLimit + 1;
            currentDraftLimit = Math.min(currentDraftLimit * 2, draftSearchHardLimit);
            stopCalibration = nextDraftMax > currentDraftLimit;
        }

        MtpCalibrationResult result = MtpCalibrationResult.completed(
                engine.getMtpLayerCount(),
                maxDraftMaxTested,
                trials
        );
        completeMtpCalibration(task, result);
        engine.publishInferenceEvent(task, InferenceEventType.COMPLETED, result.getMessage());
        engine.finishTask(task);
        return ExecutionResult.COMPLETED;
    }

    private int resolveMtpCalibrationDraftSearchHardLimit(MtpCalibrationRequest request) {
        return request.isAutoDraftMax()
                ? MtpCalibrationRequest.DEFAULT_AUTO_DRAFT_MAX_LIMIT
                : request.getMaxDraftMax();
    }

    private int initialMtpCalibrationDraftLimit(MtpCalibrationRequest request, int draftSearchHardLimit) {
        return request.isAutoDraftMax()
                ? Math.min(MtpCalibrationRequest.DEFAULT_AUTO_DRAFT_MAX_INITIAL_LIMIT, draftSearchHardLimit)
                : draftSearchHardLimit;
    }

    private boolean shouldExpandMtpCalibrationDraftLimit(List<MtpTrialResult> trials,
                                                         int currentDraftLimit,
                                                         int draftSearchHardLimit) {
        if (currentDraftLimit >= draftSearchHardLimit) return false;
        MtpTrialResult best = bestMtpTrial(trials);
        if (best == null) return false;
        int expansionThreshold = Math.max(1, (int) Math.ceil(currentDraftLimit * 0.75));
        return best.getDraftMax() >= expansionThreshold;
    }

    private boolean canResumeFromContextCheckpoint(SamplerConfig config) {
        // JJML context state does not persist sampler/grammar history yet.
        // Use KV checkpoints only when replaying sampler state is not required.
        if (config == null) return true;
        if (config.hasGrammar()) return false;
        if (config.temperature() != 0.0f) return false;
        if (config.penaltyRepeat() != 1.0f) return false;
        if (config.penaltyFreq() != 0.0f) return false;
        if (config.penaltyPresent() != 0.0f) return false;
        return true;
    }

    private MtpTrialResult bestMtpTrial(List<MtpTrialResult> trials) {
        MtpTrialResult best = null;
        for (MtpTrialResult trial : trials) {
            if (trial == null || !trial.isSuccess()) continue;
            if (best == null || trial.getTokensPerSecond() > best.getTokensPerSecond()) {
                best = trial;
            }
        }
        return best;
    }

    private String buildMtpCalibrationPrompt(InferenceTask task,
                                             MtpCalibrationRequest request,
                                             int draftSearchLimit) {
        int contextSize = engine.getPlannedContextSize(InferenceLane.TASK);
        int mtpTargetOutputSlots = SpeculativeParams.requiredMtpTargetOutputs(draftSearchLimit);
        int maxRunnablePromptTokens = contextSize
                - request.getMaxTokens()
                - mtpTargetOutputSlots
                - MTP_CALIBRATION_CONTEXT_RESERVE;
        if (maxRunnablePromptTokens < MtpCalibrationRequest.MIN_HEAVY_PROMPT_TOKENS) {
            throw new IllegalArgumentException("planned task context size " + contextSize
                    + " is too small for heavy MTP calibration"
                    + " (minimumPromptTokens=" + MtpCalibrationRequest.MIN_HEAVY_PROMPT_TOKENS
                    + ", maxTokens=" + request.getMaxTokens()
                    + ", mtpTargetOutputSlots=" + mtpTargetOutputSlots + ")");
        }
        int targetPromptTokens = Math.min(request.getTargetPromptTokens(), maxRunnablePromptTokens);

        StringBuilder context = new StringBuilder(MTP_CALIBRATION_USER_PREFIX);
        String formattedPrompt = formatCalibrationMessages(task, context + MTP_CALIBRATION_FINAL_INSTRUCTION);
        int promptTokens = countPromptTokens(formattedPrompt);
        if (promptTokens > maxRunnablePromptTokens) {
            throw new IllegalStateException("calibration prompt template exceeds runnable prompt budget"
                    + " (promptTokens=" + promptTokens
                    + ", maxRunnablePromptTokens=" + maxRunnablePromptTokens + ")");
        }
        int blockIndex = 1;
        while (promptTokens < targetPromptTokens) {
            int previousLength = context.length();
            context.append("Log block ").append(blockIndex++).append(":\n")
                    .append(MTP_CALIBRATION_BLOCK)
                    .append('\n');
            String candidatePrompt = formatCalibrationMessages(task, context + MTP_CALIBRATION_FINAL_INSTRUCTION);
            int candidatePromptTokens = countPromptTokens(candidatePrompt);
            if (candidatePromptTokens > maxRunnablePromptTokens) {
                context.setLength(previousLength);
                break;
            }
            formattedPrompt = candidatePrompt;
            promptTokens = candidatePromptTokens;
            if (blockIndex > 512) {
                throw new IllegalStateException("failed to build heavy MTP calibration prompt");
            }
        }
        if (promptTokens < MtpCalibrationRequest.MIN_HEAVY_PROMPT_TOKENS) {
            throw new IllegalStateException("failed to build heavy MTP calibration prompt within runnable budget"
                    + " (promptTokens=" + promptTokens
                    + ", minimumPromptTokens=" + MtpCalibrationRequest.MIN_HEAVY_PROMPT_TOKENS
                    + ", maxRunnablePromptTokens=" + maxRunnablePromptTokens + ")");
        }
        return formattedPrompt;
    }

    private String formatCalibrationMessages(InferenceTask task, String userContent) {
        List<LlamaCppChatMessage> messages = List.of(
                new LlamaCppChatMessage("system", MTP_CALIBRATION_SYSTEM_PROMPT),
                new LlamaCppChatMessage("user", userContent)
        );
        return engine.promptSnapshot(messages, task.getSamplerConfig()).text();
    }

    private int countPromptTokens(String formattedPrompt) {
        IntBuffer tokens = engine.getModel().getVocabulary().tokenize(formattedPrompt);
        return tokens.remaining();
    }

    private void completeMtpCalibration(InferenceTask task, MtpCalibrationResult result) {
        task.getMtpCalibrationFuture().complete(result);
        task.getSyncFuture().complete(result.getMessage());
    }

    private void cancelMtpCalibration(InferenceTask task) {
        task.getMtpCalibrationFuture().cancel(false);
        task.getSyncFuture().cancel(false);
        task.getGenerationFuture().cancel(false);
        engine.publishInferenceEvent(task, InferenceEventType.CANCELLED, "MTP calibration was cancelled.");
        engine.finishTask(task);
    }

    private PromptSnapshot promptSnapshot(InferenceTask task) {
        return engine.promptSnapshot(task.getMessages(), task.getSamplerConfig());
    }

    private int[] tokenizePrompt(String formattedPrompt) {
        return ChatPromptTemplate.tokenize(engine.getModel(), formattedPrompt);
    }

    private void completeGeneration(InferenceTask task, String text, LlmTokenUsage usage) {
        completeGeneration(task, text, usage, "");
    }

    private void completeGeneration(InferenceTask task, String text, LlmTokenUsage usage, String thinkingContent) {
        LlmGenerationResult result = new LlmGenerationResult(text, usage, thinkingContent);
        publishStreamFinish(task, StreamFinishType.COMPLETED, usage, null, thinkingContent);
        task.getSyncFuture().complete(text);
        task.getGenerationFuture().complete(result);
    }

    private void publishStreamFinish(InferenceTask task, StreamFinishType type, LlmTokenUsage usage, Throwable error) {
        publishStreamFinish(task, type, usage, error, "");
    }

    private void publishStreamFinish(InferenceTask task, StreamFinishType type, LlmTokenUsage usage, Throwable error, String thinkingContent) {
        Consumer<LlmStreamFinish> callback = task.getFinishCallback();
        if (callback == null) return;
        try {
            callback.accept(new LlmStreamFinish(type, usage, error, thinkingContent));
        } catch (Exception ignored) {
        }
    }

    private void appendAccepted(InferenceTask task,
                                StringBuilder text,
                                StringBuilder thinkingContent,
                                ReasoningTagNormalizer.AcceptResult accepted) {
        String acceptedText = accepted.text();
        if (!acceptedText.isEmpty()) {
            text.append(acceptedText);
            Consumer<String> streamCallback = task.getStreamCallback();
            if (streamCallback != null) streamCallback.accept(acceptedText);
        }

        String acceptedThinking = accepted.thinkingContent();
        if (!acceptedThinking.isEmpty()) {
            thinkingContent.append(acceptedThinking);
            Consumer<String> thinkingCallback = task.getThinkingStreamCallback();
            if (thinkingCallback != null) thinkingCallback.accept(acceptedThinking);
        }
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
            suspended.task.getGenerationFuture().cancel(false);
            suspended.task.getMtpCalibrationFuture().cancel(false);
            suspended.close();
            publishStreamFinish(suspended.task, StreamFinishType.CANCELLED, suspended.usage(), null, suspended.thinkingContent.toString());
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
        private final StringBuilder thinkingContent = new StringBuilder();
        private final StringBuilder rawGeneratedText = new StringBuilder();
        private final List<Integer> generatedTokenIds = new ArrayList<>();
        private final ReasoningTagNormalizer reasoningNormalizer;
        private PromptSnapshot prompt;
        private InferenceTokenGenerator generator;
        private GenerationCheckpoint checkpoint;
        private int generatedTokens;
        private int completionTokens;
        private int checkpointedTokens;

        private SuspendedTask(InferenceTask task) {
            this.task = task;
            this.reasoningNormalizer = new ReasoningTagNormalizer(
                    task.getInferenceOptions().isCaptureThinkingContent()
            );
        }

        private void appendAccepted(ReasoningTagNormalizer.AcceptResult accepted) {
            String acceptedText = accepted.text();
            if (!acceptedText.isEmpty()) {
                generatedText.append(acceptedText);
                Consumer<String> streamCallback = task.getStreamCallback();
                if (streamCallback != null) streamCallback.accept(acceptedText);
            }

            String acceptedThinking = accepted.thinkingContent();
            if (!acceptedThinking.isEmpty()) {
                thinkingContent.append(acceptedThinking);
                Consumer<String> thinkingCallback = task.getThinkingStreamCallback();
                if (thinkingCallback != null) thinkingCallback.accept(acceptedThinking);
            }
        }

        private void close() {
            if (generator != null) {
                try { generator.close(); } catch (Exception ignored) {}
                generator = null;
            }
        }

        private void coolDown() {
            captureCheckpointBeforeSuspend();
            close();
        }

        private void captureCheckpointIfDue(int intervalTokens) {
            if (!(generator instanceof CheckpointableTokenGenerator checkpointable)) return;
            if (generatedTokens == 0) return;
            if (generatedTokens - checkpointedTokens < intervalTokens) return;
            GenerationCheckpoint nextCheckpoint = checkpointable.checkpoint();
            if (nextCheckpoint == null) return;
            checkpoint = nextCheckpoint;
            checkpointedTokens = nextCheckpoint.generatedTokenCount();
        }

        private void captureCheckpointBeforeSuspend() {
            if (!(generator instanceof CheckpointableTokenGenerator checkpointable)) return;
            if (generatedTokens == 0) return;
            GenerationCheckpoint nextCheckpoint = checkpointable.checkpoint();
            if (nextCheckpoint == null) return;
            if (!isCheckpointUsableNow(nextCheckpoint)) return;
            checkpoint = nextCheckpoint;
            checkpointedTokens = nextCheckpoint.generatedTokenCount();
        }

        private boolean isCheckpointUsableNow(GenerationCheckpoint candidate) {
            if (candidate.isMtpTarget()) {
                int promptTokenCount = prompt == null ? 0 : prompt.tokenIds().length;
                return candidate.restoredTokenCount() < promptTokenCount + generatedTokens;
            }
            return candidate.generatedTokenCount() < generatedTokens;
        }

        private int replayCharacters() {
            int promptLength = prompt == null ? 0 : prompt.textLength();
            return promptLength + rawGeneratedText.length();
        }

        private LlmTokenUsage usage() {
            int promptTokenCount = prompt == null ? 0 : prompt.tokenIds().length;
            return new LlmTokenUsage(promptTokenCount, completionTokens);
        }
    }
}
