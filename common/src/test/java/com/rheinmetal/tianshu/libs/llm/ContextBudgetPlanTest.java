package com.rheinmetal.tianshu.libs.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextBudgetPlanTest {
    @Test
    void contextBudgetPlanReportsPromptBudgetFromPlannedContext() {
        LlmContextBudgetPlan plan = new LlmContextBudgetPlan(
                4096,
                32768,
                2000,
                1600,
                1536,
                64,
                256L,
                true,
                ""
        );

        assertEquals(1600, plan.plannedContextSize());
        assertEquals(1536, plan.promptTokenBudget());
    }

    @Test
    void contextBudgetPlanRejectsPromptBudgetThatDoesNotMatchPlannedContext() {
        assertThrows(IllegalArgumentException.class, () -> new LlmContextBudgetPlan(
                4096,
                32768,
                4096,
                4096,
                4096,
                64,
                256L,
                true,
                ""
        ));
    }

    @Test
    void contextBudgetPlanAllowsPlannedContextBelowConfiguredContext() {
        LlmContextBudgetPlan plan = new LlmContextBudgetPlan(
                4096,
                32768,
                2048,
                2048,
                1984,
                64,
                256L,
                true,
                ""
        );

        assertEquals(2048, plan.plannedContextSize());
        assertEquals(1984, plan.promptTokenBudget());
    }

    @Test
    void runtimeCapabilitiesUnavailableUsesUnknownValues() {
        LlmRuntimeCapabilities capabilities = LlmRuntimeCapabilities.unavailable();

        assertEquals(false, capabilities.ready());
        assertEquals(false, capabilities.supportsThinking());
        assertEquals(false, capabilities.supportsMtp());
        assertEquals(0, capabilities.mtpLayerCount());
    }
}
