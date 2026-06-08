package com.rheinmetal.tianshu.libs.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReasoningTagNormalizerTest {
    @Test
    void normalizesKnownReasoningTags() {
        assertEquals(
                "before <think>hidden</think> after",
                ReasoningTagNormalizer.normalize("before <reasoning>hidden</reasoning> after")
        );
        assertEquals(
                "<think>hidden</think>",
                ReasoningTagNormalizer.normalize("<|begin_of_thought|>hidden<|end_of_thought|>")
        );
        assertEquals(
                "<think>hidden</think>",
                ReasoningTagNormalizer.normalize("<|end_of_sentence|><|reserved_special_token_0|>hidden<|reserved_special_token_1|><|end_of_sentence|>")
        );
    }

    @Test
    void normalizesTagsSplitAcrossStreamingTokens() {
        ReasoningTagNormalizer normalizer = new ReasoningTagNormalizer();
        String result = normalizer.accept("a <reas")
                + normalizer.accept("oning>b</reas")
                + normalizer.accept("oning> c")
                + normalizer.finish();

        assertEquals("a <think>b</think> c", result);
    }

    @Test
    void streamsNonEmptyReasoningBeforeCloseTag() {
        ReasoningTagNormalizer normalizer = new ReasoningTagNormalizer();

        assertEquals("", normalizer.accept("<reasoning>\n"));
        assertEquals("<think>\nwork", normalizer.accept("work"));
        assertEquals(" more", normalizer.accept(" more"));
        assertEquals("</think>", normalizer.accept("</reasoning>"));
    }

    @Test
    void dropsEmptyReasoningBlocks() {
        assertEquals("answer", ReasoningTagNormalizer.normalize("<think></think>answer"));
        assertEquals("answer", ReasoningTagNormalizer.normalize("<think>\n\n</think>answer"));
        assertEquals("answer", ReasoningTagNormalizer.normalize("<no_think></no_think>answer"));
    }

    @Test
    void handlesLinePrefixedAnalysisFinalPairs() {
        assertEquals(
                "<think>\nwork\n</think>\nanswer",
                ReasoningTagNormalizer.normalize("analysis:\nwork\nfinal:\nanswer")
        );
    }

    @Test
    void doesNotTreatContextualEndTokensAsReasoningWhenOutsideReasoning() {
        assertEquals("a <|end|> b", ReasoningTagNormalizer.normalize("a <|end|> b"));
    }
}
