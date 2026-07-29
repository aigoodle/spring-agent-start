package io.github.aigoodle.knowledge.nlp;

import io.github.aigoodle.knowledge.chunk.TokenCounter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lightweight, dependency-free tokenizer for keyword indexing/matching. Latin runs
 * become lowercase word tokens; CJK text contributes both single characters and
 * adjacent-character bigrams (a cheap stand-in for a segmenter that works well for
 * keyword overlap scoring). Inspired by RAGFlow's term-based matching.
 */
public final class KeywordTokenizer {

    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "of", "to", "and", "or", "is", "are", "in", "on", "for", "with",
            "的", "了", "和", "是", "在", "我", "有", "也", "就", "不", "与", "及");

    private KeywordTokenizer() {
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        StringBuilder latin = new StringBuilder();
        List<Character> cjkRun = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (TokenCounter.isCjk(c) && Character.isLetterOrDigit(c)) {
                flushLatin(latin, tokens);
                cjkRun.add(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjkRun, tokens);
                latin.append(Character.toLowerCase(c));
            } else {
                flushLatin(latin, tokens);
                flushCjk(cjkRun, tokens);
            }
        }
        flushLatin(latin, tokens);
        flushCjk(cjkRun, tokens);
        tokens.removeIf(STOP::contains);
        return tokens;
    }

    /** Distinct tokens, preserving order. */
    public static Set<String> tokenSet(String text) {
        return new LinkedHashSet<>(tokenize(text));
    }

    public static String join(String text) {
        return String.join(" ", tokenize(text));
    }

    private static void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.length() > 0) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    private static void flushCjk(List<Character> cjkRun, List<String> tokens) {
        if (cjkRun.isEmpty()) {
            return;
        }
        for (int i = 0; i < cjkRun.size(); i++) {
            tokens.add(String.valueOf(cjkRun.get(i)));
            if (i + 1 < cjkRun.size()) {
                tokens.add("" + cjkRun.get(i) + cjkRun.get(i + 1));
            }
        }
        cjkRun.clear();
    }
}
