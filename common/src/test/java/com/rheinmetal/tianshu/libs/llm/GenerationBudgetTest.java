package com.rheinmetal.tianshu.libs.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationBudgetTest {
    @Test
    void unboundedRequestUsesAllContextRemainder() {
        LlmContextBudgetPolicy policy = new LlmContextBudgetPolicy(64, 256L);

        int limit = GenerationBudget.resolveCompletionLimit(0, 4096, 1024, policy);

        assertEquals(4096 - 1024 - policy.promptMarginTokens(), limit);
    }

    @Test
    void unboundedRequestIsClampedToContextRemainder() {
        LlmContextBudgetPolicy policy = new LlmContextBudgetPolicy(64, 256L);

        int limit = GenerationBudget.resolveCompletionLimit(0, 4096, 3900, policy);

        assertEquals(4096 - 3900 - policy.promptMarginTokens(), limit);
    }

    @Test
    void explicitRequestIsClampedToContextRemainder() {
        LlmContextBudgetPolicy policy = new LlmContextBudgetPolicy(64, 256L);

        int limit = GenerationBudget.resolveCompletionLimit(2048, 4096, 3900, policy);

        assertEquals(4096 - 3900 - policy.promptMarginTokens(), limit);
    }

    @Test
    void explicitRequestIsPreservedWhenContextHasEnoughRoom() {
        LlmContextBudgetPolicy policy = new LlmContextBudgetPolicy(64, 256L);

        int limit = GenerationBudget.resolveCompletionLimit(128, 4096, 1024, policy);

        assertEquals(128, limit);
    }

    @Test
    void exhaustedContextProducesZeroCompletionBudget() {
        LlmContextBudgetPolicy policy = new LlmContextBudgetPolicy(64, 256L);

        int limit = GenerationBudget.resolveCompletionLimit(0, 4096, 4090, policy);

        assertEquals(0, limit);
    }
}
