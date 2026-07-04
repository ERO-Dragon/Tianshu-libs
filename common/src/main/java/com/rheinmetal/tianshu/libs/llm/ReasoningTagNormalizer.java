package com.rheinmetal.tianshu.libs.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Normalizes model-specific reasoning wrappers into one internal contract.
 * Empty reasoning blocks are dropped so callers do not need to handle
 * model-specific no-think markers such as an empty Qwen <think></think>.
 * Non-empty reasoning is streamed as soon as the first non-whitespace
 * reasoning content appears; it is not buffered until the closing tag.
 */
final class ReasoningTagNormalizer {
    static final String CANONICAL_OPEN = "<think>";
    static final String CANONICAL_CLOSE = "</think>";

    private static final List<TagAlias> OPEN_ALIASES = buildOpenAliases();
    private static final List<TagAlias> CLOSE_ALIASES = buildCloseAliases();
    private static final List<TagAlias> IGNORE_OPEN_ALIASES = buildIgnoreOpenAliases();
    private static final List<TagAlias> IGNORE_CLOSE_ALIASES = buildIgnoreCloseAliases();
    private static final List<TagAlias> IGNORE_SELF_ALIASES = buildIgnoreSelfAliases();
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder reasoningPrefixBuffer = new StringBuilder();
    private final boolean captureThinkingContent;
    private boolean inReasoning;
    private boolean reasoningEmitted;
    private boolean inIgnoredBlock;

    ReasoningTagNormalizer() {
        this(false);
    }

    ReasoningTagNormalizer(boolean captureThinkingContent) {
        this(captureThinkingContent, false);
    }

    ReasoningTagNormalizer(boolean captureThinkingContent, boolean startsInReasoning) {
        this.captureThinkingContent = captureThinkingContent;
        this.inReasoning = startsInReasoning;
    }

    String accept(String token) {
        return acceptNormalized(token).text();
    }

    AcceptResult acceptNormalized(String token) {
        if (token == null || token.isEmpty()) return new AcceptResult("", "", false, false);
        pending.append(token);
        return drain(false);
    }

    String finish() {
        return finishNormalized().text();
    }

    AcceptResult finishNormalized() {
        return drain(true);
    }

    static String normalize(String text) {
        ReasoningTagNormalizer normalizer = new ReasoningTagNormalizer();
        return normalizer.accept(text) + normalizer.finish();
    }

    static boolean promptEndsInReasoningOpen(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = stripTrailing(text).toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return false;
        for (TagAlias alias : OPEN_ALIASES) {
            int start = lower.length() - alias.rawLower.length();
            if (start < 0) continue;
            if (!lower.endsWith(alias.rawLower)) continue;
            if (alias.linePrefix && start > 0 && lower.charAt(start - 1) != '\n' && lower.charAt(start - 1) != '\r') {
                continue;
            }
            return true;
        }
        return false;
    }

    private AcceptResult drain(boolean finishing) {
        StringBuilder out = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        boolean[] completionContent = new boolean[] { false };
        boolean[] thinkingContent = new boolean[] { false };
        while (pending.length() > 0) {
            Match match = findNextMatch();
            if (match != null) {
                emitTextBeforeMatch(out, thinking, match.index, completionContent, thinkingContent);
                pending.delete(0, match.alias.raw.length());
                switch (match.kind) {
                    case REASONING_OPEN -> beginReasoning();
                    case REASONING_CLOSE -> emitReasoningBlock();
                    case IGNORE_OPEN -> inIgnoredBlock = true;
                    case IGNORE_CLOSE -> inIgnoredBlock = false;
                    case IGNORE_SELF -> {
                    }
                }
                continue;
            }

            int keepLength = finishing ? 0 : longestAliasPrefixSuffixLength();
            int safeLength = pending.length() - keepLength;
            if (safeLength <= 0) break;
            emitText(out, thinking, pending.substring(0, safeLength), completionContent, thinkingContent);
            pending.delete(0, safeLength);
        }

        if (finishing && inReasoning) {
            emitReasoningBlock();
        }
        return new AcceptResult(out.toString(), thinking.toString(), completionContent[0], thinkingContent[0]);
    }

    private void emitTextBeforeMatch(StringBuilder out,
                                     StringBuilder thinking,
                                     int length,
                                     boolean[] completionContent,
                                     boolean[] thinkingContent) {
        if (length <= 0) return;
        emitText(out, thinking, pending.substring(0, length), completionContent, thinkingContent);
        pending.delete(0, length);
    }

    private void emitText(StringBuilder out,
                          StringBuilder thinking,
                          String text,
                          boolean[] completionContent,
                          boolean[] thinkingContent) {
        if (inReasoning) {
            if (!text.isEmpty()) thinkingContent[0] = true;
            emitReasoningText(thinking, text);
        } else if (inIgnoredBlock) {
            return;
        } else {
            out.append(text);
            if (!text.isEmpty()) completionContent[0] = true;
        }
    }

    private void emitReasoningText(StringBuilder thinking, String text) {
        if (!captureThinkingContent) return;
        if (reasoningEmitted) {
            thinking.append(text);
            return;
        }
        reasoningPrefixBuffer.append(text);
        if (reasoningPrefixBuffer.toString().trim().isEmpty()) return;
        thinking.append(reasoningPrefixBuffer);
        reasoningPrefixBuffer.setLength(0);
        reasoningEmitted = true;
    }

    private void beginReasoning() {
        if (inReasoning) {
            emitReasoningText(new StringBuilder(), CANONICAL_OPEN);
            return;
        }
        inReasoning = true;
        reasoningEmitted = false;
        reasoningPrefixBuffer.setLength(0);
    }

    private void emitReasoningBlock() {
        if (!inReasoning) {
            return;
        }
        reasoningPrefixBuffer.setLength(0);
        reasoningEmitted = false;
        inReasoning = false;
    }

    private Match findNextMatch() {
        String lowerPending = pending.toString().toLowerCase(Locale.ROOT);
        Match best = null;
        if (inIgnoredBlock) {
            for (TagAlias alias : IGNORE_CLOSE_ALIASES) {
                best = chooseBetter(best, indexOfAlias(lowerPending, alias), alias, MatchKind.IGNORE_CLOSE);
            }
            return best;
        }
        for (TagAlias alias : OPEN_ALIASES) {
            best = chooseBetter(best, indexOfAlias(lowerPending, alias), alias, MatchKind.REASONING_OPEN);
        }
        for (TagAlias alias : CLOSE_ALIASES) {
            if (!alias.contextual || inReasoning) {
                best = chooseBetter(best, indexOfAlias(lowerPending, alias), alias, MatchKind.REASONING_CLOSE);
            }
        }
        for (TagAlias alias : IGNORE_OPEN_ALIASES) {
            best = chooseBetter(best, indexOfAlias(lowerPending, alias), alias, MatchKind.IGNORE_OPEN);
        }
        for (TagAlias alias : IGNORE_CLOSE_ALIASES) {
            if (inIgnoredBlock) {
                best = chooseBetter(best, indexOfAlias(lowerPending, alias), alias, MatchKind.IGNORE_CLOSE);
            }
        }
        for (TagAlias alias : IGNORE_SELF_ALIASES) {
            best = chooseBetter(best, indexOfAlias(lowerPending, alias), alias, MatchKind.IGNORE_SELF);
        }
        return best;
    }

    private int longestAliasPrefixSuffixLength() {
        String lowerPending = pending.toString().toLowerCase(Locale.ROOT);
        int best = 0;
        for (TagAlias alias : relevantAliases()) {
            best = Math.max(best, aliasPrefixSuffixLength(lowerPending, alias));
        }
        return best;
    }

    private List<TagAlias> relevantAliases() {
        if (inIgnoredBlock) return IGNORE_CLOSE_ALIASES;
        List<TagAlias> aliases = new ArrayList<>();
        aliases.addAll(OPEN_ALIASES);
        aliases.addAll(IGNORE_OPEN_ALIASES);
        aliases.addAll(IGNORE_SELF_ALIASES);
        for (TagAlias alias : CLOSE_ALIASES) {
            if (!alias.contextual || inReasoning) aliases.add(alias);
        }
        return aliases;
    }

    private int aliasPrefixSuffixLength(String lowerPending, TagAlias alias) {
        int max = Math.min(lowerPending.length(), alias.rawLower.length() - 1);
        for (int length = max; length > 0; length--) {
            int start = lowerPending.length() - length;
            if (!alias.rawLower.startsWith(lowerPending.substring(start))) continue;
            if (!alias.linePrefix || start == 0 || lowerPending.charAt(start - 1) == '\n' || lowerPending.charAt(start - 1) == '\r') {
                return length;
            }
        }
        return 0;
    }

    private int indexOfAlias(String lowerPending, TagAlias alias) {
        if (alias.linePrefix) {
            return indexOfLinePrefix(lowerPending, alias.rawLower);
        }
        return lowerPending.indexOf(alias.rawLower);
    }

    private int indexOfLinePrefix(String lowerPending, String rawLower) {
        int from = 0;
        while (from < lowerPending.length()) {
            int index = lowerPending.indexOf(rawLower, from);
            if (index < 0) return -1;
            if (index == 0 || lowerPending.charAt(index - 1) == '\n' || lowerPending.charAt(index - 1) == '\r') {
                return index;
            }
            from = index + 1;
        }
        return -1;
    }

    private Match chooseBetter(Match best, int index, TagAlias alias, MatchKind kind) {
        if (index < 0) return best;
        if (best == null
                || index < best.index
                || (index == best.index && alias.raw.length() > best.alias.raw.length())) {
            return new Match(index, alias, kind);
        }
        return best;
    }

    private static List<TagAlias> buildOpenAliases() {
        List<TagAlias> aliases = new ArrayList<>();
        add(aliases, "<think>");
        add(aliases, "<reasoning>");
        add(aliases, "<reason>");
        add(aliases, "<thought>");
        add(aliases, "<analysis>");
        add(aliases, "<scratchpad>");
        add(aliases, "<inner_monologue>");
        add(aliases, "<reflection>");
        add(aliases, "<chain_of_thought>");
        add(aliases, "<|begin_of_thought|>");
        add(aliases, "<|end_of_sentence|><|reserved_special_token_0|>");
        add(aliases, "<|begin_thought|>");
        add(aliases, "<|begin_reasoning|>");
        add(aliases, "<|start_thinking|>");
        add(aliases, "<|startofthought|>");
        add(aliases, "<|channel>thought");
        add(aliases, "<|channel>analysis");
        add(aliases, "<channel|>thought");
        add(aliases, "<channel|>analysis");
        add(aliases, "<|analysis|>", true, false);
        add(aliases, "analysis:", true, false);
        return sort(aliases);
    }

    private static List<TagAlias> buildCloseAliases() {
        List<TagAlias> aliases = new ArrayList<>();
        add(aliases, "</think>");
        add(aliases, "</reasoning>");
        add(aliases, "</reason>");
        add(aliases, "</thought>");
        add(aliases, "</analysis>");
        add(aliases, "</scratchpad>");
        add(aliases, "</inner_monologue>");
        add(aliases, "</reflection>");
        add(aliases, "</chain_of_thought>");
        add(aliases, "<|end_of_thought|>");
        add(aliases, "<|reserved_special_token_1|><|end_of_sentence|>");
        add(aliases, "<|end_thought|>");
        add(aliases, "<|end_reasoning|>");
        add(aliases, "<|end_thinking|>");
        add(aliases, "<|endofthought|>");
        add(aliases, "<|channel>final", false, true);
        add(aliases, "<channel|>final", false, true);
        add(aliases, "<channel|>", false, true);
        add(aliases, "<|final|>", true, true);
        add(aliases, "<|assistant|>final", true, true);
        add(aliases, "<|end|>", false, true);
        add(aliases, "final:", true, true);
        return sort(aliases);
    }

    private static List<TagAlias> buildIgnoreOpenAliases() {
        List<TagAlias> aliases = new ArrayList<>();
        add(aliases, "<no_think>");
        add(aliases, "<nothink>");
        add(aliases, "<no_thinking>");
        add(aliases, "<|no_think|>");
        add(aliases, "<|no_thinking|>");
        add(aliases, "<|no_reasoning|>");
        return sort(aliases);
    }

    private static List<TagAlias> buildIgnoreCloseAliases() {
        List<TagAlias> aliases = new ArrayList<>();
        add(aliases, "</no_think>");
        add(aliases, "</nothink>");
        add(aliases, "</no_thinking>");
        return sort(aliases);
    }

    private static List<TagAlias> buildIgnoreSelfAliases() {
        List<TagAlias> aliases = new ArrayList<>();
        add(aliases, "/no_think");
        add(aliases, "/nothink");
        return sort(aliases);
    }

    private static void add(List<TagAlias> aliases, String raw) {
        add(aliases, raw, false, false);
    }

    private static void add(List<TagAlias> aliases, String raw, boolean linePrefix, boolean contextual) {
        aliases.add(new TagAlias(raw, raw.toLowerCase(Locale.ROOT), linePrefix, contextual));
    }

    private static List<TagAlias> sort(List<TagAlias> aliases) {
        aliases.sort(Comparator.comparingInt((TagAlias alias) -> alias.raw.length()).reversed());
        return List.copyOf(aliases);
    }

    private static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private record TagAlias(String raw, String rawLower, boolean linePrefix, boolean contextual) {
    }

    private enum MatchKind {
        REASONING_OPEN,
        REASONING_CLOSE,
        IGNORE_OPEN,
        IGNORE_CLOSE,
        IGNORE_SELF
    }

    private record Match(int index, TagAlias alias, MatchKind kind) {
    }

    record AcceptResult(String text, String thinkingContent, boolean completionContent, boolean thinkingToken) {
        boolean countAsThinkingToken() {
            return thinkingToken && (!completionContent || thinkingContent.length() >= text.length());
        }

        boolean countAsCompletionToken() {
            return completionContent && !countAsThinkingToken();
        }
    }
}
