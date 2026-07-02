package com.rheinmetal.tianshu.libs.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReasoningTagNormalizerTest {
    @Test
    void normalizesKnownReasoningTags() {
        ReasoningTagNormalizer.AcceptResult result = acceptAll(
                new ReasoningTagNormalizer(true),
                "before <reasoning>hidden</reasoning> after"
        );
        assertEquals(
                "before  after",
                result.text()
        );
        assertEquals("hidden", result.thinkingContent());

        assertEquals(
                "",
                ReasoningTagNormalizer.normalize("<|begin_of_thought|>hidden<|end_of_thought|>")
        );
        assertEquals(
                "",
                ReasoningTagNormalizer.normalize("<|end_of_sentence|><|reserved_special_token_0|>hidden<|reserved_special_token_1|><|end_of_sentence|>")
        );
        result = acceptAll(
                new ReasoningTagNormalizer(true),
                "<|channel>thought hidden<|channel>final answer"
        );
        assertEquals(" answer", result.text());
        assertEquals(" hidden", result.thinkingContent());

        result = acceptAll(
                new ReasoningTagNormalizer(true),
                "<channel|>thought hidden<channel|>final answer"
        );
        assertEquals(" answer", result.text());
        assertEquals(" hidden", result.thinkingContent());

        result = acceptAll(
                new ReasoningTagNormalizer(true),
                "<channel|>thought hidden<channel|> answer"
        );
        assertEquals(" answer", result.text());
        assertEquals(" hidden", result.thinkingContent());
    }

    @Test
    void normalizesTagsSplitAcrossStreamingTokens() {
        ReasoningTagNormalizer normalizer = new ReasoningTagNormalizer(true);
        ReasoningTagNormalizer.AcceptResult first = normalizer.acceptWithUsage("a <reas");
        ReasoningTagNormalizer.AcceptResult second = normalizer.acceptWithUsage("oning>b</reas");
        ReasoningTagNormalizer.AcceptResult third = normalizer.acceptWithUsage("oning> c");
        ReasoningTagNormalizer.AcceptResult end = normalizer.finishWithUsage();

        assertEquals("a  c", first.text() + second.text() + third.text() + end.text());
        assertEquals("b", first.thinkingContent() + second.thinkingContent() + third.thinkingContent() + end.thinkingContent());
    }

    @Test
    void streamsNonEmptyReasoningBeforeCloseTag() {
        ReasoningTagNormalizer normalizer = new ReasoningTagNormalizer(true);

        assertEquals("", normalizer.acceptWithUsage("<reasoning>\n").thinkingContent());
        assertEquals("\nwork", normalizer.acceptWithUsage("work").thinkingContent());
        assertEquals(" more", normalizer.acceptWithUsage(" more").thinkingContent());
        assertEquals("", normalizer.acceptWithUsage("</reasoning>").thinkingContent());
    }

    @Test
    void canSuppressReasoningContent() {
        ReasoningTagNormalizer normalizer = new ReasoningTagNormalizer(false);
        ReasoningTagNormalizer.AcceptResult result = acceptAll(normalizer, "a <reasoning>hidden</reasoning> b");

        assertEquals("a  b", result.text());
        assertEquals("", result.thinkingContent());
    }

    @Test
    void canSuppressGemmaThoughtChannel() {
        ReasoningTagNormalizer normalizer = new ReasoningTagNormalizer(false);
        ReasoningTagNormalizer.AcceptResult result = acceptAll(normalizer, "<channel|>thought hidden<channel|>final answer");

        assertEquals(" answer", result.text());
        assertEquals("", result.thinkingContent());
    }

    @Test
    void dropsEmptyReasoningBlocks() {
        assertEquals("answer", ReasoningTagNormalizer.normalize("<think></think>answer"));
        assertEquals("answer", ReasoningTagNormalizer.normalize("<think>\n\n</think>answer"));
        assertEquals("answer", ReasoningTagNormalizer.normalize("<no_think></no_think>answer"));
    }

    @Test
    void handlesLinePrefixedAnalysisFinalPairs() {
        ReasoningTagNormalizer.AcceptResult result = acceptAll(
                new ReasoningTagNormalizer(true),
                "analysis:\nwork\nfinal:\nanswer"
        );
        assertEquals("\nanswer", result.text());
        assertEquals("\nwork\n", result.thinkingContent());
    }

    @Test
    void doesNotTreatContextualEndTokensAsReasoningWhenOutsideReasoning() {
        assertEquals("a <|end|> b", ReasoningTagNormalizer.normalize("a <|end|> b"));
    }

    private static ReasoningTagNormalizer.AcceptResult acceptAll(ReasoningTagNormalizer normalizer, String text) {
        ReasoningTagNormalizer.AcceptResult accepted = normalizer.acceptWithUsage(text);
        ReasoningTagNormalizer.AcceptResult finished = normalizer.finishWithUsage();
        return new ReasoningTagNormalizer.AcceptResult(
                accepted.text() + finished.text(),
                accepted.thinkingContent() + finished.thinkingContent(),
                accepted.visibleCompletion() || finished.visibleCompletion()
        );
    }
}
