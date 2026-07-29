package io.github.aigoodle.knowledge.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-aware recursive text splitter (LangChain-style): try increasingly fine
 * separators until each piece fits {@code maxTokens}, hard-splitting only as a last
 * resort, then greedily merge adjacent pieces and add token overlap between chunks.
 */
public final class RecursiveSplitter {

    private RecursiveSplitter() {
    }

    public static List<String> split(String text, List<String> separators, int maxTokens, int overlapTokens) {
        List<String> atoms = new ArrayList<>();
        recurse(text, separators, 0, Math.max(1, maxTokens), atoms);
        return mergeWithOverlap(atoms, Math.max(1, maxTokens), Math.max(0, overlapTokens));
    }

    private static void recurse(String text, List<String> separators, int sepIndex, int maxTokens,
                                List<String> out) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (TokenCounter.count(text) <= maxTokens) {
            out.add(text.strip());
            return;
        }
        if (sepIndex >= separators.size()) {
            // No separators left: hard split on token budget by characters.
            out.addAll(hardSplit(text, maxTokens));
            return;
        }
        String sep = separators.get(sepIndex);
        if (sep.isEmpty() || !text.contains(sep)) {
            recurse(text, separators, sepIndex + 1, maxTokens, out);
            return;
        }
        for (String part : text.split(java.util.regex.Pattern.quote(sep))) {
            if (part.isBlank()) {
                continue;
            }
            // Re-attach the separator so sentence punctuation is preserved.
            String piece = part + (isPunctuation(sep) ? sep : "");
            recurse(piece, separators, sepIndex + 1, maxTokens, out);
        }
    }

    private static List<String> hardSplit(String text, int maxTokens) {
        List<String> result = new ArrayList<>();
        // Approx chars per chunk: 1 token ~ 1 CJK char or ~4 latin chars; use a safe lower bound.
        int approxChars = Math.max(1, maxTokens);
        for (int i = 0; i < text.length(); i += approxChars) {
            result.add(text.substring(i, Math.min(text.length(), i + approxChars)).strip());
        }
        return result;
    }

    private static List<String> mergeWithOverlap(List<String> atoms, int maxTokens, int overlapTokens) {
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String atom : atoms) {
            if (atom.isBlank()) {
                continue;
            }
            if (current.length() == 0) {
                current.append(atom);
            } else if (TokenCounter.count(current + "\n" + atom) <= maxTokens) {
                current.append("\n").append(atom);
            } else {
                merged.add(current.toString());
                String overlap = overlapTokens > 0 ? tail(current.toString(), overlapTokens) : "";
                current = new StringBuilder();
                if (!overlap.isBlank()) {
                    current.append(overlap).append("\n");
                }
                current.append(atom);
            }
        }
        if (current.length() > 0) {
            merged.add(current.toString());
        }
        return merged;
    }

    /** Return a trailing slice of {@code text} of roughly {@code overlapTokens} tokens. */
    private static String tail(String text, int overlapTokens) {
        if (TokenCounter.count(text) <= overlapTokens) {
            return text;
        }
        int lo = 0;
        int hi = text.length();
        // Walk back from the end accumulating until we hit the token budget.
        for (int i = text.length() - 1; i >= 0; i--) {
            if (TokenCounter.count(text.substring(i)) >= overlapTokens) {
                lo = i;
                break;
            }
        }
        return text.substring(lo, hi).strip();
    }

    private static boolean isPunctuation(String sep) {
        String s = sep.strip();
        return s.length() == 1 && !Character.isLetterOrDigit(s.charAt(0));
    }
}
